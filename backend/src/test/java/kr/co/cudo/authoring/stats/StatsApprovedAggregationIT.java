package kr.co.cudo.authoring.stats;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.CountRow;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검수완료(APPROVED) 한정 통계 집계의 <b>실 SQL 의미</b> 회귀 가드.
 *
 * <p>Mockito 단위 테스트({@code StatsServiceTest})는 조립만 검증할 뿐 JPQL 이 실제로 무엇을
 * 세는지는 못 잡는다. 이 IT 가 고정하는 핵심 정책은 하나다 —
 * <b>{@code LS_RAW_DATA_STATUS} 는 배정 시점에 lazy 생성되므로 {@code LS_DATA_RAW} 전건과 1:1 이
 * 아니다.</b> 따라서 approved 집계는 반드시 <b>INNER JOIN + DATA_STTS_CD='APPROVED'</b> 여야 하고,
 * 상태 행이 없는 영상은 <b>제외되는 것이 요구되는 동작</b>이다. LEFT JOIN 으로 바꾸거나 상태 조건을
 * 빼면 이 테스트가 먼저 깨진다.
 *
 * <p><b>격리 전략</b>: 공유 Testcontainers PG 를 다른 테스트와 함께 쓰므로 전역 클린업 대신
 * ①고유 이벤트 코드(테스트 실행마다 UUID 파생)로 분포 집계를 완전 격리하고,
 * ②전역 카운트는 seed 전/후 <b>델타</b>로만 단언한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class StatsApprovedAggregationIT {

    private static final String APPROVED = LsRawDataStatus.STTS_APPROVED;

    @Autowired private StatsQueryRepository statsQueryRepository;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private LsDataSrcRepository lsDataSrcRepository;

    /** 이 테스트 인스턴스 전용 이벤트 코드 — 타 테스트 데이터와 분포 집계가 섞이지 않게 한다. */
    private String eventCode;

    private long baseApprovedFrames;
    private long baseTotalFrames;
    private long baseApprovedVideos;

    @BeforeEach
    void setUp() {
        // EVNT_TYPE_CD 는 VARCHAR(20) — 접두 4 + UUID 앞 12 = 16자.
        eventCode = "EVI-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        baseApprovedFrames = statsQueryRepository.countFramesByDataSttsCd(APPROVED);
        baseTotalFrames = statsQueryRepository.countCumulativeFrames();
        baseApprovedVideos = approvedVideoCount();
    }

    // ---------------------------------------------------------------- fixtures

    /** 영상 1건 + 프레임 N 건. workflowStatus 가 null 이면 LS_RAW_DATA_STATUS 행을 만들지 않는다. */
    private Long seed(String workflowStatus, int frameCount) {
        String clipId = "STATS-IT-" + UUID.randomUUID();
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                clipId, "CCTV-STATS-IT", eventCode, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30));
        Long rawSn = raw.getRawSn();

        for (int i = 1; i <= frameCount; i++) {
            lsDataSrcRepository.save(LsDataSrc.create(
                    rawSn, i, "/var/frames/" + rawSn + "/" + i + ".jpg",
                    LocalDateTime.of(2026, 5, 1, 9, 0, 0)));
        }

        if (workflowStatus != null) {
            LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
            stts.transitionTo(workflowStatus);
            dataSttsRepository.save(stts);
        }
        return rawSn;
    }

    /** 승인 영상 건수 — 신규 쿼리를 추가하지 않고 기존 상태별 카운트에서 얻는다(서비스와 동일 원천). */
    private long approvedVideoCount() {
        return statsQueryRepository.countByDataSttsCd().stream()
                .filter(r -> APPROVED.equals(r.getCode()))
                .mapToLong(CountRow::getCnt)
                .sum();
    }

    private long myEventCount(List<CountRow> rows) {
        return rows.stream()
                .filter(r -> eventCode.equals(r.getCode()))
                .mapToLong(CountRow::getCnt)
                .sum();
    }

    /**
     * 승인 1 + 미승인 4(반려/검수중/대기/배정) + 상태행 없음 1 을 심는다.
     * 프레임: 승인 2, 미승인 3+1+1+1=6, 상태행 없음 4 → 전체 12, 승인분만 2.
     */
    private void seedMixed() {
        seed(APPROVED, 2);
        seed(LsRawDataStatus.STTS_REJECTED, 3);
        seed(LsRawDataStatus.STTS_IN_REVIEW, 1);
        seed(LsRawDataStatus.STTS_PENDING, 1);
        seed(LsRawDataStatus.STTS_ASSIGNED, 1);
        seed(null, 4);   // 배정 전 — LS_RAW_DATA_STATUS 행 자체가 없음
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("승인된_영상만_approvedVideoCount에_집계된다")
    void onlyApprovedVideosCounted() {
        // given — 영상 6건 중 APPROVED 는 1건뿐
        seedMixed();

        // when
        long approvedDelta = approvedVideoCount() - baseApprovedVideos;
        long approvedByEvent = myEventCount(statsQueryRepository.countVideoByEventTypeAndStatus(APPROVED));

        // then
        assertThat(approvedDelta).isEqualTo(1L);
        assertThat(approvedByEvent).isEqualTo(1L);
    }

    @Test
    @DisplayName("승인되지_않은_영상의_프레임은_approvedImageCount에서_제외된다")
    void framesOfUnapprovedVideosExcluded() {
        // given — 전체 프레임 12, 그중 승인 영상 프레임은 2
        seedMixed();

        // when
        long approvedFrameDelta = statsQueryRepository.countFramesByDataSttsCd(APPROVED) - baseApprovedFrames;
        long totalFrameDelta = statsQueryRepository.countCumulativeFrames() - baseTotalFrames;
        long approvedFrameByEvent = myEventCount(statsQueryRepository.countFrameByEventTypeAndStatus(APPROVED));

        // then — 반려/검수중/대기/배정 영상의 프레임 6건은 제외
        assertThat(totalFrameDelta).isEqualTo(12L);
        assertThat(approvedFrameDelta).isEqualTo(2L);
        assertThat(approvedFrameByEvent).isEqualTo(2L);
    }

    @Test
    @DisplayName("검수상태_행이_없는_영상은_approved집계에서_제외된다")
    void videosWithoutStatusRowAreExcluded() {
        // given — LS_RAW_DATA_STATUS 행이 없는 영상만 5건(프레임 7건).
        //         INNER JOIN 이 아니면(LEFT JOIN) 여기서 approved 집계가 부풀어 오른다.
        seed(null, 7);
        seed(null, 0);
        seed(null, 0);
        seed(null, 0);
        seed(null, 0);

        // when
        long approvedFrameDelta = statsQueryRepository.countFramesByDataSttsCd(APPROVED) - baseApprovedFrames;
        long totalFrameDelta = statsQueryRepository.countCumulativeFrames() - baseTotalFrames;

        // then — 전체 프레임은 늘지만 approved 는 전혀 늘지 않는다
        assertThat(totalFrameDelta).isEqualTo(7L);
        assertThat(approvedFrameDelta).isZero();
        assertThat(approvedVideoCount() - baseApprovedVideos).isZero();
        assertThat(myEventCount(statsQueryRepository.countVideoByEventTypeAndStatus(APPROVED))).isZero();
        assertThat(myEventCount(statsQueryRepository.countFrameByEventTypeAndStatus(APPROVED))).isZero();
    }

    @Test
    @DisplayName("approved카운트는_항상_전체카운트_이하다")
    void approvedNeverExceedsTotal() {
        // given
        seedMixed();

        // when
        long approvedFrames = statsQueryRepository.countFramesByDataSttsCd(APPROVED);
        long totalFrames = statsQueryRepository.countCumulativeFrames();

        // then
        assertThat(approvedFrames).isLessThanOrEqualTo(totalFrames);
        assertThat(approvedVideoCount()).isLessThanOrEqualTo(videoRepository.count());
    }

    @Test
    @DisplayName("승인영상이_0건이면_approved카운트가_0이고_분포는_전항목_0이다")
    void zeroApprovedYieldsZeroCounts() {
        // given — 승인 없이 미승인 영상만 적재
        seed(LsRawDataStatus.STTS_PENDING, 3);
        seed(LsRawDataStatus.STTS_REJECTED, 2);

        // when / then — 예외 없이 0. (분포 리스트가 "전 항목 0" 으로 채워지는지는
        //               서비스 레이어 책임이라 StatsServiceTest 가 검증한다.)
        assertThat(statsQueryRepository.countFramesByDataSttsCd(APPROVED) - baseApprovedFrames).isZero();
        assertThat(myEventCount(statsQueryRepository.countVideoByEventTypeAndStatus(APPROVED))).isZero();
        assertThat(myEventCount(statsQueryRepository.countFrameByEventTypeAndStatus(APPROVED))).isZero();
    }

    @Test
    @DisplayName("승인영상_이벤트분포_합계가_approvedVideoCount_이하다")
    void approvedEventDistributionSumWithinApprovedVideoCount() {
        // given — 승인 3건(모두 같은 이벤트 코드) + 미승인 2건
        seed(APPROVED, 1);
        seed(APPROVED, 1);
        seed(APPROVED, 1);
        seed(LsRawDataStatus.STTS_PENDING, 1);

        // when
        long distributionSum = statsQueryRepository.countVideoByEventTypeAndStatus(APPROVED).stream()
                .mapToLong(CountRow::getCnt).sum();

        // then
        assertThat(myEventCount(statsQueryRepository.countVideoByEventTypeAndStatus(APPROVED))).isEqualTo(3L);
        assertThat(distributionSum).isLessThanOrEqualTo(approvedVideoCount());
    }

    /**
     * SCR-STAT-002 일별 작업량 — <b>전체 기준</b>이라 배정(LS_TASK_ALTMNT) 조인이 없다.
     *
     * <p>작업자 통계용 {@code findDailyCompletionForWorker} 는 LABELER 배정을 조인하므로
     * 배정 이력 없이 승인된 영상은 세지 않는다. 전체 기준 쿼리는 그 영상도 포함해야 하며,
     * 이것이 카드 수치({@code approvedVideoCount} = APPROVED 상태 행 수)와 차트 합계를
     * 같은 원천으로 묶는 조건이다. 배정 조인을 되살리면 이 테스트가 먼저 깨진다.
     */
    @Test
    @DisplayName("일별작업량_전체쿼리는_배정이력이_없는_승인영상도_포함한다")
    void dailyCompletionAllIncludesUnassignedApprovedVideos() {
        // given — 배정(LS_TASK_ALTMNT) 없이 APPROVED 상태만 가진 영상 2건 + 미승인 1건
        LocalDateTime since = java.time.LocalDate.now().minusDays(29).atStartOfDay();
        long before = statsQueryRepository.findDailyCompletionAll(since).size();
        seed(APPROVED, 0);
        seed(APPROVED, 0);
        seed(LsRawDataStatus.STTS_PENDING, 0);

        // when
        List<StatsQueryRepository.DailyRawRow> rows = statsQueryRepository.findDailyCompletionAll(since);

        // then — 승인 2건만 증가(미승인 제외), 전 행이 윈도우 내 타임스탬프
        assertThat(rows.size() - before).isEqualTo(2L);
        assertThat(rows).allSatisfy(r -> assertThat(r.getUpdDt()).isAfterOrEqualTo(since));
    }
}
