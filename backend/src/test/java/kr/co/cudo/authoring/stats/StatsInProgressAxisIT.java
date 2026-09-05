package kr.co.cudo.authoring.stats;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.stats.dto.OverallStatSummaryResponse;
import kr.co.cudo.authoring.stats.dto.WorkerStatSummaryResponse;
import kr.co.cudo.authoring.stats.service.StatsService;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code inProgress} 판정 축 회귀 가드 — 전체 통계(SCR-STAT-002)와 작업자 통계(SCR-STAT-001)가
 * <b>같은 축</b>으로 "진행 중" 을 판정하는지 고정한다.
 *
 * <p><b>축</b>: 배정된 {@code LS_RAW_DATA_STATUS} 행 중 <b>완료 상태({@code APPROVED})가 아닌 것</b>
 * 전부(단일 진실원 {@code StatsQueryRepository.IN_PROGRESS_PREDICATE}). 진행 상태를 열거하지 않는다 —
 * 검수 완료로 쓰이는 상태값은 {@code APPROVED} 하나뿐이고({@code STTS_COMPLETED} 로 전이하는 코드가
 * 없다), 열거하면 새 상태값이 생길 때 완료에도 진행에도 안 잡혀 화면에서 조용히 사라진다.
 *
 * <p><b>왜 이 가드가 필요했나</b>: 두 화면이 각자 판정하고 있었다 — 작업자 통계는
 * {@code ASSIGNED + IN_REVIEW} 를 <b>열거</b>해 더했고 전체 통계는 여집합으로 셌다. 그래서
 * {@code PENDING}/{@code BATCH_QUEUED}/{@code PROCESSING}/{@code REJECTED}/{@code FAILED} 가 갈렸고,
 * 특히 <b>반려된 작업은 전체 통계에서는 진행 중인데 작업자 통계에서는 어디에도 없었다</b>.
 * 같은 작업자를 두 화면에서 보면 숫자가 달랐다.
 *
 * <p><b>단위</b>: 두 응답 모두 건수(정수)라 환산이 없다 —
 * 비율 필드의 단위 비대칭(작업자 0~1 / 전체 0~100)은 이 지표와 무관하다.
 *
 * <p><b>격리 전략</b>: 공유 Testcontainers PG 를 다른 테스트와 함께 쓰므로 전역 클린업 대신
 * 테스트마다 고유 userNo 를 발급해 그 사용자 몫만 단언한다({@code StatsAutoLabelRateAxisIT} 와 동일).
 */
@SpringBootTest
@ActiveProfiles("local")
class StatsInProgressAxisIT {

    @Autowired private StatsService statsService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private UserRepository userRepository;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager txManager;

    private long userNo;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        userNo = ThreadLocalRandom.current().nextLong(900_000_000L, 999_999_999L);
        tx.executeWithoutResult(s -> userRepository.upsertUser(userNo, null, "진행축작업자"));
    }

    // ---------------------------------------------------------------- fixtures

    /** 영상 1건 + 지정 상태의 상태행 + 이 테스트 사용자의 LABELER 배정. */
    private void assignVideo(String workflowStatus) {
        String clipId = "STAT-IP-" + UUID.randomUUID();
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                clipId, "CCTV-STAT-IP", "EVT-STAT-IP", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30));
        Long rawSn = raw.getRawSn();

        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(workflowStatus);
        dataSttsRepository.save(stts);

        assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, userNo, userNo));
    }

    /** SCR-STAT-001 작업자 통계 — REVIEWER 로 대상 작업자를 지정해 조회한다. */
    private long workerInProgress() {
        TokenClaims actor = new TokenClaims(String.valueOf(userNo), Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(600));
        WorkerStatSummaryResponse summary = statsService.getWorkerSummary(actor, userNo);
        return summary.inProgress();
    }

    /** SCR-STAT-002 전체 구축 현황의 이 사용자 행. 배정이 없으면 행 자체가 없다. */
    private Optional<OverallStatSummaryResponse.WorkerRow> overallRow() {
        return statsService.getOverallSummary().workers().stream()
                .filter(w -> w.userId() == userNo)
                .findFirst();
    }

    private long overallInProgress() {
        return overallRow().orElseThrow().inProgress();
    }

    // ---------------------------------------------------------------- 판정 축

    @Test
    @DisplayName("반려된_작업도_두_통계에서_같은_진행중_수치로_잡힌다")
    void rejectedCountsSameOnBothScreens() {
        // given — 반려 2건 + 검수중 1건. 구 판정(ASSIGNED + IN_REVIEW 열거)이라면
        //          작업자 통계는 반려를 빼고 1 을 내는데 전체 통계는 3 을 낸다.
        assignVideo(LsRawDataStatus.STTS_REJECTED);
        assignVideo(LsRawDataStatus.STTS_REJECTED);
        assignVideo(LsRawDataStatus.STTS_IN_REVIEW);

        // when
        long worker = workerInProgress();
        long overall = overallInProgress();

        // then — 같은 축이면 셋 다 아직 완료되지 않은 작업이다.
        assertThat(worker).isEqualTo(overall);
        assertThat(worker).isEqualTo(3L);
    }

    @Test
    @DisplayName("배치_진행_상태도_두_통계에서_같은_진행중_수치로_잡힌다")
    void batchStatesCountSameOnBothScreens() {
        // given — 열거 방식이 통째로 놓치던 상태들. 작업자 통계는 구 판정에서 0 이었다.
        assignVideo(LsRawDataStatus.STTS_PENDING);
        assignVideo(LsRawDataStatus.STTS_BATCH_QUEUED);
        assignVideo(LsRawDataStatus.STTS_PROCESSING);
        assignVideo(LsRawDataStatus.STTS_FAILED);

        // when / then
        assertThat(workerInProgress()).isEqualTo(overallInProgress());
        assertThat(workerInProgress()).isEqualTo(4L);
    }

    // ---------------------------------------------------------------- 경계

    @Test
    @DisplayName("배정이_한_건도_없으면_작업자_통계의_진행중은_0이다")
    void zeroWhenNoAssignment() {
        // given — 사용자 마스터에만 존재하고 배정은 0건(setUp 의 upsert 만 수행).

        // when / then — 전체 통계는 행 자체를 만들지 않는다(기존 INNER JOIN 동작 유지).
        assertThat(workerInProgress()).isZero();
        assertThat(overallRow()).isEmpty();
    }

    @Test
    @DisplayName("배정이_전량_승인이면_두_통계_모두_진행중_0이다")
    void zeroWhenAllApproved() {
        // given
        assignVideo(LsRawDataStatus.STTS_APPROVED);
        assignVideo(LsRawDataStatus.STTS_APPROVED);

        // when / then
        assertThat(workerInProgress()).isEqualTo(overallInProgress());
        assertThat(workerInProgress()).isZero();
    }

    @Test
    @DisplayName("배정이_전량_반려면_두_통계_모두_배정_수만큼_진행중이다")
    void allRejectedCountsAll() {
        // given — 완료가 아니므로 전부 진행 중이다(반려는 작업자가 다시 손봐야 하는 건이다).
        assignVideo(LsRawDataStatus.STTS_REJECTED);
        assignVideo(LsRawDataStatus.STTS_REJECTED);
        assignVideo(LsRawDataStatus.STTS_REJECTED);

        // when / then
        assertThat(workerInProgress()).isEqualTo(overallInProgress());
        assertThat(workerInProgress()).isEqualTo(3L);
    }

    @Test
    @DisplayName("승인과_반려가_섞이면_승인만_빠지고_나머지가_진행중이다")
    void approvedAndRejectedMixed() {
        // given — 승인 2 / 반려 1 / 배정 1 / 대기 1.
        assignVideo(LsRawDataStatus.STTS_APPROVED);
        assignVideo(LsRawDataStatus.STTS_APPROVED);
        assignVideo(LsRawDataStatus.STTS_REJECTED);
        assignVideo(LsRawDataStatus.STTS_ASSIGNED);
        assignVideo(LsRawDataStatus.STTS_PENDING);

        // when
        long worker = workerInProgress();

        // then — 승인 2건만 빠진다(구 판정이라면 작업자 통계는 배정 1건만 세어 1 이다).
        assertThat(worker).isEqualTo(overallInProgress());
        assertThat(worker).isEqualTo(3L);
    }

    @Test
    @DisplayName("다른_작업자에게_배정된_영상은_내_진행중에_섞이지_않는다")
    void otherWorkersAssignmentsExcluded() {
        // given — 내 배정: 반려 1건 / 남의 배정: 대기 2건.
        assignVideo(LsRawDataStatus.STTS_REJECTED);
        long otherUserNo = userNo - 1L;
        new TransactionTemplate(txManager).executeWithoutResult(
                s -> userRepository.upsertUser(otherUserNo, null, "타작업자"));
        for (int i = 0; i < 2; i++) {
            String clipId = "STAT-IP-OTHER-" + UUID.randomUUID();
            LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                    clipId, "CCTV-STAT-IP", "EVT-STAT-IP", "11680",
                    LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                    LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30));
            dataSttsRepository.save(LsRawDataStatus.initial(raw.getRawSn()));
            assignmentRepository.save(
                    LsTaskAssignment.createLabeler(raw.getRawSn(), otherUserNo, otherUserNo));
        }

        // when / then — 남의 배정 2건이 섞이면 3 이 된다.
        assertThat(workerInProgress()).isEqualTo(overallInProgress());
        assertThat(workerInProgress()).isEqualTo(1L);
    }
}
