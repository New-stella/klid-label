package kr.co.cudo.authoring.portal.augment;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
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
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 포털 증강 창구 — 접수·현황·단건을 보안 체인까지 통과시켜 확인한다.
 *
 * <p>가드 둘을 함께 고정한다:
 * <ul>
 *   <li><b>데이터마트 로드분은 요청 대상이 아니다</b> — 포털 업로드 자산이 아닌 영상 식별자로
 *       요청하면 남의 자산·없는 자산과 같은 코드로 거절된다.</li>
 *   <li><b>포털 요청이 관제 증강 이력에 섞이지 않는다</b> — 검수자 목록에 뜨면 채널이 새는 것이다.</li>
 * </ul>
 *
 * <p>저장 경로를 시험별 임시 디렉터리로 덮지 않는다(컨텍스트가 하나 더 생긴다) — 운영 기본 경로
 * 아래에 자기 파일만 쓰고 끝나면 그 파일만 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PortalAugmentIT {

    private static final String ALICE = "augment-alice";
    private static final String BOB = "augment-bob";
    private static final String BODY = "{\"generationCondition\":{\"season\":\"WINTER\"}}";

    @Autowired private MockMvc mockMvc;
    @Autowired private PortalUploadAssetRepository assetRepository;
    @Autowired private PortalUploadFrameRepository frameRepository;
    @Autowired private PortalUploadProperties uploadProperties;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String aliceToken;
    private String bobToken;
    private String reviewerToken;
    private Long readyUldSn;
    private Long pendingUldSn;
    private Path createdVideo;

    @BeforeEach
    void setUp() throws IOException {
        aliceToken = JwtTestSupport.token(secret, ALICE, "PORTAL_USER", "PORTAL", issuer, 60);
        bobToken = JwtTestSupport.token(secret, BOB, "PORTAL_USER", "PORTAL", issuer, 60);
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        Path base = Paths.get(uploadProperties.storagePath()).toAbsolutePath().normalize();
        Files.createDirectories(base);
        createdVideo = base.resolve("augment-it-" + System.nanoTime() + ".mp4");
        Files.write(createdVideo, new byte[1024]);

        readyUldSn = assetRepository.insertUploaded(
                ALICE, createdVideo.toString(), "street.mp4", "video/mp4", 1024L);
        assetRepository.transitionToProcessing(readyUldSn);
        frameRepository.save(LsDataSrc.create(readyUldSn, 0L, 0L,
                createdVideo.getParent().resolve("frame-0.jpg").toString(), null));
        assetRepository.transitionToReady(readyUldSn);

        // 마킹을 기다리는 자산 — 준비가 끝나지 않아 요청을 받지 않는다.
        pendingUldSn = assetRepository.insertUploaded(
                ALICE, createdVideo.toString(), "waiting.mp4", "video/mp4", 1024L);
    }

    @AfterEach
    void cleanup() {
        for (String user : new String[]{ALICE, BOB}) {
            assetRepository.findPageByOwner(user, null, PageRequest.of(0, 100)).getContent()
                    .forEach(u -> assetRepository.deleteOwned(u.uldSn(), user));
        }
        if (createdVideo != null) {
            try {
                Files.deleteIfExists(createdVideo);
            } catch (IOException ignored) {
                // 정리 실패는 제품 결함이 아니다.
            }
        }
    }

    private long request() throws Exception {
        MvcResult res = mockMvc.perform(post("/v1/portal/uploads/" + readyUldSn + "/augments")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.uldSn").value(readyUldSn))
                .andExpect(jsonPath("$.data.augSn").isNumber())
                .andReturn();
        Number augSn = com.jayway.jsonpath.JsonPath.read(
                res.getResponse().getContentAsString(), "$.data.augSn");
        return augSn.longValue();
    }

    @Test
    @DisplayName("본인_준비완료_영상에_증강을_요청하면_201이고_현황에_뜬다")
    void requestsAndListsOwnAugment() throws Exception {
        long augSn = request();

        mockMvc.perform(get("/v1/portal/augments")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].augSn").value(augSn))
                .andExpect(jsonPath("$.data.content[0].uldSn").value(readyUldSn))
                .andExpect(jsonPath("$.data.content[0].orgnlFileNm").value("street.mp4"))
                .andExpect(jsonPath("$.data.content[0].generationCondition.season").value("WINTER"))
                .andExpect(jsonPath("$.data.content[0].resultReady").value(false));

        mockMvc.perform(get("/v1/portal/augments/" + augSn)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.augSn").value(augSn))
                .andExpect(jsonPath("$.data.resultUldSn").doesNotExist())
                .andExpect(jsonPath("$.data.resultReady").value(false));
    }

    /**
     * ★ 가드 — 데이터마트에서 불러온 영상(포털 업로드 자산이 아닌 영상)은 요청 대상이 아니다.
     * 없는 식별자와 <b>같은 코드</b>여야 존재 여부가 드러나지 않는다.
     */
    @Test
    @DisplayName("★포털_업로드_자산이_아닌_영상은_요청_대상이_아니다 — 없는_식별자와_같은_403")
    void nonPortalAssetIsNotARequestTarget() throws Exception {
        mockMvc.perform(post("/v1/portal/uploads/999999999/augments")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("남의_자산에_요청하면_403이고_남의_요청_조회도_403이다")
    void foreignAccessIsForbidden() throws Exception {
        long augSn = request();

        mockMvc.perform(post("/v1/portal/uploads/" + readyUldSn + "/augments")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/portal/augments/" + augSn)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/portal/augments")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("준비가_끝나지_않은_영상은_409다")
    void notReadyAssetIsConflict() throws Exception {
        mockMvc.perform(post("/v1/portal/uploads/" + pendingUldSn + "/augments")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("생성_조건이_없으면_400이다")
    void missingConditionIsBadRequest() throws Exception {
        mockMvc.perform(post("/v1/portal/uploads/" + readyUldSn + "/augments")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("음수_페이지는_400이고_상한을_넘는_크기는_거부가_아니라_절단이다")
    void pagingContract() throws Exception {
        mockMvc.perform(get("/v1/portal/augments?page=-1")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/portal/augments?size=5000")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    /**
     * ★ 가드 — 포털 요청은 <b>검수자의 증강 이력</b>에 뜨지 않는다. 뜨면 채널이 새는 것이고,
     * 그 목록에는 포털 회원이 낸 요청에 대해 검수자가 할 수 있는 일이 없다(채택·반려가 없다).
     */
    @Test
    @DisplayName("★포털_증강_요청은_관제_증강_이력에_섞이지_않는다")
    void portalAugmentDoesNotLeakIntoControlHistory() throws Exception {
        request();

        String body = mockMvc.perform(get("/v1/augments?page=0&size=100")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.List<Number> jobIds =
                com.jayway.jsonpath.JsonPath.read(body, "$.data.content[*].jobId");
        assertThat(jobIds.stream().map(Number::longValue)).doesNotContain(readyUldSn);
    }
}
