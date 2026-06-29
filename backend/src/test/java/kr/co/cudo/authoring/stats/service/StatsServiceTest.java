package kr.co.cudo.authoring.stats.service;

import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.eventtype.dto.EventTypeResponse;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.stats.dto.DashboardSummaryResponse;
import kr.co.cudo.authoring.stats.dto.EventDistributionItem;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.CountRow;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StatsService 단위 테스트 (Mockito) — 이벤트 분포 집계(buildDistribution) 검증.
 *
 * <p>Phase 3 재설계: 분포는 더 이상 하드코딩 6종(EVT_*)이 아니라 관제 EV-코드를
 * {@link EventTypeService#filterOptions()} 의 카테고리/라벨/memberCodes 로 집계한다.
 * raw Map 키는 LS_DATA_RAW.EVNT_TYPE_CD 실제 저장값(EV-코드)이다.
 */
class StatsServiceTest {

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
                statsQueryRepository, videoRepository, authrtRepository, userRepository, eventTypeService);

        // getSummary 가 호출하는 분포 외 집계는 단위 테스트 관심사가 아니므로 빈/0 으로 폴백.
        lenient().when(statsQueryRepository.countByDataSttsCd()).thenReturn(List.of());
        lenient().when(statsQueryRepository.countFrameByEventType()).thenReturn(List.of());
        lenient().when(statsQueryRepository.countCumulativeFrames()).thenReturn(0L);
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
}
