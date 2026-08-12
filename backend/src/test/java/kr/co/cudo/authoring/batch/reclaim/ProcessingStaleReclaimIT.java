package kr.co.cudo.authoring.batch.reclaim;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.status.ManualStageSkip;
import kr.co.cudo.authoring.batch.status.ReprocessClaimOrigin;
import kr.co.cudo.authoring.support.RawVideoFixture;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「처리 중」 고착 회수의 실 DB 통합 테스트 — 선점 표식 · 회수 원자성 · 두 컬럼 복구.
 *
 * <p>여기서 지키는 것:
 * <ol>
 *   <li><b>선점 표식이 진행 축·수동 스킵 축을 오염시키지 않는다</b> — 표식은
 *       {@code PROC_STTS_CD='SKIPPED'} + 전용 {@code PROC_STEP_CD} 네임스페이스라 화면 단계 표시와
 *       수동 스킵 판정 어디에도 걸리면 안 된다.</li>
 *   <li><b>열림 → 닫힘 순서로 판정이 뒤집힌다</b> — append-only 이며 마지막 행이 정한다.</li>
 *   <li><b>회수는 두 컬럼을 함께 되돌린다</b> — 배치 단계만 되돌리면 작업 상태가 {@code PROCESSING} 에
 *       남아 작업자가 검수 제출을 못 한다.</li>
 *   <li><b>회수는 원자 클레임이다</b> — 두 번째 호출은 영향 행수 0 으로 실패한다(2노드 이중 회수 차단).</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
class ProcessingStaleReclaimIT {

    @Autowired private BatchStatusService statusService;
    @Autowired private BatchTransitionService transitionService;
    @Autowired private ProcessingStaleReclaimTxService reclaimTxService;
    @Autowired private ProcessingStaleReclaimSweeper sweeper;
    @Autowired private ProcessingStaleReclaimProperties properties;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long seededRawSn;

    @AfterEach
    void cleanSeededVideo() {
        if (seededRawSn != null) {
            RawVideoFixture.deleteRaws(jdbcTemplate, seededRawSn);
            seededRawSn = null;
        }
    }

    private Long newRaw() {
        seededRawSn = RawVideoFixture.newRaw(jdbcTemplate);
        return seededRawSn;
    }

    private String stageOf(Long rawSn) {
        return jdbcTemplate.queryForObject(
                "SELECT DATA_STTS_CD FROM LS_DATA_RAW WHERE RAW_SN = ?", String.class, rawSn);
    }

    private String workStatusOf(Long rawSn) {
        return jdbcTemplate.queryForObject(
                "SELECT DATA_STTS_CD FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", String.class, rawSn);
    }

    /**
     * ★배선 고정 — 「코드는 맞는데 배포 형상에서 한 번도 안 도는」 사고를 막는다.
     *
     * <p>이 리포에는 구현이 정확한데 실행 경로가 0건이던 사고 이력이 있다. 스윕이 실제 컨텍스트에서
     * 빈으로 뜨고 전용 데몬 스케줄러까지 예약됐는지를 컨텍스트 경유로 확인한다.
     */
    @Test
    @DisplayName("★회수_스윕이_실제_컨텍스트에서_빈으로_뜨고_전용_스케줄러가_예약된다")
    void sweeperIsWiredAndScheduled() {
        assertThat(sweeper).isNotNull();
        assertThat(sweeper.isScheduled()).isTrue();
        // 임계는 기동 가드가 통과시킨 값이며 하한 미만일 수 없다.
        assertThat(properties.staleTimeoutMinutes())
                .isGreaterThanOrEqualTo(ProcessingStaleReclaimProperties.MIN_STALE_TIMEOUT_MINUTES);
    }

    @Test
    @DisplayName("선점_표식은_열림일_때만_읽히고_닫히면_사라진다_append_only")
    void claimMarkerIsOpenUntilClosed() {
        Long rawSn = newRaw();
        assertThat(statusService.openReprocessClaimMarker(rawSn)).isEmpty();

        statusService.recordReprocessClaimOpened(rawSn, LsDataRaw.DATA_STTS_COMPLETED);
        assertThat(statusService.openReprocessClaimMarker(rawSn))
                .hasValueSatisfying(l -> assertThat(ReprocessClaimOrigin.parse(l.getErrMsg()))
                        .contains(ReprocessClaimOrigin.COMPLETED));

        statusService.recordReprocessClaimClosed(rawSn, "실행 종료로 선점 해제");
        assertThat(statusService.openReprocessClaimMarker(rawSn)).isEmpty();

        // 다시 선점하면 새 열림 행이 마지막이 되어 판정이 되살아난다(스킵→해제→재스킵과 같은 골격).
        statusService.recordReprocessClaimOpened(rawSn, LsDataRaw.DATA_STTS_FAILED);
        assertThat(statusService.openReprocessClaimMarker(rawSn))
                .hasValueSatisfying(l -> assertThat(ReprocessClaimOrigin.parse(l.getErrMsg()))
                        .contains(ReprocessClaimOrigin.FAILED));
    }

    @Test
    @DisplayName("★선점_표식이_진행축과_수동스킵축을_오염시키지_않는다")
    void claimMarkerDoesNotPolluteOtherAxes() {
        Long rawSn = newRaw();
        statusService.markStage(rawSn, BatchStage.FRAME_EXTRACT);

        statusService.recordReprocessClaimOpened(rawSn, LsDataRaw.DATA_STTS_FAILED);
        statusService.recordReprocessClaimClosed(rawSn, "실행 종료로 선점 해제");

        // 진행 축(화면 단계 표시)은 표식 행을 보지 않는다.
        assertThat(statusService.currentStage(rawSn)).isEqualTo(BatchStage.FRAME_EXTRACT);
        // 수동 스킵 축도 표식 행을 보지 않는다(ERR_CD 네임스페이스가 다르다).
        assertThat(statusService.isBundleManuallySkipped(rawSn, BatchStageBundle.VLM)).isFalse();
        assertThat(statusService.isBundleManuallySkipped(rawSn, BatchStageBundle.AUTOLABEL)).isFalse();
        assertThat(statusService.manuallySkippedBundles(rawSn)).isEmpty();
        assertThat(statusService.hasClearedManualSkip(rawSn, BatchStageBundle.VLM)).isFalse();
    }

    @Test
    @DisplayName("수동_스킵_표식과_선점_표식은_서로의_조회에_섞이지_않는다")
    void manualSkipMarkerAndClaimMarkerAreIsolated() {
        Long rawSn = newRaw();
        statusService.recordManualStageSkip(rawSn, BatchStageBundle.VLM,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
        statusService.recordReprocessClaimOpened(rawSn, LsDataRaw.DATA_STTS_COMPLETED);

        // 수동 스킵은 여전히 서 있고
        assertThat(statusService.isBundleManuallySkipped(rawSn, BatchStageBundle.VLM)).isTrue();
        // 선점 표식도 열려 있다 — 마지막 표식 판정이 서로를 덮지 않는다.
        assertThat(statusService.openReprocessClaimMarker(rawSn)).isPresent();
    }

    @Test
    @DisplayName("★회수는_배치단계와_작업상태를_함께_되돌린다_완주축")
    void reclaimRestoresBothColumnsForCompletedOrigin() {
        Long rawSn = newRaw();
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ? WHERE RAW_SN = ?",
                LsDataRaw.DATA_STTS_PROCESSING, rawSn);
        jdbcTemplate.update("INSERT INTO LS_RAW_DATA_STATUS "
                + "(RAW_DATA_ID, DATA_STTS_CD, STP_CYCL, IGI_CYCL, UPD_DT, VER) "
                + "VALUES (?, 'PROCESSING', 0, 0, CURRENT_TIMESTAMP, 0)", rawSn);

        boolean reclaimed = transitionService.reclaimStuckProcessing(rawSn,
                ReprocessClaimOrigin.COMPLETED.stageStatus(),
                ReprocessClaimOrigin.COMPLETED.workStatus());

        assertThat(reclaimed).isTrue();
        assertThat(stageOf(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
        // 작업 상태를 남겨 두면 상태 머신에 그 출발 전이가 없어 작업자가 검수 제출을 못 한다.
        assertThat(workStatusOf(rawSn)).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("회수는_원자_클레임이라_두_번째_호출은_실패한다_2노드_이중회수_차단")
    void reclaimIsAtomic() {
        Long rawSn = newRaw();
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ? WHERE RAW_SN = ?",
                LsDataRaw.DATA_STTS_PROCESSING, rawSn);

        assertThat(transitionService.reclaimStuckProcessing(rawSn,
                LsDataRaw.DATA_STTS_FAILED, "FAILED")).isTrue();
        // 이미 PROCESSING 이 아니므로 두 번째는 0행이다.
        assertThat(transitionService.reclaimStuckProcessing(rawSn,
                LsDataRaw.DATA_STTS_FAILED, "FAILED")).isFalse();
        assertThat(stageOf(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_FAILED);
    }

    @Test
    @DisplayName("복구_목표가_비어_있으면_되돌리지_않는다_추측_복구_금지")
    void missingRestoreTargetIsRejected() {
        Long rawSn = newRaw();
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ? WHERE RAW_SN = ?",
                LsDataRaw.DATA_STTS_PROCESSING, rawSn);

        assertThat(transitionService.reclaimStuckProcessing(rawSn, null, "FAILED")).isFalse();
        assertThat(transitionService.reclaimStuckProcessing(rawSn, "  ", "FAILED")).isFalse();
        assertThat(transitionService.reclaimStuckProcessing(rawSn, LsDataRaw.DATA_STTS_FAILED, null))
                .isFalse();
        assertThat(stageOf(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_PROCESSING);
    }

    @Test
    @DisplayName("고착_후보는_PROCESSING이면서_오래_갱신되지_않은_영상만_뽑힌다")
    void candidateQuerySelectsOnlyStaleProcessing() {
        Long rawSn = newRaw();
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ? WHERE RAW_SN = ?",
                LsDataRaw.DATA_STTS_PROCESSING, rawSn);

        // 방금 갱신 → 후보 아님
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET MDFCN_DT = CURRENT_TIMESTAMP WHERE RAW_SN = ?", rawSn);
        assertThat(reclaimTxService.findStaleCandidates(LocalDateTime.now().minusHours(3), 0L, 200))
                .doesNotContain(rawSn);

        // 오래 전 갱신 → 후보
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET MDFCN_DT = ? WHERE RAW_SN = ?",
                LocalDateTime.now().minusDays(1), rawSn);
        assertThat(reclaimTxService.findStaleCandidates(LocalDateTime.now().minusHours(3), 0L, 200))
                .contains(rawSn);

        // PROCESSING 이 아니면 후보가 아니다
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ? WHERE RAW_SN = ?",
                LsDataRaw.DATA_STTS_COMPLETED, rawSn);
        assertThat(reclaimTxService.findStaleCandidates(LocalDateTime.now().minusHours(3), 0L, 200))
                .doesNotContain(rawSn);
    }

    /**
     * ★회전 커서 — 이 값보다 큰 {@code RAW_SN} 만 후보다. 이게 없으면 판정 불가 후보가 상한을 채워
     * 뒷줄의 진짜 회수 대상이 영영 뽑히지 않는다.
     */
    @Test
    @DisplayName("★회전_커서보다_앞선_영상은_후보에서_빠진다")
    void candidateQueryHonoursTheRotationCursor() {
        Long rawSn = newRaw();
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ?, MDFCN_DT = ? WHERE RAW_SN = ?",
                LsDataRaw.DATA_STTS_PROCESSING, LocalDateTime.now().minusDays(1), rawSn);
        LocalDateTime cutoff = LocalDateTime.now().minusHours(3);

        assertThat(reclaimTxService.findStaleCandidates(cutoff, rawSn - 1, 200)).contains(rawSn);
        assertThat(reclaimTxService.findStaleCandidates(cutoff, rawSn, 200)).doesNotContain(rawSn);
    }

    /**
     * ★★<b>되돌리기와 표식 닫기는 한 트랜잭션</b>이다 — 둘 중 하나만 남으면 안 된다.
     *
     * <p>표식이 열린 채 남으면 나중에 <b>다른 진입 경로</b>로 고착된 같은 영상을 스윕이 옛 출발 상태로
     * 되돌린다(완주 영상의 {@code FAILED} 강등 = 이 설계가 막으려던 파괴).
     */
    @Test
    @DisplayName("★★회수는_상태_되돌리기와_표식_닫기를_함께_끝낸다")
    void reclaimAndCloseAreOneUnit() {
        Long rawSn = newRaw();
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ? WHERE RAW_SN = ?",
                LsDataRaw.DATA_STTS_PROCESSING, rawSn);
        statusService.recordReprocessClaimOpened(rawSn, LsDataRaw.DATA_STTS_COMPLETED);
        assertThat(statusService.openReprocessClaimMarker(rawSn)).isPresent();

        assertThat(reclaimTxService.reclaimAndClose(rawSn, ReprocessClaimOrigin.COMPLETED)).isTrue();

        assertThat(stageOf(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
        // 표식이 닫혀야 같은 영상이 다음 에피소드에서 옛 출발 축으로 회수되지 않는다.
        assertThat(statusService.openReprocessClaimMarker(rawSn)).isEmpty();
    }

    @Test
    @DisplayName("경합에_진_회수는_표식을_닫지_않는다_승자의_기록만_남는다")
    void loserDoesNotCloseTheMarker() {
        Long rawSn = newRaw();
        // 이미 PROCESSING 이 아니다 → 조건부 UPDATE 0행.
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ? WHERE RAW_SN = ?",
                LsDataRaw.DATA_STTS_COMPLETED, rawSn);
        statusService.recordReprocessClaimOpened(rawSn, LsDataRaw.DATA_STTS_COMPLETED);

        assertThat(reclaimTxService.reclaimAndClose(rawSn, ReprocessClaimOrigin.COMPLETED)).isFalse();

        assertThat(statusService.openReprocessClaimMarker(rawSn)).isPresent();
    }

    /**
     * ★★<b>에피소드 결속</b> — 닫힘 행이 유실돼 표식이 열린 채 남아도, 그 표식 <b>이후에</b> 배치가
     * 종결까지 갔으면 옛 에피소드의 잔재이므로 회수하지 않는다.
     */
    @Test
    @DisplayName("★★표식_이후의_종결_기록은_잔재_표식의_증거다_그_영상은_회수하지_않는다")
    void staleEpisodeMarkerIsWithheld() {
        Long rawSn = newRaw();
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ?, MDFCN_DT = ? WHERE RAW_SN = ?",
                LsDataRaw.DATA_STTS_PROCESSING, LocalDateTime.now().minusDays(1), rawSn);
        // 전체 재기동(FAILED 출발)이 선점했고, 그 실행은 끝까지 갔는데 닫힘 행이 유실됐다.
        statusService.recordReprocessClaimOpened(rawSn, LsDataRaw.DATA_STTS_FAILED);
        statusService.markStage(rawSn, BatchStage.FRAME_EXTRACT);
        statusService.markCompleted(rawSn);
        // 선점 표식은 그대로 열려 있다 — 이것만 보면 회수 대상으로 보인다.
        assertThat(statusService.openReprocessClaimMarker(rawSn)).isPresent();

        ReclaimDecision decision = reclaimTxService.decide(rawSn, LocalDateTime.now().plusMinutes(10));

        assertThat(decision.isReclaimable()).isFalse();
        assertThat(decision.reason()).isEqualTo(ReclaimDecision.WithholdReason.STALE_EPISODE);
        // 회수됐다면 완주 영상이 FAILED 로 강등됐을 것이다.
        assertThat(stageOf(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_PROCESSING);
    }

    @Test
    @DisplayName("표식_이전의_종결_기록은_직전_실행의_것이라_회수를_막지_않는다")
    void terminationBeforeTheMarkerDoesNotBlockReclaim() {
        Long rawSn = newRaw();
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ?, MDFCN_DT = ? WHERE RAW_SN = ?",
                LsDataRaw.DATA_STTS_PROCESSING, LocalDateTime.now().minusDays(1), rawSn);
        // 직전 실행이 끝난 뒤(=종결 기록) 새로 선점했고, 큐 대기 중 노드가 죽었다.
        statusService.markStage(rawSn, BatchStage.FRAME_EXTRACT);
        statusService.markCompleted(rawSn);
        jdbcTemplate.update("UPDATE LS_BATCH_PROC_LOG SET MDFCN_DT = ? "
                        + "WHERE DATA_RAW_SN = ? AND PROC_STTS_CD = 'COMPLETED'",
                LocalDateTime.now().minusDays(2), rawSn);
        statusService.recordReprocessClaimOpened(rawSn, LsDataRaw.DATA_STTS_COMPLETED);

        ReclaimDecision decision = reclaimTxService.decide(rawSn, LocalDateTime.now().plusMinutes(10));

        assertThat(decision.isReclaimable()).isTrue();
        assertThat(decision.origin()).isEqualTo(ReprocessClaimOrigin.COMPLETED);
    }
}
