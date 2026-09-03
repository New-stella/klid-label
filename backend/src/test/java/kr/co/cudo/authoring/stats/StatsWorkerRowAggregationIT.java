package kr.co.cudo.authoring.stats;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.stats.dto.OverallStatSummaryResponse;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCR-STAT-002 작업자별 현황 행의 <b>실 SQL 집계 의미</b> 회귀 가드.
 *
 * <p>고정하는 판정 기준 두 가지 — 둘 다 코드 실측으로 정한 것이라 바뀌면 여기서 먼저 깨진다:
 * <ul>
 *   <li><b>inProgress</b> = LABELER 로 배정된 {@code LS_RAW_DATA_STATUS} 행 중
 *       {@code DATA_STTS_CD <> 'APPROVED'}. 이 저장소에서 검수 완료 상태값은 APPROVED 하나이며
 *       ({@code STTS_COMPLETED} 로 전이하는 코드는 없다) 나머지 상태(PENDING/ASSIGNED/
 *       BATCH_QUEUED/PROCESSING/IN_REVIEW/REJECTED/FAILED)는 모두 "아직 완료되지 않은" 작업이다.</li>
 *   <li><b>autoLabelRate</b> = 자동 생성 플래그 {@code LS_DATA_LBL_AI_INFO.AUTO_LBL_YN='Y'} 기준
 *       백분율. {@code LsDataLbl.autoLblYn} 은 {@code @Transient} 라 DB 에 없으므로 본체 컬럼으로는
 *       판정할 수 없다.</li>
 * </ul>
 *
 * <p><b>격리 전략</b>: 공유 Testcontainers PG 를 다른 테스트와 함께 쓰므로 전역 클린업을 하지 않고,
 * 테스트마다 <b>고유 userNo</b> 를 발급해 그 사용자의 행 하나만 꺼내 단언한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class StatsWorkerRowAggregationIT {

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

    private TransactionTemplate tx;

    /** 이 테스트 인스턴스 전용 사용자 — 다른 테스트가 남긴 배정과 섞이지 않게 한다. */
    private long userNo;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        // 시드 사용자(1~1000 대)와 겹치지 않는 난수 대역. USER_NO 는 PK 라 충돌하면 upsert 로 흡수된다.
        userNo = ThreadLocalRandom.current().nextLong(900_000_000L, 999_999_999L);
        // upsertUser 는 @Modifying native 문장이라 호출자 트랜잭션이 필요하다.
        tx.executeWithoutResult(s -> userRepository.upsertUser(userNo, "stat-it", "통계작업자"));
    }

    // ---------------------------------------------------------------- fixtures

    /** 영상 1건 + 지정 상태의 상태행 + 이 테스트 사용자의 LABELER 배정. */
    private Long assignVideo(String workflowStatus) {
        String clipId = "STAT-WR-" + UUID.randomUUID();
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                clipId, "CCTV-STAT-WR", "EVT-STAT-WR", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30));
        Long rawSn = raw.getRawSn();

        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(workflowStatus);
        dataSttsRepository.save(stts);

        assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, userNo, userNo));
        return rawSn;
    }

    private Long addFrame(Long rawSn, int frameNo) {
        return srcRepository.save(LsDataSrc.create(
                rawSn, frameNo, "/var/frames/" + rawSn + "/" + frameNo + ".jpg",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0))).getSrcSn();
    }

    /** 자동 라벨 — 본체 + AI_INFO(AUTO_LBL_YN='Y') 페어. 실제 오토라벨링 흐름과 동일. */
    private void addAutoLabel(Long rawSn, Long srcSn) {
        LsDataLbl lbl = lblRepository.save(
                LsDataLbl.createAutoBbox(srcSn, null, "person", "[]", new BigDecimal("0.90"), null));
        // V6 — 생산이력이 라벨 행의 컬럼이라 AI 정보 행 대신 그 라벨에 직접 부여한다.
        lbl.applyAiSource(LsDataLbl.SRC_YOLO, new BigDecimal("0.90"));
        lblRepository.saveAndFlush(lbl);
    }

    /** 수동 라벨 — AI_INFO 를 만들지 않는다(사람이 그린 라벨의 실제 형상). */
    private void addManualLabel(Long srcSn) {
        lblRepository.save(LsDataLbl.createManual(
                srcSn, LsDataLbl.TYPE_BBOX, null, "person", "[]", String.valueOf(userNo)));
    }

    private Optional<OverallStatSummaryResponse.WorkerRow> myRow() {
        return statsService.getOverallSummary().workers().stream()
                .filter(w -> w.userId() == userNo)
                .findFirst();
    }

    // ---------------------------------------------------------------- inProgress

    @Test
    @DisplayName("배정됐지만_승인되지_않은_작업만_진행중으로_집계된다")
    void inProgressCountsAssignedButNotApproved() {
        // given — 승인 2건 + 미승인 4건(대기/배정/검수중/반려)
        assignVideo(LsRawDataStatus.STTS_APPROVED);
        assignVideo(LsRawDataStatus.STTS_APPROVED);
        assignVideo(LsRawDataStatus.STTS_PENDING);
        assignVideo(LsRawDataStatus.STTS_ASSIGNED);
        assignVideo(LsRawDataStatus.STTS_IN_REVIEW);
        assignVideo(LsRawDataStatus.STTS_REJECTED);

        // when
        OverallStatSummaryResponse.WorkerRow row = myRow().orElseThrow();

        // then — 승인 2건은 빠지고 나머지 4건만
        assertThat(row.inProgress()).isEqualTo(4L);
    }

    @Test
    @DisplayName("배정이_모두_승인되면_진행중은_0이다")
    void inProgressIsZeroWhenAllApproved() {
        // given
        assignVideo(LsRawDataStatus.STTS_APPROVED);
        assignVideo(LsRawDataStatus.STTS_APPROVED);

        // when
        OverallStatSummaryResponse.WorkerRow row = myRow().orElseThrow();

        // then
        assertThat(row.inProgress()).isZero();
        assertThat(row.approvalRate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("배치_진행_상태도_아직_완료되지_않은_작업으로_센다")
    void inProgressIncludesBatchStates() {
        // given — 배치 파이프라인 상태들. 새 상태값이 생겨도 어느 쪽에서도 빠지지 않아야 한다.
        assignVideo(LsRawDataStatus.STTS_BATCH_QUEUED);
        assignVideo(LsRawDataStatus.STTS_PROCESSING);
        assignVideo(LsRawDataStatus.STTS_FAILED);

        // when
        OverallStatSummaryResponse.WorkerRow row = myRow().orElseThrow();

        // then
        assertThat(row.inProgress()).isEqualTo(3L);
    }

    // ---------------------------------------------------------------- autoLabelRate

    @Test
    @DisplayName("라벨이_한_건도_없으면_오토라벨비율은_0이다")
    void autoLabelRateIsZeroWhenNoLabels() {
        // given — 배정만 있고 라벨은 없다(분모 0 — 0 으로 나누면 NaN/Infinity 가 응답에 실린다)
        assignVideo(LsRawDataStatus.STTS_ASSIGNED);

        // when
        OverallStatSummaryResponse.WorkerRow row = myRow().orElseThrow();

        // then
        assertThat(row.autoLabelRate()).isEqualTo(0.0);
        assertThat(Double.isFinite(row.autoLabelRate())).isTrue();
    }

    @Test
    @DisplayName("오토라벨비율은_자동생성_플래그_기준_백분율이다")
    void autoLabelRateIsPercentOfAutoFlag() {
        // given — 라벨 4건 중 자동 1건 = 25%
        Long rawSn = assignVideo(LsRawDataStatus.STTS_IN_REVIEW);
        Long srcSn = addFrame(rawSn, 0);
        addAutoLabel(rawSn, srcSn);
        addManualLabel(srcSn);
        addManualLabel(srcSn);
        addManualLabel(srcSn);

        // when
        OverallStatSummaryResponse.WorkerRow row = myRow().orElseThrow();

        // then — 0.25 가 아니라 25.0 (approvalRate 와 같은 백분율 축)
        assertThat(row.autoLabelRate()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("모든_라벨이_자동생성이면_오토라벨비율은_100이다")
    void autoLabelRateIsHundredWhenAllAuto() {
        // given
        Long rawSn = assignVideo(LsRawDataStatus.STTS_IN_REVIEW);
        Long srcSn = addFrame(rawSn, 0);
        addAutoLabel(rawSn, srcSn);
        addAutoLabel(rawSn, srcSn);

        // when
        OverallStatSummaryResponse.WorkerRow row = myRow().orElseThrow();

        // then
        assertThat(row.autoLabelRate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("수동_라벨만_있으면_오토라벨비율은_0이다")
    void autoLabelRateIsZeroWhenAllManual() {
        // given
        Long rawSn = assignVideo(LsRawDataStatus.STTS_IN_REVIEW);
        Long srcSn = addFrame(rawSn, 0);
        addManualLabel(srcSn);
        addManualLabel(srcSn);

        // when
        OverallStatSummaryResponse.WorkerRow row = myRow().orElseThrow();

        // then
        assertThat(row.autoLabelRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("다른_작업자에게_배정된_영상의_라벨은_내_오토라벨비율에_섞이지_않는다")
    void autoLabelRateCountsOnlyOwnAssignments() {
        // given — 내 배정: 수동 1건 / 남의 배정: 자동 3건
        Long mine = assignVideo(LsRawDataStatus.STTS_IN_REVIEW);
        addManualLabel(addFrame(mine, 0));

        String clipId = "STAT-WR-OTHER-" + UUID.randomUUID();
        LsDataRaw otherRaw = videoRepository.save(LsDataRaw.createFromIngest(
                clipId, "CCTV-STAT-WR", "EVT-STAT-WR", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30));
        Long otherSrc = addFrame(otherRaw.getRawSn(), 0);
        addAutoLabel(otherRaw.getRawSn(), otherSrc);
        addAutoLabel(otherRaw.getRawSn(), otherSrc);
        addAutoLabel(otherRaw.getRawSn(), otherSrc);

        // when
        OverallStatSummaryResponse.WorkerRow row = myRow().orElseThrow();

        // then — 남의 자동 라벨 3건이 섞이면 75% 가 된다
        assertThat(row.autoLabelRate()).isEqualTo(0.0);
    }

    // ---------------------------------------------------------------- 경계

    @Test
    @DisplayName("배정이_없는_사용자는_작업자_목록에_나오지_않는다")
    void unassignedUserIsAbsent() {
        // given — 사용자 마스터에만 존재하고 배정은 0건 (setUp 의 upsert 만 수행)

        // when / then — 행이 아예 없다(0 으로 채운 행을 만들지 않는다: 기존 INNER JOIN 동작 유지)
        assertThat(myRow()).isEmpty();
    }

    @Test
    @DisplayName("기존_labeled_reviewed_approvalRate_집계는_변하지_않는다")
    void legacyAggregationsUnchanged() {
        // given — labeled = APPROVED/IN_REVIEW/REJECTED 합(=3), 대기·배정은 제외
        assignVideo(LsRawDataStatus.STTS_APPROVED);
        assignVideo(LsRawDataStatus.STTS_IN_REVIEW);
        assignVideo(LsRawDataStatus.STTS_REJECTED);
        assignVideo(LsRawDataStatus.STTS_PENDING);

        // when
        OverallStatSummaryResponse.WorkerRow row = myRow().orElseThrow();

        // then
        assertThat(row.labeled()).isEqualTo(3L);
        assertThat(row.reviewed()).isZero();          // REVIEWER 배정 없음
        assertThat(row.approvalRate()).isEqualTo(50.0); // 승인 1 / (승인 1 + 반려 1)
        assertThat(row.name()).isEqualTo("통계작업자");
    }
}
