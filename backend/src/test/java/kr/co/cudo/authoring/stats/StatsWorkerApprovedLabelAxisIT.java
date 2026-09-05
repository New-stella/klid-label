package kr.co.cudo.authoring.stats;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
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
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCR-STAT-001 작업자 통계 신규 3필드의 <b>실 SQL 의미</b> 회귀 가드 —
 * {@code assignedTotal} / {@code completionRate} / {@code approvedLabelCount}. @design API-056, SCREEN-020
 *
 * <p>Mockito 단위 테스트({@code StatsServiceTest})는 조립·단위·경계만 검증한다. 이 IT 가 고정하는 것은
 * 그것으로 잡히지 않는 두 가지다.
 *
 * <h3>① {@code approvedLabelCount} 의 INNER JOIN 게이트 (Critical)</h3>
 * {@code LS_RAW_DATA_STATUS} 행은 배정 시점에 lazy 생성되어 {@code LS_DATA_RAW} 전건과 <b>1:1 이
 * 아니다</b>. 따라서 이 집계는 <b>INNER JOIN + DATA_STTS_CD='APPROVED'</b> 여야 하며, 상태 행이 없거나
 * 승인되지 않은 영상의 라벨은 <b>제외되는 것이 요구되는 동작</b>이다 — LEFT JOIN 으로 바꾸거나 상태
 * 조건을 빼면 학습데이터로 확정되지 않은 분량이 계상되고 이 테스트가 먼저 깨진다.
 *
 * <h3>② {@code assignedTotal} 이 반려를 이중 계상하지 않는다</h3>
 * 배정 총계는 {@code completed + inProgress} 이고 반려는 이미 {@code inProgress}(= APPROVED 아님) 안에
 * 있다. 여기에 {@code rejected} 를 또 더하면 반려 건이 두 번 세어진다.
 *
 * <p><b>격리 전략</b>: 공유 Testcontainers PG 를 다른 테스트와 함께 쓰므로 전역 클린업 대신 테스트마다
 * 고유 userNo 를 발급해 그 사용자 몫만 단언한다({@code StatsInProgressAxisIT} 와 동일).
 *
 * <p><b>픽스처 값은 서로 다르게 둔다</b>: 이 응답에는 {@code long} 필드가 여럿이라 두 값을 뒤바꿔
 * 실어도 타입이 같아 어디서도 걸리지 않는다. 값이 겹치면 그 오류가 통과한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class StatsWorkerApprovedLabelAxisIT {

    private static final String APPROVED = LsRawDataStatus.STTS_APPROVED;

    @Autowired private StatsService statsService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private UserRepository userRepository;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager txManager;

    private long userNo;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        userNo = ThreadLocalRandom.current().nextLong(900_000_000L, 999_999_999L);
        tx.executeWithoutResult(s -> userRepository.upsertUser(userNo, null, "승인라벨축작업자"));
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * 영상 1건 + 프레임 1건 + 그 프레임의 라벨 N 건을 심는다.
     *
     * @param workflowStatus 상태 코드. <b>null 이면 {@code LS_RAW_DATA_STATUS} 행을 만들지 않는다</b>
     *                       (= 배정 전 영상 — INNER JOIN 게이트가 걸러야 하는 대상).
     * @param assign         이 테스트 사용자의 LABELER 배정을 만들지 여부
     * @param labelCount     그 영상 프레임에 달 라벨 수
     */
    private void seed(String workflowStatus, boolean assign, int labelCount) {
        String clipId = "STAT-AL-" + UUID.randomUUID();
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                clipId, "CCTV-STAT-AL", "EVT-STAT-AL", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30));
        Long rawSn = raw.getRawSn();

        LsDataSrc src = srcRepository.save(LsDataSrc.create(
                rawSn, 1, "/var/frames/" + rawSn + "/1.jpg",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0)));

        for (int i = 0; i < labelCount; i++) {
            // 수동 라벨(autoLblYn=null) — 이 IT 의 관심사는 검수 게이트이지 자동 여부가 아니다.
            lblRepository.save(LsDataLbl.createManual(
                    src.getSrcSn(), LsDataLbl.TYPE_BBOX, null, "person",
                    "[[10,10],[20,20]]", String.valueOf(userNo)));
        }

        if (workflowStatus != null) {
            LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
            stts.transitionTo(workflowStatus);
            dataSttsRepository.save(stts);
        }
        if (assign) {
            assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, userNo, userNo));
        }
    }

    /**
     * 승인·배정된 영상 1건 안에 <b>정상 프레임과 폐기 프레임을 함께</b> 심는다 (R4 축).
     *
     * <p>폐기는 엔티티의 유일한 쓰기 통로인 {@link LsDataSrc#discard()} 로 세운다 — 테스트가
     * {@code DSCD_YN} 에 직접 값을 넣으면 프로덕션이 쓰지 않는 상태를 만들어낼 수 있다.
     *
     * @param keptLabels      폐기하지 않은 프레임에 달 라벨 수(= 학습데이터로 나가는 분량)
     * @param discardedLabels 폐기한 프레임에 달 라벨 수(= 산출·데이터마트에서 빠지는 분량)
     */
    private void seedApprovedWithDiscardedFrame(int keptLabels, int discardedLabels) {
        String clipId = "STAT-AL-DSCD-" + UUID.randomUUID();
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                clipId, "CCTV-STAT-AL", "EVT-STAT-AL", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30));
        Long rawSn = raw.getRawSn();

        LsDataSrc kept = srcRepository.save(LsDataSrc.create(
                rawSn, 1, "/var/frames/" + rawSn + "/1.jpg",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0)));
        LsDataSrc discarded = srcRepository.save(LsDataSrc.create(
                rawSn, 2, "/var/frames/" + rawSn + "/2.jpg",
                LocalDateTime.of(2026, 5, 1, 9, 0, 1)));
        discarded.discard();
        srcRepository.save(discarded);

        seedLabels(kept.getSrcSn(), keptLabels);
        seedLabels(discarded.getSrcSn(), discardedLabels);

        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(APPROVED);
        dataSttsRepository.save(stts);
        assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, userNo, userNo));
    }

    private void seedLabels(Long srcSn, int count) {
        for (int i = 0; i < count; i++) {
            lblRepository.save(LsDataLbl.createManual(
                    srcSn, LsDataLbl.TYPE_BBOX, null, "person",
                    "[[10,10],[20,20]]", String.valueOf(userNo)));
        }
    }

    /** REVIEWER 로 대상 작업자를 지정해 조회한다(WORKER 는 본인만 볼 수 있어 픽스처 사용자로 위장하지 않는다). */
    private WorkerStatSummaryResponse summary() {
        TokenClaims actor = new TokenClaims(String.valueOf(userNo), Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(600));
        return statsService.getWorkerSummary(actor, userNo);
    }

    // ---------------------------------------------------------------- ① 검수 게이트

    @Test
    @DisplayName("검수완료_라벨수는_승인된_영상의_라벨만_센다")
    void approvedLabelCountCountsOnlyApprovedVideos() {
        // given — 배정 3건: 승인 1건(라벨 4) + 반려 1건(라벨 7) + 검수중 1건(라벨 2).
        //   전체 라벨 13 중 학습데이터로 확정된 것은 승인 영상의 4건뿐이다.
        seed(APPROVED, true, 4);
        seed(LsRawDataStatus.STTS_REJECTED, true, 7);
        seed(LsRawDataStatus.STTS_IN_REVIEW, true, 2);

        // when
        WorkerStatSummaryResponse worker = summary();

        // then
        assertThat(worker.labelCount()).isEqualTo(13L);
        assertThat(worker.approvedLabelCount()).isEqualTo(4L);
        assertThat(worker.approvedLabelCount()).isLessThanOrEqualTo(worker.labelCount());
    }

    @Test
    @DisplayName("검수상태_행이_없는_영상의_라벨은_검수완료_라벨수에서_제외된다")
    void labelsOfVideosWithoutStatusRowAreExcluded() {
        // given — 배정은 있으나 LS_RAW_DATA_STATUS 행이 없는 영상(라벨 5건)만.
        //   ★INNER JOIN 이 아니면(LEFT JOIN) 여기서 approvedLabelCount 가 부풀어 오른다.
        seed(null, true, 5);

        // when
        WorkerStatSummaryResponse worker = summary();

        // then — 전체 라벨은 세지만 검수완료 라벨은 전혀 세지 않는다.
        assertThat(worker.labelCount()).isEqualTo(5L);
        assertThat(worker.approvedLabelCount()).isZero();
    }

    @Test
    @DisplayName("배정되지_않은_승인영상의_라벨은_검수완료_라벨수에서_제외된다")
    void labelsOfUnassignedApprovedVideosAreExcluded() {
        // given — 승인됐지만 이 작업자에게 배정되지 않은 영상(라벨 9건) + 배정된 승인 영상(라벨 3건).
        //   작업자 통계는 그 작업자 몫만 세야 한다.
        seed(APPROVED, false, 9);
        seed(APPROVED, true, 3);

        // when
        WorkerStatSummaryResponse worker = summary();

        // then — 배정분 3건만. 9 가 섞이면 LABELER 배정 서브쿼리가 빠진 것이다.
        assertThat(worker.labelCount()).isEqualTo(3L);
        assertThat(worker.approvedLabelCount()).isEqualTo(3L);
    }

    // ------------------------------------------------------- ①-b 폐기 프레임 제외 (R4)

    @Test
    @DisplayName("폐기된_프레임의_라벨은_검수완료_라벨수에서_빠지고_전체_라벨수에는_남는다")
    void discardedFrameLabelsAreExcludedFromApprovedCountButKeptInTotal() {
        // given — 승인·배정된 영상 1건에 정상 프레임(라벨 3) + 폐기 프레임(라벨 5).
        //   학습데이터 산출(DatasetExportTxService)과 데이터마트 뷰(v_completed_frame)는 폐기 프레임을
        //   구조적으로 제외하므로, "학습데이터로 확정된 분량"인 approvedLabelCount 도 같은 기준이어야
        //   한다. 반면 labelCount 는 "배정된 전체 분량"이라는 <다른 축>이라 폐기분을 계속 센다.
        seedApprovedWithDiscardedFrame(3, 5);

        // when
        WorkerStatSummaryResponse worker = summary();

        // then — 두 축을 한 자리에서 대조한다(축을 뒤바꿔 구현하면 여기서 걸린다).
        assertThat(worker.labelCount())
                .as("전체 라벨 수는 배정 전체 분량 축이라 폐기분도 센다")
                .isEqualTo(8L);
        assertThat(worker.approvedLabelCount())
                .as("검수완료 라벨 수는 산출 분량 축이라 폐기 프레임 라벨 5건을 제외한다")
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("승인영상의_모든_프레임이_폐기되면_검수완료_라벨수가_0이다")
    void allFramesDiscardedYieldsZeroApprovedLabels() {
        // given — 승인·배정 영상이지만 라벨이 폐기 프레임에만 달려 있다(정상 프레임 라벨 0).
        seedApprovedWithDiscardedFrame(0, 6);

        // when
        WorkerStatSummaryResponse worker = summary();

        // then — 산출물에 나가는 라벨이 하나도 없으므로 0. 전체 축은 6 을 유지한다.
        assertThat(worker.labelCount()).isEqualTo(6L);
        assertThat(worker.approvedLabelCount()).isZero();
    }

    @Test
    @DisplayName("라벨이_없으면_두_라벨수_모두_0이고_예외가_없다")
    void zeroLabelsYieldZeroCounts() {
        // given — 배정·승인은 있으나 라벨 0건
        seed(APPROVED, true, 0);

        // when
        WorkerStatSummaryResponse worker = summary();

        // then
        assertThat(worker.labelCount()).isZero();
        assertThat(worker.approvedLabelCount()).isZero();
        assertThat(worker.autoLabelRate()).isEqualTo(0.0);
    }

    // ---------------------------------------------------------------- ② 배정 총계·완료율

    @Test
    @DisplayName("배정총계는_반려를_이중계상하지_않는다")
    void assignedTotalDoesNotDoubleCountRejected() {
        // given — 승인 2 + 반려 3 + 대기 1 = 배정 6건.
        //   반려는 이미 진행중(APPROVED 아님)에 들어 있으므로 rejected 를 또 더하면 9 가 된다.
        seed(APPROVED, true, 1);
        seed(APPROVED, true, 1);
        seed(LsRawDataStatus.STTS_REJECTED, true, 1);
        seed(LsRawDataStatus.STTS_REJECTED, true, 1);
        seed(LsRawDataStatus.STTS_REJECTED, true, 1);
        seed(LsRawDataStatus.STTS_PENDING, true, 1);

        // when
        WorkerStatSummaryResponse worker = summary();

        // then
        assertThat(worker.completed()).isEqualTo(2L);
        assertThat(worker.rejected()).isEqualTo(3L);
        assertThat(worker.inProgress()).isEqualTo(4L);          // 반려 3 + 대기 1
        assertThat(worker.assignedTotal()).isEqualTo(6L);       // 2 + 4
        assertThat(worker.assignedTotal())
                .as("배정 총계는 완료 + 진행중이며 반려를 따로 더하지 않는다")
                .isEqualTo(worker.completed() + worker.inProgress());
    }

    @Test
    @DisplayName("완료율은_0에서1사이_비율이며_백분율이_아니다")
    void completionRateIsRatioNotPercent() {
        // given — 승인 1 + 반려 3 = 배정 4건 → 0.25
        seed(APPROVED, true, 1);
        seed(LsRawDataStatus.STTS_REJECTED, true, 1);
        seed(LsRawDataStatus.STTS_REJECTED, true, 1);
        seed(LsRawDataStatus.STTS_REJECTED, true, 1);

        // when
        WorkerStatSummaryResponse worker = summary();

        // then — 25.0 이 나오면 전체 구축 현황(0~100)의 단위가 흘러들어온 것이다.
        assertThat(worker.assignedTotal()).isEqualTo(4L);
        assertThat(worker.completionRate()).isEqualTo(0.25);
        assertThat(worker.completionRate()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("배정이_없으면_완료율이_0이고_NaN이_아니다")
    void completionRateIsZeroWithoutAssignment() {
        // given — 이 사용자에게 배정된 영상이 하나도 없다(분모 0).
        //   ※ 픽스처를 심지 않는다 — setUp 의 신규 userNo 는 배정 0건이다.

        // when
        WorkerStatSummaryResponse worker = summary();

        // then
        assertThat(worker.assignedTotal()).isZero();
        assertThat(worker.completionRate()).isEqualTo(0.0);
        assertThat(Double.isNaN(worker.completionRate())).isFalse();
        assertThat(worker.approvedLabelCount()).isZero();
    }
}
