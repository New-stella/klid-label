package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

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
 * VlmTimeseriesStep 단위 테스트 — 벤더 확정 계약(v2.0.1) describe 규격 정합 + 상관관계 배선(Phase 2).
 *
 * <p>핵심: 위탁 성공 시 (request_id → CHANNEL_VLM, rawSn) 매핑을 ledger 에 등록해 콜백 역조회를 성립시킨다(결함1/2 폐쇄).
 */
class VlmTimeseriesStepTest {

    private VlmClient vlmClient;
    private VideoRepository videoRepository;
    private BatchStatusService batchStatusService;
    private ObjectMapper objectMapper;
    private WebhookIdempotencyLedger ledger;
    private LsDeidentProcLogRepository deidentProcLogRepository;
    private LsMarkingRepository markingRepository;
    private DeidentReportGate deidentReportGate;
    private VlmTimeseriesStep step;

    @BeforeEach
    void setUp() {
        vlmClient = mock(VlmClient.class);
        videoRepository = mock(VideoRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        objectMapper = new ObjectMapper();
        ledger = mock(WebhookIdempotencyLedger.class);
        deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        deidentReportGate = mock(DeidentReportGate.class);
        step = new VlmTimeseriesStep(vlmClient, videoRepository, batchStatusService,
                objectMapper, ledger, deidentProcLogRepository, markingRepository,
                deidentReportGate);
    }

    /** 비식별 경로가 존재하는 영상 시드 — existsById=true + 최신 성공 procLog 의 비식별 경로. */
    private void seed(Long rawSn, String deidPath) {
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        LsDeidentProcLog plog = mock(LsDeidentProcLog.class);
        lenient().when(plog.getDeIdntfFilePathNm()).thenReturn(deidPath);
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(plog));
    }

    private void stubAccepted() {
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.just(new VlmTimeseriesResponse("echo", "accepted")));
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
        //         vlmClient 에 남는 상호작용은 활성 여부 조회뿐이고 전송은 없다.
        verify(vlmClient).isEnabled();
        verifyNoMoreInteractions(vlmClient);
        // 비식별 경로 조회 자체도 하지 않는다(전송 대상 경로를 만들지 않는다).
        verifyNoInteractions(deidentProcLogRepository);
        // 상관키 발급(ledger)도 남기지 않는다 — 위탁하지 않았으므로 콜백 대기 상태를 만들지 않는다.
        verifyNoInteractions(ledger);
        // 실패가 아니라 보류: SKIPPED 응답 + 사유가 LS_BATCH_PROC_LOG 에 적재된다(B-ISSUE-24 규약).
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
        VlmTimeseriesResponse accepted = step.run(310L);

        // then
        assertThat(withheld.status()).isEqualTo("skipped");
        assertThat(accepted.status()).isEqualTo("accepted");
        verify(vlmClient, times(1)).submitTimeseries(any());
        verify(ledger, times(1)).recordIssued(any(), eq(LsWebhookIdempotency.CHANNEL_VLM),
                isNull(), eq(310L));
    }

    @Test
    @DisplayName("describe_위탁_요청_바디에_비식별경로_frame_policy_callback_url_포함")
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
        assertThat(req.callbackUrl()).endsWith("/v1/vlm/callback");
    }

    @Test
    @DisplayName("위탁_성공_시_recordIssued로_request_id가_CHANNEL_VLM_rawSn과_함께_ledger에_등록됨")
    void recordIssuedWiredWithChannelAndRawSn() {
        seed(210L, "/data/deid/210.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        stubAccepted();

        step.run(210L);

        // describe 요청에 실린 request_id 와 동일한 키가 CHANNEL_VLM + rawSn 으로 등록되어야 한다.
        ArgumentCaptor<VlmTimeseriesRequest> reqCap = ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient).submitTimeseries(reqCap.capture());
        String sentRequestId = reqCap.getValue().requestId();

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(ledger).recordIssued(keyCap.capture(), eq(LsWebhookIdempotency.CHANNEL_VLM),
                isNull(), eq(210L));
        assertThat(keyCap.getValue()).isEqualTo(sentRequestId);
    }

    @Test
    @DisplayName("recordIssued가_describe_호출_전에_수행됨_등록실패시_describe_미호출_EXTERNAL_API_ERROR")
    void recordIssuedFailureAbortsSubmit() {
        seed(211L, "/data/deid/211.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        doThrow(new RuntimeException("ledger down"))
                .when(ledger).recordIssued(any(), eq(LsWebhookIdempotency.CHANNEL_VLM), isNull(), eq(211L));

        assertThatThrownBy(() -> step.run(211L))
                .isInstanceOf(CustomException.class);
        // 매핑 없는 위탁 방지 — describe 미호출
        verify(vlmClient, never()).submitTimeseries(any());
    }

    @Test
    @DisplayName("eventName_marks는_describe_요청에_포함되지_않음")
    void noEventNameNoMarksInRequest() {
        seed(220L, "/data/deid/220.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        stubAccepted();

        step.run(220L);

        ArgumentCaptor<VlmTimeseriesRequest> captor = ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient).submitTimeseries(captor.capture());
        VlmTimeseriesRequest req = captor.getValue();
        // describe DTO 자체에 eventName/marks accessor 가 없다 — 직렬화 바디 검증은 VlmClientTest 담당.
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
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(230L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> step.run(230L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("비식별");
        verify(vlmClient, never()).submitTimeseries(any());
        verify(ledger, never()).recordIssued(any(), any(), any(), any());
    }

    @Test
    @DisplayName("외부_호출_실패_시_EXTERNAL_API_ERROR_예외_전파")
    void clientFailureWrappedAsCustomException() {
        seed(201L, "/data/deid/201.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("vlm down")));

        assertThatThrownBy(() -> step.run(201L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("VLM 위탁");
    }

    @Test
    @DisplayName("빈_응답_시_EXTERNAL_API_ERROR")
    void emptyResponseRejected() {
        seed(203L, "/data/deid/203.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class))).thenReturn(Mono.empty());

        assertThatThrownBy(() -> step.run(203L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("응답");
    }

    @Test
    @DisplayName("위탁_성공_시_request_id_status가_LS_BATCH_PROC_LOG에_기록")
    void resultPersistedToBatchProcLog() {
        seed(300L, "/data/deid/300.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.just(new VlmTimeseriesResponse("REQ-300", "accepted")));

        step.run(300L);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(batchStatusService, times(1))
                .recordVlmTimeseriesResult(eq(300L), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertThat(payload).contains("REQ-300");
        assertThat(payload).contains("accepted");
    }

    /**
     * B-ISSUE-24 — 구 테스트({@code enabled_false_시_..._미호출})가 "skip 은 아무것도 기록하지 않는다"는
     * <b>반대 의도</b>를 고정하고 있었다. skip 이 DB 에 무흔적이면 VLM 비활성/장애 구간에 처리된 영상이
     * "메타 없음 + 무기록" 으로 남아 재처리 대상 식별이 애플리케이션 로그 보존기간에 종속된다.
     * 이제 <b>SKIPPED 행 + 사유</b>를 기록하는 의도로 반전한다.
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
        // 외부 응답이 없으므로 응답 payload 기록은 여전히 하지 않는다.
        verify(batchStatusService, never()).recordVlmTimeseriesResult(any(), any());
    }

    @Test
    @DisplayName("위탁_성공_경로에서는_SKIPPED_를_기록하지_않는다")
    void noSkipRecordOnSuccess() {
        seed(302L, "/data/deid/302.mp4");
        when(vlmClient.isEnabled()).thenReturn(true);
        stubAccepted();

        step.run(302L);

        verify(batchStatusService, never()).recordVlmSkipped(any(), any());
    }
}
