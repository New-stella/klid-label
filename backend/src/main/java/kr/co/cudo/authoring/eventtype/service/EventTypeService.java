package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.eventtype.dto.EventTypeResponse;
import kr.co.cudo.authoring.eventtype.repository.MngExEvntTypeMapRepository;
import kr.co.cudo.authoring.eventtype.repository.MngExEvntTypeRepository;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.entity.MngExEvntType;
import kr.co.cudo.authoring.video.entity.MngExEvntTypeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 이벤트 타입 라벨 매핑·필터 옵션 제공 서비스 (Phase 2).
 *
 * <p>관제 마스터(MNG_EX_EVNT_TYPE) + 매핑(MNG_EX_EVNT_TYPE_MAP, READ 전용)을 토대로
 * <ol>
 *   <li><b>filterOptions()</b> — 수집대상(CLCT_YN='Y')이고 제외 대분류가 아닌 코드를
 *       카테고리(EVNT_CLS_CD+EVNT_CTGRY_CD)로 dedup 한 필터 옵션 목록. 제외 대분류 집합은
 *       시스템 설정({@code eventtype.excluded-class-codes}, 기본 '08'=배회)에서 읽으므로
 *       REVIEWER 가 배포 없이 조정할 수 있다.</li>
 *   <li><b>codeLabelMap()</b> — 전체 코드(수집/비수집 무관)를 한글 라벨로 해석한 코드→라벨 맵.</li>
 *   <li><b>resolveLabel()</b> — 임의 코드 1건의 라벨 해석(미등록/라벨없음은 원문 폴백).</li>
 * </ol>
 *
 * <p><b>N+1 회피</b>: 카테고리 라벨을 코드마다 {@code findCategoryLabel} 로 조회하면 N회 쿼리가
 * 된다. 대신 {@code findByCdType('02')} 로 카테고리명행을 1회 로드해 (cls+ctgry)→EVNT_NM
 * in-memory 맵으로 매칭한다.
 *
 * <p><b>캐시</b>: 두 조회 모두 near-immutable(관제 코드 체계) 이므로 인자 없는 단순 키로
 * {@link CacheConfig#CACHE_EVENT_TYPE} 에 장수명 캐시한다. 유일한 무효화 트리거는 제외 대분류 코드
 * 설정 변경({@code SystemConfigService.update})이다.
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

    private final MngExEvntTypeRepository evntTypeRepository;
    private final MngExEvntTypeMapRepository evntTypeMapRepository;
    private final SystemConfigService systemConfigService;

    /**
     * 자기 자신(캐시 프록시) 주입 — {@link #resolveLabel} 의 {@code codeLabelMap()} 호출이
     * {@code @Cacheable} AOP 프록시를 경유하도록 한다. {@code this.codeLabelMap()} 직접 호출은
     * self-invocation 이라 프록시를 우회해 캐시가 적중하지 않고 매 호출마다 {@code findAll()} +
     * {@code findByCdType('02')} 2쿼리를 재실행한다(Phase 3 StatsService 가 집계 항목마다 resolveLabel
     * 을 부르면 N×2 쿼리 폭증). 순환 주입이므로 {@code @Lazy} 로 끊는다. 컨테이너 밖(순수 단위 테스트)
     * 에서는 null 이며 이 경우 프록시/캐시가 없으므로 {@code this} 로 폴백한다.
     */
    @Autowired
    @Lazy
    private EventTypeService self;

    /**
     * 필터 드롭다운용 카테고리 옵션 목록.
     *
     * <p>수집대상(CLCT_YN='Y') 중 제외 대분류(설정 {@link ConfigKeys#EVENT_EXCLUDED_CLASS_CODES},
     * 기본 '08'=배회)를 제외하고 (cls, ctgry)로 dedup 한다. 정렬은 categoryKey 오름차순으로
     * 안정적이며, 카테고리 라벨행이 없으면 categoryKey 로 폴백한다.
     *
     * <p>결과는 장수명 캐시에 담기므로, 설정 변경 시 {@code SystemConfigService.update} 가
     * {@link CacheConfig#CACHE_EVENT_TYPE} 를 함께 무효화해 즉시 반영된다.
     */
    @Cacheable(value = CacheConfig.CACHE_EVENT_TYPE, key = "'filterOptions'")
    public List<EventTypeResponse> filterOptions() {
        Map<String, String> categoryLabels = loadCategoryLabels();
        Set<String> excluded = excludedClassCodesFailSafe();

        // categoryKey -> 소속 코드(정렬). TreeMap 으로 categoryKey 오름차순 안정.
        Map<String, List<String>> grouped = new java.util.TreeMap<>();
        for (MngExEvntType type : evntTypeRepository.findByClctYn("Y")) {
            if (excluded.contains(type.getEvntClsCd())) {
                continue;
            }
            String categoryKey = categoryKey(type);
            grouped.computeIfAbsent(categoryKey, k -> new ArrayList<>()).add(type.getEvntTypeCd());
        }

        List<EventTypeResponse> options = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            String categoryKey = entry.getKey();
            List<String> memberCodes = entry.getValue();
            memberCodes.sort(java.util.Comparator.naturalOrder());
            String label = categoryLabels.getOrDefault(categoryKey, categoryKey);
            options.add(EventTypeResponse.from(categoryKey, label, memberCodes));
        }
        return options;
    }

    /**
     * 전체 코드(수집/비수집 무관)의 코드→한글라벨 맵. 라벨 미존재 코드는 맵에 넣지 않는다
     * ({@link #resolveLabel} 가 원문 폴백을 책임진다). 입력 순서 보존(LinkedHashMap).
     */
    @Cacheable(value = CacheConfig.CACHE_EVENT_TYPE, key = "'codeLabelMap'")
    public Map<String, String> codeLabelMap() {
        Map<String, String> categoryLabels = loadCategoryLabels();

        Map<String, String> result = new LinkedHashMap<>();
        for (MngExEvntType type : evntTypeRepository.findAll()) {
            String label = categoryLabels.get(categoryKey(type));
            if (label != null) {
                result.put(type.getEvntTypeCd(), label);
            }
        }
        return result;
    }

    /**
     * 임의 이벤트 코드 1건의 라벨 해석. 관제 미등록/라벨없는 코드는 원문 코드를 폴백 반환한다
     * (예외 금지). null/blank 입력은 입력값을 그대로 반환한다(일관 폴백).
     */
    public String resolveLabel(String evntTypeCd) {
        if (evntTypeCd == null || evntTypeCd.isBlank()) {
            return evntTypeCd;
        }
        return labelMapViaProxy().getOrDefault(evntTypeCd, evntTypeCd);
    }

    /**
     * 영상 상세 EV-코드 → 카테고리 키 역인덱스 (Phase 4a).
     *
     * <p>관제 마스터 전체({@code findAll}) 1회 로드로 (EV-코드 → EVNT_CLS_CD+EVNT_CTGRY_CD) 맵을
     * 구성한다. 수집/비수집·ignore 무관 모든 등록 코드를 담아 영상 EV-코드의 카테고리 도출에 쓴다
     * (프리셋은 categoryKey 로 저장되므로 매칭 전 변환이 필요). {@code @Cacheable} 로 역인덱스를
     * 캐시해 매 변환마다 재구성·재조회하지 않는다(near-immutable 관제 코드 체계).
     */
    @Cacheable(value = CacheConfig.CACHE_EVENT_TYPE, key = "'codeToCategoryKey'")
    public Map<String, String> codeToCategoryKey() {
        Map<String, String> index = new HashMap<>();
        for (MngExEvntType type : evntTypeRepository.findAll()) {
            index.put(type.getEvntTypeCd(), categoryKey(type));
        }
        return index;
    }

    /**
     * 영상 상세 EV-코드(예 {@code EV03000102}) → 카테고리 키(예 {@code "030001"}) 변환.
     *
     * <p>프리셋은 categoryKey 단위로 저장되므로, 영상의 상세 EV-코드를 카테고리 키로 변환한 뒤
     * 프리셋 매칭에 사용한다. 관제 미등록 코드/null/blank 는 빈 Optional 을 반환한다(fail-safe —
     * 호출자가 프리셋 미적용으로 처리). 역인덱스는 {@code @Cacheable} 캐시를 경유한다.
     */
    public Optional<String> categoryKeyOf(String evntTypeCd) {
        if (evntTypeCd == null || evntTypeCd.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(codeToCategoryKeyViaProxy().get(evntTypeCd));
    }

    /**
     * 카테고리 키(예 {@code "010001"}) → 그 카테고리에 속한 <b>EV-코드 집합</b> — {@link #categoryKeyOf}
     * 의 역방향.
     *
     * <p>목록 필터(영상 처리 현황 {@code GET /v1/videos?eventTypeCd=...})가 쓴다. FE 는 카테고리 키를
     * 보내는데 영상이 보유한 값은 상세 EV-코드({@code LS_DATA_RAW.EVNT_TYPE_CD})라, 코드마다 Java 로
     * 변환해 비교하면 <b>페이징 후에만</b> 걸러져 건수·페이지가 어긋난다. 카테고리를 EV-코드 집합으로
     * 펼쳐 DB {@code IN} 으로 넘기면 필터·집계가 모두 전체 기준으로 성립한다.
     *
     * <p>축은 {@link #categoryKeyOf} 와 <b>같은 역인덱스</b>({@code codeToCategoryKey}, @Cacheable)다 —
     * 별도 조회를 두면 두 축이 갈라진다. 미등록/null/blank 카테고리 키는 <b>빈 집합</b>을 반환하며,
     * 호출자는 이를 "매칭 0건"(예외 아님)으로 처리해야 한다(fail-safe — {@link #categoryKeyOf} 와 동일).
     */
    public Set<String> codesForCategoryKey(String categoryKey) {
        if (categoryKey == null || categoryKey.isBlank()) {
            return Set.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : codeToCategoryKeyViaProxy().entrySet()) {
            if (categoryKey.equals(entry.getValue())) {
                codes.add(entry.getKey());
            }
        }
        return codes;
    }

    /**
     * 유효 카테고리 키 집합 — {@link #filterOptions()} 의 categoryKey 집합(수집대상·non-ignore 9종).
     *
     * <p>프리셋 {@code eventTypeCd} 검증에 사용한다(드롭다운에 노출되는 카테고리만 프리셋 매핑 허용).
     * {@code filterOptions()} 캐시를 경유하므로 별도 DB 재조회가 없다.
     */
    public Set<String> validCategoryKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (EventTypeResponse option : filterOptionsViaProxy()) {
            keys.add(option.categoryKey());
        }
        return keys;
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

    /** {@link #codeToCategoryKey()} 를 캐시 프록시 경유로 호출(@Cacheable 적중). self==null 시 this 폴백. */
    private Map<String, String> codeToCategoryKeyViaProxy() {
        return (self != null ? self : this).codeToCategoryKey();
    }

    /** {@link #filterOptions()} 를 캐시 프록시 경유로 호출(@Cacheable 적중). self==null 시 this 폴백. */
    private List<EventTypeResponse> filterOptionsViaProxy() {
        return (self != null ? self : this).filterOptions();
    }

    /**
     * 카테고리명행(CD_TYPE='02')을 1회 로드해 (cls+ctgry)→EVNT_NM 맵으로 구성한다.
     *
     * <p>CD_TYPE='02' 에는 카테고리명행 외에 동일 (cls, ctgry) 의 상세행(DTL_EVNT/EVNT_TYPE_CD 채워짐)이
     * 섞일 수 있다({@code MngExEvntTypeMapRepository.findCategoryLabel} 가 5컬럼을 고정하는 이유와 동일).
     * 카테고리 라벨은 DTL_EVNT='' AND EVNT_TYPE_CD='' 인 카테고리명행에서만 도출해야 하므로 in-memory
     * 에서도 동일 조건으로 걸러 상세행이 라벨을 덮어쓰지 않게 한다.
     */
    private Map<String, String> loadCategoryLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (MngExEvntTypeMap row : evntTypeMapRepository.findByCdType("02")) {
            if (!isCategoryNameRow(row)) {
                continue;
            }
            labels.put(row.getEvntClsCd() + row.getEvntCtgryCd(), row.getEvntNm());
        }
        return labels;
    }

    /** 카테고리명행 여부 — DTL_EVNT 와 EVNT_TYPE_CD 가 모두 빈 값(null/공백)인 행. */
    private static boolean isCategoryNameRow(MngExEvntTypeMap row) {
        return isBlank(row.getDtlEvnt()) && isBlank(row.getEvntTypeCd());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    private static String categoryKey(MngExEvntType type) {
        return type.getEvntClsCd() + type.getEvntCtgryCd();
    }
}
