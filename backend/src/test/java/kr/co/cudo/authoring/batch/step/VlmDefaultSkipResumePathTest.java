package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.runner.VlmWithheldResumeRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.LsBatchProcLog;
import kr.co.cudo.authoring.batch.status.ManualStageSkip;
import kr.co.cudo.authoring.batch.status.VlmDefaultSkipMarker;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.batch.vlm.VlmTimeseriesMetaPresence;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * <b>전체 설정 건너뛰기가 «재개 경로»에서도 외부 벤더 호출을 0 으로 만든다</b>는 회귀 가드.
 * [@design ADR-050]
 *
 * <h2>무엇이 깨져 있었나</h2>
 * <p>자동 표식({@link VlmDefaultSkipMarker})의 호출부가 오케스트레이터 <b>한 곳뿐</b>이었다. 그런데
 * 위탁으로 나가는 진입점은 그 밖에도 있다 — {@code VlmWithheldResumeRunner} 가 {@code run} ·
 * {@code runWithMarking} 을 <b>직접</b> 부르고, 그 러너는 비식별 신고 해소 이벤트({@code VlmResumeBridge})와
 * <b>주기 미결 스위퍼</b>({@code VlmSubmitPendingSweeper} — 사람 개입 0)에서 도달한다.
 * 스텝 안의 게이트는 표식을 <b>읽기만</b> 하는데 그 경로에는 표식을 세우는 자가 없어 항상 통과했고,
 * 스위치가 켜져 있어도 외부 벤더가 호출됐다. ADR-050 이 약속한 「외부 호출 0건 · 헛된 실패 기록 없음 ·
 * 마킹이 위탁 실패로 고착되지 않음」이 셋 다 무너지는 상태였다.
 *
 * <h2>왜 실제 협력자를 조립하는가</h2>
 * <p>표식 컴포넌트를 목으로 두면 「호출했다」만 확인하게 되어 <b>진짜 결함</b>(표식이 서지 않아 게이트가
 * 통과한다)을 재현하지 못한다. 그래서 {@link VlmDefaultSkipMarker} 와 재개 러너는 실제 객체를 쓰고,
 * 표식 축은 목 {@link BatchStatusService} 가 <b>기록 → 조회</b>로 이어지도록 스텁한다.
 */
class VlmDefaultSkipResumePathTest {

    private static final long RAW_SN = 9100L;

    private VlmClient vlmClient;
    private VideoRepository videoRepository;
    private BatchStatusService batchStatusService;
    private SystemConfigService systemConfigService;
    private WebhookIdempotencyLedger ledger;
    private VlmMarkingTxService markingTxService;
    private VlmTimeseriesMetaPresence timeseriesMetaPresence;
    private LsMarkingRepository markingRepository;
    private LsDeidentProcLogRepository deidentProcLogRepository;

    private VlmTimeseriesStep step;
    private VlmWithheldResumeRunner resumeRunner;

    @BeforeEach
    void setUp() {
        vlmClient = mock(VlmClient.class);
        videoRepository = mock(VideoRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        systemConfigService = mock(SystemConfigService.class);
        ledger = mock(WebhookIdempotencyLedger.class);
        markingTxService = mock(VlmMarkingTxService.class);
        timeseriesMetaPresence = mock(VlmTimeseriesMetaPresence.class);
        markingRepository = mock(LsMarkingRepository.class);
        deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);

        VlmDefaultSkipMarker marker = new VlmDefaultSkipMarker(batchStatusService, systemConfigService);
        step = new VlmTimeseriesStep(vlmClient, videoRepository, mock(IngestSourceRepository.class),
                batchStatusService, ledger, deidentProcLogRepository,
                mock(DeidentReportGate.class), markingTxService, mock(VlmSubmitOutcomeRecorder.class),
                timeseriesMetaPresence, new ObjectMapper(), Schedulers.immediate(), marker);
        resumeRunner = new VlmWithheldResumeRunner(step, batchStatusService,
                timeseriesMetaPresence, markingRepository);

        // 영상은 실재하고 신고도 없다 — 게이트를 뚫으면 실제로 위탁까지 간다(가드가 헛돌지 않게).
        when(videoRepository.existsById(RAW_SN)).thenReturn(true);
        // 재개 러너의 사전 조건: 재개 대상 미수행 기록 존재 + 시계열 메타 0건.
        when(batchStatusService.isStageSkippedWithAnyReason(eq(RAW_SN), eq(BatchStage.VLM), any()))
                .thenReturn(true);
        when(timeseriesMetaPresence.count(RAW_SN)).thenReturn(0L);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN)).thenReturn(List.of());
    }

    /** 전체 설정 스위치를 켠다(사유 포함). */
    private void switchOn(String reason) {
        when(systemConfigService.findString(ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT))
                .thenReturn(Optional.of("true"));
        when(systemConfigService.findString(ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT_REASON))
                .thenReturn(Optional.ofNullable(reason));
    }

    /**
     * 표식 축 스텁 — 기록하면 그 뒤 조회가 그 행을 돌려주게 이어 붙인다.
     *
     * <p>이 배선이 없으면 「표식을 세웠는가」만 보고 「그래서 게이트가 막았는가」를 못 본다.
     */
    private void wireMarkerRoundTrip() {
        when(batchStatusService.latestManualSkipMarker(RAW_SN, BatchStageBundle.VLM))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doAnswer(inv -> {
            LsBatchProcLog row = LsBatchProcLog.createManualSkipMarker(
                    RAW_SN, BatchStageBundle.VLM, ManualStageSkip.ERR_CD_SKIPPED,
                    inv.getArgument(2), inv.getArgument(3));
            when(batchStatusService.latestManualSkipMarker(RAW_SN, BatchStageBundle.VLM))
                    .thenReturn(Optional.of(row));
            when(batchStatusService.isStageManuallySkipped(RAW_SN, BatchStage.VLM)).thenReturn(true);
            return null;
        }).when(batchStatusService).recordManualStageSkipInNewTx(
                eq(RAW_SN), eq(BatchStageBundle.VLM), anyString(), anyString());
    }

    @Test
    @DisplayName("★★신고_해소_재개에서도_스위치가_켜져_있으면_외부_벤더_호출이_0이고_표식이_선다")
    void resumeFromDeidentReportIsBlockedByDefaultSkip() {
        switchOn("벤더 미연동");
        wireMarkerRoundTrip();

        // when — 신고 해소 이벤트(VlmResumeBridge)가 부르는 바로 그 진입점.
        resumeRunner.resumeAsync(RAW_SN);

        // then — 외부로 나가는 상호작용이 한 건도 없다.
        verifyNoInteractions(vlmClient);
        verifyNoInteractions(ledger);
        // 그리고 「왜 건너뛰었는지」가 표식으로 남는다(설정 사유가 본문에 실린다).
        verify(batchStatusService).recordManualStageSkipInNewTx(
                eq(RAW_SN), eq(BatchStageBundle.VLM),
                org.mockito.ArgumentMatchers.contains("벤더 미연동"),
                eq("batch-vlm-default-skip"));
    }

    /**
     * 미결 스위퍼는 <b>주기 스케줄 잡</b>이라 사람 개입이 0 이다 — 이 경로가 새면 아무도 모르는 사이에
     * 외부 위탁이 계속 나간다. 러너 진입점이 같으므로(resumeAsync) 마킹이 있는 분기까지 함께 고정한다.
     */
    @Test
    @DisplayName("★★미결_회수_재개에서도_마킹이_있는_분기까지_외부_호출이_0이다")
    void resumeWithMarkingIsBlockedByDefaultSkip() {
        switchOn("벤더 미연동");
        wireMarkerRoundTrip();
        LsMarking marking = mock(LsMarking.class);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN))
                .thenReturn(List.of(marking));

        resumeRunner.resumeAsync(RAW_SN);

        verifyNoInteractions(vlmClient);
        verifyNoInteractions(ledger);
        // 마킹 상태도 건드리지 않는다 — 위탁하지 않았으므로 VLM_REQUESTED 로 올리면 영구 고착이 된다.
        verifyNoInteractions(markingTxService);
    }

    /**
     * ★사람의 결정이 우선이다 — 그 (영상 × 묶음) 에 표식이 이미 있으면(건너뜀이든 해제든) 덮지 않는다.
     * 특히 해제 표식을 덮으면 사람이 되살린 영상이 조용히 다시 건너뛰어진다.
     */
    @Test
    @DisplayName("★재개_경로에서도_사람이_남긴_표식은_덮지_않는다")
    void existingHumanMarkerIsNotOverwritten() {
        switchOn("벤더 미연동");
        LsBatchProcLog cleared = LsBatchProcLog.createManualSkipMarker(
                RAW_SN, BatchStageBundle.VLM, ManualStageSkip.ERR_CD_CLEARED,
                ManualStageSkip.MANUAL_CLEARED_REASON, "7");
        when(batchStatusService.latestManualSkipMarker(RAW_SN, BatchStageBundle.VLM))
                .thenReturn(Optional.of(cleared));
        // 해제 상태이므로 게이트는 통과한다 — 즉 위탁이 실제로 나가는 것이 «정상»이다.
        when(batchStatusService.isStageManuallySkipped(RAW_SN, BatchStage.VLM)).thenReturn(false);

        resumeRunner.resumeAsync(RAW_SN);

        verify(batchStatusService, never()).recordManualStageSkipInNewTx(
                any(), any(), anyString(), anyString());
    }

    /**
     * ★스위치가 꺼져 있으면 재개는 <b>종전대로</b> 위탁한다 — 이 가드가 없으면 위 두 테스트를
     * 「항상 건너뛴다」로 만족시킬 수 있다.
     */
    @Test
    @DisplayName("★★스위치가_꺼져_있으면_재개는_종전대로_위탁한다_회귀")
    void resumeStillSubmitsWhenSwitchOff() {
        when(systemConfigService.findString(ConfigKeys.BATCH_VLM_SKIP_BY_DEFAULT))
                .thenReturn(Optional.empty());
        when(batchStatusService.latestManualSkipMarker(RAW_SN, BatchStageBundle.VLM))
                .thenReturn(Optional.empty());
        when(batchStatusService.isStageManuallySkipped(RAW_SN, BatchStage.VLM)).thenReturn(false);
        when(vlmClient.submitTimeseries(any()))
                .thenReturn(reactor.core.publisher.Mono.just(new VlmTimeseriesResponse("req-1", VlmTimeseriesResponse.STATUS_ACCEPTED)));
        // 비식별 경로가 있어야 위탁까지 간다.
        stubDeidentifiedPath();

        resumeRunner.resumeAsync(RAW_SN);

        verify(vlmClient).submitTimeseries(any());
        verify(batchStatusService, never()).recordManualStageSkipInNewTx(
                any(), any(), anyString(), anyString());
    }

    /** 비식별 영상 경로 스텁 — 없으면 fail-closed 로 위탁 이전에 끊긴다. */
    private void stubDeidentifiedPath() {
        kr.co.cudo.authoring.batch.entity.LsDeidentProcLog procLog =
                mock(kr.co.cudo.authoring.batch.entity.LsDeidentProcLog.class);
        when(procLog.getDeIdntfFilePathNm()).thenReturn("/nas/videos/9100/deidentified.mp4");
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(RAW_SN))
                .thenReturn(Optional.of(procLog));
    }
}
