package kr.co.cudo.authoring.stats;

import kr.co.cudo.authoring.eventtype.service.EventTypeCacheEvictor;
import kr.co.cudo.authoring.stats.dto.EventDistributionItem;
import kr.co.cudo.authoring.stats.service.StatsService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대시보드 이벤트 분포의 <b>표시명 그룹 합산</b> 회귀 가드 (2026-08-05 · R3).
 *
 * <h3>왜 IT 인가 (Critical)</h3>
 * <p>{@code StatsServiceTest} 는 {@code eventTypeService.filterOptions()} 를 통째로 mock 하므로
 * <b>그룹이 실제로 어떻게 만들어지는지</b>는 전혀 검증하지 않는다. 즉 그룹 병합이 잘못돼 다른 그룹
 * 코드나 <b>비수집·제외 대분류 코드</b>가 {@code memberCodes} 로 새어 들어가도 mock 이 그 사실을
 * 가리고, 통계 카운트만 조용히 부풀어 오른다. 이 IT 는 <b>실제 {@code EventTypeService} + 실제 집계
 * 쿼리</b> 조합으로 다음을 고정한다.
 *
 * <ul>
 *   <li>표시명이 같은 상세 코드들의 카운트가 <b>한 칸으로 합산</b>된다.</li>
 *   <li>그 칸의 {@code eventTypeCd} 는 <b>대표코드</b>(그룹 내 최소 코드), {@code label} 은 표시명이다.</li>
 *   <li>비수집({@code CLCT_YN='N'}) 코드의 영상은 <b>어떤 칸에도</b> 합산되지 않는다.</li>
 *   <li>제외 대분류({@code EVENT_EXCLUDED_CLASS_CODES} 기본 '08') 코드도 마찬가지로 제외된다.</li>
 * </ul>
 *
 * <h3>격리 전략</h3>
 * <p>{@code test-data-stats-clean.sql} 로 {@code LS_DATA_RAW} 를 비워 <b>분포 합계를 절대값으로</b>
 * 단언할 수 있게 한다(비수집·제외 코드가 새어 들어오면 합계가 즉시 틀어진다 — 이 절대 합계가 누수
 * 탐지의 핵심 단언이다). 이벤트유형은 <b>이 클래스 전용 코드</b>를 직접 심어 공유 시드(dev-seed·
 * stats-clean)의 행 구성 변화에 기대값이 종속되지 않게 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data-stats-clean.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StatsEventTypeGroupDistributionIT {

    /** 표시명이 같은 2종 — 대표코드는 그룹 내 최소 코드인 {@link #GROUP_REPRESENTATIVE} 다. */
    private static final String GROUP_REPRESENTATIVE = "EV94000101";
    private static final String GROUP_MEMBER = "EV94000103";
    private static final String GROUP_LABEL = "침수-통계IT";

    /** 표시명이 다른 독립 그룹 — 그룹 경계가 무너지면 위 그룹에 합산돼 버린다. */
    private static final String OTHER_GROUP = "EV94000201";
    private static final String OTHER_LABEL = "교통-통계IT";

    /** 비수집({@code CLCT_YN='N'}) — 필터 옵션에 없으므로 분포 그리드에도 없어야 한다. */
    private static final String NON_COLLECTED = "EV94000301";

    /**
     * 제외 대분류('08' = 배회) 소속. 설정({@code eventtype.excluded-class-codes}) 기본값이자
     * 조회 실패 시 폴백 기본값이라 어느 경로로도 제외가 성립한다.
     */
    private static final String EXCLUDED_CLASS = "EV08940101";

    private static final List<String> SEEDED_TYPE_CODES =
            List.of(GROUP_REPRESENTATIVE, GROUP_MEMBER, OTHER_GROUP, NON_COLLECTED, EXCLUDED_CLASS);

    @Autowired private StatsService statsService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private EventTypeCacheEvictor eventTypeCacheEvictor;

    private final JdbcTemplate jdbc;

    StatsEventTypeGroupDistributionIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        seedEventType(GROUP_REPRESENTATIVE, GROUP_LABEL, "94", "0001", "Y");
        seedEventType(GROUP_MEMBER, GROUP_LABEL, "94", "0001", "Y");
        seedEventType(OTHER_GROUP, OTHER_LABEL, "94", "0002", "Y");
        seedEventType(NON_COLLECTED, "비수집-통계IT", "94", "0003", "N");
        seedEventType(EXCLUDED_CLASS, "제외대분류-통계IT", "08", "0001", "Y");
        // 장수명(6h) 캐시라 방금 심은 마스터가 반영되려면 비워야 한다.
        eventTypeCacheEvictor.evictNow();

        // 그룹 5건(대표 2 + 비대표 3) / 독립 1건 / 비수집 4건 / 제외 대분류 2건 = 총 12영상.
        //   그리드에 실려야 하는 것은 5 + 1 = 6 뿐이다.
        seedVideos(GROUP_REPRESENTATIVE, 2);
        seedVideos(GROUP_MEMBER, 3);
        seedVideos(OTHER_GROUP, 1);
        seedVideos(NON_COLLECTED, 4);
        seedVideos(EXCLUDED_CLASS, 2);
    }

    /**
     * 심어 둔 마스터·영상을 되돌린다 — {@code eventType} 캐시는 <b>컨텍스트 공유</b>라 남겨 두면
     * 다른 테스트가 "DB 에는 없는데 캐시에는 있는" 유형을 보게 된다.
     */
    @AfterEach
    void tearDown() {
        for (String code : SEEDED_TYPE_CODES) {
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE EVNT_TYPE_CD = ?", code);
            jdbc.update("DELETE FROM LS_EVNT_TYPE WHERE EVNT_TYPE_CD = ?", code);
        }
        eventTypeCacheEvictor.evictNow();
    }

    // ---------------------------------------------------------------- fixtures

    private void seedEventType(String code, String name, String clsfCd, String ctgryCd, String clctYn) {
        jdbc.update("INSERT INTO LS_EVNT_TYPE "
                        + "(EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, EVNT_CTGRY_CD, CLCT_YN) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (EVNT_TYPE_CD) DO UPDATE "
                        + "SET EVNT_NM = EXCLUDED.EVNT_NM, EVNT_CLSF_CD = EXCLUDED.EVNT_CLSF_CD, "
                        + "    EVNT_CTGRY_CD = EXCLUDED.EVNT_CTGRY_CD, CLCT_YN = EXCLUDED.CLCT_YN",
                code, name, clsfCd, ctgryCd, clctYn);
    }

    private void seedVideos(String evntTypeCd, int count) {
        for (int i = 0; i < count; i++) {
            String clipId = "STATS-GRP-" + evntTypeCd + "-" + i;
            videoRepository.save(LsDataRaw.createFromIngest(
                    clipId, "CCTV-STATS-GRP", evntTypeCd, "11680",
                    LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                    LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30));
        }
    }

    private List<EventDistributionItem> distribution() {
        return statsService.getSummary(null).eventDistribution();
    }

    private static Optional<EventDistributionItem> cellOf(List<EventDistributionItem> grid, String code) {
        return grid.stream().filter(i -> code.equals(i.eventTypeCd())).findFirst();
    }

    // ------------------------------------------------------------------- tests

    @Test
    @DisplayName("표시명이_같은_상세코드들의_영상은_대표코드_한칸으로_합산된다")
    void sameDisplayNameCodesAreSummedIntoRepresentativeCell() {
        // when
        List<EventDistributionItem> grid = distribution();

        // then — 대표 2건 + 비대표 3건 = 5건이 한 칸에 합산되고, 칸 이름은 표시명이다
        assertThat(cellOf(grid, GROUP_REPRESENTATIVE))
                .as("memberCodes 합산이 빠지면 대표코드 자기 카운트(2)만 남는다")
                .get()
                .extracting(EventDistributionItem::label, EventDistributionItem::count)
                .containsExactly(GROUP_LABEL, 5L);
    }

    @Test
    @DisplayName("그룹_비대표코드는_별도_칸으로_노출되지_않는다")
    void groupMemberIsNotExposedAsSeparateCell() {
        // when
        List<EventDistributionItem> grid = distribution();

        // then — 접기가 풀리면 같은 이름의 칸이 그리드에 두 번 뜬다
        assertThat(cellOf(grid, GROUP_MEMBER)).isEmpty();
        assertThat(grid).filteredOn(i -> GROUP_LABEL.equals(i.label())).hasSize(1);
    }

    @Test
    @DisplayName("표시명이_다른_유형은_다른_칸으로_분리된다")
    void differentDisplayNameStaysSeparateCell() {
        // when
        List<EventDistributionItem> grid = distribution();

        // then — 표시명을 무시하고 전부 한 덩어리로 묶으면 여기서 먼저 깨진다
        assertThat(cellOf(grid, OTHER_GROUP)).get()
                .extracting(EventDistributionItem::label, EventDistributionItem::count)
                .containsExactly(OTHER_LABEL, 1L);
    }

    @Test
    @DisplayName("비수집_코드의_영상은_어떤_칸에도_합산되지_않는다")
    void nonCollectedCodeIsNeverSummed() {
        // when
        List<EventDistributionItem> grid = distribution();

        // then — 자기 칸이 없을 뿐 아니라 <다른 그룹의 memberCodes 로도> 새어 들어가면 안 된다.
        //   그리드 총합이 곧 그 누수 탐지기다(clean 시드라 우리 영상만 존재).
        assertThat(cellOf(grid, NON_COLLECTED)).isEmpty();
        assertThat(grid.stream().mapToLong(EventDistributionItem::count).sum())
                .as("비수집 4건·제외 대분류 2건이 섞이면 6을 넘는다")
                .isEqualTo(6L);
    }

    @Test
    @DisplayName("제외_대분류_코드의_영상도_어떤_칸에도_합산되지_않는다")
    void excludedClassCodeIsNeverSummed() {
        // when
        List<EventDistributionItem> grid = distribution();

        // then
        assertThat(cellOf(grid, EXCLUDED_CLASS)).isEmpty();
        assertThat(grid).noneMatch(i -> "제외대분류-통계IT".equals(i.label()));
    }
}
