package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.batch.vlm.VlmTimeseriesMetaPresence;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * VlmTimeseriesStep 단위 테스트 — describe 규격 정합 + 상관관계 배선 + <b>논블로킹 제출</b>(Phase C-1).
 *
 * <p>핵심 계약:
 * <ul>
 *   <li>위탁 전 (request_id → CHANNEL_VLM, rawSn) 매핑을 ledger 에 등록해 콜백 역조회를 성립시킨다.</li>
 *   <li>제출은 ACK 를 기다리지 않는다 — 반환 status 는 {@code submitted} 이며, 수락/실패는 완료
 *       핸들러({@link VlmSubmitOutcomeRecorder})가 비동기로 기록한다.</li>
 * </ul>
 *
 * <p>스케줄러는 {@link Schedulers#immediate()} 로 주입해 완료 핸들러 호출을 결정론적으로 검증한다
 * (전용 풀로의 오프로딩 자체는 {@code VlmTimeseriesStepNonBlockingTest} 가 별도로 고정한다).
 */
class VlmTimeseriesStepTest {

    private VlmClient vlmClient;
    private VideoRepository videoRepository;
    private IngestSourceRepository ingestSourceRepository;
    private BatchStatusService batchStatusService;
    private WebhookIdempotencyLedger ledger;
    private LsDeidentProcLogRepository deidentProcLogRepository;
    private DeidentReportGate deidentReportGate;
    private VlmMarkingTxService markingTxService;
    private VlmSubmitOutcomeRecorder outcomeRecorder;
    private VlmTimeseriesMetaPresence timeseriesMetaPresence;
    private VlmTimeseriesStep step;

    @BeforeEach
    void setUp() {
        vlmClient = mock(VlmClient.class);
        videoRepository = mock(VideoRepository.class);
        ingestSourceRepository = mock(IngestSourceRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        ledger = mock(WebhookIdempotencyLedger.class);
        deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);
        deidentReportGate = mock(DeidentReportGate.class);
        markingTxService = mock(VlmMarkingTxService.class);
        outcomeRecorder = mock(VlmSubmitOutcomeRecorder.class);
        timeseriesMetaPresence = mock(VlmTimeseriesMetaPresence.class);
        step = new VlmTimeseriesStep(vlmClient, videoRepository, ingestSourceRepository,
                batchStatusService, ledger, deidentProcLogRepository, deidentReportGate,
                markingTxService, outcomeRecorder, timeseriesMetaPresence,
                new ObjectMapper(), Schedulers.immediate());
    }

    /**
     * 관제 인입값(검증이벤트유형) 시드 — Phase 2 이후 위탁의 <b>사전 조건</b>이다.
     * 값이 없으면 스텝이 외부 호출 없이 SKIPPED 로 끝난다(@req R6).
     */
    private void seedEventType(Long rawSn, String vrfcEvntTypeCd) {
        IngestSourceRow row = mock(IngestSourceRow.class);
        lenient().when(row.getVrfcEvntTypeCd()).thenReturn(vrfcEvntTypeCd);
        lenient().when(ingestSourceRepository.findSourceMeta(rawSn)).thenReturn(row);
    }

    /** 비식별 경로가 존재하는 영상 시드 — existsById=true + 최신 성공 procLog 의 비식별 경로. */
    private void seed(Long rawSn, String deidPath) {
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        seedEventType(rawSn, "fire");
        LsDeidentProcLog plog = mock(LsDeidentProcLog.class);
        lenient().when(plog.getDeIdntfFilePathNm()).thenReturn(deidPath);
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(plog));
    }

    private void stubAccepted() {
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenAnswer(inv -> {
                    VlmTimeseriesRequest r = inv.getArgument(0);
                    return Mono.just(new VlmTimeseriesResponse(r.requestId(), "accepted"));
                });
    }

    @Test
    @DisplayName("enabled_false_시_외부_호출_0건_등록_0건_SKIPPED_반환")
    void enabledFalseNoOp() {
        when(vlmClient.isEnabled()).thenReturn(false);

        VlmTimeseriesResponse resp = step.run(100L);

        verify(vlmClient, never()).submitTimeseries(any());
        verify(videoRepository, never()).existsById(any());
        verify(ledger, never()).recordIssued(any(), any(), any(), any());
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("skipped");
    }

    // ---------- S7-VLM · 비식별 누락 신고 구간 외부 전송 차단 (HIGH-2 · CWE-359) ----------

    @Test
    @DisplayName("비식별_신고_구간_영상은_외부_VLM_호출_0건이고_보류로_기록된다")
    void underDeidentReportWithholdsExternalSubmit() {
        // given — VLM 활성 + 실재하는 영상이지만, 그 비식별본에 마스킹 누락 신고가 열려 있다.
        //          (비식별 경로 스텁을 일부러 두지 않는다 — 게이트가 경로 조회 <b>전에</b> 끊어야 한다.)
        when(videoRepository.existsById(300L)).thenReturn(true);
        when(vlmClient.isEnabled()).thenReturn(true);
        when(deidentReportGate.isUnderDeidentReport(300L)).thenReturn(true);

        // when
        VlmTimeseriesResponse resp = step.run(300L);

        // then — 외부 벤더로 나가는 상호작용이 0건이어야 한다(회수 불가 유출 차단).
        verify(vlmClient).isEnabled();
        verifyNoMoreInteractions(vlmClient);
        verifyNoInteractions(deidentProcLogRepository);
        verifyNoInteractions(ledger);
        // 선커밋(마킹 전이)도 하지 않는다 — 위탁 자체를 하지 않았으므로 상태를 올리면 안 된다.
        verifyNoInteractions(markingTxService);
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("skipped");
        verify(batchStatusService).recordVlmSkipped(300L, VlmTimeseriesStep.SKIP_REASON_DEIDENT_REPORT);
    }

    @Test
    @DisplayName("신고가_해소되면_같은_영상의_VLM_위탁이_재개된다")
    void resumesAfterDeidentReportResolved() {
        // given — 같은 영상에 대해 1차는 신고 구간(차단), 2차는 해소 후(통과).
        seed(310L, "/data/deid/310.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        when(deidentReportGate.isUnderDeidentReport(310L)).thenReturn(true, false);
        stubAccepted();

        // when — 1차 차단
        VlmTimeseriesResponse withheld = step.run(310L);
        // when — 2차(해소 후) 재개
        VlmTimeseriesResponse submitted = step.run(310L);

        // then
        assertThat(withheld.status()).isEqualTo("skipped");
        assertThat(submitted.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
        verify(vlmClient, times(1)).submitTimeseries(any());
        verify(ledger, times(1)).recordIssued(any(), eq(LsWebhookIdempotency.CHANNEL_VLM),
                isNull(), eq(310L));
    }

    @Test
    @DisplayName("verify_위탁_요청_바디에_event_type_비식별경로_frame_policy_callback_url_포함")
    void describeRequestBody() {
        seed(200L, "/data/deid/200.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        stubAccepted();

        step.run(200L);

        ArgumentCaptor<VlmTimeseriesRequest> captor = ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient, times(1)).submitTimeseries(captor.capture());
        VlmTimeseriesRequest req = captor.getValue();
        assertThat(req.requestId()).isNotBlank();
        assertThat(req.media()).isNotNull();
        assertThat(req.media().type()).isEqualTo("video");
        assertThat(req.media().sourceType()).isEqualTo("path");
        assertThat(req.media().path()).isEqualTo("/data/deid/200.mp4");
        assertThat(req.media().framePolicy().mode()).isEqualTo("frame_interval");
        assertThat(req.media().framePolicy().framerate()).isEqualTo(25);
        assertThat(req.eventType()).isEqualTo("fire");
        assertThat(req.callbackUrl()).endsWith("/v1/vlm/callback");
    }

    @Test
    @DisplayName("위탁_성공_시_recordIssued로_request_id가_CHANNEL_VLM_rawSn과_함께_ledger에_등록됨")
    void recordIssuedWiredWithChannelAndRawSn() {
        seed(210L, "/data/deid/210.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        stubAccepted();

        step.run(210L);

        ArgumentCaptor<VlmTimeseriesRequest> reqCap = ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient).submitTimeseries(reqCap.capture());
        String sentRequestId = reqCap.getValue().requestId();

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(ledger).recordIssued(keyCap.capture(), eq(LsWebhookIdempotency.CHANNEL_VLM),
                isNull(), eq(210L));
        assertThat(keyCap.getValue()).isEqualTo(sentRequestId);
    }

    @Test
    @DisplayName("recordIssued가_위탁_호출_전에_수행됨_등록실패시_위탁_미호출_EXTERNAL_API_ERROR")
    void recordIssuedFailureAbortsSubmit() {
        seed(211L, "/data/deid/211.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        doThrow(new RuntimeException("ledger down"))
                .when(ledger).recordIssued(any(), eq(LsWebhookIdempotency.CHANNEL_VLM), isNull(), eq(211L));

        assertThatThrownBy(() -> step.run(211L))
                .isInstanceOf(CustomException.class);
        // 매핑 없는 위탁 방지 — 외부 호출 미수행
        verify(vlmClient, never()).submitTimeseries(any());
    }

    @Test
    @DisplayName("eventName_marks_원문은_요청에_포함되지_않음")
    void noEventNameNoMarksInRequest() {
        seed(220L, "/data/deid/220.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        stubAccepted();

        step.run(220L);

        ArgumentCaptor<VlmTimeseriesRequest> captor = ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient).submitTimeseries(captor.capture());
        VlmTimeseriesRequest req = captor.getValue();
        assertThat(req.media().framePolicy().selectedFrames()).isNull();
        assertThat(req.callbackUrl()).doesNotContain("event");
    }

    @Test
    @DisplayName("rawSn_null_시_INVALID_INPUT_예외_등록_미수행")
    void nullRawSnRejected() {
        assertThatThrownBy(() -> step.run(null))
                .isInstanceOf(CustomException.class);
        verify(vlmClient, never()).submitTimeseries(any());
        verify(ledger, never()).recordIssued(any(), any(), any(), any());
    }

    @Test
    @DisplayName("영상_미존재_시_NOT_FOUND_예외_등록_미수행")
    void videoNotFoundRejected() {
        when(vlmClient.isEnabled()).thenReturn(true);
        when(videoRepository.existsById(202L)).thenReturn(false);

        assertThatThrownBy(() -> step.run(202L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("영상");
        verify(vlmClient, never()).submitTimeseries(any());
        verify(ledger, never()).recordIssued(any(), any(), any(), any());
    }

    @Test
    @DisplayName("비식별_경로_없으면_원본_미전송_fail_closed_EXTERNAL_API_ERROR")
    void missingDeidPathFailsClosed() {
        when(vlmClient.isEnabled()).thenReturn(true);
        when(videoRepository.existsById(230L)).thenReturn(true);
        seedEventType(230L, "fire");
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(230L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> step.run(230L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("비식별");
        verify(vlmClient, never()).submitTimeseries(any());
        verify(ledger, never()).recordIssued(any(), any(), any(), any());
    }

    /**
     * ★ 계약 변경(Phase C-1): 구 테스트는 외부 호출 실패가 {@code EXTERNAL_API_ERROR} 로 <b>동기 전파</b>
     * 되는 것을 고정했다(→ BatchOrchestrator FAILED + BatchRetryQueue). 제출이 논블로킹이 되면 실패는
     * 파이프라인 스레드 밖에서 발생하므로 그 사슬은 성립하지 않으며, 되살려서도 안 된다(재시도 큐는
     * rawSn 단위로 파이프라인 전체를 재실행한다). 이제 실패는 <b>완료 핸들러에 위임</b>되고
     * 파이프라인 스레드로는 예외가 새지 않는다.
     */
    @Test
    @DisplayName("외부_호출_실패는_파이프라인_스레드로_전파되지_않고_완료핸들러에_위임된다")
    void clientFailureDelegatedToOutcomeRecorder() {
        seed(201L, "/data/deid/201.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        RuntimeException boom = new RuntimeException("vlm down");
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.error(boom));

        VlmTimeseriesResponse resp = step.run(201L);

        assertThat(resp.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
        verify(outcomeRecorder).onSubmitFailed(eq(201L), isNull(), eq(boom));
        verify(outcomeRecorder, never()).onAccepted(any(), any(), any());
    }

    /**
     * ★ 계약 변경(Phase C-1): 빈 응답 가드는 유지하되 <b>비동기 실패 경로</b>로 옮겼다.
     * {@code Mono.empty()} 는 onNext/onError 어느 핸들러도 타지 않아 무흔적 유실이 되므로,
     * {@code switchIfEmpty} 로 실패 신호로 승격해 완료 핸들러가 기록·회수하게 한다.
     */
    @Test
    @DisplayName("빈_응답도_무흔적_유실되지_않고_제출실패로_기록된다")
    void emptyResponseRoutedToFailureHandler() {
        seed(203L, "/data/deid/203.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class))).thenReturn(Mono.empty());

        VlmTimeseriesResponse resp = step.run(203L);

        assertThat(resp.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
        ArgumentCaptor<Throwable> causeCap = ArgumentCaptor.forClass(Throwable.class);
        verify(outcomeRecorder).onSubmitFailed(eq(203L), isNull(), causeCap.capture());
        assertThat(causeCap.getValue()).isInstanceOf(CustomException.class);
        assertThat(causeCap.getValue()).hasMessageContaining("응답");
    }

    @Test
    @DisplayName("ACK_수신시_완료핸들러가_수락응답을_넘겨받는다")
    void ackDelegatedToOutcomeRecorder() {
        seed(300L, "/data/deid/300.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.just(new VlmTimeseriesResponse("REQ-300", "accepted")));

        step.run(300L);

        ArgumentCaptor<VlmTimeseriesResponse> respCap =
                ArgumentCaptor.forClass(VlmTimeseriesResponse.class);
        verify(outcomeRecorder, times(1)).onAccepted(eq(300L), any(), respCap.capture());
        assertThat(respCap.getValue().requestId()).isEqualTo("REQ-300");
        assertThat(respCap.getValue().status()).isEqualTo("accepted");
        verify(outcomeRecorder, never()).onSubmitFailed(any(), any(), any());
    }

    /**
     * B-ISSUE-24 — skip 이 DB 에 무흔적이면 VLM 비활성/장애 구간에 처리된 영상이 "메타 없음 + 무기록"
     * 으로 남아 재처리 대상 식별이 애플리케이션 로그 보존기간에 종속된다.
     */
    @Test
    @DisplayName("VLM_비활성일_때_LS_BATCH_PROC_LOG_에_VLM_SKIPPED_행이_사유와_함께_남는다")
    void skipRecordedWhenDisabled() {
        when(vlmClient.isEnabled()).thenReturn(false);

        step.run(301L);

        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(batchStatusService, times(1)).recordVlmSkipped(eq(301L), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue())
                .as("사유가 비어 있으면 기록의 목적(재처리 대상 식별)을 달성하지 못한다")
                .isNotBlank()
                .contains("vlm.client.enabled");
        verify(batchStatusService, never()).recordVlmTimeseriesResult(any(), any());
    }

    // ---------- 재실행 멱등 (@req R1) — 배치 재시도가 같은 영상을 중복 위탁하지 않는다 ----------

    /**
     * 시나리오 4 — VLM 위탁 후 뒷단계(프레임추출·YOLO·SAM2)가 실패해 재시도되면, 콜백으로 결과가 이미
     * 적재된 영상은 <b>외부 호출 0회</b>로 통과해야 한다. 이 가드가 없으면 재시도마다 새 request_id 로
     * 같은 비식별 영상이 외부 벤더에 재위탁된다(비용·레이트리밋 + 상관키 다중화).
     */
    @Test
    @DisplayName("시계열_메타가_이미_있으면_재시도해도_외부_위탁이_0회다")
    void idempotentSkipWhenTimeseriesMetaPresent() {
        // given — 위탁 가능한 영상이지만 콜백으로 시계열 메타가 이미 적재됐다.
        seed(400L, "/data/deid/400.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        when(timeseriesMetaPresence.count(400L)).thenReturn(3L);

        // when
        VlmTimeseriesResponse resp = step.run(400L);

        // then — 외부 호출·상관키 발급·마킹 선커밋 전부 0건.
        verify(vlmClient, never()).submitTimeseries(any());
        verify(ledger, never()).recordIssued(any(), any(), any(), any());
        verifyNoInteractions(markingTxService);
        assertThat(resp.status()).isEqualTo("skipped");
        // ★ SKIPPED 축을 오염시키지 않는다 — 그 축은 "재개가 필요한 보류" 전용이며, 여기에 사유를 남기면
        //   재개 러너가 이미 결과가 있는 영상을 재위탁 후보로 집는다.
        verify(batchStatusService, never()).recordVlmSkipped(any(), any());
        verify(batchStatusService, never()).recordVlmSkippedInNewTx(any(), any());
    }

    /**
     * 시나리오 5 — 원장이 미결({@code ISSUED}/{@code ACCEPTED})이면 콜백 대기 중이므로 재위탁하지 않는다.
     * 회수는 미결 스위퍼의 책임이며, 여기서 다시 보내면 같은 영상에 상관키가 둘 생긴다.
     */
    @Test
    @DisplayName("원장이_미결이면_재시도해도_재위탁하지_않는다")
    void idempotentSkipWhenSubmitOutstanding() {
        // given — 메타는 아직 없지만(콜백 미도착) 원장에 미결 위탁이 남아 있다.
        seed(401L, "/data/deid/401.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        when(timeseriesMetaPresence.count(401L)).thenReturn(0L);
        when(ledger.hasOutstandingSubmit(LsWebhookIdempotency.CHANNEL_VLM, 401L)).thenReturn(true);

        // when
        VlmTimeseriesResponse resp = step.run(401L);

        // then
        verify(vlmClient, never()).submitTimeseries(any());
        verify(ledger, never()).recordIssued(any(), any(), any(), any());
        verifyNoInteractions(markingTxService);
        assertThat(resp.status()).isEqualTo("skipped");
    }

    /**
     * 시나리오 6(회귀 — 가장 중요) — 멱등 가드가 <b>정상 최초 실행</b>을 막아서는 안 된다.
     * 메타 0건 + 원장 미결 없음이면 종전과 동일하게 위탁이 나간다.
     */
    @Test
    @DisplayName("메타_0건이고_미결도_없으면_멱등_가드가_최초_위탁을_막지_않는다")
    void firstRunNotBlockedByIdempotencyGuards() {
        seed(402L, "/data/deid/402.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        when(timeseriesMetaPresence.count(402L)).thenReturn(0L);
        when(ledger.hasOutstandingSubmit(LsWebhookIdempotency.CHANNEL_VLM, 402L)).thenReturn(false);
        stubAccepted();

        VlmTimeseriesResponse resp = step.run(402L);

        assertThat(resp.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
        verify(vlmClient, times(1)).submitTimeseries(any());
        verify(ledger, times(1)).recordIssued(any(), eq(LsWebhookIdempotency.CHANNEL_VLM),
                isNull(), eq(402L));
    }

    /**
     * 게이트 순서 회귀 — 멱등 게이트는 <b>신고 게이트 뒤</b>에 있어야 한다. 앞에 두면 신고 구간에서
     * 보류 사유가 기록되지 않아, 해소 시 재개 트리거가 그 영상을 찾지 못해 시계열 메타가 영구 결손된다.
     */
    @Test
    @DisplayName("신고_구간에서는_멱등_게이트보다_신고_보류_사유가_먼저_기록된다")
    void deidentReportGateEvaluatedBeforeIdempotencyGate() {
        when(videoRepository.existsById(403L)).thenReturn(true);
        when(vlmClient.isEnabled()).thenReturn(true);
        when(deidentReportGate.isUnderDeidentReport(403L)).thenReturn(true);
        // 메타가 이미 있어도(멱등 조건 충족) 신고 보류 사유가 남아야 한다.
        lenient().when(timeseriesMetaPresence.count(403L)).thenReturn(5L);

        step.run(403L);

        verify(batchStatusService).recordVlmSkipped(403L, VlmTimeseriesStep.SKIP_REASON_DEIDENT_REPORT);
        verify(vlmClient, never()).submitTimeseries(any());
    }

    @Test
    @DisplayName("위탁_성공_경로에서는_SKIPPED_를_기록하지_않는다")
    void noSkipRecordOnSuccess() {
        seed(302L, "/data/deid/302.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        stubAccepted();

        step.run(302L);

        verify(batchStatusService, never()).recordVlmSkipped(any(), any());
        verify(batchStatusService, never()).recordVlmSkippedInNewTx(any(), any());
    }
}
