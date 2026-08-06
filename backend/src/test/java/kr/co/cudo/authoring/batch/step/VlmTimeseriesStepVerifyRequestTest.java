package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 — 외부 VLM 위탁 규격 전환(describe → <b>verify</b>) 계약 고정.
 *
 * <p>여기서 고정하는 것은 셋이다:
 * <ol>
 *   <li><b>{@code event_type}</b> 조달·게이트 — 관제 인입값({@code LS_DATA_INGEST.VRFC_EVNT_TYPE_CD})
 *       이 없거나 허용목록(6종) 밖이면 <b>외부 호출 0건 + 선커밋 0건</b> + SKIPPED 기록.</li>
 *   <li><b>{@code frame_policy}</b> 마킹 도출 — 수동 마킹이면 {@code frame_selected}(정렬·중복제거·
 *       상한 8), 자동 마킹이면 {@code frame_interval}(framerate = 마킹 프레임 간격).</li>
 *   <li><b>재개 경로 동형성</b> — {@code BatchContext} 없이 마킹 엔티티만 주어져도 최초 위탁과 같은
 *       frame_policy 가 나온다({@code VlmWithheldResumeRunner}/{@code VlmSubmitPendingSweeper} 경로).</li>
 * </ol>
 */
class VlmTimeseriesStepVerifyRequestTest {

    private VlmClient vlmClient;
    private kr.co.cudo.authoring.video.repository.VideoRepository videoRepository;
    private IngestSourceRepository ingestSourceRepository;
    private BatchStatusService batchStatusService;
    private WebhookIdempotencyLedger ledger;
    private LsDeidentProcLogRepository deidentProcLogRepository;
    private DeidentReportGate deidentReportGate;
    private VlmMarkingTxService markingTxService;
    private VlmSubmitOutcomeRecorder outcomeRecorder;
    private VlmTimeseriesStep step;

    @BeforeEach
    void setUp() {
        vlmClient = mock(VlmClient.class);
        videoRepository = mock(kr.co.cudo.authoring.video.repository.VideoRepository.class);
        ingestSourceRepository = mock(IngestSourceRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        ledger = mock(WebhookIdempotencyLedger.class);
        deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);
        deidentReportGate = mock(DeidentReportGate.class);
        markingTxService = mock(VlmMarkingTxService.class);
        outcomeRecorder = mock(VlmSubmitOutcomeRecorder.class);
        step = new VlmTimeseriesStep(vlmClient, videoRepository, ingestSourceRepository,
                batchStatusService, ledger, deidentProcLogRepository, deidentReportGate,
                markingTxService, outcomeRecorder, new ObjectMapper(), Schedulers.immediate());
    }

    /** 위탁 가능한 영상 시드 — 활성 토글 + 영상 존재 + 비식별 경로 + 관제 검증이벤트유형. */
    private void seed(Long rawSn, String vrfcEvntTypeCd) {
        when(vlmClient.isEnabled()).thenReturn(true);
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        LsDeidentProcLog plog = mock(LsDeidentProcLog.class);
        lenient().when(plog.getDeIdntfFilePathNm()).thenReturn("/data/deid/" + rawSn + ".mp4");
        lenient().when(deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(plog));
        IngestSourceRow row = mock(IngestSourceRow.class);
        lenient().when(row.getVrfcEvntTypeCd()).thenReturn(vrfcEvntTypeCd);
        lenient().when(ingestSourceRepository.findSourceMeta(rawSn)).thenReturn(row);
        lenient().when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenAnswer(inv -> {
                    VlmTimeseriesRequest r = inv.getArgument(0);
                    return Mono.just(new VlmTimeseriesResponse(r.requestId(), "accepted"));
                });
    }

    private VlmTimeseriesRequest captureRequest() {
        ArgumentCaptor<VlmTimeseriesRequest> captor = ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient).submitTimeseries(captor.capture());
        return captor.getValue();
    }

    private LsMarking manualMarking(Long rawSn, String marksJson) {
        return LsMarking.createManual(rawSn, "화재", "/raw/" + rawSn + ".mp4", marksJson, 1L);
    }

    private static String marks(int... frameIndexes) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < frameIndexes.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"frameIndex\":").append(frameIndexes[i]).append(",\"timestamp\":\"00:0")
                    .append(i % 10).append("\"}");
        }
        return sb.append(']').toString();
    }

    // ───────────────────────── frame_policy — 수동 마킹 ─────────────────────────

    @Test
    @DisplayName("수동마킹이면_frame_selected와_selected_frames를_보낸다")
    void manualMarkingSendsFrameSelected() {
        // given
        seed(600L, "fire");
        LsMarking marking = manualMarking(600L, marks(10, 25, 40));

        // when
        step.runWithMarking(600L, marking);

        // then
        VlmTimeseriesRequest.FramePolicy policy = captureRequest().media().framePolicy();
        assertThat(policy.mode()).isEqualTo("frame_selected");
        assertThat(policy.selectedFrames()).containsExactly(10, 25, 40);
    }

    @Test
    @DisplayName("수동마킹_프레임이_8개를_초과하면_frameIndex_오름차순_앞_8개만_보낸다")
    void manualMarkingTruncatesToEightSorted() {
        // given — 저장 순서가 시간순이라는 보장이 없으므로 일부러 뒤섞어 넣는다.
        seed(601L, "fall");
        LsMarking marking = manualMarking(601L, marks(90, 10, 70, 30, 50, 20, 80, 40, 60, 100));

        // when
        step.runWithMarking(601L, marking);

        // then — 정렬 후 앞 8개(벤더 규격 §3.2 selected_frames 최대 8)
        VlmTimeseriesRequest.FramePolicy policy = captureRequest().media().framePolicy();
        assertThat(policy.mode()).isEqualTo("frame_selected");
        assertThat(policy.selectedFrames()).containsExactly(10, 20, 30, 40, 50, 60, 70, 80);
    }

    @Test
    @DisplayName("selected_frames의_중복_프레임은_제거된다")
    void manualMarkingDeduplicatesFrames() {
        // given — 중복이 섞이면 벤더가 422(비재시도 영구 실패)를 낼 수 있다.
        seed(602L, "violence");
        LsMarking marking = manualMarking(602L, marks(5, 5, 12, 12, 12, 3));

        // when
        step.runWithMarking(602L, marking);

        // then
        assertThat(captureRequest().media().framePolicy().selectedFrames())
                .containsExactly(3, 5, 12);
    }

    // ───────────────────────── frame_policy — 자동 마킹 ─────────────────────────

    @Test
    @DisplayName("자동마킹이면_frame_interval을_보내고_selected_frames는_없다")
    void autoMarkingSendsFrameInterval() {
        // given
        seed(610L, "flooding");
        LsMarking marking = LsMarking.createAuto(610L, "침수", 15, "/raw/610.mp4", marks(0, 15), 1L);

        // when
        step.runWithMarking(610L, marking);

        // then
        VlmTimeseriesRequest.FramePolicy policy = captureRequest().media().framePolicy();
        assertThat(policy.mode()).isEqualTo("frame_interval");
        assertThat(policy.selectedFrames()).isNull();
    }

    @Test
    @DisplayName("자동마킹의_framerate는_마킹_프레임간격_값이다")
    void autoMarkingFramerateComesFromMarkingInterval() {
        // given — 벤더 §2.1: framerate 는 "몇 프레임당 1장" 이므로 마킹 프레임 간격이 대응값이다.
        seed(611L, "kidnapping");
        LsMarking marking = LsMarking.createAuto(611L, "납치", 12, "/raw/611.mp4", marks(0, 12), 1L);

        // when
        step.runWithMarking(611L, marking);

        // then — 설정 기본값(25)이 아니라 마킹 간격 12
        assertThat(captureRequest().media().framePolicy().framerate()).isEqualTo(12);
    }

    @Test
    @DisplayName("마킹_프레임간격이_0이거나_null이면_설정_기본값으로_폴백한다")
    void autoMarkingFallsBackWhenIntervalNotPositive() {
        // given — framerate=0 을 그대로 보내면 벤더 422(비재시도 영구 실패)다.
        seed(612L, "fire");
        LsMarking marking = manualMarking(612L, "[]"); // 수동 모드는 frmeIntvNocs 가 null

        // when — marks 가 비어 frame_selected 가 성립하지 않아 interval 로 폴백
        step.runWithMarking(612L, marking);

        // then
        VlmTimeseriesRequest.FramePolicy policy = captureRequest().media().framePolicy();
        assertThat(policy.mode()).isEqualTo("frame_interval");
        assertThat(policy.framerate()).isEqualTo(25);
    }

    @Test
    @DisplayName("framerate는_모드와_무관하게_항상_전송된다")
    void framerateAlwaysPresent() {
        // given — 벤더 §3.2: framerate 는 mode 무관 필수.
        seed(613L, "fire");
        LsMarking manual = manualMarking(613L, marks(7, 9));

        // when
        step.runWithMarking(613L, manual);

        // then
        VlmTimeseriesRequest.FramePolicy policy = captureRequest().media().framePolicy();
        assertThat(policy.mode()).isEqualTo("frame_selected");
        assertThat(policy.framerate()).isNotNull();
        assertThat(policy.framerate()).isPositive();
    }

    @Test
    @DisplayName("markCn_파싱에_실패하면_예외없이_frame_interval로_폴백한다")
    void malformedMarkCnFallsBackWithoutException() {
        // given — 파싱 실패가 예외로 터지면 파이프라인은 FAILED + 전량 재실행, 재개 경로는 영구 대기다.
        seed(614L, "fire");
        LsMarking marking = manualMarking(614L, "{not-json");

        // when
        VlmTimeseriesResponse resp = step.runWithMarking(614L, marking);

        // then — 위탁 자체는 진행되고 정책만 안전한 기본값으로 내려간다(fail-secure).
        assertThat(resp.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
        VlmTimeseriesRequest.FramePolicy policy = captureRequest().media().framePolicy();
        assertThat(policy.mode()).isEqualTo("frame_interval");
        assertThat(policy.selectedFrames()).isNull();
    }

    @Test
    @DisplayName("미지의_마킹모드는_frame_interval로_폴백하고_경고한다")
    void unknownMarkModeFallsBackToInterval() {
        // given — 미지의 모드가 조용히 흡수되면 추적이 불가능하므로 폴백 + WARN 이 계약이다.
        seed(615L, "fire");
        LsMarking marking = mock(LsMarking.class);
        when(marking.getMarkModeCd()).thenReturn("SEMI_AUTO");
        lenient().when(marking.getMarkCn()).thenReturn(marks(1, 2, 3));
        lenient().when(marking.getFrmeIntvNocs()).thenReturn(null);

        // when
        step.runWithMarking(615L, marking);

        // then
        VlmTimeseriesRequest.FramePolicy policy = captureRequest().media().framePolicy();
        assertThat(policy.mode()).isEqualTo("frame_interval");
        assertThat(policy.selectedFrames()).isNull();
        assertThat(policy.framerate()).isEqualTo(25);
    }

    // ───────────────────────── event_type 조달·게이트 ─────────────────────────

    @Test
    @DisplayName("요청_바디에_event_type이_포함된다")
    void requestCarriesEventType() {
        // given
        seed(620L, "car_accident");

        // when
        step.run(620L);

        // then
        assertThat(captureRequest().eventType()).isEqualTo("car_accident");
    }

    @Test
    @DisplayName("검증이벤트유형이_없어도_위탁한다_구_사전차단_폐기")
    void missingEventTypeStillSubmits() {
        // given — 관제가 아직 값을 채우지 않은 영상. 구 동작은 여기서 SKIPPED(보류)였으나
        //         2026-08-06 사용자 확정으로 <b>항상 위탁</b>하고 수용 여부는 벤더 응답이 정한다.
        seed(621L, null);

        // when
        VlmTimeseriesResponse resp = step.run(621L);

        // then — 값을 지어내지 않으므로 event_type 은 null 그대로 나간다(벤더 필수 필드라 422 예상).
        assertThat(resp.status()).isEqualTo("submitted");
        assertThat(captureRequest().eventType()).isNull();
        verify(batchStatusService, never()).recordVlmSkipped(621L,
                VlmTimeseriesStep.SKIP_REASON_EVENT_TYPE_MISSING);
    }

    @Test
    @DisplayName("우리가_아는_6종_밖의_검증이벤트유형도_그대로_위탁한다_구_허용목록_차단_폐기")
    void unsupportedEventTypeStillSubmits() {
        // given — 허용목록은 우리 쪽 사본이라, 벤더가 enum 을 넓히면 정상 값을 우리가 먼저 막게 된다.
        //         판정의 단일 진실원을 벤더 응답으로 옮겼다.
        seed(622L, "trespassing");

        // when
        VlmTimeseriesResponse resp = step.run(622L);

        // then — 정규화(trim·소문자)만 적용하고 값 자체는 변형·차단하지 않는다.
        assertThat(resp.status()).isEqualTo("submitted");
        assertThat(captureRequest().eventType()).isEqualTo("trespassing");
        verify(batchStatusService, never()).recordVlmSkipped(622L,
                VlmTimeseriesStep.SKIP_REASON_EVENT_TYPE_UNSUPPORTED);
    }

    @Test
    @DisplayName("검증이벤트유형이_없어도_선커밋_배선은_정상_수행된다")
    void missingEventTypeStillPreCommits() {
        // given
        seed(623L, null);

        // when
        step.runWithMarking(623L, manualMarking(623L, marks(1, 2)));

        // then — 위탁이 실제로 나가므로 상관키 등록·마킹 전이가 반드시 durable 커밋돼야 한다.
        //        (구 동작은 여기서 게이트에 걸려 두 배선을 건너뛰었다.)
        verify(ledger).recordIssued(any(), any(), any(), org.mockito.ArgumentMatchers.eq(623L));
        verify(markingTxService).persistVlmRequested(any());
    }

    @Test
    @DisplayName("비식별_신고_구간이면_event_type_조달보다_먼저_보류된다")
    void deidentReportGatePrecedesEventTypeLookup() {
        // given — 신고 구간이면서 검증이벤트유형도 없는 영상.
        seed(624L, null);
        when(deidentReportGate.isUnderDeidentReport(624L)).thenReturn(true);

        // when
        step.run(624L);

        // then — 신고 보류는 그대로 유지된다(개인정보 축이라 이번 정책 반전과 무관).
        verify(batchStatusService).recordVlmSkipped(624L,
                VlmTimeseriesStep.SKIP_REASON_DEIDENT_REPORT);
        verify(vlmClient, never()).submitTimeseries(any());
        verify(ingestSourceRepository, never()).findSourceMeta(anyLong());
    }

    @Test
    @DisplayName("영상_인입행이_없어도_예외없이_위탁한다")
    void missingIngestRowStillSubmits() {
        // given — findSourceMeta 는 영상 행이 없으면 null 을 돌려준다.
        seed(625L, "fire");
        when(ingestSourceRepository.findSourceMeta(625L)).thenReturn(null);

        // when
        VlmTimeseriesResponse resp = step.run(625L);

        // then — 조달 실패가 곧 차단은 아니다. null 인 채로 위탁하고 벤더 응답이 판정한다.
        assertThat(resp.status()).isEqualTo("submitted");
        assertThat(captureRequest().eventType()).isNull();
    }

    @Test
    @DisplayName("구_보류사유_상수는_과거행_판독을_위해_재개목록에_존치된다")
    void legacyEventTypeSkipReasonsRemainResumable() {
        // 신규 발생은 없지만 이미 적재된 LS_BATCH_PROC_LOG 행의 재개 배선이 이 값에 걸려 있다.
        // 상수를 지우거나 문자열을 바꾸면 과거 보류분이 영구 고착된다.
        assertThat(VlmTimeseriesStep.RESUMABLE_SKIP_REASONS)
                .contains(VlmTimeseriesStep.SKIP_REASON_EVENT_TYPE_MISSING,
                        VlmTimeseriesStep.SKIP_REASON_EVENT_TYPE_UNSUPPORTED);
    }

    // ───────────────────────── 재개 경로 동형성 ─────────────────────────

    @Test
    @DisplayName("재개_경로에서도_수동마킹이_frame_selected로_전송된다")
    void resumePathDerivesFrameSelectedFromMarkingEntity() {
        // given — VlmWithheldResumeRunner / VlmSubmitPendingSweeper 는 BatchContext 없이
        //          마킹 엔티티만 들고 runWithMarking 을 직접 호출한다. ctx.getMarks() 에 의존해
        //          도출하면 이 경로에서 항상 빈 리스트가 되어 수동 마킹이 조용히 강등된다.
        seed(630L, "fire");
        LsMarking marking = manualMarking(630L, marks(4, 8, 15));

        // when — 파이프라인(execute) 을 거치지 않는 재개 경로와 동일한 호출
        step.runWithMarking(630L, marking);

        // then
        VlmTimeseriesRequest.FramePolicy policy = captureRequest().media().framePolicy();
        assertThat(policy.mode()).isEqualTo("frame_selected");
        assertThat(policy.selectedFrames()).containsExactly(4, 8, 15);
    }
}
