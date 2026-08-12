package kr.co.cudo.authoring.batch.reclaim;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.status.LsBatchProcLog;
import kr.co.cudo.authoring.batch.status.ReprocessClaimMarker;
import kr.co.cudo.authoring.batch.status.ReprocessClaimOrigin;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 고착 판정의 <b>축</b>을 고정한다 — "경과 시간"이 아니라 "진행이 멈췄는가", 그리고 "그 표식이
 * <b>이번 에피소드</b>의 것인가".
 *
 * <p>지키는 것은 넷이다.
 * <ol>
 *   <li><b>진행 중인 파이프라인은 회수되지 않는다</b> — 단계가 넘어갈 때마다 진행 로그가 갱신되므로
 *       그 값이 임계 안이면 대상이 아니다. 뺏으면 그 작업이 나중에 끝나며 상태를 덮어써 뒤죽박죽이 되고,
 *       그사이 다른 진입이 같은 영상을 선점해 파이프라인이 2벌 돈다.</li>
 *   <li><b>큐에서 대기 중인 정상 요청도 회수되지 않는다</b> — 대기 중에는 진행 로그가 한 번도 갱신되지
 *       않아 <b>직전 실행 때의 옛 시각</b>에 멈춰 있다. 진행 로그만 보면 방금 접수된 요청이 즉시
 *       "멈춤"으로 판정된다. 선점 시각이 그 구간을 덮는다.</li>
 *   <li><b>지난 에피소드의 잔재 표식으로는 회수하지 않는다</b> — 닫힘 행이 유실되면 표식이 열린 채 남고,
 *       그것을 근거로 되돌리면 완주 영상이 옛 출발 상태({@code FAILED})로 강등된다.</li>
 *   <li><b>알 수 없으면 회수하지 않는다</b> — 선점 표식이 없거나 값이 해석되지 않으면 보류다.</li>
 * </ol>
 *
 * <p>시각 제어는 {@code cutoff} 파라미터로 한다(표식의 {@code REG_DT} 는 생성 시각 고정) — cutoff 를
 * 미래로 두면 "표식이 임계보다 오래됐다", 과거로 두면 "아직 임계 안"이 된다.
 */
class ProcessingStaleReclaimTxServiceTest {

    private static final Long RAW_SN = 777L;

    private VideoRepository videoRepository;
    private BatchStatusService batchStatusService;
    private BatchTransitionService transitionService;
    private ProcessingStaleReclaimTxService service;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        transitionService = mock(BatchTransitionService.class);
        service = new ProcessingStaleReclaimTxService(
                videoRepository, batchStatusService, transitionService);
    }

    /** 열려 있는 선점 표식(REG_DT = 지금). */
    private void givenOpenClaim(ReprocessClaimOrigin origin) {
        when(batchStatusService.openReprocessClaimMarker(RAW_SN)).thenReturn(Optional.of(
                LsBatchProcLog.createReprocessClaimMarker(
                        RAW_SN, ReprocessClaimMarker.ERR_CD_OPEN, origin.name(),
                        ReprocessClaimMarker.REG_ID)));
    }

    @Test
    @DisplayName("★진행_로그가_최근에_갱신됐으면_회수하지_않는다_실행중인_파이프라인_보호")
    void doesNotReclaimWhileStagesAreStillProgressing() {
        givenOpenClaim(ReprocessClaimOrigin.FAILED);
        // 선점은 임계를 넘겼지만(표식 REG_DT < cutoff) 단계가 방금 넘어갔다 → 살아 있다.
        LocalDateTime cutoff = LocalDateTime.now().plusMinutes(10);
        when(batchStatusService.latestProgressUpdatedAt(RAW_SN))
                .thenReturn(Optional.of(cutoff.plusMinutes(1)));

        ReclaimDecision decision = service.decide(RAW_SN, cutoff);

        assertThat(decision.isReclaimable()).isFalse();
        // 살아 있는 것은 정상이므로 보류(=사람이 봐야 함)로 집계되면 안 된다.
        assertThat(decision.isWithheld()).isFalse();
    }

    @Test
    @DisplayName("★큐에서_대기_중인_선점은_회수하지_않는다_진행로그가_옛_시각이어도")
    void doesNotReclaimFreshClaimEvenWhenProgressLogIsAncient() {
        givenOpenClaim(ReprocessClaimOrigin.COMPLETED);
        // 진행 로그는 3일 전(직전 실행 때) 그대로다 — 이 값만 보면 즉시 "멈춤"으로 오판한다.
        when(batchStatusService.latestProgressUpdatedAt(RAW_SN))
                .thenReturn(Optional.of(LocalDateTime.now().minusDays(3)));
        // 선점은 방금(cutoff 이후)이다.
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);

        assertThat(service.decide(RAW_SN, cutoff).isReclaimable()).isFalse();
    }

    @Test
    @DisplayName("선점도_진행도_임계를_넘겼으면_고착으로_보고_출발축을_돌려준다")
    void reclaimsWhenNeitherClaimNorProgressMovedWithinThreshold() {
        givenOpenClaim(ReprocessClaimOrigin.COMPLETED);
        LocalDateTime cutoff = LocalDateTime.now().plusMinutes(10);
        when(batchStatusService.latestProgressUpdatedAt(RAW_SN))
                .thenReturn(Optional.of(LocalDateTime.now().minusDays(1)));

        ReclaimDecision decision = service.decide(RAW_SN, cutoff);

        assertThat(decision.isReclaimable()).isTrue();
        assertThat(decision.origin()).isEqualTo(ReprocessClaimOrigin.COMPLETED);
    }

    @Test
    @DisplayName("진행_로그가_아예_없어도_선점_시각만으로_판정한다_한_단계도_실행되지_않은_고착")
    void judgesByClaimTimeAloneWhenNoProgressRowExists() {
        givenOpenClaim(ReprocessClaimOrigin.FAILED);
        when(batchStatusService.latestProgressUpdatedAt(RAW_SN)).thenReturn(Optional.empty());

        assertThat(service.decide(RAW_SN, LocalDateTime.now().plusMinutes(10)).origin())
                .isEqualTo(ReprocessClaimOrigin.FAILED);
        assertThat(service.decide(RAW_SN, LocalDateTime.now().minusMinutes(10)).isReclaimable())
                .isFalse();
    }

    /**
     * ★★<b>에피소드 결속</b> — 닫힘 행 1건이 유실되면 표식은 열린 채 남는다(러너 {@code finally} 의
     * 기록 실패·프로세스 사망). 그 잔재를 근거로 회수하면 「전체 재기동(FAILED 출발) → 완주 → 이후 다른
     * 경로로 고착」 형상에서 <b>완주 영상이 {@code FAILED} 로 강등</b>돼 전체 재기동 경로가 열리고, 그
     * 경로가 사람이 손댄 보간 라벨을 전량 삭제·재생성한다.
     *
     * <p>가르는 신호는 <b>표식 이후의 종결 기록</b>이다 — 진짜 고착(큐 대기 중 사망)은 한 단계도 실행하지
     * 못해 표식 이후 종결 기록이 없다.
     */
    @Test
    @DisplayName("★★표식이_열린_뒤_배치가_종결까지_갔으면_옛_에피소드_잔재로_보고_회수하지_않는다")
    void withholdsWhenBatchTerminatedAfterMarkerOpened() {
        givenOpenClaim(ReprocessClaimOrigin.FAILED);
        when(batchStatusService.latestProgressUpdatedAt(RAW_SN))
                .thenReturn(Optional.of(LocalDateTime.now().minusDays(1)));
        when(batchStatusService.progressTerminatedAfter(eq(RAW_SN), any())).thenReturn(true);

        ReclaimDecision decision = service.decide(RAW_SN, LocalDateTime.now().plusMinutes(10));

        assertThat(decision.isReclaimable()).isFalse();
        assertThat(decision.reason())
                .isEqualTo(ReclaimDecision.WithholdReason.STALE_EPISODE);
    }

    @Test
    @DisplayName("종결_기록이_표식보다_앞서면_진짜_고착이므로_회수한다")
    void reclaimsWhenTerminationPredatesTheMarker() {
        givenOpenClaim(ReprocessClaimOrigin.COMPLETED);
        when(batchStatusService.latestProgressUpdatedAt(RAW_SN))
                .thenReturn(Optional.of(LocalDateTime.now().minusDays(1)));
        // 직전 실행의 종결은 표식보다 이전이다 → 잔재가 아니다.
        when(batchStatusService.progressTerminatedAfter(eq(RAW_SN), any())).thenReturn(false);

        assertThat(service.decide(RAW_SN, LocalDateTime.now().plusMinutes(10)).isReclaimable())
                .isTrue();
    }

    @Test
    @DisplayName("★선점_표식이_없으면_회수하지_않는다_다른_경로로_고착된_영상_보호")
    void doesNotReclaimWithoutClaimMarker() {
        // 마킹 브리지·자동 재시도 잡·dev 트리거는 선점 직전 상태를 기록하지 않는다.
        // 되돌릴 목표를 모르는 채 추측하면 완주 영상이 FAILED 로 강등될 수 있다(fail-closed).
        when(batchStatusService.openReprocessClaimMarker(RAW_SN)).thenReturn(Optional.empty());

        ReclaimDecision decision = service.decide(RAW_SN, LocalDateTime.now().plusMinutes(10));

        assertThat(decision.isReclaimable()).isFalse();
        assertThat(decision.reason()).isEqualTo(ReclaimDecision.WithholdReason.NO_CLAIM_MARKER);
    }

    @Test
    @DisplayName("★표식의_출발상태_값이_해석되지_않으면_회수하지_않는다")
    void doesNotReclaimWhenStoredOriginIsUnreadable() {
        when(batchStatusService.openReprocessClaimMarker(RAW_SN)).thenReturn(Optional.of(
                LsBatchProcLog.createReprocessClaimMarker(
                        RAW_SN, ReprocessClaimMarker.ERR_CD_OPEN, "MARKING_READY",
                        ReprocessClaimMarker.REG_ID)));

        ReclaimDecision decision = service.decide(RAW_SN, LocalDateTime.now().plusMinutes(10));

        assertThat(decision.isReclaimable()).isFalse();
        assertThat(decision.reason()).isEqualTo(ReclaimDecision.WithholdReason.UNREADABLE_ORIGIN);
    }

    @Test
    @DisplayName("후보_조회는_rawSn이나_상한이_유효하지_않으면_DB를_치지_않는다")
    void guardsCandidateQueryArguments() {
        assertThat(service.findStaleCandidates(null, 0L, 10)).isEmpty();
        assertThat(service.findStaleCandidates(LocalDateTime.now(), 0L, 0)).isEmpty();
        assertThat(service.findStaleCandidates(LocalDateTime.now(), -1L, 10)).isEmpty();
        assertThat(service.decide(null, LocalDateTime.now()).isReclaimable()).isFalse();
        assertThat(service.decide(RAW_SN, null).isReclaimable()).isFalse();
        verify(videoRepository, never()).findStaleProcessingRawSns(
                any(), any(), anyLong(), any());
        verify(batchStatusService, never()).openReprocessClaimMarker(anyLong());
    }

    /**
     * ★<b>완주 출발 영상은 절대 {@code FAILED} 로 회수되지 않는다</b> — 회수의 유일한 데이터 파괴 경로다.
     * 축 매핑은 {@link ReprocessClaimOrigin} 이 소유하며 여기서 재유도하지 않는다.
     */
    @Test
    @DisplayName("★완주_출발은_COMPLETED와_ASSIGNED로_되돌리고_FAILED로_강등하지_않는다")
    void completedOriginIsNeverDemotedToFailed() {
        when(transitionService.reclaimStuckProcessing(anyLong(), anyString(), anyString()))
                .thenReturn(true);

        assertThat(service.reclaimAndClose(RAW_SN, ReprocessClaimOrigin.COMPLETED)).isTrue();

        verify(transitionService).reclaimStuckProcessing(
                RAW_SN, LsDataRaw.DATA_STTS_COMPLETED, LsRawDataStatus.STTS_ASSIGNED);
        verify(transitionService, never()).reclaimStuckProcessing(
                anyLong(), eq(LsDataRaw.DATA_STTS_FAILED), anyString());
        verify(transitionService, never()).reclaimStuckProcessing(
                anyLong(), anyString(), eq(LsRawDataStatus.STTS_FAILED));
    }

    @Test
    @DisplayName("실패_출발은_두_컬럼_모두_FAILED로_되돌린다")
    void failedOriginRestoresBothColumns() {
        when(transitionService.reclaimStuckProcessing(anyLong(), anyString(), anyString()))
                .thenReturn(true);

        service.reclaimAndClose(RAW_SN, ReprocessClaimOrigin.FAILED);

        verify(transitionService).reclaimStuckProcessing(
                RAW_SN, LsDataRaw.DATA_STTS_FAILED, LsRawDataStatus.STTS_FAILED);
    }

    @Test
    @DisplayName("회수하면_무엇을_왜_되돌렸는지_감사_표식을_남긴다")
    void winnerRecordsAudit() {
        when(transitionService.reclaimStuckProcessing(anyLong(), anyString(), anyString()))
                .thenReturn(true);

        service.reclaimAndClose(RAW_SN, ReprocessClaimOrigin.COMPLETED);

        verify(batchStatusService).recordReprocessClaimReclaimed(RAW_SN,
                ProcessingStaleReclaimTxService.RECLAIM_DETAIL_PREFIX
                        + LsDataRaw.DATA_STTS_COMPLETED);
    }

    /**
     * ★★<b>되돌리기와 표식 닫기는 한 트랜잭션</b>이어야 한다 — 전파 설정으로 고정한다.
     *
     * <p>따로 커밋하면 사이에서 죽거나 뒤가 던질 때 <b>상태는 되돌아갔는데 표식이 열린 채</b> 남는다.
     * 그 표식은 나중에 다른 진입 경로로 고착된 같은 영상을 옛 출발 상태로 되돌리게 만들고, 「전체
     * 재기동(FAILED) → 완주 → 다른 경로로 고착」 형상에서는 <b>완주 영상이 {@code FAILED} 로 강등</b>돼
     * 사람이 손댄 보간 라벨이 전량 삭제·재생성된다.
     *
     * <p>런타임 동작으로는 "닫혔다"만 관측되고 <b>같은 경계였는지</b>는 관측되지 않으므로(협력자 하나만
     * {@code REQUIRES_NEW} 로 되돌려도 성공 경로의 결과는 동일하다) 전파 설정 자체를 고정한다.
     */
    @Test
    @DisplayName("★★되돌리기와_표식_닫기는_한_트랜잭션에_묶인다_전파설정_고정")
    void revertAndMarkerCloseShareOneTransaction() throws Exception {
        // 경계는 여기서 연다.
        assertThat(propagationOf(ProcessingStaleReclaimTxService.class,
                "reclaimAndClose", Long.class, ReprocessClaimOrigin.class))
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
        // 두 협력자는 그 경계에 <b>참여</b>해야 한다 — REQUIRES_NEW 면 따로 커밋돼 원자성이 깨진다.
        assertThat(propagationOf(BatchTransitionService.class,
                "reclaimStuckProcessing", Long.class, String.class, String.class))
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
        assertThat(propagationOf(BatchStatusService.class,
                "recordReprocessClaimReclaimed", Long.class, String.class))
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
    }

    private static org.springframework.transaction.annotation.Propagation propagationOf(
            Class<?> type, String method, Class<?>... params) throws NoSuchMethodException {
        org.springframework.transaction.annotation.Transactional tx =
                type.getMethod(method, params)
                        .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx).as("%s#%s 에 @Transactional 이 없다", type.getSimpleName(), method).isNotNull();
        return tx.propagation();
    }

    @Test
    @DisplayName("원자_클레임에_진_노드는_감사를_남기지_않는다_2노드_이중회수_차단")
    void loserNodeLeavesNoAudit() {
        // 조건부 UPDATE 0행 = 다른 노드가 이미 회수했거나 파이프라인이 스스로 마감했다.
        when(transitionService.reclaimStuckProcessing(anyLong(), anyString(), anyString()))
                .thenReturn(false);

        assertThat(service.reclaimAndClose(RAW_SN, ReprocessClaimOrigin.FAILED)).isFalse();

        verify(batchStatusService, never()).recordReprocessClaimReclaimed(anyLong(), anyString());
    }
}
