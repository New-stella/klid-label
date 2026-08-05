package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.eventtype.dto.EventTypeResponse;
import kr.co.cudo.authoring.eventtype.entity.LsEvntType;
import kr.co.cudo.authoring.eventtype.repository.LsEvntTypeRepository;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
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
 *
 * <p><b>무효화 트리거는 둘</b>이다 — ① 제외 대분류 설정 변경 ② 신규 유형 자동등록
 * ({@link EventTypeAutoRegistrar}). 둘 다 이 클래스가 고정한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class EventTypeCacheIT {

    @Autowired private EventTypeService eventTypeService;
    @Autowired private EventTypeAutoRegistrar autoRegistrar;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private CacheManager cacheManager;

    @MockBean private LsEvntTypeRepository typeRepository;

    private static LsEvntType type(String code, String name, String clsf, String clctYn) {
        LsEvntType t = mock(LsEvntType.class);
        lenient().when(t.getEvntTypeCd()).thenReturn(code);
        lenient().when(t.getEvntNm()).thenReturn(name);
        lenient().when(t.getEvntClsfCd()).thenReturn(clsf);
        lenient().when(t.isCollected()).thenReturn("Y".equalsIgnoreCase(clctYn));
        return t;
    }

    @Test
    @DisplayName("라벨맵이_캐시되어_2회호출시_repository가_1회만_조회된다")
    void codeLabelMapCachedAfterFirstCall() {
        // given
        var cache = cacheManager.getCache("eventType");
        assertThat(cache).isNotNull();
        cache.clear();
        // mock 엔티티 생성(when 스텁 포함)을 thenReturn 인자 안에서 하면 UnfinishedStubbing → 먼저 리스트 구성.
        List<LsEvntType> all = List.of(type("EV02000201", "쓰러짐", "02", "Y"));
        when(typeRepository.findAll()).thenReturn(all);

        // when — 2회 호출
        var first = eventTypeService.codeLabelMap();
        var second = eventTypeService.codeLabelMap();

        // then — 결과 동일 + DB 조회는 1회만(2번째는 캐시 적중)
        assertThat(first).containsEntry("EV02000201", "쓰러짐");
        assertThat(second).isEqualTo(first);
        verify(typeRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("filterOptions_캐시되어_2회호출시_repository가_1회만_조회된다")
    void filterOptionsCachedAfterFirstCall() {
        // given — codeLabelMap 과 캐시 키('filterOptions')가 다르므로 독립 검증 필요
        var cache = cacheManager.getCache("eventType");
        assertThat(cache).isNotNull();
        cache.clear();
        List<LsEvntType> all = List.of(type("EV02000201", "쓰러짐", "02", "Y"));
        when(typeRepository.findAll()).thenReturn(all);

        // when — 2회 호출
        var first = eventTypeService.filterOptions();
        var second = eventTypeService.filterOptions();

        // then — 결과 동일 + DB 조회는 1회만(2번째는 캐시 적중)
        assertThat(first).hasSize(1);
        assertThat(first.get(0).label()).isEqualTo("쓰러짐");
        assertThat(second).isEqualTo(first);
        verify(typeRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("filterKeyOf_등록코드집합이_캐시되어_반복조회시_repository_재조회_안한다")
    void filterKeyOfRegisteredCodesCached() {
        // given — filterKeyOf 는 registeredCodes()(@Cacheable)를 self 프록시 경유로 호출해야 한다.
        // self-invocation 이면 프록시 우회로 매 판정마다 findAll() 이 재실행된다(회귀 가드).
        var cache = cacheManager.getCache("eventType");
        assertThat(cache).isNotNull();
        cache.clear();
        List<LsEvntType> all = List.of(
                type("EV03000102", "교통사고", "03", "Y"),
                type("EV01000101", "침수(범람)", "01", "Y"));
        when(typeRepository.findAll()).thenReturn(all);

        // when — 여러 번 판정 호출(캐시 워밍 후 추가 조회 0)
        var first = eventTypeService.filterKeyOf("EV03000102");
        var second = eventTypeService.filterKeyOf("EV01000101");
        var third = eventTypeService.filterKeyOf("EV99999999");

        // then — 판정 정상 + repository 조회는 최초 1회만
        assertThat(first).contains("EV03000102");
        assertThat(second).contains("EV01000101");
        assertThat(third).isEmpty();
        verify(typeRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("캐시_무효화_트리거가_유지된다")
    void 캐시_무효화_트리거가_유지된다() {
        // given — 배회(08)/미아(09) 두 대분류가 수집대상. 시드 기본값 ["08"] 로 08 만 제외된 상태를 캐싱.
        var eventCache = cacheManager.getCache("eventType");
        var sysCache = cacheManager.getCache("sysconfig");
        assertThat(eventCache).isNotNull();
        assertThat(sysCache).isNotNull();
        eventCache.clear();
        sysCache.clear();
        List<LsEvntType> all = List.of(
                type("EV08000101", "배회", "08", "Y"),
                type("EV09000101", "미아", "09", "Y"));
        when(typeRepository.findAll()).thenReturn(all);
        assertThat(eventTypeService.filterOptions())
                .extracting(EventTypeResponse::categoryKey)
                .containsExactly("EV09000101");

        // when — REVIEWER 가 제외 코드를 ["09"] 로 변경
        TokenClaims reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
                java.time.Instant.now().plusSeconds(600));
        try {
            systemConfigService.update(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES, "[\"09\"]", reviewer);

            // then — eventType 캐시까지 무효화되어 배포 없이 즉시 반영(08 노출, 09 제외)
            assertThat(eventTypeService.filterOptions())
                    .extracting(EventTypeResponse::categoryKey)
                    .containsExactly("EV08000101");
        } finally {
            // 후속 테스트 영향 차단 — 시드 기본값으로 원복.
            systemConfigService.update(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES, "[\"08\"]", reviewer);
            eventCache.clear();
        }
    }

    @Test
    @DisplayName("이름_갱신시_필터옵션_캐시가_무효화된다")
    void 이름_갱신시_필터옵션_캐시가_무효화된다() {
        // given — 캐시 워밍(유형 1건)
        var cache = cacheManager.getCache("eventType");
        assertThat(cache).isNotNull();
        cache.clear();
        List<LsEvntType> before = List.of(type("EV02000201", "쓰러짐", "02", "Y"));
        when(typeRepository.findAll()).thenReturn(before);
        when(typeRepository.registerOrRefresh("EV09000101", "미아", "09", null)).thenReturn(1);
        assertThat(eventTypeService.filterOptions()).hasSize(1);

        // when — 신규 유형이 자동등록되고, 그 뒤 마스터가 2건이 된다
        List<LsEvntType> after = List.of(
                type("EV02000201", "쓰러짐", "02", "Y"),
                type("EV09000101", "미아", "09", "Y"));
        when(typeRepository.findAll()).thenReturn(after);
        boolean registered = autoRegistrar.register("EV09000101", "미아", "09", null);

        // then — 등록 직후 캐시가 비워져 새 유형이 <즉시> 보인다(TTL 6시간을 기다리지 않는다)
        assertThat(registered).isTrue();
        assertThat(eventTypeService.filterOptions())
                .extracting(EventTypeResponse::categoryKey)
                .containsExactly("EV02000201", "EV09000101");
    }

    @Test
    @DisplayName("값이_그대로면_재인입은_캐시를_비우지_않는다")
    void 값이_그대로면_재인입은_캐시를_비우지_않는다() {
        // given — 캐시 워밍. 매 인입마다 캐시를 비우면 장수명(6h) 캐시가 무력화된다(회귀 가드).
        var cache = cacheManager.getCache("eventType");
        assertThat(cache).isNotNull();
        cache.clear();
        List<LsEvntType> all = List.of(type("EV02000201", "쓰러짐", "02", "Y"));
        when(typeRepository.findAll()).thenReturn(all);
        when(typeRepository.registerOrRefresh("EV02000201", "쓰러짐", "02", null)).thenReturn(0);
        eventTypeService.filterOptions();

        // when — 이미 있고 값도 같은 유형이 다시 인입된다(등록·갱신 0건)
        boolean registered = autoRegistrar.register("EV02000201", "쓰러짐", "02", null);

        // then — 캐시 유지(추가 findAll 없음)
        assertThat(registered).isFalse();
        eventTypeService.filterOptions();
        verify(typeRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("무관한_설정키_수정은_eventType_캐시를_비우지_않는다")
    void unrelatedConfigUpdateKeepsEventTypeCache() {
        // given — filterOptions 캐시 워밍
        var eventCache = cacheManager.getCache("eventType");
        assertThat(eventCache).isNotNull();
        eventCache.clear();
        List<LsEvntType> all = List.of(type("EV02000201", "쓰러짐", "02", "Y"));
        when(typeRepository.findAll()).thenReturn(all);
        eventTypeService.filterOptions();

        // when — 제외코드와 무관한 설정 키 변경
        TokenClaims reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
                java.time.Instant.now().plusSeconds(600));
        int original = systemConfigService.getInt(ConfigKeys.BATCH_CONCURRENCY);
        try {
            systemConfigService.update(ConfigKeys.BATCH_CONCURRENCY,
                    String.valueOf(original == 3 ? 2 : 3), reviewer);

            // then — 이벤트유형 캐시는 유지(불필요한 마스터 재조회 방지)
            eventTypeService.filterOptions();
            verify(typeRepository, times(1)).findAll();
        } finally {
            systemConfigService.update(ConfigKeys.BATCH_CONCURRENCY, String.valueOf(original), reviewer);
            eventCache.clear();
        }
    }

    @Test
    @DisplayName("resolveLabel_반복호출시_codeLabelMap캐시가_적중해_repository_재조회_안한다")
    void resolveLabelHitsCodeLabelMapCache() {
        // given — resolveLabel 은 codeLabelMap()(@Cacheable)을 self 프록시 경유로 호출해야 한다.
        // self-invocation 이면 프록시 우회로 매 호출 findAll() 이 재실행된다(회귀 가드).
        var cache = cacheManager.getCache("eventType");
        assertThat(cache).isNotNull();
        cache.clear();
        List<LsEvntType> all = List.of(type("EV02000201", "쓰러짐", "02", "Y"));
        when(typeRepository.findAll()).thenReturn(all);

        // when — resolveLabel 을 여러 번 호출(캐시 워밍 후 추가 조회 0)
        String first = eventTypeService.resolveLabel("EV02000201");
        String second = eventTypeService.resolveLabel("EV02000201");
        String unknown = eventTypeService.resolveLabel("EV99999999");

        // then — 라벨 해석 정상 + repository 조회는 최초 1회만
        assertThat(first).isEqualTo("쓰러짐");
        assertThat(second).isEqualTo("쓰러짐");
        assertThat(unknown).isEqualTo("EV99999999");
        verify(typeRepository, times(1)).findAll();
    }
}
