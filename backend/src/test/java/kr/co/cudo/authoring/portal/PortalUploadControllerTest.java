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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V107 포털 이미지 업로드/자산 관리 통합 테스트 — 업로드 검증(확장자/크기/개수/매직바이트) ·
 * 채널 격리 · IDOR(403) · 서빙 MIME/nosniff · 삭제 정리 · 업로드→목록→서빙 통합 흐름.
 *
 * <p>저장 경로/한도는 {@link DynamicPropertySource} 로 임시 디렉토리·소형 한도로 오버라이드해
 * 실제 스토리지 오염과 20MB 대용량 테스트 비용을 회피한다. 비-트랜잭션(실제 커밋) 모드라 파일
 * 정리 콜백이 동작하며, 누적 row 는 소유자 스코프로 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PortalUploadControllerTest {

    private static Path tempStorage;

    @DynamicPropertySource
    static void overrideStorage(DynamicPropertyRegistry registry) throws IOException {
        tempStorage = Files.createTempDirectory("portal-upload-test");
        registry.add("portal.upload.storage-path", () -> tempStorage.toString());
        registry.add("portal.upload.max-image-size-bytes", () -> "1024");
        registry.add("portal.upload.max-images-per-request", () -> "3");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsPortalUldRepository uldRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private static final String ALICE = "ctrl-alice";
    private static final String BOB = "ctrl-bob";
    private String aliceToken;
    private String bobToken;
    private String reviewerInternalToken;

    /** PNG 8바이트 시그니처 + IHDR 청크(길이 13 + "IHDR") — header-only 방어 통과하는 소형 정상 이미지. */
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};
    /** SOI(FF D8 FF) + APP0 + EOI(FF D9) — 파일끝 EOI 까지 유효한 최소 JPEG. */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, (byte) 0xFF, (byte) 0xD9};

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

    // ---------------------------------------------------------------- helpers

    private MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, MediaType.IMAGE_PNG_VALUE, content);
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder uploadReq(
            String token, MockMultipartFile... files) {
        var req = multipart("/v1/portal/uploads/images");
        for (MockMultipartFile f : files) {
            req.file(f);
        }
        req.header("Authorization", "Bearer " + token);
        return req;
    }

    private JsonNode uploadOk(String token, MockMultipartFile... files) throws Exception {
        MvcResult res = mockMvc.perform(uploadReq(token, files))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).path("data");
    }

    private int uploadStatus(String token, MockMultipartFile... files) throws Exception {
        return mockMvc.perform(uploadReq(token, files)).andReturn().getResponse().getStatus();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("이미지_다중_업로드_성공시_201과_READY_상태")
    void uploadSuccess() throws Exception {
        JsonNode data = uploadOk(aliceToken, file("a.png", PNG), file("b.jpg", JPEG));
        assertThat(data.isArray()).isTrue();
        assertThat(data).hasSize(2);
        assertThat(data.get(0).path("uldSttsCd").asText()).isEqualTo("READY");
        assertThat(data.get(0).path("frmeSn").isNull()).isFalse();
        assertThat(data.get(0).path("uldTypeCd").asText()).isEqualTo("IMAGE");
    }

    @Test
    @DisplayName("허용되지_않은_확장자_업로드시_400")
    void disallowedExtension() throws Exception {
        assertThat(uploadStatus(aliceToken, file("a.txt", PNG))).isEqualTo(400);
    }

    @Test
    @DisplayName("확장자는_png인데_매직바이트가_JPEG면_400")
    void extensionMagicMismatch() throws Exception {
        assertThat(uploadStatus(aliceToken, file("a.png", JPEG))).isEqualTo(400);
    }

    @Test
    @DisplayName("SVG_시그니처_파일은_png_확장자여도_400")
    void svgRejected() throws Exception {
        assertThat(uploadStatus(aliceToken, file("evil.png", "<svg xmlns=".getBytes()))).isEqualTo(400);
    }

    @Test
    @DisplayName("크기_제한_초과시_400")
    void oversize() throws Exception {
        byte[] big = new byte[2048]; // > max-image-size-bytes(1024)
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThat(uploadStatus(aliceToken, file("big.png", big))).isEqualTo(400);
    }

    @Test
    @DisplayName("요청당_개수_초과시_400")
    void tooMany() throws Exception {
        assertThat(uploadStatus(aliceToken,
                file("a.png", PNG), file("b.png", PNG), file("c.png", PNG), file("d.png", PNG)))
                .isEqualTo(400);
    }

    @Test
    @DisplayName("빈_파일_업로드시_400")
    void emptyFile() throws Exception {
        assertThat(uploadStatus(aliceToken, file("a.png", new byte[0]))).isEqualTo(400);
    }

    @Test
    @DisplayName("다중_업로드_중_1건_불량시_전체_거부되고_저장_0건")
    void oneBadRejectsAll() throws Exception {
        assertThat(uploadStatus(aliceToken, file("good.png", PNG), file("bad.txt", PNG))).isEqualTo(400);
        // 저장 0건 — 목록 조회 시 alice 자산 없음.
        mockMvc.perform(get("/v1/portal/uploads").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("내부채널_토큰으로_포털업로드_호출시_403")
    void internalChannelForbidden() throws Exception {
        assertThat(uploadStatus(reviewerInternalToken, file("a.png", PNG))).isEqualTo(403);
    }

    @Test
    @DisplayName("타사용자_자산_상세_조회시_403")
    void detailIdor() throws Exception {
        long uldSn = uploadOk(aliceToken, file("a.png", PNG)).get(0).path("uldSn").asLong();
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        // 소유자는 200.
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("타사용자_프레임_이미지_조회시_403")
    void frameImageIdor() throws Exception {
        long frmeSn = uploadOk(aliceToken, file("a.png", PNG)).get(0).path("frmeSn").asLong();
        mockMvc.perform(get("/v1/portal/uploads/frames/" + frmeSn + "/image")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("타사용자_삭제시_403")
    void deleteIdor() throws Exception {
        long uldSn = uploadOk(aliceToken, file("a.png", PNG)).get(0).path("uldSn").asLong();
        mockMvc.perform(delete("/v1/portal/uploads/" + uldSn).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("이미지_서빙_응답에_nosniff_헤더와_DB_확정_ContentType")
    void serveImageHasNosniffAndDbContentType() throws Exception {
        long frmeSn = uploadOk(aliceToken, file("a.png", PNG)).get(0).path("frmeSn").asLong();
        mockMvc.perform(get("/v1/portal/uploads/frames/" + frmeSn + "/image")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE));
    }

    @Test
    @DisplayName("업로드_목록_프레임이미지_서빙_통합_흐름_GREEN")
    void integrationFlow() throws Exception {
        JsonNode data = uploadOk(aliceToken, file("a.png", PNG));
        long uldSn = data.get(0).path("uldSn").asLong();
        long frmeSn = data.get(0).path("frmeSn").asLong();

        // 목록 — 본인 자산 1건.
        mockMvc.perform(get("/v1/portal/uploads").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));

        // 프레임 목록.
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/frames")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));

        // 이미지 서빙.
        mockMvc.perform(get("/v1/portal/uploads/frames/" + frmeSn + "/image")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
    }

    /**
     * 실 스택(실 DB + V11 시드 + 신설 집계 JPQL)에서 만료 예정 시각이 실제로 실려 나오는지 확인한다.
     * @design AC-033, DFEAT-055
     *
     * <p>단위 테스트는 리포지토리를 mock 하므로 집계 쿼리가 <b>실행되는지</b>는 검증하지 못한다.
     * 여기서만 실 쿼리가 돌고, 보존기간 설정 행({@code portal.upload.retention-days})이 시드로
     * 실재하는지도 함께 드러난다(시드가 없으면 값이 null 로 내려와 이 테스트가 깨진다).
     */
    @Test
    @DisplayName("업로드_직후_목록과_상세에_보존기간_만료예정시각이_등록일_기준으로_실린다")
    void listAndDetailCarryExpiresAt() throws Exception {
        long uldSn = uploadOk(aliceToken, file("a.png", PNG)).get(0).path("uldSn").asLong();

        MvcResult listResult = mockMvc.perform(
                        get("/v1/portal/uploads").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode row = new ObjectMapper().readTree(listResult.getResponse().getContentAsString())
                .path("data").path("content").get(0);

        // READY 자산이므로 등록일 + portal.upload.retention-days(시드 7일)
        java.time.LocalDateTime regDt = java.time.LocalDateTime.parse(row.path("regDt").asText());
        assertThat(row.path("expiresAt").isNull()).isFalse();
        assertThat(java.time.LocalDateTime.parse(row.path("expiresAt").asText()))
                .isEqualTo(regDt.plusDays(7));

        // 상세도 같은 값 — 목록과 판정기를 공유한다.
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresAt").value(regDt.plusDays(7).toString()));
    }

    @Test
    @DisplayName("목록조회_type필터_IMAGE만_반환")
    void listTypeFilterImage() throws Exception {
        uploadOk(aliceToken, file("a.png", PNG));
        // IMAGE 필터 — 1건.
        mockMvc.perform(get("/v1/portal/uploads").param("type", "IMAGE")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].uldTypeCd").value("IMAGE"));
        // VIDEO 필터 — 0건(이미지만 업로드).
        mockMvc.perform(get("/v1/portal/uploads").param("type", "VIDEO")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("미지원_type_필터시_400")
    void listUnknownTypeRejected() throws Exception {
        mockMvc.perform(get("/v1/portal/uploads").param("type", "EXE")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("size_100초과시_100으로_캡")
    void listSizeCappedAt100() throws Exception {
        mockMvc.perform(get("/v1/portal/uploads").param("size", "500")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    @DisplayName("타사용자_프레임목록_조회시_403")
    void framesListIdor() throws Exception {
        long uldSn = uploadOk(aliceToken, file("a.png", PNG)).get(0).path("uldSn").asLong();
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/frames")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        // 소유자는 200.
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/frames")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("헤더만_유효한_truncated_파일_400")
    void truncatedHeaderOnlyRejected() throws Exception {
        // JPEG SOI 만 있고 EOI(FF D9) 없이 잘린 파일.
        byte[] truncatedJpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
        assertThat(uploadStatus(aliceToken, file("trunc.jpg", truncatedJpeg))).isEqualTo(400);
        // PNG 8바이트 시그니처만 있고 IHDR 없이 잘린 파일.
        byte[] truncatedPng = {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x00, 0x00, 0x00, 0x00};
        assertThat(uploadStatus(aliceToken, file("trunc.png", truncatedPng))).isEqualTo(400);
    }

    @Test
    @DisplayName("삭제시_파일과_프레임_라벨_행이_함께_제거됨")
    void deleteRemovesRowsAndFile() throws Exception {
        JsonNode data = uploadOk(aliceToken, file("a.png", PNG));
        long uldSn = data.get(0).path("uldSn").asLong();
        long frmeSn = data.get(0).path("frmeSn").asLong();

        mockMvc.perform(delete("/v1/portal/uploads/" + uldSn).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        // 행 제거 — 상세/서빙 모두 403(부재).
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/portal/uploads/frames/" + frmeSn + "/image")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());
        // 목록 0건.
        mockMvc.perform(get("/v1/portal/uploads").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    // ------------------------------------------------- A-ISSUE-61 정렬 allowlist (strict)

    @Test
    @DisplayName("자산목록_미등록_정렬키는_500이_아니라_400")
    void uploadListUnknownSortKeyReturns400() throws Exception {
        // given: 엔티티에 없는 정렬 키 (수정 전에는 PropertyReferenceException → 500)
        String body = mockMvc.perform(get("/v1/portal/uploads")
                        .param("sort", "secretField,desc")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"))
                .andReturn().getResponse().getContentAsString();

        // then: 입력 반사·내부 엔티티명·예외 클래스명 미노출 (CWE-209)
        assertThat(body)
                .doesNotContain("secretField")
                .doesNotContain("LsPortalUld")
                .doesNotContain("PropertyReferenceException");
    }

    @Test
    @DisplayName("자산목록_원본파일명_정렬키_400")
    void uploadListFileNameSortKeyReturns400() throws Exception {
        // 사용자가 지은 원본 파일명(PII 여지)은 정렬 축으로 열지 않는다.
        mockMvc.perform(get("/v1/portal/uploads")
                        .param("sort", "orgnlFileNm,asc")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("자산목록_등록된_정렬키는_200_이고_정렬_미지정도_200")
    void uploadListAllowedSortKeyReturns200() throws Exception {
        uploadOk(aliceToken, file("a.png", PNG));

        mockMvc.perform(get("/v1/portal/uploads")
                        .param("sort", "regDt,desc")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));

        // 하위호환 — 정렬 파라미터 없는 기존 호출(@PageableDefault 에 기본 정렬 없음)도 그대로 200.
        mockMvc.perform(get("/v1/portal/uploads")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("프레임목록_미등록_정렬키는_500이_아니라_400")
    void frameListUnknownSortKeyReturns400() throws Exception {
        long uldSn = uploadOk(aliceToken, file("a.png", PNG)).get(0).path("uldSn").asLong();

        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/frames")
                        .param("sort", "secretField,desc")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        // 등록 키 + 정렬 미지정은 그대로 200.
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/frames")
                        .param("sort", "frmeNo,asc")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/frames")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
    }
}
