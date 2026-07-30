package kr.co.cudo.authoring.batch.vlm;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.runner.VlmWithheldResumeRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase C-1 — VLM <b>미결 위탁 회수 스윕</b> 단위 테스트.
 *
 * <p>고정하는 계약: 클레임 성공 건만 기록·재개하고, 클레임 실패(=다른 노드 소유) 건은 손대지 않으며,
 * 회수 예산을 넘긴 영상은 기록만 남기고 재개하지 않는다. 어떤 경로에서도 상태 강등은 없다.
 */
class VlmSubmitPendingSweeperTest {

    private VlmSubmitReclaimTxService reclaimTxService;
    private BatchStatusService batchStatusService;
    private VlmWithheldResumeRunner resumeRunner;

    @BeforeEach
    void setUp() {
        reclaimTxService = mock(VlmSubmitReclaimTxService.class);
        batchStatusService = mock(BatchStatusService.class);
        resumeRunner = mock(VlmWithheldResumeRunner.class);
    }

    /** enabled=false 로 만들어 스케줄러를 띄우지 않고 {@code run()} 만 직접 구동한다. */
    private VlmSubmitPendingSweeper sweeper(int maxReclaims) {
        return new VlmSubmitPendingSweeper(reclaimTxService, batchStatusService, resumeRunner,
                false, 900_000L, 300_000L, 30, 360, 50, maxReclaims);
    }

    @Test
    @DisplayName("ACK없이_미결로_남은_행을_회수하고_사유기록_후_재개를_트리거한다")
    void reclaimsPendingSubmitAndResumes() {
        when(reclaimTxService.findCandidates(any(), anyInt()))
                .thenReturn(List.of(new VlmSubmitReclaimTxService.Candidate("REQ-1", 800L)));
        when(reclaimTxService.claim(eq("REQ-1"), any())).thenReturn(true);
        when(reclaimTxService.reclaimBudgetExceeded(eq(800L), anyInt())).thenReturn(false);

        int reclaimed = sweeper(3).run();

        assertThat(reclaimed).isEqualTo(1);
        verify(batchStatusService).recordVlmSkippedInNewTx(
                800L, VlmTimeseriesStep.SKIP_REASON_ACK_MISSING);
        verify(resumeRunner).resumeAsync(800L);
    }

    @Test
    @DisplayName("원자_클레임에_실패한_후보는_기록도_재개도_하지_않는다_2노드_이중처리_차단")
    void loserNodeDoesNothing() {
        when(reclaimTxService.findCandidates(any(), anyInt()))
                .thenReturn(List.of(new VlmSubmitReclaimTxService.Candidate("REQ-2", 801L)));
        // 다른 노드가 먼저 클레임 → 조건부 UPDATE 0행
        when(reclaimTxService.claim(eq("REQ-2"), any())).thenReturn(false);

        int reclaimed = sweeper(3).run();

        assertThat(reclaimed).isZero();
        verify(batchStatusService, never()).recordVlmSkippedInNewTx(anyLong(), any());
        verify(resumeRunner, never()).resumeAsync(anyLong());
    }

    @Test
    @DisplayName("회수_예산을_초과한_영상은_기록만_남기고_재개하지_않는다_무한재위탁_차단")
    void budgetExceededSuppressesResume() {
        when(reclaimTxService.findCandidates(any(), anyInt()))
                .thenReturn(List.of(new VlmSubmitReclaimTxService.Candidate("REQ-3", 802L)));
        when(reclaimTxService.claim(eq("REQ-3"), any())).thenReturn(true);
        when(reclaimTxService.reclaimBudgetExceeded(eq(802L), anyInt())).thenReturn(true);

        int reclaimed = sweeper(3).run();

        assertThat(reclaimed).isEqualTo(1);
        verify(batchStatusService).recordVlmSkippedInNewTx(
                802L, VlmTimeseriesStep.SKIP_REASON_ACK_MISSING);
        verify(resumeRunner, never()).resumeAsync(anyLong());
    }

    @Test
    @DisplayName("한_건이_실패해도_나머지_후보_처리는_계속된다")
    void perCandidateFailureDoesNotAbortSweep() {
        when(reclaimTxService.findCandidates(any(), anyInt())).thenReturn(List.of(
                new VlmSubmitReclaimTxService.Candidate("REQ-4", 803L),
                new VlmSubmitReclaimTxService.Candidate("REQ-5", 804L)));
        when(reclaimTxService.claim(eq("REQ-4"), any())).thenThrow(new RuntimeException("db hiccup"));
        when(reclaimTxService.claim(eq("REQ-5"), any())).thenReturn(true);
        when(reclaimTxService.reclaimBudgetExceeded(eq(804L), anyInt())).thenReturn(false);

        int reclaimed = sweeper(3).run();

        assertThat(reclaimed).isEqualTo(1);
        verify(resumeRunner, times(1)).resumeAsync(804L);
    }

    @Test
    @DisplayName("stale_임계는_하한으로_clamp된다_정상_ACK대기_위탁_오회수_방지")
    void staleTimeoutClampedToFloor() {
        VlmSubmitPendingSweeper tooAggressive = new VlmSubmitPendingSweeper(
                reclaimTxService, batchStatusService, resumeRunner,
                false, 900_000L, 300_000L, 1, 1, 50, 3);

        assertThat(tooAggressive.staleTimeoutMinutes()).isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("회수는_배치_단계_상태를_강등하지_않는다")
    void reclaimNeverDemotesStage() {
        when(reclaimTxService.findCandidates(any(), anyInt()))
                .thenReturn(List.of(new VlmSubmitReclaimTxService.Candidate("REQ-6", 805L)));
        when(reclaimTxService.claim(eq("REQ-6"), any())).thenReturn(true);
        when(reclaimTxService.reclaimBudgetExceeded(eq(805L), anyInt())).thenReturn(false);

        sweeper(3).run();

        verify(batchStatusService, never()).markFailed(any(), any());
        verify(batchStatusService, never()).markStage(any(), any(BatchStage.class));
    }

    @Test
    @DisplayName("cutoff는_현재시각에서_stale임계만큼_과거로_계산된다")
    void cutoffDerivedFromStaleTimeout() {
        when(reclaimTxService.findCandidates(any(), anyInt())).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now().minusMinutes(30);
        sweeper(3).run();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reclaimTxService).findCandidates(cutoff.capture(), eq(50));
        assertThat(cutoff.getValue()).isBeforeOrEqualTo(LocalDateTime.now().minusMinutes(30));
        assertThat(cutoff.getValue()).isAfterOrEqualTo(before.minusMinutes(1));
    }

    // ───────────────────────── H1 — 콜백 창(ACK 수신 건) 별도 패스 ─────────────────────────

    /**
     * ★ H1 — ACK 를 받은 건은 <b>ACK 창이 아니라 콜백 창</b>에서, 그것도 <b>다른 사유</b>로 회수된다.
     *
     * <p>사유를 나누는 이유: {@code ACK_MISSING} 은 "외부에 작업이 아예 없을 수 있음"이고
     * {@code CALLBACK_MISSING} 은 "외부에 작업이 실재하는데 결과만 안 옴"이라, 운영이 벤더 상태를
     * 확인해야 하는 건인지 판단이 갈린다.
     *
     * <p><b>RED 실증</b>: 스위퍼를 구 형태(ISSUED 단일 패스)로 되돌리면
     * {@code findAcceptedCandidates} 패스가 사라져 회수 0건 + CALLBACK_MISSING 미기록으로 RED 다.
     */
    @Test
    @DisplayName("ACK수신후_콜백창을_넘긴_건은_CALLBACK_MISSING으로_회수되고_재개된다")
    void callbackWindowReclaimsAckedSubmission() {
        when(reclaimTxService.findCandidates(any(), anyInt())).thenReturn(List.of());
        when(reclaimTxService.findAcceptedCandidates(any(), anyInt()))
                .thenReturn(List.of(new VlmSubmitReclaimTxService.Candidate("REQ-A1", 810L)));
        when(reclaimTxService.claimAccepted(eq("REQ-A1"), any())).thenReturn(true);
        when(reclaimTxService.reclaimBudgetExceeded(eq(810L), anyInt())).thenReturn(false);

        int reclaimed = sweeper(3).run();

        assertThat(reclaimed).isEqualTo(1);
        verify(batchStatusService).recordVlmSkippedInNewTx(
                810L, VlmTimeseriesStep.SKIP_REASON_CALLBACK_MISSING);
        // ACK 창의 클레임(ISSUED 술어)으로 처리하면 안 된다 — 상태가 달라 0행이고 사유도 틀린다.
        verify(reclaimTxService, never()).claim(eq("REQ-A1"), any());
        verify(resumeRunner).resumeAsync(810L);
    }

    /**
     * ★ H1 — 두 패스는 <b>서로 다른 임계</b>로 돈다. 하나의 임계로 덮으면 진행 중(분석 중)인 정상
     * 위탁을 ACK 창이 뺏어 같은 비식별 영상을 중복 위탁한다(H1 의 실체).
     */
    @Test
    @DisplayName("ACK창과_콜백창은_서로_다른_임계로_조회된다_단일임계_통합_금지")
    void twoWindowsUseDistinctCutoffs() {
        when(reclaimTxService.findCandidates(any(), anyInt())).thenReturn(List.of());
        when(reclaimTxService.findAcceptedCandidates(any(), anyInt())).thenReturn(List.of());

        sweeper(3).run();

        ArgumentCaptor<LocalDateTime> ackCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> callbackCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reclaimTxService).findCandidates(ackCutoff.capture(), eq(50));
        verify(reclaimTxService).findAcceptedCandidates(callbackCutoff.capture(), eq(50));
        // 콜백 창(360분)은 ACK 창(30분)보다 훨씬 과거를 가리켜야 한다 = 훨씬 오래 기다린다.
        assertThat(callbackCutoff.getValue())
                .as("콜백 창이 ACK 창보다 짧거나 같으면 분석 중인 정상 위탁을 회수해 H1 이 재발한다")
                .isBefore(ackCutoff.getValue());
    }

    @Test
    @DisplayName("콜백창_임계도_하한으로_clamp된다_ACK창보다_커야_의미가_있다")
    void callbackTimeoutClampedToFloor() {
        VlmSubmitPendingSweeper tooAggressive = new VlmSubmitPendingSweeper(
                reclaimTxService, batchStatusService, resumeRunner,
                false, 900_000L, 300_000L, 1, 1, 50, 3);

        assertThat(tooAggressive.callbackTimeoutMinutes()).isGreaterThanOrEqualTo(60);
        assertThat(tooAggressive.callbackTimeoutMinutes())
                .isGreaterThan(tooAggressive.staleTimeoutMinutes());
    }

    @Test
    @DisplayName("콜백창_클레임_실패건은_기록도_재개도_하지_않는다_2노드_이중처리_차단")
    void callbackWindowLoserNodeDoesNothing() {
        when(reclaimTxService.findCandidates(any(), anyInt())).thenReturn(List.of());
        when(reclaimTxService.findAcceptedCandidates(any(), anyInt()))
                .thenReturn(List.of(new VlmSubmitReclaimTxService.Candidate("REQ-A2", 811L)));
        when(reclaimTxService.claimAccepted(eq("REQ-A2"), any())).thenReturn(false);

        int reclaimed = sweeper(3).run();

        assertThat(reclaimed).isZero();
        verify(batchStatusService, never()).recordVlmSkippedInNewTx(anyLong(), any());
        verify(resumeRunner, never()).resumeAsync(anyLong());
    }
}
