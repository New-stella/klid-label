package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.status.BatchBundleFailureGate;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.ManualStageSkip;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영상 처리 현황 목록({@code GET /v1/videos})의 <b>실패한 작업 묶음 필터</b> 회귀 가드.
 * [@design API-042] [@design ADR-050]
 *
 * <h2>왜 이 필터가 필요한가 (사람이 결정하는 입구가 닫혀 있었다)</h2>
 * <p>건너뛰기는 그 묶음이 <b>실패한</b> 영상에만 열린다. 그런데 시계열 위탁은 논블로킹이라 실패해도
 * 예외가 위로 올라가지 않아 <b>배치 단계 상태가 완료로 남고</b> 진행 축에도 {@code FAILED} 가 남지
 * 않는다. 즉 {@code dataSttsCd=FAILED} 로는 벤더 장애로 실패한 영상을 <b>한 건도 모을 수 없었고</b>,
 * 일괄 건너뛰기가 쓰이는 바로 그 자리에서 대상을 골라낼 방법이 없었다.
 *
 * <h2>고정하는 계약</h2>
 * <ol>
 *   <li><b>두 축을 모두</b> 본다 — 진행 축 실패(오토라벨) · 위탁 실패 감사 행(시계열).</li>
 *   <li><b>하위호환</b> — 이 파라미터를 보내지 않던 호출의 결과가 조금도 달라지지 않는다.</li>
 *   <li><b>미지 값은 400</b>, 요청 값을 되비추지 않는다.</li>
 *   <li>{@code skippedStage} 와 <b>함께</b> 지정할 수 있다(축이 다르다).</li>
 *   <li>표식·감사 행이 쌓여도 <b>총건수가 틀어지지 않는다</b>({@code EXISTS} 를 쓰는 이유).</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VideoListFailedBundleFilterIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private BatchStatusService batchStatusService;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    /** 이 클래스가 심은 영상만 고르는 검색어 — 다른 시드에 기대 건수가 종속되지 않게 한다. */
    private String tag;

    private Long vlmSubmitFailed;
    private Long autolabelFailed;
    private Long vlmWithheld;
    private Long untouched;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        tag = "failfilterit" + System.nanoTime();

        vlmSubmitFailed = saveVideo();
        autolabelFailed = saveVideo();
        vlmWithheld = saveVideo();
        untouched = saveVideo();

        // 시계열 — 위탁 확정 실패(감사 행). 진행 축에는 아무것도 남지 않는다(논블로킹 제출).
        batchStatusService.recordVlmSkipped(vlmSubmitFailed, VlmTimeseriesStep.SKIP_REASON_SUBMIT_FAILED);
        // 오토라벨 — 진행 축 실패.
        batchStatusService.markStage(autolabelFailed, BatchStage.YOLO);
        batchStatusService.markFailed(autolabelFailed, new IllegalStateException("ai-server down"));
        // 시계열 — 스스로 재개되는 <b>보류</b>. 실패가 아니므로 걸리면 안 된다.
        batchStatusService.recordVlmSkipped(vlmWithheld, VlmTimeseriesStep.SKIP_REASON_DEIDENT_REPORT);
    }

    private Long saveVideo() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + tag + "-" + System.nanoTime(), tag, "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        return videoRepository.save(raw).getRawSn();
    }

    private ResultActions list(String extraQuery) throws Exception {
        return mockMvc.perform(get("/v1/videos?size=50&cctvNameKeyword=" + tag + extraQuery)
                .header("Authorization", "Bearer " + reviewerToken));
    }

    /**
     * ★★이 케이스가 이 필터의 존재 이유다 — 위탁 실패 영상은 배치 단계가 <b>완료</b>로 남으므로
     * 다른 어떤 필터로도 잡히지 않는다.
     */
    @Test
    @DisplayName("★★시계열_위탁_확정_실패_영상만_남는다_진행축에는_흔적이_없다")
    void filtersVlmSubmitFailure() throws Exception {
        list("&failedStage=VLM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].rawSn").value(vlmSubmitFailed));
    }

    @Test
    @DisplayName("★오토라벨은_진행_축_실패로_잡힌다")
    void filtersAutolabelProgressFailure() throws Exception {
        list("&failedStage=AUTOLABEL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].rawSn").value(autolabelFailed));
    }

    /**
     * ★스스로 재개되는 보류를 실패로 읽으면 <b>정상 영상을 건너뛰는 길</b>이 다시 열린다 —
     * 화면이 그 영상에 건너뛰기 버튼을 띄우는데 서버는 412 로 튕기는 어긋남도 함께 생긴다.
     */
    @Test
    @DisplayName("★★재개되는_보류_영상은_실패_필터에_걸리지_않는다")
    void withheldVideoIsNotFailure() throws Exception {
        list("&failedStage=VLM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.rawSn == " + vlmWithheld + ")]").isEmpty())
                .andExpect(jsonPath("$.data.content[?(@.rawSn == " + untouched + ")]").isEmpty());
    }

    /** ★★신규 파라미터는 optional 이고 BE 기본값을 바꾸지 않는다. */
    @Test
    @DisplayName("★★하위호환_파라미터를_보내지_않으면_전건이_그대로_나온다")
    void backwardCompatibleWhenParamAbsent() throws Exception {
        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4));

        // 빈 문자열도 「미지정」이다(FE 가 필터를 비운 상태로 보낼 수 있다).
        list("&failedStage=")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4));
    }

    /**
     * ★두 필터는 축이 다르다 — 한쪽은 「사람이 건너뛴 상태」, 다른 쪽은 「실패한 상태」. 함께 지정하면
     * <b>교집합</b>이며, 한쪽이 다른 쪽을 덮어쓰면 화면이 엉뚱한 목록을 보여준다.
     */
    @Test
    @DisplayName("★건너뜀_필터와_함께_지정하면_교집합이다")
    void combinesWithSkippedStageFilter() throws Exception {
        // 실패 영상 하나만 건너뛴 상태로 만든다.
        batchStatusService.recordManualStageSkip(vlmSubmitFailed, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");

        list("&failedStage=VLM&skippedStage=VLM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].rawSn").value(vlmSubmitFailed));

        // 실패했지만 건너뛰지 않은 영상은 교집합에서 빠진다.
        list("&failedStage=AUTOLABEL&skippedStage=VLM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    /**
     * ★감사 행·표식이 쌓여도 목록 행이 증식하면 안 된다({@code EXISTS} 를 쓰는 이유) — 조인이면
     * 행 개수만큼 목록이 늘어 {@code totalElements} 까지 틀어진다.
     */
    @Test
    @DisplayName("★★실패_감사행이_여러_개_쌓여도_한_번만_나오고_총건수가_틀어지지_않는다")
    void auditRowsDoNotMultiplyRows() throws Exception {
        batchStatusService.recordVlmSkipped(vlmSubmitFailed, VlmTimeseriesStep.SKIP_REASON_ACK_MISSING);
        batchStatusService.recordVlmSkipped(vlmSubmitFailed, VlmTimeseriesStep.SKIP_REASON_CALLBACK_MISSING);
        batchStatusService.recordManualStageSkip(vlmSubmitFailed, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");

        list("&failedStage=VLM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1));
    }

    /**
     * ★미지 값은 0건이 아니라 <b>400</b> 이다 — 조용히 0건을 주면 화면이 "실패한 영상이 없다"로 오독해
     * 벤더 장애를 못 본다.
     */
    @Test
    @DisplayName("★지원하지_않는_값은_400이고_요청_값을_되비추지_않는다")
    void unsupportedValueIsRejected() throws Exception {
        list("&failedStage=YOLO")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        list("&failedStage=%3Cscript%3Ealert(1)%3C/script%3E")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("script"))));
    }

    /**
     * ★쿼리에 박아 둔 코드값이 소유자의 상수와 갈리면 필터가 <b>조용히</b> 0건이 된다 — 조회 결과가
     * 비는 것은 오류로 드러나지 않으므로 상수 동일성을 기계로 고정한다
     * ({@code VideoListSkippedBundleFilterIT} 와 같은 관례).
     */
    @Test
    @DisplayName("★★쿼리_상수는_판정_소유자의_상수와_같아야_한다_갈리면_조용히_0건이_된다")
    void queryConstantsMatchOwners() {
        assertThat(VideoRepository.PROGRESS_FAILED_STTS_CD).isEqualTo("FAILED");
        assertThat(VideoRepository.VLM_STAGE_CD).isEqualTo(BatchStage.VLM.name());
        assertThat(BatchBundleFailureGate.vlmFailureSkipReasons())
                .containsExactly(
                        VlmTimeseriesStep.SKIP_REASON_SUBMIT_FAILED,
                        VlmTimeseriesStep.SKIP_REASON_ACK_MISSING,
                        VlmTimeseriesStep.SKIP_REASON_CALLBACK_MISSING);
    }
}
