package kr.co.cudo.authoring.batch.controller;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배치 단계 수동 스킵/해제 E2E (MockMvc 통합). [@design API-198] [@design API-200]
 *
 * <p>인가(REVIEWER 전용) · 입력 검증(사유 필수 · 허용 단계) · 실제 표식 적재/해제를 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BatchStageSkipControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private BatchStatusService batchStatusService;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    private Long saveVideo() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-SK-" + System.nanoTime(), "CCTV-SK", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        return videoRepository.save(raw).getRawSn();
    }

    private static String body(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    @Test
    @DisplayName("묶음스킵API_WORKER는_403")
    void workerForbidden() throws Exception {
        Long rawSn = saveVideo();

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/VLM/skip")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("벤더 장애")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("묶음스킵API_인증없음_401")
    void unauthenticated() throws Exception {
        Long rawSn = saveVideo();

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/VLM/skip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("벤더 장애")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("묶음스킵API_사유가_없거나_공백이면_400")
    void reasonRequired() throws Exception {
        Long rawSn = saveVideo();

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/VLM/skip")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/VLM/skip")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("묶음스킵API_허용되지_않은_값은_400")
    void unsupportedBundle() throws Exception {
        Long rawSn = saveVideo();

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/FRAME_EXTRACT/skip")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("추출 생략")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("★★묶음스킵API_구_개별단계값_YOLO_SAM2는_400이다_단위가_묶음으로_반전됐다")
    void legacyPerStageValuesRejected() throws Exception {
        Long rawSn = saveVideo();

        for (String legacy : new String[]{"YOLO", "SAM2", "INTERPOLATE"}) {
            mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/" + legacy + "/skip")
                            .header("Authorization", "Bearer " + reviewerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("생략")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
        }
    }

    @Test
    @DisplayName("묶음스킵API_존재하지_않는_영상_404")
    void notFound() throws Exception {
        mockMvc.perform(post("/v1/videos/999999999/batch/stages/VLM/skip")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("벤더 장애")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("묶음스킵API_REVIEWER_성공_200이고_표식이_실제로_선다")
    void reviewerSkipSuccess() throws Exception {
        Long rawSn = saveVideo();

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/VLM/skip")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("외부 벤더 장애로 시계열 분석 생략")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rawSn").value(rawSn))
                .andExpect(jsonPath("$.data.stage").value("VLM"))
                .andExpect(jsonPath("$.data.skipped").value(true));

        assertThat(batchStatusService.isStageManuallySkipped(rawSn, BatchStage.VLM)).isTrue();
    }

    @Test
    @DisplayName("★★오토라벨을_건너뛰면_구성_단계_셋이_전부_건너뛰어진다_보간_포함")
    void autolabelSkipCoversAllThreeStages() throws Exception {
        // 보간이 묶음 밖이면 어떤 재수행에서도 무조건 돌아 사람이 손댄 보간 라벨을 지운다 —
        //   이 단정이 그 사고를 구조적으로 막는 계약이다.
        Long rawSn = saveVideo();

        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/AUTOLABEL/skip")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("AI 서버 점검으로 오토라벨 생략")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("AUTOLABEL"));

        assertThat(batchStatusService.isStageManuallySkipped(rawSn, BatchStage.YOLO)).isTrue();
        assertThat(batchStatusService.isStageManuallySkipped(rawSn, BatchStage.SAM2)).isTrue();
        assertThat(batchStatusService.isStageManuallySkipped(rawSn, BatchStage.INTERPOLATE)).isTrue();
        // 다른 묶음·전제 단계는 영향받지 않는다.
        assertThat(batchStatusService.isStageManuallySkipped(rawSn, BatchStage.VLM)).isFalse();
        assertThat(batchStatusService.isStageManuallySkipped(rawSn, BatchStage.FRAME_EXTRACT)).isFalse();
    }

    @Test
    @DisplayName("묶음스킵해제API_REVIEWER_204이고_표식이_사라진다")
    void reviewerClearSuccess() throws Exception {
        Long rawSn = saveVideo();
        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/AUTOLABEL/skip")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("오토라벨 생략")))
                .andExpect(status().isOk());
        assertThat(batchStatusService.isStageManuallySkipped(rawSn, BatchStage.YOLO)).isTrue();

        mockMvc.perform(delete("/v1/videos/" + rawSn + "/batch/stages/AUTOLABEL/skip")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());

        // ★ 해제도 묶음 단위 — 구성 단계 셋이 <b>함께</b> 풀린다(일부만 남지 않는다).
        assertThat(batchStatusService.isStageManuallySkipped(rawSn, BatchStage.YOLO)).isFalse();
        assertThat(batchStatusService.isStageManuallySkipped(rawSn, BatchStage.SAM2)).isFalse();
        assertThat(batchStatusService.isStageManuallySkipped(rawSn, BatchStage.INTERPOLATE)).isFalse();
    }

    @Test
    @DisplayName("묶음스킵해제API_스킵상태가_아니어도_204_멱등")
    void clearIsIdempotent() throws Exception {
        Long rawSn = saveVideo();

        mockMvc.perform(delete("/v1/videos/" + rawSn + "/batch/stages/AUTOLABEL/skip")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("묶음스킵해제API_WORKER는_403")
    void clearWorkerForbidden() throws Exception {
        Long rawSn = saveVideo();

        mockMvc.perform(delete("/v1/videos/" + rawSn + "/batch/stages/VLM/skip")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    // ── API-043 v8 — 영상 상세의 skippedStages 노출 ────────────────────

    @Test
    @DisplayName("★영상상세API_건너뛴_작업묶음이_skippedStages로_내려온다")
    void videoDetailExposesSkippedBundles() throws Exception {
        Long rawSn = saveVideo();

        // given — 스킵 전에는 빈 배열이다(null 아님).
        mockMvc.perform(get("/v1/videos/" + rawSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skippedStages").isArray())
                .andExpect(jsonPath("$.data.skippedStages.length()").value(0));

        // when — 선언 역순으로 두 묶음을 스킵한다(반환 순서가 적재 순서에 끌려가면 안 된다).
        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/AUTOLABEL/skip")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("오토라벨 생략")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/videos/" + rawSn + "/batch/stages/VLM/skip")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("벤더 장애")))
                .andExpect(status().isOk());

        // then — 적재 순서가 아니라 파이프라인 순서(VLM → AUTOLABEL)로 내려온다.
        mockMvc.perform(get("/v1/videos/" + rawSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skippedStages.length()").value(2))
                .andExpect(jsonPath("$.data.skippedStages[0]").value("VLM"))
                .andExpect(jsonPath("$.data.skippedStages[1]").value("AUTOLABEL"));

        // when — 되돌리기(해제)
        mockMvc.perform(delete("/v1/videos/" + rawSn + "/batch/stages/VLM/skip")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());

        // then — 해제한 묶음은 목록에서 빠진다(화면의 되돌리기 조작이 즉시 반영된다).
        mockMvc.perform(get("/v1/videos/" + rawSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skippedStages.length()").value(1))
                .andExpect(jsonPath("$.data.skippedStages[0]").value("AUTOLABEL"));
    }
}
