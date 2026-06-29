package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.eventtype.repository.MngExEvntTypeMapRepository;
import kr.co.cudo.authoring.eventtype.repository.MngExEvntTypeRepository;
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
