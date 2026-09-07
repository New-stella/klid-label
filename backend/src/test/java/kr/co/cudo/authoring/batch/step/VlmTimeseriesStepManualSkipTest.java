package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.batch.vlm.VlmTimeseriesMetaPresence;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * VLM 스텝의 <b>수동 스킵 게이트</b> — 직접 호출 경로에서도 우회되지 않는다. [@design API-198]
 *
 * <h3>왜 오케스트레이터 게이트만으로는 부족한가</h3>
 * <p>{@code VlmWithheldResumeRunner} 는 과거의 재개 가능 SKIPPED 행을 보고 {@code run}/{@code runWithMarking}
 * 을 <b>직접</b> 부른다. 그 경로가 열려 있으면 비식별 신고 해소 이벤트가 REVIEWER 의 스킵 결정을
 * 뒤집고 영상을 외부 벤더로 재위탁하게 된다 — 회수 불가능한 유출이다.
 */
class VlmTimeseriesStepManualSkipTest {

    private static final long RAW_SN = 500L;

    private VlmClient vlmClient;
    private VideoRepository videoRepository;
    private BatchStatusService batchStatusService;
    private WebhookIdempotencyLedger ledger;
    private LsDeidentProcLogRepository deidentProcLogRepository;
    private DeidentReportGate deidentReportGate;
    private VlmMarkingTxService markingTxService;
    private VlmTimeseriesStep step;

    @BeforeEach
    void setUp() {
        vlmClient = mock(VlmClient.class);
        videoRepository = mock(VideoRepository.class);
        IngestSourceRepository ingestSourceRepository = mock(IngestSourceRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        ledger = mock(WebhookIdempotencyLedger.class);
        deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);
        deidentReportGate = mock(DeidentReportGate.class);
        markingTxService = mock(VlmMarkingTxService.class);
        VlmSubmitOutcomeRecorder outcomeRecorder = mock(VlmSubmitOutcomeRecorder.class);
        VlmTimeseriesMetaPresence timeseriesMetaPresence = mock(VlmTimeseriesMetaPresence.class);

        step = new VlmTimeseriesStep(vlmClient, videoRepository, ingestSourceRepository,
                batchStatusService, ledger, deidentProcLogRepository, deidentReportGate,
                markingTxService, outcomeRecorder, timeseriesMetaPresence,
                new ObjectMapper(), Schedulers.immediate(),
                mock(kr.co.cudo.authoring.batch.status.VlmDefaultSkipMarker.class),
                mock(kr.co.cudo.authoring.aiserver.service.AiSrvrSelector.class),
                mock(kr.co.cudo.authoring.sysconfig.service.VerificationEventQuestionResolver.class),
                mock(kr.co.cudo.authoring.evntanno.service.MarkingSelectedQuestionReader.class));
    }

    @Test
    @DisplayName("★수동_스킵된_VLM은_직접_호출해도_외부_위탁을_하지_않는다")
    void manualSkipBlocksDirectInvocation() {
        // given — VLM 활성 + 실재 영상이지만 REVIEWER 가 이 단계를 수동 스킵했다.
        //   (비식별 경로 스텁을 일부러 두지 않는다 — 게이트가 경로 조회 전에 끊어야 한다.)
        when(batchStatusService.isStageManuallySkipped(RAW_SN, BatchStage.VLM)).thenReturn(true);

        // when — 재개 러너가 부르는 것과 같은 직접 진입점.
        VlmTimeseriesResponse resp = step.run(RAW_SN);

        // then — 외부 벤더로 나가는 상호작용이 0건이다.
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("skipped");
        verifyNoInteractions(vlmClient);
        verifyNoInteractions(ledger);
        verifyNoInteractions(deidentProcLogRepository);
        // 선커밋(마킹 전이)도 하지 않는다 — 위탁하지 않았으므로 상태를 올리면 안 된다.
        verifyNoInteractions(markingTxService);
        // 영상 존재 조회조차 가지 않는다(게이트가 가장 앞이다).
        verifyNoInteractions(videoRepository);
    }

    @Test
    @DisplayName("★수동_스킵_게이트는_비식별_신고_게이트보다_먼저_평가된다")
    void manualSkipEvaluatedFirst() {
        // given — 두 게이트가 동시에 서 있어도 수동 스킵이 먼저 끊어 신고 게이트 조회조차 하지 않는다.
        //   (사람이 이미 "이 단계는 하지 않는다"고 결정했으므로 그 뒤의 판정은 의미가 없다.)
        when(batchStatusService.isStageManuallySkipped(RAW_SN, BatchStage.VLM)).thenReturn(true);

        // when
        step.run(RAW_SN);

        // then
        verifyNoInteractions(deidentReportGate);
    }

    @Test
    @DisplayName("수동_스킵은_보류_기록을_남기지_않는다_표식이_이미_사유를_갖고_있다")
    void manualSkipDoesNotWriteSkipRow() {
        // given — 재기동마다 감사 행을 덧붙이면 로그 테이블이 무한히 커진다(CWE-770).
        when(batchStatusService.isStageManuallySkipped(RAW_SN, BatchStage.VLM)).thenReturn(true);

        // when
        step.run(RAW_SN);

        // then — 판정 조회 외에 기록 호출이 없어야 한다.
        org.mockito.Mockito.verify(batchStatusService).isStageManuallySkipped(RAW_SN, BatchStage.VLM);
        org.mockito.Mockito.verifyNoMoreInteractions(batchStatusService);
    }
}
