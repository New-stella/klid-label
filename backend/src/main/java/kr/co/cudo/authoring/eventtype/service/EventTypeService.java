package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.eventtype.dto.EventTypeResponse;
import kr.co.cudo.authoring.eventtype.repository.MngExEvntTypeMapRepository;
import kr.co.cudo.authoring.eventtype.repository.MngExEvntTypeRepository;
import kr.co.cudo.authoring.video.entity.MngExEvntType;
import kr.co.cudo.authoring.video.entity.MngExEvntTypeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 이벤트 타입 라벨 매핑·필터 옵션 제공 서비스 (Phase 2).
 *
 * <p>관제 마스터(MNG_EX_EVNT_TYPE) + 매핑(MNG_EX_EVNT_TYPE_MAP, READ 전용)을 토대로
 * <ol>
 *   <li><b>filterOptions()</b> — 수집대상(CLCT_YN='Y')이고 ignore 대분류('08')가 아닌 코드를
 *       카테고리(EVNT_CLS_CD+EVNT_CTGRY_CD)로 dedup 한 필터 옵션 목록.</li>
 *   <li><b>codeLabelMap()</b> — 전체 코드(수집/비수집 무관)를 한글 라벨로 해석한 코드→라벨 맵.</li>
 *   <li><b>resolveLabel()</b> — 임의 코드 1건의 라벨 해석(미등록/라벨없음은 원문 폴백).</li>
 * </ol>
 *
 * <p><b>N+1 회피</b>: 카테고리 라벨을 코드마다 {@code findCategoryLabel} 로 조회하면 N회 쿼리가
 * 된다. 대신 {@code findByCdType('02')} 로 카테고리명행을 1회 로드해 (cls+ctgry)→EVNT_NM
 * in-memory 맵으로 매칭한다.
 *
 * <p><b>캐시</b>: 두 조회 모두 near-immutable(관제 코드 체계) 이므로 인자 없는 단순 키로
 * {@link CacheConfig#CACHE_EVENT_TYPE} 에 장수명 캐시한다. 별도 무효화는 두지 않는다(앱 수명/TTL).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventTypeService {

    /** ignore 처리 대분류 — 배회(EV08*). 필터 옵션에서 제외한다. */
    private static final String IGNORE_CLASS_CD = "08";

    private final MngExEvntTypeRepository evntTypeRepository;
    private final MngExEvntTypeMapRepository evntTypeMapRepository;

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
     * <p>수집대상(CLCT_YN='Y') 중 ignore 대분류('08')를 제외하고 (cls, ctgry)로 dedup 한다.
     * 정렬은 categoryKey 오름차순으로 안정적이며, 카테고리 라벨행이 없으면 categoryKey 로 폴백한다.
     */
    @Cacheable(value = CacheConfig.CACHE_EVENT_TYPE, key = "'filterOptions'")
    public List<EventTypeResponse> filterOptions() {
        Map<String, String> categoryLabels = loadCategoryLabels();

        // categoryKey -> 소속 코드(정렬). TreeMap 으로 categoryKey 오름차순 안정.
        Map<String, List<String>> grouped = new java.util.TreeMap<>();
        for (MngExEvntType type : evntTypeRepository.findByClctYn("Y")) {
            if (IGNORE_CLASS_CD.equals(type.getEvntClsCd())) {
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
     * {@code codeLabelMap()} 을 캐시 프록시({@link #self}) 경유로 호출해 @Cacheable 적중을 보장한다.
     * 컨테이너 밖(self==null)에서는 프록시/캐시가 없으므로 this 로 폴백한다.
     */
    private Map<String, String> labelMapViaProxy() {
        return (self != null ? self : this).codeLabelMap();
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
