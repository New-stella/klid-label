package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.eventtype.dto.EventTypeResponse;
import kr.co.cudo.authoring.eventtype.entity.LsEvntCtgry;
import kr.co.cudo.authoring.eventtype.entity.LsEvntType;
import kr.co.cudo.authoring.eventtype.policy.EventTypeDisplayNamePolicy;
import kr.co.cudo.authoring.eventtype.repository.LsEvntCtgryRepository;
import kr.co.cudo.authoring.eventtype.repository.LsEvntTypeRepository;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 이벤트유형 라벨 매핑·필터 옵션 제공 서비스.
 *
 * <h3>축은 유형(type)이다 (V168 — 구 카테고리 축 폐기)</h3>
 * <p>조달처가 관제 공유 마스터 2종({@code MNG_EX_EVNT_TYPE} + {@code _MAP})에서 <b>저작도구 소유
 * 마스터</b>({@code LS_EVNT_TYPE})로 바뀌었고, 동시에 축이 <b>카테고리 → 유형</b>으로 바뀌었다.
 * 근거는 어노테이션 계약이다 — export JSON {@code NiaVideo} 의 {@code event_id}/{@code event_name}
 * 은 영상당 <b>단일 유형 값</b>이지 카테고리명이 아니다. 구 구현은 (대분류+카테고리)로 dedup 해
 * 상세 유형들을 하나의 이름으로 뭉갰기 때문에, 관제가 유형별로 실어 보내는 이름이 표시될 자리가
 * 아예 없었다.
 *
 * <p><b>API 응답 스키마는 불변</b>이다({@link EventTypeResponse} 필드명·타입 유지). 값의 <b>입도</b>만
 * 카테고리 → 유형으로 바뀐다: {@code categoryKey} 에는 이제 유형코드가, {@code memberCodes} 에는
 * 그 유형코드 1건이 담긴다.
 *
 * <h3>표시명은 4단 폴백이다 — 판정은 한 곳에만</h3>
 * <p>{@code COALESCE(운영자 표시명, 관제 수신 유형명, 카테고리명, 유형코드)}. 관제 마스터에는
 * <b>유형별 이름이 애초에 없었고</b> 사람이 읽는 이름은 카테고리 레벨에만 있었다(원래 3계층 구조).
 * 판정은 {@link EventTypeDisplayNamePolicy} 에만 있으며 필터 옵션·라벨맵·관리 화면·<b>승인 시점
 * 동결</b>({@code DatasetMetaSourceRepository})이 모두 같은 결과를 내야 한다.
 *
 * <h3>제공 기능</h3>
 * <ol>
 *   <li><b>filterOptions()</b> — 등록된 유형 중 수집대상({@code CLCT_YN='Y'})이고 제외 대분류가
 *       아닌 것을 유형코드 오름차순으로 반환한다.</li>
 *   <li><b>codeLabelMap()</b> — 등록된 전체 유형(수집/비수집 무관)의 코드 → <b>표시명</b> 맵.
 *       표시명이 코드와 같은(= 이름이 하나도 없는) 유형은 담지 않는다({@link #resolveLabel} 가
 *       원문 폴백을 책임진다).</li>
 *   <li><b>resolveLabel()</b> — 임의 코드 1건의 라벨 해석(미등록/이름없음은 원문 폴백).</li>
 *   <li><b>filterKeyOf() / codesForFilterKey() / validFilterKeys()</b> — 프리셋 매핑·목록 필터가
 *       쓰는 <b>등록 여부 판정</b>. 축이 유형이라 키와 코드가 같은 값이지만, 호출부가
 *       "등록되지 않은 값은 매칭 0건" 이라는 fail-safe 계약에 의존하므로 메서드는 유지한다.</li>
 * </ol>
 *
 * <h3>★ 제외 대분류가 null 인 유형은 <b>노출</b>한다 (fail-open — 근거)</h3>
 * <p>대분류({@code EVNT_CLSF_CD})는 <b>관제가 인입에 실어 보내는 값</b>이며 코드에서 유도하지
 * 않는다(사용자 확정 2026-08-04 — 유도하면 비규격 코드에서 존재하지 않는 대분류가 만들어진다).
 * 관제가 아직 안 보내면 이 값은 null 이다. null 을 <b>제외</b>로 처리하면 관제 송신 전까지
 * <b>모든 유형이 필터에서 사라지고</b> 그 유형의 영상이 목록에서 통째로 보이지 않는다 —
 * 값 결손이 가용성 사고로 번지는 형태다. 반대로 노출하면 최악의 경우 "숨기고 싶던 대분류가 잠시
 * 보인다"인데, 이는 되돌릴 수 있고 관측 가능하다. 그래서 <b>제외는 명시적으로 지정된 대분류에만</b>
 * 적용한다.
 *
 * <h3>캐시</h3>
 * <p>near-immutable(코드 체계)이므로 인자 없는 단순 키로 {@link CacheConfig#CACHE_EVENT_TYPE} 에
 * 장수명 캐시한다. 무효화 트리거는 <b>둘</b>이다 — ① 제외 대분류 설정 변경
 * ({@code SystemConfigService.update}) ② <b>신규 유형 자동등록</b>({@link EventTypeAutoRegistrar}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventTypeService {

    /**
     * 제외 대분류 코드 설정({@link ConfigKeys#EVENT_EXCLUDED_CLASS_CODES}) 조회 실패 시 폴백 기본값.
     * 구 소스 상수({@code IGNORE_CLASS_CD = "08"}, 배회)와 동일해 설정 미시드 환경에서도 동작이 같다.
     */
    private static final Set<String> DEFAULT_EXCLUDED_CLASS_CODES = Set.of("08");

    private final LsEvntTypeRepository evntTypeRepository;
    private final LsEvntCtgryRepository evntCtgryRepository;
    private final SystemConfigService systemConfigService;

    /**
     * 자기 자신(캐시 프록시) 주입 — 내부 호출이 {@code @Cacheable} AOP 프록시를 경유하도록 한다.
     * {@code this.codeLabelMap()} 직접 호출은 self-invocation 이라 프록시를 우회해 캐시가 적중하지
     * 않고 매 호출마다 {@code findAll()} 을 재실행한다(집계 항목마다 resolveLabel 을 부르는
     * StatsService 에서 N 쿼리 폭증). 순환 주입이므로 {@code @Lazy} 로 끊는다. 컨테이너 밖(순수 단위
     * 테스트)에서는 null 이며 이 경우 프록시/캐시가 없으므로 {@code this} 로 폴백한다.
     */
    @Autowired
    @Lazy
    private EventTypeService self;

    /**
     * 필터 드롭다운용 <b>이벤트유형</b> 옵션 목록.
     *
     * <p>등록된 유형 중 수집대상({@code CLCT_YN='Y'})이고 제외 대분류(설정
     * {@link ConfigKeys#EVENT_EXCLUDED_CLASS_CODES}, 기본 '08'=배회)가 아닌 것을 유형코드 오름차순으로
     * 반환한다. 이름이 없는 유형은 라벨을 유형코드로 폴백한다(구 구현이 카테고리명행 부재 시
     * categoryKey 로 폴백하던 것과 같은 관례).
     *
     * <p>결과는 장수명 캐시에 담기므로, 설정 변경/신규 자동등록 시 캐시가 비워져 즉시 반영된다.
     */
    @Cacheable(value = CacheConfig.CACHE_EVENT_TYPE, key = "'filterOptions'")
    public List<EventTypeResponse> filterOptions() {
        Set<String> excluded = excludedClassCodesFailSafe();

        List<LsEvntType> collected = new ArrayList<>();
        for (LsEvntType type : evntTypeRepository.findAll()) {
            if (type.getEvntTypeCd() == null || !type.isCollected() || isExcluded(type, excluded)) {
                continue;
            }
            collected.add(type);
        }
        collected.sort(Comparator.comparing(LsEvntType::getEvntTypeCd));

        Map<String, String> categoryNames = loadCategoryNames();
        List<EventTypeResponse> options = new ArrayList<>(collected.size());
        for (LsEvntType type : collected) {
            String code = type.getEvntTypeCd();
            options.add(EventTypeResponse.from(code, labelOf(type, categoryNames), List.of(code)));
        }
        return options;
    }

    /**
     * 등록된 전체 유형(수집/비수집 무관)의 코드 → 이벤트명 맵. 이름이 없는 유형은 맵에 넣지 않는다
     * ({@link #resolveLabel} 가 원문 폴백을 책임진다). 코드 오름차순(LinkedHashMap).
     */
    @Cacheable(value = CacheConfig.CACHE_EVENT_TYPE, key = "'codeLabelMap'")
    public Map<String, String> codeLabelMap() {
        List<LsEvntType> all = new ArrayList<>(evntTypeRepository.findAll());
        all.removeIf(t -> t.getEvntTypeCd() == null);
        all.sort(Comparator.comparing(LsEvntType::getEvntTypeCd));

        Map<String, String> categoryNames = loadCategoryNames();
        Map<String, String> result = new LinkedHashMap<>();
        for (LsEvntType type : all) {
            // 표시명은 항상 해석 가능하다(최종 폴백=유형코드). 코드와 같은 값이면 "이름 없음"이므로
            //   맵에 넣지 않는다 — 소비측의 원문 폴백 계약과 결과가 같고 응답도 가벼워진다.
            String label = labelOf(type, categoryNames);
            if (label != null && !label.equals(type.getEvntTypeCd())) {
                result.put(type.getEvntTypeCd(), label);
            }
        }
        return result;
    }

    /**
     * 임의 이벤트 코드 1건의 라벨 해석. 미등록/이름없는 코드는 원문 코드를 폴백 반환한다(예외 금지).
     * null/blank 입력은 입력값을 그대로 반환한다(일관 폴백).
     */
    public String resolveLabel(String evntTypeCd) {
        if (evntTypeCd == null || evntTypeCd.isBlank()) {
            return evntTypeCd;
        }
        return labelMapViaProxy().getOrDefault(evntTypeCd, evntTypeCd);
    }

    /**
     * 등록된 <b>전체</b> 유형코드 집합(수집/비수집·제외 무관) — 등록 여부 판정의 단일 원천.
     *
     * <p>{@code @Cacheable} 로 캐시해 매 판정마다 재조회하지 않는다.
     */
    @Cacheable(value = CacheConfig.CACHE_EVENT_TYPE, key = "'registeredCodes'")
    public Set<String> registeredCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (LsEvntType type : evntTypeRepository.findAll()) {
            if (type.getEvntTypeCd() != null) {
                codes.add(type.getEvntTypeCd());
            }
        }
        return codes;
    }

    /**
     * 영상 EV-코드 → <b>필터/프리셋 키</b> 변환. 축이 유형이므로 <b>등록된 코드는 자기 자신</b>이
     * 키이고, 미등록/null/blank 는 빈 Optional 이다(fail-safe — 호출자가 "프리셋 미적용"으로 처리).
     *
     * <p>구 이름은 {@code categoryKeyOf} 였다. 이제 카테고리로 변환하지 않으므로 이름을 바꿨다 —
     * 동작이 바뀐 메서드에 옛 이름을 남기면 호출부가 옛 의미로 읽는다(이 저장소의 드리프트 사고 패턴).
     */
    public Optional<String> filterKeyOf(String evntTypeCd) {
        if (evntTypeCd == null || evntTypeCd.isBlank()) {
            return Optional.empty();
        }
        return registeredCodesViaProxy().contains(evntTypeCd) ? Optional.of(evntTypeCd) : Optional.empty();
    }

    /**
     * 필터 키 → 그 키가 실제로 매칭하는 <b>영상 EV-코드 집합</b> — {@link #filterKeyOf} 의 역방향.
     *
     * <p>목록 필터(영상 처리 현황 {@code GET /v1/videos?eventTypeCd=...})가 쓴다. 축이 유형이라
     * 결과는 항상 0~1개지만, 호출부는 <b>집합</b>을 DB {@code IN} 으로 넘겨 필터·집계를 전체 기준으로
     * 성립시키는 구조라 시그니처를 유지한다. 미등록/null/blank 는 <b>빈 집합</b>이며, 호출자는 이를
     * "매칭 0건"(예외 아님)으로 처리해야 한다.
     */
    public Set<String> codesForFilterKey(String filterKey) {
        return filterKeyOf(filterKey).map(Set::of).orElseGet(Set::of);
    }

    /**
     * 유효 필터 키 집합 — {@link #filterOptions()} 가 노출하는 유형코드 집합.
     *
     * <p>프리셋 {@code eventTypeCd} 검증에 사용한다(드롭다운에 노출되는 유형만 프리셋 매핑 허용).
     * {@code filterOptions()} 캐시를 경유하므로 별도 DB 재조회가 없다.
     */
    public Set<String> validFilterKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (EventTypeResponse option : filterOptionsViaProxy()) {
            keys.add(option.categoryKey());
        }
        return keys;
    }

    /**
     * 제외 판정 — 대분류가 <b>있고</b> 그 값이 제외 목록에 있을 때만 제외한다.
     *
     * <p>대분류 null(관제 미송신)은 제외하지 않는다(fail-open) — 클래스 javadoc § 참조.
     */
    private static boolean isExcluded(LsEvntType type, Set<String> excluded) {
        String clsf = trimToNull(type.getEvntClsfCd());
        return clsf != null && excluded.contains(clsf);
    }

    /**
     * 표시 라벨 — 4단 폴백. 판정은 {@link EventTypeDisplayNamePolicy} <b>한 곳</b>에만 있다.
     *
     * @param categoryNames (대분류+카테고리) → 카테고리명 인덱스
     */
    private static String labelOf(LsEvntType type, Map<String, String> categoryNames) {
        return EventTypeDisplayNamePolicy.resolve(type.getOptrIndctNm(), type.getEvntNm(),
                categoryNames.get(categoryJoinKeyOf(type)), type.getEvntTypeCd());
    }

    /** 카테고리 조인 키 — (대분류, 카테고리) 복합 PK 를 문자열 1개로 접는다. null 은 매칭 없음. */
    private static String categoryJoinKeyOf(LsEvntType type) {
        if (type.getEvntClsfCd() == null || type.getEvntCtgryCd() == null) {
            return null;
        }
        return type.getEvntClsfCd() + "\u001f" + type.getEvntCtgryCd();
    }

    /** 카테고리명 인덱스 1회 로드 — 유형마다 조회하면 N+1 이 된다(행수 10 규모). */
    private Map<String, String> loadCategoryNames() {
        Map<String, String> index = new LinkedHashMap<>();
        for (LsEvntCtgry ctgry : evntCtgryRepository.findAll()) {
            if (ctgry.getEvntClsfCd() == null || ctgry.getEvntCtgryCd() == null) {
                continue;
            }
            index.put(ctgry.getEvntClsfCd() + "\u001f" + ctgry.getEvntCtgryCd(),
                    ctgry.getEvntCtgryNm());
        }
        return index;
    }

    /**
     * 제외 대분류 코드 집합을 설정에서 읽는다. 설정 미시드·값 손상 등 조회 실패 시에는 예외를
     * 전파하지 않고 기본값('08')으로 폴백한다(fail-safe — 필터 드롭다운이 통째로 죽지 않게).
     */
    private Set<String> excludedClassCodesFailSafe() {
        try {
            return systemConfigService.getStringSet(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES);
        } catch (CustomException e) {
            // 설정값 원문은 싣지 않고, 예외 메시지도 제어문자 제거 후 기록한다(CWE-117).
            log.warn("[EventType] 제외코드 설정 조회 실패 — 기본값(08) 폴백: {}",
                    LogSanitizer.sanitize(e.getMessage()));
            return DEFAULT_EXCLUDED_CLASS_CODES;
        }
    }

    /**
     * {@code codeLabelMap()} 을 캐시 프록시({@link #self}) 경유로 호출해 @Cacheable 적중을 보장한다.
     * 컨테이너 밖(self==null)에서는 프록시/캐시가 없으므로 this 로 폴백한다.
     */
    private Map<String, String> labelMapViaProxy() {
        return (self != null ? self : this).codeLabelMap();
    }

    /** {@link #registeredCodes()} 를 캐시 프록시 경유로 호출(@Cacheable 적중). self==null 시 this 폴백. */
    private Set<String> registeredCodesViaProxy() {
        return (self != null ? self : this).registeredCodes();
    }

    /** {@link #filterOptions()} 를 캐시 프록시 경유로 호출(@Cacheable 적중). self==null 시 this 폴백. */
    private List<EventTypeResponse> filterOptionsViaProxy() {
        return (self != null ? self : this).filterOptions();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 카테고리명 인덱스를 외부(관리 서비스)가 재사용할 수 있게 노출 — 판정 복제를 막는다. */
    public Map<String, String> categoryNameIndex() {
        return loadCategoryNames();
    }
}
