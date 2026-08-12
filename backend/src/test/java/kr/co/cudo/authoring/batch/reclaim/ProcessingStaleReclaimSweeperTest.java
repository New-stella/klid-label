package kr.co.cudo.authoring.batch.reclaim;

import kr.co.cudo.authoring.batch.status.ReprocessClaimOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

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
 * 「처리 중」 고착 회수 스윕의 계약 고정.
 *
 * <p>지키는 것:
 * <ul>
 *   <li>판정이 보류면 상태를 건드리지도 감사 기록을 남기지도 않는다.</li>
 *   <li>회수된 영상을 <b>자동 재시도 큐에 넣지 않는다</b>(사람이 다시 누른다).</li>
 *   <li><b>판정 불가 후보가 회수 큐 앞머리를 영구 점유하지 못한다</b> — 커서가 회전한다.</li>
 * </ul>
 *
 * <p>출발 축 매핑(완주 영상이 {@code FAILED} 로 강등되지 않는다)은 되돌리기를 수행하는
 * {@code ProcessingStaleReclaimTxServiceTest} 가 고정한다 — 이 스윕은 축을 재유도하지 않고
 * {@link ReprocessClaimOrigin} 을 그대로 넘긴다.
 */
class ProcessingStaleReclaimSweeperTest {

    private ProcessingStaleReclaimTxService reclaimTxService;

    @BeforeEach
    void setUp() {
        reclaimTxService = mock(ProcessingStaleReclaimTxService.class);
    }

    /** enabled=false 로 만들어 스케줄러를 띄우지 않고 {@code run()} 만 직접 구동한다. */
    private ProcessingStaleReclaimSweeper sweeper() {
        return sweeper(50);
    }

    private ProcessingStaleReclaimSweeper sweeper(int batchSize) {
        return new ProcessingStaleReclaimSweeper(
                new ProcessingStaleReclaimProperties(false, 900_000L, 600_000L, 180, batchSize),
                reclaimTxService);
    }

    private void givenCandidate(Long rawSn, ReclaimDecision decision) {
        when(reclaimTxService.findStaleCandidates(any(), anyLong(), anyInt()))
                .thenReturn(List.of(rawSn));
        when(reclaimTxService.decide(eq(rawSn), any())).thenReturn(decision);
    }

    @Test
    @DisplayName("고착으로_판정된_영상은_출발축_그대로_되돌리기에_넘긴다")
    void reclaimsWithResolvedOrigin() {
        givenCandidate(11L, ReclaimDecision.reclaim(ReprocessClaimOrigin.COMPLETED));
        when(reclaimTxService.reclaimAndClose(eq(11L), any())).thenReturn(true);

        assertThat(sweeper().run()).isEqualTo(1);

        verify(reclaimTxService).reclaimAndClose(11L, ReprocessClaimOrigin.COMPLETED);
    }

    @Test
    @DisplayName("★판정이_보류되면_상태를_건드리지_않는다")
    void skipsWhenOriginCannotBeResolved() {
        givenCandidate(13L, ReclaimDecision.withheld(
                ReclaimDecision.WithholdReason.NO_CLAIM_MARKER));

        assertThat(sweeper().run()).isZero();

        verify(reclaimTxService, never()).reclaimAndClose(anyLong(), any());
    }

    @Test
    @DisplayName("아직_살아_있는_후보는_되돌리지_않는다")
    void skipsAliveCandidate() {
        givenCandidate(13L, ReclaimDecision.alive());

        assertThat(sweeper().run()).isZero();

        verify(reclaimTxService, never()).reclaimAndClose(anyLong(), any());
    }

    @Test
    @DisplayName("원자_클레임에_진_노드는_회수_건수로_세지_않는다_2노드_이중회수_차단")
    void loserNodeIsNotCounted() {
        givenCandidate(14L, ReclaimDecision.reclaim(ReprocessClaimOrigin.FAILED));
        // 조건부 UPDATE 0행 = 다른 노드가 이미 회수했거나 파이프라인이 스스로 마감했다.
        when(reclaimTxService.reclaimAndClose(eq(14L), any())).thenReturn(false);

        assertThat(sweeper().run()).isZero();
    }

    @Test
    @DisplayName("건별_예외는_스윕_전체를_멈추지_않고_나머지_후보를_계속_처리한다")
    void perCandidateFailureDoesNotStopTheSweep() {
        when(reclaimTxService.findStaleCandidates(any(), anyLong(), anyInt()))
                .thenReturn(List.of(16L, 17L));
        when(reclaimTxService.decide(eq(16L), any()))
                .thenThrow(new IllegalStateException("boom"));
        when(reclaimTxService.decide(eq(17L), any()))
                .thenReturn(ReclaimDecision.reclaim(ReprocessClaimOrigin.FAILED));
        when(reclaimTxService.reclaimAndClose(eq(17L), any())).thenReturn(true);

        assertThat(sweeper().run()).isEqualTo(1);
        verify(reclaimTxService, times(1)).reclaimAndClose(anyLong(), any());
    }

    @Test
    @DisplayName("스윕이_통째로_실패해도_예외를_던지지_않는다_스케줄러_스레드_사망_방지")
    void sweepSwallowsThrowableToKeepSchedulerAlive() {
        when(reclaimTxService.findStaleCandidates(any(), anyLong(), anyInt()))
                .thenThrow(new OutOfMemoryError("simulated"));

        assertThat(sweeper().run()).isZero();
    }

    @Test
    @DisplayName("후보가_없으면_아무_것도_하지_않는다")
    void noCandidatesNoWork() {
        when(reclaimTxService.findStaleCandidates(any(), anyLong(), anyInt())).thenReturn(List.of());

        assertThat(sweeper().run()).isZero();
        verify(reclaimTxService, never()).reclaimAndClose(anyLong(), any());
    }

    /**
     * ★★<b>판정 불가 후보가 회수 큐 앞머리를 영구 점유하면 스윕이 조용히 무력화된다.</b>
     *
     * <p>후보 조회는 {@code RAW_SN} 오름차순 + 상한이고, 보류된 후보는 상태가 그대로여서 다음 tick 에도
     * 같은 자리에 다시 뽑힌다. 표식 없는 {@code PROCESSING} 은 선점 직전 상태를 기록하지 않는 다른 진입
     * 경로가 남기며 <b>회수해 줄 다른 주체가 없다</b> — 그런 영상이 상한만큼 쌓이면 그 뒤의 진짜 회수
     * 대상은 영영 판정되지 않는다.
     *
     * <p>커서가 회전하면 다음 tick 이 그 뒤부터 훑어 뒷줄에 도달한다.
     */
    @Test
    @DisplayName("★★상한을_꽉_채운_tick_다음에는_그_뒤부터_훑는다_앞줄_영구점유_차단")
    void cursorAdvancesPastAFullPageSoTheTailIsReached() {
        int limit = 3;
        List<Long> blockedHead = List.of(1L, 2L, 3L);
        when(reclaimTxService.findStaleCandidates(any(), eq(0L), eq(limit)))
                .thenReturn(blockedHead);
        // 커서가 앞줄 뒤로 넘어가야만 보이는 진짜 회수 대상.
        when(reclaimTxService.findStaleCandidates(any(), eq(3L), eq(limit)))
                .thenReturn(List.of(9L));
        blockedHead.forEach(rawSn -> when(reclaimTxService.decide(eq(rawSn), any()))
                .thenReturn(ReclaimDecision.withheld(
                        ReclaimDecision.WithholdReason.NO_CLAIM_MARKER)));
        when(reclaimTxService.decide(eq(9L), any()))
                .thenReturn(ReclaimDecision.reclaim(ReprocessClaimOrigin.FAILED));
        when(reclaimTxService.reclaimAndClose(eq(9L), any())).thenReturn(true);

        ProcessingStaleReclaimSweeper sweeper = sweeper(limit);

        assertThat(sweeper.run()).isZero();      // 1회차 — 보류만 3건
        assertThat(sweeper.run()).isEqualTo(1);  // 2회차 — 뒷줄의 진짜 대상에 도달한다

        verify(reclaimTxService).reclaimAndClose(9L, ReprocessClaimOrigin.FAILED);
    }

    @Test
    @DisplayName("상한에_못_미친_tick_다음에는_처음부터_다시_훑는다_끝에_닿으면_회전")
    void cursorWrapsToStartWhenPageIsNotFull() {
        int limit = 3;
        when(reclaimTxService.findStaleCandidates(any(), anyLong(), eq(limit)))
                .thenReturn(List.of(5L, 6L)); // 상한 미달 = 끝까지 훑었다
        when(reclaimTxService.decide(anyLong(), any()))
                .thenReturn(ReclaimDecision.withheld(
                        ReclaimDecision.WithholdReason.STALE_EPISODE));

        ProcessingStaleReclaimSweeper sweeper = sweeper(limit);
        sweeper.run();
        sweeper.run();

        // 두 번째 tick 도 커서 0(처음)에서 시작한다 — 앞줄을 영영 건너뛰지 않는다.
        verify(reclaimTxService, times(2)).findStaleCandidates(any(), eq(0L), eq(limit));
    }

    /**
     * ★자동 재시도 금지는 <b>구조로</b> 고정한다 — 스윕은 재시도 큐·러너를 의존성으로 갖지 않는다.
     *
     * <p>회수된 영상이 자동으로 다시 돌면 같은 이유로 또 멈추고, 완주 축 영상이면 전체 재기동 경로가
     * 열려 사람이 손댄 보간 라벨이 파괴된다. 의존성이 없으면 나중에 누가 "편의상" 재시도를 붙일 때
     * 반드시 생성자를 건드려야 하므로 리뷰에 드러난다.
     */
    @Test
    @DisplayName("★회수는_자동_재시도를_트리거하지_않는다_재시도_협력자를_아예_보유하지_않는다")
    void reclaimNeverTriggersAutomaticRetry() {
        List<Class<?>> collaborators = java.util.Arrays.stream(
                        ProcessingStaleReclaimSweeper.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)
                .toList();

        assertThat(collaborators)
                .noneMatch(t -> t.getSimpleName().contains("RetryQueue"))
                .noneMatch(t -> t.getSimpleName().contains("Runner"))
                .noneMatch(t -> t.getSimpleName().contains("Orchestrator"));
    }

    @Test
    @DisplayName("임계는_설정값을_그대로_쓴다_컷오프가_설정_분만큼_과거다")
    void cutoffFollowsConfiguredThreshold() {
        when(reclaimTxService.findStaleCandidates(any(), anyLong(), anyInt())).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now().minusMinutes(180);

        sweeper().run();

        org.mockito.ArgumentCaptor<LocalDateTime> captor =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reclaimTxService).findStaleCandidates(captor.capture(), eq(0L), eq(50));
        assertThat(captor.getValue()).isBetween(before.minusSeconds(30), before.plusSeconds(30));
    }

    @Test
    @DisplayName("tick당_후보_상한은_설정값을_넘지_않는다_자원_고갈_방지")
    void batchSizeIsPassedThrough() {
        when(reclaimTxService.findStaleCandidates(any(), anyLong(), anyInt()))
                .thenReturn(LongStream.rangeClosed(1, 7).boxed().toList());
        when(reclaimTxService.decide(anyLong(), any())).thenReturn(ReclaimDecision.alive());

        sweeper(7).run();

        verify(reclaimTxService).findStaleCandidates(any(), eq(0L), eq(7));
    }
}
