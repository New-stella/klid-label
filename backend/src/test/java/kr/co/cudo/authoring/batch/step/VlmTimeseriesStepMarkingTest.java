package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VlmTimeseriesStep.runWithMarking() 테스트 — describe 규격(v2.0.1) 정합.
 *
 * <p>describe 규격상 eventName/marks 는 요청 바디에 포함하지 않으나(R9), 위탁 성공 시
 * 마킹 상태를 VLM_REQUESTED 로 전이한다(파이프라인 상태 머신 유지).
 */
class VlmTimeseriesStepMarkingTest {

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

    private void seed(Long rawSn) {
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        LsDeidentProcLog plog = mock(LsDeidentProcLog.class);
        lenient().when(plog.getDeIdntfFilePathNm()).thenReturn("/data/deid/" + rawSn + ".mp4");
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(plog));
    }

    private LsMarking newMarking(Long rawSn) {
        return LsMarking.createAuto(rawSn, "fire", 5,
                "raw/path.mp4", "[{\"frameIndex\":0,\"timestamp\":0.0}]", 1L);
    }

    private void stubAccepted() {
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.just(new VlmTimeseriesResponse("echo", "accepted")));
    }

    @Test
    @DisplayName("runWithMarking_describe_위탁_비식별경로_전송")
    void runWithMarking_sendsDeidPath() {
        seed(400L);
        LsMarking marking = newMarking(400L);
        when(vlmClient.isEnabled()).thenReturn(true);
        stubAccepted();

        VlmTimeseriesResponse resp = step.runWithMarking(400L, marking);

        ArgumentCaptor<VlmTimeseriesRequest> captor = ArgumentCaptor.forClass(VlmTimeseriesRequest.class);
        verify(vlmClient).submitTimeseries(captor.capture());
        VlmTimeseriesRequest req = captor.getValue();
        assertThat(req.media().path()).isEqualTo("/data/deid/400.mp4");
        assertThat(resp.status()).isEqualTo("accepted");
    }

    @Test
    @DisplayName("runWithMarking_마킹_상태_VLM_REQUESTED_전이")
    void runWithMarking_transitionsMarkingStatus() {
        seed(401L);
        LsMarking marking = newMarking(401L);
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);
        when(vlmClient.isEnabled()).thenReturn(true);
        stubAccepted();

        step.runWithMarking(401L, marking);

        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_REQUESTED);
    }

    @Test
    @DisplayName("runWithMarking_VLM_disabled시_SKIP_전이없음")
    void runWithMarking_disabled_skips() {
        when(vlmClient.isEnabled()).thenReturn(false);
        LsMarking marking = newMarking(402L);

        VlmTimeseriesResponse resp = step.runWithMarking(402L, marking);

        assertThat(resp.status()).isEqualTo("skipped");
        verify(vlmClient, never()).submitTimeseries(any());
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);
    }

    @Test
    @DisplayName("retry_재실행_시_이미_VLM_COMPLETED_마킹은_VLM_REQUESTED로_역행하지_않는다")
    void runWithMarking_alreadyCompleted_doesNotRegress() {
        seed(404L);
        LsMarking marking = newMarking(404L);
        // (b)까지 세팅: PENDING → VLM_REQUESTED → VLM_COMPLETED
        marking.markVlmRequested();
        marking.markVlmCompleted();
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
        when(vlmClient.isEnabled()).thenReturn(true);
        stubAccepted();

        // when — retry(BatchRetryQuartzJob → orchestrator.process) 로 파이프라인 재실행
        step.runWithMarking(404L, marking);

        // then — 마킹 상태는 역행하지 않고 VLM_COMPLETED 유지, 불필요한 save 도 발생하지 않음
        assertThat(marking.getSttsCd()).isEqualTo(LsMarking.STATUS_VLM_COMPLETED);
        verify(markingRepository, never()).save(any());
    }

    @Test
    @DisplayName("runWithMarking_null_마킹시_기존_run_호출과_동일")
    void runWithMarking_nullMarking_fallsBackToRun() {
        seed(403L);
        when(vlmClient.isEnabled()).thenReturn(true);
        stubAccepted();

        VlmTimeseriesResponse resp = step.runWithMarking(403L, null);

        verify(vlmClient).submitTimeseries(any(VlmTimeseriesRequest.class));
        assertThat(resp.status()).isEqualTo("accepted");
    }
}
