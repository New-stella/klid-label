package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.eventtype.repository.MngExEvntTypeMapRepository;
import kr.co.cudo.authoring.eventtype.repository.MngExEvntTypeRepository;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.entity.MngExEvntType;
import kr.co.cudo.authoring.video.entity.MngExEvntTypeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EventTypeService 캐시 동작 통합 테스트 — {@code @Cacheable} 프록시가 활성인 컨텍스트에서
 * 반복 조회 시 리포지토리(DB) 재조회가 일어나지 않음을 고정한다.
 *
 * <p>리포지토리는 {@code @MockBean} 으로 대체해 호출 횟수를 검증한다. 캐시는 컨텍스트별로
 * 격리되며, 테스트 시작 시 {@code eventType} 캐시를 비워 deterministic 하게 만든다.
 */
@SpringBootTest
@ActiveProfiles("local")
class EventTypeCacheIT {

    @Autowired private EventTypeService eventTypeService;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private CacheManager cacheManager;

    @MockBean private MngExEvntTypeRepository typeRepository;
    @MockBean private MngExEvntTypeMapRepository mapRepository;

    private static MngExEvntType type(String code, String cls, String ctgry, String clctYn) {
        MngExEvntType t = mock(MngExEvntType.class);
        lenient().when(t.getEvntTypeCd()).thenReturn(code);
        lenient().when(t.getEvntClsCd()).thenReturn(cls);
        lenient().when(t.getEvntCtgryCd()).thenReturn(ctgry);
        lenient().when(t.getClctYn()).thenReturn(clctYn);
        return t;
    }

    private static MngExEvntTypeMap categoryRow(String cls, String ctgry, String evntNm) {
        MngExEvntTypeMap m = mock(MngExEvntTypeMap.class);
        lenient().when(m.getEvntClsCd()).thenReturn(cls);
        lenient().when(m.getEvntCtgryCd()).thenReturn(ctgry);
        lenient().when(m.getEvntNm()).thenReturn(evntNm);
        // 카테고리명행 식별 조건(DTL_EVNT=''/EVNT_TYPE_CD='') 을 명시 — Mockito null 묵시 통과 제거
        // (EventTypeServiceTest 와 동일 스텁).
        lenient().when(m.getDtlEvnt()).thenReturn("");
        lenient().when(m.getEvntTypeCd()).thenReturn("");
        return m;
    }

    @Test
    @DisplayName("라벨맵이_캐시되어_2회호출시_repository가_1회만_조회된다")
    void codeLabelMapCachedAfterFirstCall() {
        // given
        var cache = cacheManager.getCache("eventType");
        assertThat(cache).isNotNull();
        cache.clear();
        // mock 엔티티 생성(when 스텁 포함)을 thenReturn 인자 안에서 하면 UnfinishedStubbing → 먼저 리스트 구성.
        List<MngExEvntType> all = List.of(type("EV02000201", "02", "0002", "Y"));
        List<MngExEvntTypeMap> maps = List.of(categoryRow("02", "0002", "쓰러짐"));
        when(typeRepository.findAll()).thenReturn(all);
        when(mapRepository.findByCdType("02")).thenReturn(maps);

        // when — 2회 호출
        var first = eventTypeService.codeLabelMap();
        var second = eventTypeService.codeLabelMap();

        // then — 결과 동일 + DB 조회는 1회만(2번째는 캐시 적중)
        assertThat(first).containsEntry("EV02000201", "쓰러짐");
        assertThat(second).isEqualTo(first);
        verify(typeRepository, times(1)).findAll();
        verify(mapRepository, times(1)).findByCdType("02");
    }

    @Test
    @DisplayName("filterOptions_캐시되어_2회호출시_repository가_1회만_조회된다")
    void filterOptionsCachedAfterFirstCall() {
        // given — codeLabelMap 과 캐시 키('filterOptions')가 다르므로 독립 검증 필요
        var cache = cacheManager.getCache("eventType");
        assertThat(cache).isNotNull();
        cache.clear();
        List<MngExEvntType> types = List.of(type("EV02000201", "02", "0002", "Y"));
        List<MngExEvntTypeMap> maps = List.of(categoryRow("02", "0002", "쓰러짐"));
        when(typeRepository.findByClctYn("Y")).thenReturn(types);
        when(mapRepository.findByCdType("02")).thenReturn(maps);

        // when — 2회 호출
        var first = eventTypeService.filterOptions();
        var second = eventTypeService.filterOptions();

        // then — 결과 동일 + DB 조회는 1회만(2번째는 캐시 적중)
        assertThat(first).hasSize(1);
        assertThat(first.get(0).label()).isEqualTo("쓰러짐");
        assertThat(second).isEqualTo(first);
        verify(typeRepository, times(1)).findByClctYn("Y");
        verify(mapRepository, times(1)).findByCdType("02");
    }

    @Test
    @DisplayName("categoryKeyOf_역인덱스가_캐시되어_반복조회시_repository_재조회_안한다")
    void categoryKeyOfReverseIndexCached() {
        // given — categoryKeyOf 는 codeToCategoryKey()(@Cacheable)를 self 프록시 경유로 호출해야 한다.
        // self-invocation 이면 프록시 우회로 매 변환마다 findAll() 이 재실행된다(회귀 가드).
        var cache = cacheManager.getCache("eventType");
        assertThat(cache).isNotNull();
        cache.clear();
        List<MngExEvntType> all = List.of(
                type("EV03000102", "03", "0001", "Y"),
                type("EV01000101", "01", "0001", "Y"));
        when(typeRepository.findAll()).thenReturn(all);

        // when — 여러 번 변환 호출(캐시 워밍 후 추가 조회 0)
        var first = eventTypeService.categoryKeyOf("EV03000102");
        var second = eventTypeService.categoryKeyOf("EV01000101");
        var third = eventTypeService.categoryKeyOf("EV99999999");

        // then — 변환 정상 + repository 조회는 최초 1회만
        assertThat(first).contains("030001");
        assertThat(second).contains("010001");
        assertThat(third).isEmpty();
        verify(typeRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("제외코드_설정을_수정하면_filterOptions_캐시가_무효화되어_즉시_반영된다")
    void excludedClassCodesConfigUpdateEvictsFilterOptionsCache() {
        // given — 배회(08)/미아(09) 두 대분류가 수집대상. 시드 기본값 ["08"] 로 08 만 제외된 상태를 캐싱.
        var eventCache = cacheManager.getCache("eventType");
        var sysCache = cacheManager.getCache("sysconfig");
        assertThat(eventCache).isNotNull();
        assertThat(sysCache).isNotNull();
        eventCache.clear();
        sysCache.clear();
        List<MngExEvntType> types = List.of(
                type("EV08000101", "08", "0001", "Y"),
                type("EV09000101", "09", "0001", "Y"));
        List<MngExEvntTypeMap> maps = List.of(
                categoryRow("08", "0001", "배회"),
                categoryRow("09", "0001", "미아"));
        when(typeRepository.findByClctYn("Y")).thenReturn(types);
        when(mapRepository.findByCdType("02")).thenReturn(maps);
        assertThat(eventTypeService.filterOptions())
                .extracting(kr.co.cudo.authoring.eventtype.dto.EventTypeResponse::categoryKey)
                .containsExactly("090001");

        // when — REVIEWER 가 제외 코드를 ["09"] 로 변경
        TokenClaims reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
                java.time.Instant.now().plusSeconds(600));
        try {
            systemConfigService.update(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES, "[\"09\"]", reviewer);

            // then — eventType 캐시까지 무효화되어 배포 없이 즉시 반영(08 노출, 09 제외)
            assertThat(eventTypeService.filterOptions())
                    .extracting(kr.co.cudo.authoring.eventtype.dto.EventTypeResponse::categoryKey)
                    .containsExactly("080001");
        } finally {
            // 후속 테스트 영향 차단 — 시드 기본값으로 원복.
            systemConfigService.update(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES, "[\"08\"]", reviewer);
            eventCache.clear();
        }
    }

    @Test
    @DisplayName("무관한_설정키_수정은_eventType_캐시를_비우지_않는다")
    void unrelatedConfigUpdateKeepsEventTypeCache() {
        // given — filterOptions 캐시 워밍
        var eventCache = cacheManager.getCache("eventType");
        assertThat(eventCache).isNotNull();
        eventCache.clear();
        List<MngExEvntType> types = List.of(type("EV02000201", "02", "0002", "Y"));
        List<MngExEvntTypeMap> maps = List.of(categoryRow("02", "0002", "쓰러짐"));
        when(typeRepository.findByClctYn("Y")).thenReturn(types);
        when(mapRepository.findByCdType("02")).thenReturn(maps);
        eventTypeService.filterOptions();

        // when — 제외코드와 무관한 설정 키 변경
        TokenClaims reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
                java.time.Instant.now().plusSeconds(600));
        int original = systemConfigService.getInt(ConfigKeys.BATCH_CONCURRENCY);
        try {
            systemConfigService.update(ConfigKeys.BATCH_CONCURRENCY,
                    String.valueOf(original == 3 ? 2 : 3), reviewer);

            // then — 관제 코드 캐시는 유지(불필요한 MNG_* 재조회 방지)
            eventTypeService.filterOptions();
            verify(typeRepository, times(1)).findByClctYn("Y");
        } finally {
            systemConfigService.update(ConfigKeys.BATCH_CONCURRENCY, String.valueOf(original), reviewer);
            eventCache.clear();
        }
    }

    @Test
    @DisplayName("resolveLabel_반복호출시_codeLabelMap캐시가_적중해_repository_재조회_안한다")
    void resolveLabelHitsCodeLabelMapCache() {
        // given — resolveLabel 은 codeLabelMap()(@Cacheable)을 self 프록시 경유로 호출해야 한다.
        // self-invocation 이면 프록시 우회로 매 호출 findAll()+findByCdType 가 재실행된다(회귀 가드).
        var cache = cacheManager.getCache("eventType");
        assertThat(cache).isNotNull();
        cache.clear();
        List<MngExEvntType> all = List.of(type("EV02000201", "02", "0002", "Y"));
        List<MngExEvntTypeMap> maps = List.of(categoryRow("02", "0002", "쓰러짐"));
        when(typeRepository.findAll()).thenReturn(all);
        when(mapRepository.findByCdType("02")).thenReturn(maps);

        // when — resolveLabel 을 여러 번 호출(캐시 워밍 후 추가 조회 0)
        String first = eventTypeService.resolveLabel("EV02000201");
        String second = eventTypeService.resolveLabel("EV02000201");
        String unknown = eventTypeService.resolveLabel("EV99999999");

        // then — 라벨 해석 정상 + repository 조회는 최초 1회만
        assertThat(first).isEqualTo("쓰러짐");
        assertThat(second).isEqualTo("쓰러짐");
        assertThat(unknown).isEqualTo("EV99999999");
        verify(typeRepository, times(1)).findAll();
        verify(mapRepository, times(1)).findByCdType("02");
    }
}
