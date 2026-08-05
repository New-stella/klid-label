package kr.co.cudo.authoring.stats.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.eventtype.dto.EventTypeResponse;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.stats.dto.DashboardSummaryResponse;
import kr.co.cudo.authoring.stats.dto.EventDistributionItem;
import kr.co.cudo.authoring.stats.dto.OverallStatSummaryResponse;
import kr.co.cudo.authoring.stats.dto.WorkerStatSummaryResponse;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.CountRow;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.DailyRawRow;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StatsService 단위 테스트 (Mockito) — 이벤트 분포 집계(buildDistribution) 검증.
 *
 * <p>Phase 3 재설계: 분포는 더 이상 하드코딩 6종(EVT_*)이 아니라 관제 EV-코드를
 * {@link EventTypeService#filterOptions()} 의 카테고리/라벨/memberCodes 로 집계한다.
 * raw Map 키는 LS_DATA_RAW.EVNT_TYPE_CD 실제 저장값(EV-코드)이다.
 */
class StatsServiceTest {

    /** 검수 승인 상태 코드 — 서비스가 사용하는 서버 상수와 동일해야 스텁이 매칭된다. */
    private static final String APPROVED = LsRawDataStatus.STTS_APPROVED;

    private StatsQueryRepository statsQueryRepository;
    private VideoRepository videoRepository;
    private LsTaskAssignmentRepository authrtRepository;
    private UserRepository userRepository;
    private EventTypeService eventTypeService;
    private StatsService service;

    @BeforeEach
    void setUp() {
        statsQueryRepository = mock(StatsQueryRepository.class);
        videoRepository = mock(VideoRepository.class);
        authrtRepository = mock(LsTaskAssignmentRepository.class);
        userRepository = mock(UserRepository.class);
        eventTypeService = mock(EventTypeService.class);
        service = new StatsService(
                statsQueryRepository, videoRepository, authrtRepository,
                new kr.co.cudo.authoring.user.service.UserNameResolver(userRepository), eventTypeService);

        // getSummary 가 호출하는 분포 외 집계는 단위 테스트 관심사가 아니므로 빈/0 으로 폴백.
        lenient().when(statsQueryRepository.countByDataSttsCd()).thenReturn(List.of());
        lenient().when(statsQueryRepository.countFrameByEventType()).thenReturn(List.of());
        lenient().when(statsQueryRepository.countCumulativeFrames()).thenReturn(0L);
        // 검수완료(APPROVED) 한정 집계 — 기본은 승인 0건 환경.
        lenient().when(statsQueryRepository.countFramesByDataSttsCd(APPROVED)).thenReturn(0L);
        lenient().when(statsQueryRepository.countVideoByEventTypeAndStatus(APPROVED)).thenReturn(List.of());
        lenient().when(statsQueryRepository.countFrameByEventTypeAndStatus(APPROVED)).thenReturn(List.of());
        // SCR-STAT-002 일별 작업량 — 기본은 승인 이력 0건 환경.
        lenient().when(statsQueryRepository.findDailyCompletionAll(any(LocalDateTime.class)))
                .thenReturn(List.of());
    }

    private static DailyRawRow dailyRow(LocalDateTime updDt) {
        DailyRawRow r = mock(DailyRawRow.class);
        lenient().when(r.getUpdDt()).thenReturn(updDt);
        return r;
    }

    private static CountRow countRow(String code, long cnt) {
        CountRow r = mock(CountRow.class);
        lenient().when(r.getCode()).thenReturn(code);
        lenient().when(r.getCnt()).thenReturn(cnt);
        return r;
    }

    /** filterOptions 스텁 — categoryKey 오름차순(안정 순서): 침수(범람)/화재/쓰러짐. */
    private void stubFilterOptions() {
        List<EventTypeResponse> options = List.of(
                EventTypeResponse.from("010001", "침수(범람)",
                        List.of("EV01000101", "EV01000102", "EV01000103")),
                EventTypeResponse.from("020001", "화재",
                        List.of("EV02000101", "EV02000102")),
                EventTypeResponse.from("020002", "쓰러짐",
                        List.of("EV02000201"))
        );
        when(eventTypeService.filterOptions()).thenReturn(options);
    }

    private void stubVideoEventCounts(CountRow... rows) {
        when(statsQueryRepository.countVideoByEventType()).thenReturn(new ArrayList<>(List.of(rows)));
    }

    private List<EventDistributionItem> distribution() {
        DashboardSummaryResponse summary = service.getSummary(null);
        return summary.eventDistribution();
    }

    @Test
    @DisplayName("이벤트분포는_EV코드_카운트를_관제_카테고리로_집계한다")
    void aggregatesEvCodeCountsByCategory() {
        // given — 쓰러짐(EV02000201) 815건
        stubFilterOptions();
        stubVideoEventCounts(countRow("EV02000201", 815L));

        // when
        List<EventDistributionItem> dist = distribution();

        // then — '쓰러짐' 카테고리에 815 가 집계되고 code 는 categoryKey(020002)
        assertThat(dist).extracting(
                        EventDistributionItem::eventTypeCd, EventDistributionItem::label, EventDistributionItem::count)
                .containsExactly(
                        tuple("010001", "침수(범람)", 0L),
                        tuple("020001", "화재", 0L),
                        tuple("020002", "쓰러짐", 815L));
    }

    @Test
    @DisplayName("같은_카테고리_여러_EV코드는_합산된다")
    void sumsMultipleEvCodesInSameCategory() {
        // given — 침수 카테고리의 3개 EV-코드 카운트
        stubFilterOptions();
        stubVideoEventCounts(
                countRow("EV01000101", 100L),
                countRow("EV01000102", 200L),
                countRow("EV01000103", 82L));

        // when
        List<EventDistributionItem> dist = distribution();

        // then — 침수(범람) = 100+200+82 = 382
        assertThat(dist).filteredOn(i -> i.eventTypeCd().equals("010001"))
                .singleElement()
                .extracting(EventDistributionItem::count)
                .isEqualTo(382L);
    }

    @Test
    @DisplayName("데이터없는_카테고리는_0으로_채워진다")
    void categoriesWithoutDataAreZeroFilled() {
        // given — raw 비어있음
        stubFilterOptions();
        stubVideoEventCounts();

        // when
        List<EventDistributionItem> dist = distribution();

        // then — filterOptions 의 3 카테고리 모두 노출, 카운트 0
        assertThat(dist).hasSize(3);
        assertThat(dist).extracting(EventDistributionItem::count).containsOnly(0L);
    }

    @Test
    @DisplayName("비수집코드는_분포그리드에_포함되지_않는다")
    void nonCollectedCodesAreExcludedFromGrid() {
        // given — 비수집 코드(EV07000201, filterOptions 에 없음) 카운트가 있어도
        stubFilterOptions();
        stubVideoEventCounts(
                countRow("EV02000201", 815L),
                countRow("EV07000201", 999L)); // 기타 상황(비수집) — 어떤 카테고리에도 속하지 않음

        // when
        List<EventDistributionItem> dist = distribution();

        // then — 그리드는 filterOptions 카테고리(3)만, 비수집 999 는 어디에도 합산되지 않음
        assertThat(dist).hasSize(3);
        assertThat(dist).noneMatch(i -> i.eventTypeCd().equals("070002"));
        assertThat(dist).extracting(EventDistributionItem::count).containsExactly(0L, 0L, 815L);
    }

    // ------------------------------------------------------------------
    // 검수완료(APPROVED) 한정 집계 — "학습데이터" 카드용 신규 필드.
    //
    // 기존 cumulative*/eventDistribution 은 "전체 기준" 의미를 그대로 유지하고,
    // approved* 만 LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED' 로 좁혀 집계한다.
    // ------------------------------------------------------------------

    /**
     * 상태별 카운트 스텁 — 승인/미승인이 섞인 환경을 만든다.
     *
     * <p>스텁 헬퍼로 감싸는 이유: {@code when(...).thenReturn(List.of(countRow(...)))} 처럼
     * {@code thenReturn} 인자 안에서 새 mock 을 만들면 Mockito 가 진행 중인 stubbing 으로 오인해
     * {@code UnfinishedStubbingException} 이 난다. 인자(=CountRow 생성)를 메서드 호출 시점에
     * 먼저 평가시켜 회피한다.
     */
    private void stubStatusCounts(CountRow... rows) {
        List<CountRow> list = new ArrayList<>(List.of(rows));
        when(statsQueryRepository.countByDataSttsCd()).thenReturn(list);
    }

    /** 검수 승인 영상의 이벤트별 <b>영상</b> 카운트 스텁. */
    private void stubApprovedVideoEventCounts(CountRow... rows) {
        List<CountRow> list = new ArrayList<>(List.of(rows));
        when(statsQueryRepository.countVideoByEventTypeAndStatus(APPROVED)).thenReturn(list);
    }

    /** 검수 승인 영상의 이벤트별 <b>프레임</b> 카운트 스텁. */
    private void stubApprovedFrameEventCounts(CountRow... rows) {
        List<CountRow> list = new ArrayList<>(List.of(rows));
        when(statsQueryRepository.countFrameByEventTypeAndStatus(APPROVED)).thenReturn(list);
    }

    @Test
    @DisplayName("승인된_영상만_approvedVideoCount에_집계된다")
    void onlyApprovedVideosAreCountedInApprovedVideoCount() {
        // given — APPROVED 3 + PENDING 5 + ASSIGNED 2 + IN_REVIEW 4 + REJECTED 1 = 전체 15
        stubFilterOptions();
        stubVideoEventCounts();
        stubStatusCounts(
                countRow(LsRawDataStatus.STTS_APPROVED, 3L),
                countRow(LsRawDataStatus.STTS_PENDING, 5L),
                countRow(LsRawDataStatus.STTS_ASSIGNED, 2L),
                countRow(LsRawDataStatus.STTS_IN_REVIEW, 4L),
                countRow(LsRawDataStatus.STTS_REJECTED, 1L));
        when(videoRepository.count()).thenReturn(15L);

        // when
        DashboardSummaryResponse summary = service.getSummary(null);

        // then — 미승인 12건은 제외되고 APPROVED 3 만 집계
        assertThat(summary.approvedVideoCount()).isEqualTo(3L);
        assertThat(summary.cumulativeVideoCount()).isEqualTo(15L);
    }

    @Test
    @DisplayName("승인되지_않은_영상의_프레임은_approvedImageCount에서_제외된다")
    void framesOfUnapprovedVideosAreExcludedFromApprovedImageCount() {
        // given — 전체 프레임 1200, 그중 승인 영상 프레임은 240
        stubFilterOptions();
        stubVideoEventCounts();
        when(statsQueryRepository.countCumulativeFrames()).thenReturn(1200L);
        when(statsQueryRepository.countFramesByDataSttsCd(APPROVED)).thenReturn(240L);

        // when
        DashboardSummaryResponse summary = service.getSummary(null);

        // then
        assertThat(summary.approvedImageCount()).isEqualTo(240L);
        assertThat(summary.cumulativeImageCount()).isEqualTo(1200L);
    }

    @Test
    @DisplayName("기존_cumulativeImageCount는_검수여부와_무관하게_전건을_유지한다")
    void cumulativeImageCountStaysTotalRegardlessOfReview() {
        // given — 승인 프레임은 0 이지만 전체 프레임은 987
        stubFilterOptions();
        stubVideoEventCounts();
        when(statsQueryRepository.countCumulativeFrames()).thenReturn(987L);
        when(statsQueryRepository.countFramesByDataSttsCd(APPROVED)).thenReturn(0L);

        // when
        DashboardSummaryResponse summary = service.getSummary(null);

        // then — 신규 필드 도입으로 기존 필드 의미가 바뀌지 않는다(하위호환)
        assertThat(summary.cumulativeImageCount()).isEqualTo(987L);
        assertThat(summary.approvedImageCount()).isZero();
    }

    @Test
    @DisplayName("기존_cumulativeVideoCount는_검수여부와_무관하게_전건을_유지한다")
    void cumulativeVideoCountStaysTotalRegardlessOfReview() {
        // given — 검수 상태 행이 하나도 없는(=배정 전) 영상 40건
        stubFilterOptions();
        stubVideoEventCounts();
        stubStatusCounts();
        when(videoRepository.count()).thenReturn(40L);

        // when
        DashboardSummaryResponse summary = service.getSummary(null);

        // then
        assertThat(summary.cumulativeVideoCount()).isEqualTo(40L);
        assertThat(summary.approvedVideoCount()).isZero();
    }

    @Test
    @DisplayName("approved카운트는_항상_전체카운트_이하다")
    void approvedCountsNeverExceedTotals() {
        // given — 승인이 섞인 일반적인 환경
        stubFilterOptions();
        stubVideoEventCounts(countRow("EV02000201", 100L));
        stubStatusCounts(
                countRow(LsRawDataStatus.STTS_APPROVED, 7L),
                countRow(LsRawDataStatus.STTS_PENDING, 13L));
        when(videoRepository.count()).thenReturn(100L);
        when(statsQueryRepository.countCumulativeFrames()).thenReturn(5000L);
        when(statsQueryRepository.countFramesByDataSttsCd(APPROVED)).thenReturn(350L);

        // when
        DashboardSummaryResponse summary = service.getSummary(null);

        // then
        assertThat(summary.approvedVideoCount()).isLessThanOrEqualTo(summary.cumulativeVideoCount());
        assertThat(summary.approvedImageCount()).isLessThanOrEqualTo(summary.cumulativeImageCount());
    }

    @Test
    @DisplayName("승인영상이_0건이면_approved카운트가_0이고_분포는_전항목_0이다")
    void zeroApprovedEnvironmentReturnsZeroCountsAndZeroFilledDistribution() {
        // given — 승인 0건 (setUp 기본 스텁), 전체 데이터는 존재
        stubFilterOptions();
        stubVideoEventCounts(countRow("EV02000201", 815L));
        stubStatusCounts(countRow(LsRawDataStatus.STTS_PENDING, 9L));
        when(videoRepository.count()).thenReturn(9L);
        when(statsQueryRepository.countCumulativeFrames()).thenReturn(700L);

        // when
        DashboardSummaryResponse summary = service.getSummary(null);

        // then — null·빈 리스트가 아니라 "전 항목 0" 리스트여야 FE 그리드가 동일하게 렌더된다
        assertThat(summary.approvedImageCount()).isZero();
        assertThat(summary.approvedVideoCount()).isZero();
        assertThat(summary.approvedEventDistribution()).hasSize(3)
                .extracting(EventDistributionItem::count).containsOnly(0L);
        assertThat(summary.approvedImageDistribution()).hasSize(3)
                .extracting(EventDistributionItem::count).containsOnly(0L);
    }

    @Test
    @DisplayName("approvedEventDistribution_항목수와_순서가_기존_eventDistribution과_동일하다")
    void approvedDistributionSharesCategoryOrderWithTotalDistribution() {
        // given — 전체는 3 카테고리에 분포, 승인은 쓰러짐(EV02000201) 만
        stubFilterOptions();
        stubVideoEventCounts(
                countRow("EV01000101", 10L),
                countRow("EV02000101", 20L),
                countRow("EV02000201", 30L));
        stubApprovedVideoEventCounts(countRow("EV02000201", 4L));
        stubApprovedFrameEventCounts(countRow("EV02000201", 44L));

        // when
        DashboardSummaryResponse summary = service.getSummary(null);

        // then — 카테고리 키·라벨·순서가 완전히 동일(카운트만 다름)
        assertThat(summary.approvedEventDistribution())
                .extracting(EventDistributionItem::eventTypeCd, EventDistributionItem::label)
                .containsExactlyElementsOf(summary.eventDistribution().stream()
                        .map(i -> tuple(i.eventTypeCd(), i.label())).toList());
        assertThat(summary.approvedImageDistribution())
                .extracting(EventDistributionItem::eventTypeCd, EventDistributionItem::label)
                .containsExactlyElementsOf(summary.imageDistribution().stream()
                        .map(i -> tuple(i.eventTypeCd(), i.label())).toList());
        assertThat(summary.approvedEventDistribution())
                .extracting(EventDistributionItem::count).containsExactly(0L, 0L, 4L);
        assertThat(summary.approvedImageDistribution())
                .extracting(EventDistributionItem::count).containsExactly(0L, 0L, 44L);
    }

    @Test
    @DisplayName("승인영상_이벤트분포_합계가_approvedVideoCount_이하다")
    void approvedDistributionSumNeverExceedsApprovedVideoCount() {
        // given — 승인 영상 10건 중 6건은 수집 카테고리, 4건은 비수집 코드(그리드 제외)
        stubFilterOptions();
        stubVideoEventCounts();
        stubStatusCounts(countRow(LsRawDataStatus.STTS_APPROVED, 10L));
        when(videoRepository.count()).thenReturn(10L);
        stubApprovedVideoEventCounts(
                countRow("EV02000201", 6L),
                countRow("EV07000201", 4L));   // 비수집 — 어떤 카테고리에도 합산되지 않음

        // when
        DashboardSummaryResponse summary = service.getSummary(null);

        // then
        long distributionSum = summary.approvedEventDistribution().stream()
                .mapToLong(EventDistributionItem::count).sum();
        assertThat(distributionSum).isEqualTo(6L);
        assertThat(distributionSum).isLessThanOrEqualTo(summary.approvedVideoCount());
    }

    @Test
    @DisplayName("전체구축현황_응답에도_approved_3필드가_포함된다")
    void overallSummaryAlsoExposesApprovedFields() {
        // given
        stubFilterOptions();
        stubVideoEventCounts(countRow("EV02000201", 30L));
        stubStatusCounts(
                countRow(LsRawDataStatus.STTS_APPROVED, 8L),
                countRow(LsRawDataStatus.STTS_REJECTED, 2L));
        when(videoRepository.count()).thenReturn(50L);
        when(statsQueryRepository.countCumulativeFrames()).thenReturn(4000L);
        when(statsQueryRepository.countFramesByDataSttsCd(APPROVED)).thenReturn(640L);
        stubApprovedVideoEventCounts(countRow("EV02000201", 8L));

        // when
        OverallStatSummaryResponse overall = service.getOverallSummary();

        // then — 전체 기준 필드는 그대로, approved 3필드가 추가 노출
        assertThat(overall.cumulativeImageCount()).isEqualTo(4000L);
        assertThat(overall.cumulativeVideoCount()).isEqualTo(50L);
        assertThat(overall.approvedImageCount()).isEqualTo(640L);
        assertThat(overall.approvedVideoCount()).isEqualTo(8L);
        // approvedVideoCount 는 processing.approved 와 동일 원천(드리프트 금지)
        assertThat(overall.approvedVideoCount()).isEqualTo(overall.processing().approved());
        assertThat(overall.approvedEventDistribution())
                .extracting(EventDistributionItem::eventTypeCd, EventDistributionItem::label)
                .containsExactlyElementsOf(overall.eventDistribution().stream()
                        .map(i -> tuple(i.eventTypeCd(), i.label())).toList());
        assertThat(overall.approvedEventDistribution())
                .extracting(EventDistributionItem::count).containsExactly(0L, 0L, 8L);
    }

    // ------------------------------------------------------------------
    // SCR-STAT-002 일별 작업량(dailyCounts) — 전체(모든 작업자) 기준 · 0-fill 30건 고정.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("일별작업량은_승인이력이_없어도_30건_전부_0으로_채워진다")
    void dailyCountsAlwaysReturnsThirtyZeroFilledDays() {
        // given — 승인 이력 0건
        stubFilterOptions();
        stubVideoEventCounts();
        when(statsQueryRepository.findDailyCompletionAll(any(LocalDateTime.class))).thenReturn(List.of());

        // when
        OverallStatSummaryResponse overall = service.getOverallSummary();

        // then — 막대차트 X축이 날짜 연속으로 그려지려면 빈 날짜도 0 으로 포함돼야 한다.
        assertThat(overall.dailyCounts()).hasSize(30);
        assertThat(overall.dailyCounts())
                .extracting(WorkerStatSummaryResponse.DailyCompletion::count)
                .containsOnly(0L);
    }

    @Test
    @DisplayName("일별작업량_날짜는_yyyy-MM-dd_오름차순이고_마지막이_오늘이다")
    void dailyCountsAreAscendingAndEndToday() {
        // given
        stubFilterOptions();
        stubVideoEventCounts();

        // when
        List<String> dates = service.getOverallSummary().dailyCounts().stream()
                .map(WorkerStatSummaryResponse.DailyCompletion::date)
                .toList();

        // then
        assertThat(dates).isSorted();
        assertThat(dates).allMatch(d -> d.matches("\\d{4}-\\d{2}-\\d{2}"));
        assertThat(dates.get(29)).isEqualTo(LocalDate.now().toString());
        assertThat(dates.get(0)).isEqualTo(LocalDate.now().minusDays(29).toString());
    }

    @Test
    @DisplayName("오늘_승인건은_마지막_항목에_반영되고_29일전은_포함_30일전은_제외된다")
    void dailyCountsWindowBoundaryIsTodayInclusiveThirtyDays() {
        // given — 오늘 2건 / 29일 전 1건 / 30일 전 1건(윈도우 밖)
        stubFilterOptions();
        stubVideoEventCounts();
        LocalDate today = LocalDate.now();
        // mock 픽스처는 when(...) 밖에서 먼저 만든다 — 중첩 스터빙 금지
        List<DailyRawRow> rows = List.of(
                dailyRow(today.atTime(9, 0)),
                dailyRow(today.atTime(18, 30)),
                dailyRow(today.minusDays(29).atTime(10, 0)),
                dailyRow(today.minusDays(30).atTime(23, 59)));
        when(statsQueryRepository.findDailyCompletionAll(any(LocalDateTime.class))).thenReturn(rows);

        // when
        List<WorkerStatSummaryResponse.DailyCompletion> daily = service.getOverallSummary().dailyCounts();

        // then — 항상 30건 유지 + 경계 정확
        assertThat(daily).hasSize(30);
        assertThat(daily.get(29).date()).isEqualTo(today.toString());
        assertThat(daily.get(29).count()).isEqualTo(2L);
        assertThat(daily.get(0).date()).isEqualTo(today.minusDays(29).toString());
        assertThat(daily.get(0).count()).isEqualTo(1L);
        // 30일 전 행은 어느 항목에도 합산되지 않는다(합계 = 3).
        assertThat(daily.stream().mapToLong(WorkerStatSummaryResponse.DailyCompletion::count).sum())
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("일별작업량_조회는_쿼리_1회이며_since는_오늘포함_30일_윈도우_시작이다")
    void dailyCountsQueriedOnceWithThirtyDayWindow() {
        // given
        stubFilterOptions();
        stubVideoEventCounts();

        // when
        service.getOverallSummary();

        // then — N+1 금지: 일자별 순회 조회가 아니라 단일 쿼리.
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(statsQueryRepository, times(1)).findDailyCompletionAll(since.capture());
        assertThat(since.getValue())
                .isEqualTo(LocalDate.now().minusDays(29).atStartOfDay());
    }

    @Test
    @DisplayName("작업자별_통계의_일별완료는_여전히_sparse다_0채움_없음")
    void workerDailyCompletionStaysSparse() {
        // given — 오늘 1건만 승인. 작업자 경로는 기존 계약(sparse)을 유지해야 한다.
        List<DailyRawRow> rows = List.of(dailyRow(LocalDate.now().atTime(9, 0)));
        when(statsQueryRepository.findDailyCompletionForWorker(
                org.mockito.ArgumentMatchers.eq(7L), any(LocalDateTime.class)))
                .thenReturn(rows);
        TokenClaims actor = new TokenClaims("7", Role.WORKER, Channel.INTERNAL, null);

        // when
        WorkerStatSummaryResponse worker = service.getWorkerSummary(actor, null);

        // then — 30건 0-fill 은 전체(overall) 경로 전용. 작업자 응답은 실제 데이터 있는 날만.
        assertThat(worker.dailyCompletion()).hasSize(1);
        assertThat(worker.dailyCompletion().get(0).date()).isEqualTo(LocalDate.now().toString());
    }
}
