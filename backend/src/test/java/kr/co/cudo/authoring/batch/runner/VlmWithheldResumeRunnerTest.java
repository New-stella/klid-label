package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.listener.VlmResumeBridge;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.label.event.DeidentGateReopenedEvent;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 신고 해소 후 <b>보류됐던 VLM 시계열 위탁이 실제로 재개되는가</b> — 결손 방지 회귀 가드
 * (2026-07-29, MEDIUM "VLM SKIPPED 에 재개 트리거가 없다").
 *
 * <p>보류(SKIPPED)는 실패 행을 남기지 않아 재시도 큐·실패 회수기가 집지 않는다. 그래서 해제 시점의
 * 재트리거가 <b>유일한 복구 경로</b>이며, 그 배선(이벤트 → 브릿지 → 러너 → 스텝)이 하나라도 끊기면
 * 시계열 메타가 영구 결손된다. 아래 테스트는 배선 각 마디를 지우면 RED 가 되도록 구성했다.
 */
class VlmWithheldResumeRunnerTest {

    private static final long RAW_SN = 7700L;

    private VlmTimeseriesStep vlmTimeseriesStep;
    private BatchStatusService batchStatusService;
    private LsDataMetaRepository metaRepository;
    private LsMarkingRepository markingRepository;
    private VlmWithheldResumeRunner runner;

    @BeforeEach
    void setUp() {
        vlmTimeseriesStep = mock(VlmTimeseriesStep.class);
        batchStatusService = mock(BatchStatusService.class);
        metaRepository = mock(LsDataMetaRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        runner = new VlmWithheldResumeRunner(
                vlmTimeseriesStep, batchStatusService, metaRepository, markingRepository);
    }

    /** 보류 기록 존재 여부 stub — 사유 상수가 바뀌면 배선이 끊기므로 상수 자체를 매칭한다. */
    private void stubWithheld(boolean withheld) {
        when(batchStatusService.isStageSkippedWithReason(
                RAW_SN, BatchStage.VLM, VlmTimeseriesStep.SKIP_REASON_DEIDENT_REPORT))
                .thenReturn(withheld);
    }

    @Test
    @DisplayName("신고_보류됐던_VLM_위탁은_해제_후_재위탁된다")
    void resumesWithheldSubmit() {
        stubWithheld(true);
        when(metaRepository.countByRawSn(RAW_SN)).thenReturn(0L);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN)).thenReturn(List.of());

        runner.resumeAsync(RAW_SN);

        verify(vlmTimeseriesStep).run(RAW_SN);
    }

    @Test
    @DisplayName("마킹이_있으면_마킹_전이를_포함한_경로로_재위탁된다")
    void resumesWithMarkingWhenPresent() {
        stubWithheld(true);
        when(metaRepository.countByRawSn(RAW_SN)).thenReturn(0L);
        LsMarking marking = mock(LsMarking.class);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN))
                .thenReturn(List.of(marking));

        runner.resumeAsync(RAW_SN);

        // 마킹 상태 전이(PENDING → VLM_REQUESTED)까지 원래 파이프라인과 동일하게 이어져야 한다.
        verify(vlmTimeseriesStep).runWithMarking(RAW_SN, marking);
        verify(vlmTimeseriesStep, never()).run(anyLong());
    }

    @Test
    @DisplayName("보류_기록이_없으면_재위탁하지_않는다")
    void skipsWhenNothingWithheld() {
        stubWithheld(false);

        runner.resumeAsync(RAW_SN);

        verifyNoInteractions(vlmTimeseriesStep);
    }

    @Test
    @DisplayName("이미_시계열_메타가_적재됐으면_중복_재위탁하지_않는다")
    void skipsWhenMetaAlreadyPresent() {
        // 보류 기록은 append-only 라 지워지지 않는다 — 멱등성은 "메타 0건" 조건이 담당한다.
        stubWithheld(true);
        when(metaRepository.countByRawSn(RAW_SN)).thenReturn(3L);

        runner.resumeAsync(RAW_SN);

        verifyNoInteractions(vlmTimeseriesStep);
    }

    @Test
    @DisplayName("재위탁_실패는_삼켜서_해소_트랜잭션에_영향을_주지_않는다")
    void swallowsResumeFailure() {
        stubWithheld(true);
        when(metaRepository.countByRawSn(RAW_SN)).thenReturn(0L);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN)).thenReturn(List.of());
        when(vlmTimeseriesStep.run(RAW_SN)).thenThrow(new IllegalStateException("VLM down"));

        runner.resumeAsync(RAW_SN); // 예외가 밖으로 나오면 실패

        verify(vlmTimeseriesStep).run(RAW_SN);
    }

    @Test
    @DisplayName("게이트_재개방_이벤트가_재개_러너로_배선되어_있다")
    void bridgeDelegatesToRunner() {
        // 배선이 끊기면(리스너 제거·다른 이벤트 구독) 이 단언이 깨진다 — 결손은 무증상이라 배선 자체를 고정한다.
        VlmWithheldResumeRunner resumeRunner = mock(VlmWithheldResumeRunner.class);
        new VlmResumeBridge(resumeRunner).onDeidentGateReopened(new DeidentGateReopenedEvent(RAW_SN));
        verify(resumeRunner).resumeAsync(eq(RAW_SN));
    }
}
