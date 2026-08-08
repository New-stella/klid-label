package kr.co.cudo.authoring.stats;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code autoLabelRate} 판정 축 회귀 가드 — 전체 통계(SCR-STAT-002)와 작업자 통계(SCR-STAT-001)가
 * <b>같은 축</b>으로 자동 생성 라벨을 판정하는지 고정한다.
 *
 * <p><b>축</b>: {@code LS_DATA_LBL_AI_INFO.AUTO_LBL_YN='Y'} 존재 여부(단일 진실원). 등록자
 * ({@code LS_DATA_LBL.REG_USER_NO}) 의 null 여부는 판정에 쓰지 않는다 —
 * 등록자를 남기지 않고 만들어지는 라벨이 자동 생성 외에도 있어서 오분류하기 때문이다
 * (예: 버전 롤백 복원 {@code LsDataLbl.createRestored} 는 등록자를 채우지 않는다).
 *
 * <p><b>단위</b>: 작업자 통계는 <b>0~1 비율</b>, 전체 통계는 <b>0~100 백분율</b>이다. 이 비대칭은
 * 외부 FE 계약이라 <b>의도적으로 유지</b>한다 — 여기서는 환산 후 두 값이 같은지만 확인한다.
 *
 * <p><b>격리 전략</b>: 공유 Testcontainers PG 를 다른 테스트와 함께 쓰므로 전역 클린업 대신
 * 테스트마다 고유 userNo 를 발급해 그 사용자 몫만 단언한다({@code StatsWorkerRowAggregationIT} 와 동일).
 */
@SpringBootTest
@ActiveProfiles("local")
class StatsAutoLabelRateAxisIT {

    @Autowired private StatsService statsService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDataLblAiInfoRepository aiInfoRepository;
    @Autowired private UserRepository userRepository;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager txManager;

    private long userNo;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        userNo = ThreadLocalRandom.current().nextLong(900_000_000L, 999_999_999L);
        tx.executeWithoutResult(s -> userRepository.upsertUser(userNo, "stat-axis-it", "판정축작업자"));
    }

    // ---------------------------------------------------------------- fixtures

    /** 영상 1건 + IN_REVIEW 상태행 + 이 테스트 사용자의 LABELER 배정. */
    private Long assignVideo() {
        String clipId = "STAT-AX-" + UUID.randomUUID();
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                clipId, "CCTV-STAT-AX", "EVT-STAT-AX", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30));
        Long rawSn = raw.getRawSn();

        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(LsRawDataStatus.STTS_IN_REVIEW);
        dataSttsRepository.save(stts);

        assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, userNo, userNo));
        return rawSn;
    }

    private Long addFrame(Long rawSn) {
        return srcRepository.save(LsDataSrc.create(
                rawSn, 0, "/var/frames/" + rawSn + "/0.jpg",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0))).getSrcSn();
    }

    /** 자동 라벨 — 본체 + AI 정보(AUTO_LBL_YN='Y'). 오토라벨링 배치·온라인 경로의 실제 형상. */
    private void addAutoLabel(Long rawSn, Long srcSn) {
        LsDataLbl lbl = lblRepository.save(
                LsDataLbl.createAutoBbox(srcSn, null, "person", "[]", new BigDecimal("0.90"), null));
        aiInfoRepository.save(LsDataLblAiInfo.create(
                lbl.getLblSn(), rawSn, srcSn, LsDataLblAiInfo.SRC_YOLO, new BigDecimal("0.90"), "stat-axis-it"));
    }

    /** 수동 라벨 — 등록자가 있고 AI 정보가 없다(사람이 그린 라벨의 실제 형상). */
    private void addManualLabel(Long srcSn) {
        lblRepository.save(LsDataLbl.createManual(
                srcSn, LsDataLbl.TYPE_BBOX, null, "person", "[]", userNo));
    }

    /**
     * 등록자가 없는데 자동 생성도 아닌 라벨 — 버전 롤백 복원 경로의 실제 형상.
     * {@code createRestored} 는 REG_USER_NO 를 채우지 않으므로 등록자 프록시로는 "자동"으로 오분류된다.
     *
     * @param withAiInfoNo AI 정보 행을 AUTO_LBL_YN='N' 로 함께 남길지 (스냅샷에 출처가 있던 경우)
     */
    private void addRestoredManualLabel(Long rawSn, Long srcSn, boolean withAiInfoNo) {
        LsDataLbl lbl = lblRepository.save(LsDataLbl.createRestored(
                srcSn, LsDataLbl.TYPE_BBOX, null, "person", "[]",
                LsDataLbl.AUTO_NO, null, null, null));
        if (withAiInfoNo) {
            aiInfoRepository.save(LsDataLblAiInfo.createRestored(
                    lbl.getLblSn(), rawSn, srcSn, LsDataLblAiInfo.SRC_YOLO, null,
                    LsDataLbl.AUTO_NO, "stat-axis-it"));
        }
    }

    /** 등록자가 있는데 자동 생성 플래그도 있는 라벨 — 플래그가 이겨야 한다(판정 축 단언용). */
    private void addRegisteredAutoLabel(Long rawSn, Long srcSn) {
        LsDataLbl lbl = lblRepository.save(LsDataLbl.createManual(
                srcSn, LsDataLbl.TYPE_BBOX, null, "person", "[]", userNo));
        aiInfoRepository.save(LsDataLblAiInfo.create(
                lbl.getLblSn(), rawSn, srcSn, LsDataLblAiInfo.SRC_YOLO, new BigDecimal("0.80"), "stat-axis-it"));
    }

    private WorkerStatSummaryResponse workerSummary() {
        TokenClaims actor = new TokenClaims(String.valueOf(userNo), Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(600));
        return statsService.getWorkerSummary(actor, userNo);
    }

    private OverallStatSummaryResponse.WorkerRow overallRow() {
        return statsService.getOverallSummary().workers().stream()
                .filter(w -> w.userId() == userNo)
                .findFirst()
                .orElseThrow();
    }

    // ---------------------------------------------------------------- 판정 축

    @Test
    @DisplayName("등록자가_없어도_자동생성_플래그가_없으면_수동으로_센다")
    void registrantNullWithoutFlagCountsAsManual() {
        // given — 라벨 3건 중 자동 플래그는 1건. 나머지 2건은 등록자가 없지만 자동이 아니다.
        Long rawSn = assignVideo();
        Long srcSn = addFrame(rawSn);
        addAutoLabel(rawSn, srcSn);
        addRestoredManualLabel(rawSn, srcSn, false);  // AI 정보 자체가 없음
        addRestoredManualLabel(rawSn, srcSn, true);   // AI 정보는 있으나 AUTO_LBL_YN='N'

        // when
        WorkerStatSummaryResponse summary = workerSummary();

        // then — 등록자 프록시라면 3건 모두 자동으로 세어 1.0 이 된다.
        assertThat(summary.labelCount()).isEqualTo(3L);
        assertThat(summary.autoLabelRate()).isEqualTo(1.0 / 3.0);
    }

    @Test
    @DisplayName("등록자가_있어도_자동생성_플래그가_있으면_자동으로_센다")
    void registrantPresentWithFlagCountsAsAuto() {
        // given — 라벨 2건 모두 등록자가 있고, 그중 1건만 자동 생성 플래그를 가진다.
        Long rawSn = assignVideo();
        Long srcSn = addFrame(rawSn);
        addRegisteredAutoLabel(rawSn, srcSn);
        addManualLabel(srcSn);

        // when
        WorkerStatSummaryResponse summary = workerSummary();

        // then — 등록자 프록시라면 둘 다 수동으로 세어 0.0 이 된다.
        assertThat(summary.autoLabelRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("한_라벨에_AI정보가_여러_행이어도_자동_1건으로_센다")
    void duplicateAiInfoRowsCountLabelOnce() {
        // given — LS_DATA_LBL_AI_INFO.DATA_LBL_SN 에 UNIQUE 가 없어 한 라벨에 여러 행이 존재할 수 있다.
        //          조인으로 세면 분자·분모가 함께 부풀어 비율이 틀어진다.
        Long rawSn = assignVideo();
        Long srcSn = addFrame(rawSn);
        LsDataLbl lbl = lblRepository.save(
                LsDataLbl.createAutoBbox(srcSn, null, "person", "[]", new BigDecimal("0.90"), null));
        aiInfoRepository.save(LsDataLblAiInfo.create(
                lbl.getLblSn(), rawSn, srcSn, LsDataLblAiInfo.SRC_YOLO, new BigDecimal("0.90"), "stat-axis-it"));
        aiInfoRepository.save(LsDataLblAiInfo.create(
                lbl.getLblSn(), rawSn, srcSn, LsDataLblAiInfo.SRC_SAM2, new BigDecimal("0.95"), "stat-axis-it"));
        addManualLabel(srcSn);

        // when
        WorkerStatSummaryResponse summary = workerSummary();

        // then — 라벨은 2건, 그중 자동 1건.
        assertThat(summary.labelCount()).isEqualTo(2L);
        assertThat(summary.autoLabelRate()).isEqualTo(0.5);
    }

    // ---------------------------------------------------------------- 경계

    @Test
    @DisplayName("라벨이_한_건도_없으면_오토라벨비율은_0이다")
    void rateIsZeroWhenNoLabels() {
        // given — 배정만 있고 라벨은 없다(분모 0 — 나누면 NaN 이 JSON 에 실린다).
        assignVideo();

        // when
        WorkerStatSummaryResponse summary = workerSummary();

        // then
        assertThat(summary.labelCount()).isZero();
        assertThat(summary.autoLabelRate()).isEqualTo(0.0);
        assertThat(Double.isFinite(summary.autoLabelRate())).isTrue();
    }

    @Test
    @DisplayName("모든_라벨이_자동생성이면_오토라벨비율은_1이다")
    void rateIsOneWhenAllAuto() {
        // given
        Long rawSn = assignVideo();
        Long srcSn = addFrame(rawSn);
        addAutoLabel(rawSn, srcSn);
        addAutoLabel(rawSn, srcSn);

        // when
        WorkerStatSummaryResponse summary = workerSummary();

        // then — 백분율(100.0)이 아니라 비율(1.0)이다.
        assertThat(summary.autoLabelRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("수동_라벨만_있으면_오토라벨비율은_0이다")
    void rateIsZeroWhenAllManual() {
        // given
        Long rawSn = assignVideo();
        Long srcSn = addFrame(rawSn);
        addManualLabel(srcSn);
        addManualLabel(srcSn);

        // when
        WorkerStatSummaryResponse summary = workerSummary();

        // then
        assertThat(summary.autoLabelRate()).isEqualTo(0.0);
    }

    // ---------------------------------------------------------------- 단위·정합

    @Test
    @DisplayName("작업자_통계의_오토라벨비율은_0에서_1_사이_비율을_유지한다")
    void workerRateStaysInZeroToOne() {
        // given — 자동 3 / 수동 1.
        Long rawSn = assignVideo();
        Long srcSn = addFrame(rawSn);
        addAutoLabel(rawSn, srcSn);
        addAutoLabel(rawSn, srcSn);
        addAutoLabel(rawSn, srcSn);
        addManualLabel(srcSn);

        // when
        WorkerStatSummaryResponse summary = workerSummary();

        // then — 0~100 백분율로 바뀌면(=75.0) 외부 FE 계약이 깨진다. 단위는 이 작업의 변경 대상이 아니다.
        assertThat(summary.autoLabelRate()).isEqualTo(0.75);
        assertThat(summary.autoLabelRate()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("전체_통계와_작업자_통계의_오토라벨비율은_단위_환산_후_같다")
    void overallAndWorkerRatesAgree() {
        // given — 두 축의 답이 갈리는 데이터. 플래그 기준 자동은 1/3 이지만
        //          등록자 프록시 기준으로는 2/3 (복원 라벨이 등록자를 안 남긴다).
        Long rawSn = assignVideo();
        Long srcSn = addFrame(rawSn);
        addAutoLabel(rawSn, srcSn);
        addRestoredManualLabel(rawSn, srcSn, false);
        addManualLabel(srcSn);

        // when
        double workerRate = workerSummary().autoLabelRate();
        double overallRate = overallRow().autoLabelRate();

        // then — 같은 축이면 자동 1 / 전체 3.
        assertThat(workerRate).isCloseTo(1.0 / 3.0, within(1e-9));
        assertThat(overallRate).isCloseTo(100.0 / 3.0, within(1e-9));
        assertThat(overallRate).isCloseTo(workerRate * 100.0, within(1e-9));
    }
}
