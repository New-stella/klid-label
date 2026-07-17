package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 — 포털 업로드 라벨 CRUD/export/다운로드 통합 테스트(MockMvc).
 * 업로드(READY 이미지) → PUT 라벨 → GET 라벨 → export → 원본 다운로드 전 구간과
 * 채널 격리/IDOR(403)/입력 검증(400)/Content-Disposition·nosniff 를 실동작으로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PortalUploadLabelControllerTest {

    private static Path tempStorage;

    @DynamicPropertySource
    static void overrideStorage(DynamicPropertyRegistry registry) throws IOException {
        tempStorage = Files.createTempDirectory("portal-upload-label-test");
        registry.add("portal.upload.storage-path", () -> tempStorage.toString());
        registry.add("portal.upload.max-image-size-bytes", () -> "4096");
        registry.add("portal.upload.max-images-per-request", () -> "3");
        registry.add("portal.upload.max-label-body-bytes", () -> "1024");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsPortalUldRepository uldRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private static final String ALICE = "lbl-alice";
    private static final String BOB = "lbl-bob";
    private String aliceToken;
    private String bobToken;
    private String reviewerInternalToken;

    /** PNG 8바이트 시그니처 + IHDR 청크 — 업로드 매직바이트 방어 통과 최소 이미지. */
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};

    @BeforeEach
    void setUp() {
        aliceToken = JwtTestSupport.token(secret, ALICE, "PORTAL_USER", "PORTAL", issuer, 60);
        bobToken = JwtTestSupport.token(secret, BOB, "PORTAL_USER", "PORTAL", issuer, 60);
        reviewerInternalToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    @AfterEach
    void cleanupRows() {
        deleteOwned(ALICE);
        deleteOwned(BOB);
    }

    private void deleteOwned(String user) {
        uldRepository.findAllByPortalUserNo(user, PageRequest.of(0, 1000))
                .forEach(u -> uldRepository.deleteById(u.getUldSn()));
    }

    @AfterAll
    static void cleanupStorage() {
        if (tempStorage != null) {
            FileSystemUtils.deleteRecursively(tempStorage.toFile());
        }
    }

    // ---------------- helpers ----------------

    /** 이미지 1건 업로드 후 대표 프레임(READY)의 uldSn/frmeSn 반환. */
    private long[] uploadImage(String token) throws Exception {
        var req = multipart("/v1/portal/uploads/images")
                .file(new MockMultipartFile("files", "a.png", MediaType.IMAGE_PNG_VALUE, PNG))
                .header("Authorization", "Bearer " + token);
        MvcResult res = mockMvc.perform(req).andExpect(status().isCreated()).andReturn();
        JsonNode data = objectMapper.readTree(res.getResponse().getContentAsString()).path("data").get(0);
        return new long[]{data.path("uldSn").asLong(), data.path("frmeSn").asLong()};
    }

    private String bboxBody() {
        return "[{\"lblTypeCd\":\"BBOX\",\"label\":\"car\",\"points\":[[10,20],[30,40]]}]";
    }

    private int putLabels(String token, long frmeSn, String body) throws Exception {
        return mockMvc.perform(put("/v1/portal/uploads/frames/" + frmeSn + "/labels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    // ---------------- tests ----------------

    @Test
    @DisplayName("라벨_PUT후_GET하면_저장된_라벨_반환")
    void putThenGet() throws Exception {
        long[] ids = uploadImage(aliceToken);
        long frmeSn = ids[1];

        mockMvc.perform(put("/v1/portal/uploads/frames/" + frmeSn + "/labels")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bboxBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].lblTypeCd").value("BBOX"))
                .andExpect(jsonPath("$.data[0].label").value("car"));

        mockMvc.perform(get("/v1/portal/uploads/frames/" + frmeSn + "/labels")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].lblTypeCd").value("BBOX"))
                .andExpect(jsonPath("$.data[0].points[0][0]").value(10.0));
    }

    @Test
    @DisplayName("빈_배열_PUT은_전체_삭제되어_GET_0건")
    void emptyArrayClears() throws Exception {
        long frmeSn = uploadImage(aliceToken)[1];
        assertThat(putLabels(aliceToken, frmeSn, bboxBody())).isEqualTo(200);

        assertThat(putLabels(aliceToken, frmeSn, "[]")).isEqualTo(200);
        mockMvc.perform(get("/v1/portal/uploads/frames/" + frmeSn + "/labels")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("BBOX_점2개_아니면_400_기존라벨_유지")
    void bboxInvalidPointsRejected() throws Exception {
        long frmeSn = uploadImage(aliceToken)[1];
        assertThat(putLabels(aliceToken, frmeSn, bboxBody())).isEqualTo(200);

        // 잘못된 BBOX(1점) → 400, 기존 라벨은 유지되어야 한다.
        assertThat(putLabels(aliceToken, frmeSn,
                "[{\"lblTypeCd\":\"BBOX\",\"label\":\"x\",\"points\":[[1,2]]}]")).isEqualTo(400);
        mockMvc.perform(get("/v1/portal/uploads/frames/" + frmeSn + "/labels")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].label").value("car"));
    }

    @Test
    @DisplayName("label_80자초과_400")
    void labelTooLongRejected() throws Exception {
        long frmeSn = uploadImage(aliceToken)[1];
        String tooLong = "x".repeat(81);
        String body = "[{\"lblTypeCd\":\"BBOX\",\"label\":\"" + tooLong + "\",\"points\":[[1,2],[3,4]]}]";
        assertThat(putLabels(aliceToken, frmeSn, body)).isEqualTo(400);
    }

    @Test
    @DisplayName("라벨_PUT_본문_상한초과시_413")
    void labelBodyTooLarge() throws Exception {
        long frmeSn = uploadImage(aliceToken)[1];
        // portal.upload.max-label-body-bytes 를 테스트에서 작게(1KB) 오버라이드 → 초과 본문은 파싱 전 413.
        StringBuilder sb = new StringBuilder("[{\"lblTypeCd\":\"BBOX\",\"label\":\"");
        sb.append("a".repeat(4096));
        sb.append("\",\"points\":[[1,2],[3,4]]}]");
        assertThat(putLabels(aliceToken, frmeSn, sb.toString())).isEqualTo(413);
    }

    @Test
    @DisplayName("허용되지_않은_타입_400")
    void invalidTypeRejected() throws Exception {
        long frmeSn = uploadImage(aliceToken)[1];
        assertThat(putLabels(aliceToken, frmeSn,
                "[{\"lblTypeCd\":\"CIRCLE\",\"label\":\"x\",\"points\":[[1,2]]}]")).isEqualTo(400);
    }

    @Test
    @DisplayName("타사용자_라벨_PUT_403")
    void putIdor() throws Exception {
        long frmeSn = uploadImage(aliceToken)[1];
        assertThat(putLabels(bobToken, frmeSn, bboxBody())).isEqualTo(403);
    }

    @Test
    @DisplayName("타사용자_라벨_GET_403")
    void getIdor() throws Exception {
        long frmeSn = uploadImage(aliceToken)[1];
        mockMvc.perform(get("/v1/portal/uploads/frames/" + frmeSn + "/labels")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("내부채널_토큰_라벨_PUT_403")
    void internalChannelForbidden() throws Exception {
        long frmeSn = uploadImage(aliceToken)[1];
        assertThat(putLabels(reviewerInternalToken, frmeSn, bboxBody())).isEqualTo(403);
    }

    @Test
    @DisplayName("export_JSON_attachment_고정파일명과_데이터_포함")
    void exportAttachment() throws Exception {
        long[] ids = uploadImage(aliceToken);
        long uldSn = ids[0];
        long frmeSn = ids[1];
        putLabels(aliceToken, frmeSn, bboxBody());

        MvcResult res = mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/export")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"portal-upload-" + uldSn + "-labels.json\""))
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andReturn();
        String json = res.getResponse().getContentAsString();
        assertThat(json).contains("car").contains("\"uldSn\":" + uldSn);
    }

    @Test
    @DisplayName("타사용자_export_403")
    void exportIdor() throws Exception {
        long uldSn = uploadImage(aliceToken)[0];
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/export")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("원본_다운로드_nosniff_attachment_DB_MIME")
    void downloadFile() throws Exception {
        long uldSn = uploadImage(aliceToken)[0];
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/file")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.startsWith("attachment;"),
                                org.hamcrest.Matchers.containsString("filename=\"download.png\""),
                                org.hamcrest.Matchers.containsString("filename*=UTF-8''"))));
    }

    @Test
    @DisplayName("타사용자_원본_다운로드_403")
    void downloadIdor() throws Exception {
        long uldSn = uploadImage(aliceToken)[0];
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/file")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }
}
