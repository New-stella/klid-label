package kr.co.cudo.authoring.portal.marking;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 포털 업로드 영상 마킹 창구 — 저장·재저장 거절·조회를 보안 체인까지 통과시켜 확인한다.
 *
 * <p>수동 방식을 쓰는 것은 의도다 — 이 시험의 자산은 프로브를 거치지 않아 길이가 없고, 자동은 그
 * 경우 충돌로 거절되는 것이 계약이다(그 축은 단위 시험이 따로 고정한다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PortalUploadMarkingIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private PortalUploadAssetRepository assetRepository;
    /**
     * ★ 저장 경로를 시험별 임시 디렉터리로 덮지 않는다 — 그렇게 하면 <b>스프링 테스트
     * 컨텍스트가 하나 더 생기고 되돌아가지 않는다</b>(그 수의 래칫 가드가 실재한다).
     * 대신 운영 기본 경로 아래에 자기 파일만 쓰고 끝나면 그 파일만 지운다.
     */
    @Autowired private PortalUploadProperties uploadProperties;

    /** 이 시험이 만든 파일 — 공유 경로라 자기 것만 지운다(디렉터리는 건드리지 않는다). */
    private Path createdVideo;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private static final String ALICE = "marking-alice";
    private static final String BOB = "marking-bob";
    private static final String MANUAL_BODY =
            "{\"mode\":\"MANUAL\",\"marks\":[{\"frameIndex\":0,\"timestamp\":\"00:00\"},"
                    + "{\"frameIndex\":300,\"timestamp\":\"00:10\"}]}";

    private String aliceToken;
    private String bobToken;
    private Long uldSn;

    @BeforeEach
    void setUp() throws IOException {
        aliceToken = JwtTestSupport.token(secret, ALICE, "PORTAL_USER", "PORTAL", issuer, 60);
        bobToken = JwtTestSupport.token(secret, BOB, "PORTAL_USER", "PORTAL", issuer, 60);
        Path base = Paths.get(uploadProperties.storagePath()).toAbsolutePath().normalize();
        Files.createDirectories(base);
        createdVideo = base.resolve("marking-it-" + System.nanoTime() + ".mp4");
        Files.write(createdVideo, new byte[1024]);
        uldSn = assetRepository.insertUploaded(
                ALICE, createdVideo.toString(), "v.mp4", "video/mp4", 1024L);
    }

    @AfterEach
    void cleanupRows() {
        for (String user : new String[]{ALICE, BOB}) {
            assetRepository.findPageByOwner(user, null, PageRequest.of(0, 100)).getContent()
                    .forEach(u -> assetRepository.deleteOwned(u.uldSn(), user));
        }
        // 공유 경로라 자기가 만든 파일만 지운다 — 부모 디렉터리는 다른 실행의 잔재가 함께 있을 수 있다.
        if (createdVideo != null) {
            try {
                Files.deleteIfExists(createdVideo);
            } catch (IOException ignored) {
                // 정리 실패는 제품 결함이 아니다.
            }
        }
    }


    private void saveManual() throws Exception {
        mockMvc.perform(post("/v1/portal/uploads/" + uldSn + "/markings")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MANUAL_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.markCount").value(2))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.uldSttsCd").value(PortalUploadLedger.STATUS_PROCESSING));
    }

    @Test
    @DisplayName("본인_영상에_수동_마킹을_저장하면_201이고_추출_중으로_넘어간다")
    void savesManualMarking() throws Exception {
        saveManual();
    }

    /**
     * ★ 재마킹을 제공하지 않는다 — 회복 경로는 자산을 지우고 다시 올리는 것뿐이다.
     */
    @Test
    @DisplayName("★같은_영상에_다시_저장하면_409다 — 재마킹_미제공")
    void reMarkingIsRejected() throws Exception {
        saveManual();

        mockMvc.perform(post("/v1/portal/uploads/" + uldSn + "/markings")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MANUAL_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("저장한_마킹을_다시_들어와_조회할_수_있다")
    void listsSavedMarking() throws Exception {
        saveManual();

        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/markings")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markings.length()").value(1))
                .andExpect(jsonPath("$.data.markings[0].mode").value("MANUAL"))
                .andExpect(jsonPath("$.data.markings[0].markCount").value(2));
    }

    @Test
    @DisplayName("저장한_것이_없으면_빈_목록이다 — 오류가_아니다")
    void listsEmptyWhenNoMarking() throws Exception {
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/markings")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markings.length()").value(0));
    }

    @Test
    @DisplayName("남의_자산은_저장도_조회도_403이며_없는_자산과_같은_코드다")
    void foreignAssetIsForbidden() throws Exception {
        mockMvc.perform(post("/v1/portal/uploads/" + uldSn + "/markings")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MANUAL_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/portal/uploads/" + uldSn + "/markings")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/portal/uploads/999999999/markings")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("수동인데_지점이_비면_400이다")
    void manualWithoutMarksIsBadRequest() throws Exception {
        mockMvc.perform(post("/v1/portal/uploads/" + uldSn + "/markings")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"MANUAL\",\"marks\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
