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
import java.util.Collections;
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
 * 카테고리 → 유형으로 바뀐다: {@code categoryKey} 에는 이제 유형코드가 담긴다.
 *
 * <h3>★ 옵션은 <b>표시명 그룹</b> 단위다 (2026-08-05 · R3·R4·R5)</h3>
 * <p>관제가 유형별 이름({@code EVNT_NM})을 아직 보내지 않아 표시명이 <b>카테고리명</b>으로 폴백되면서
 * 드롭다운에 같은 이름이 여러 번(침수 3·교통사고 3·화재 2) 떴다. 그래서 <b>표시명이 같은 유형들을
 * 한 옵션으로 접는다</b> — {@code categoryKey}=그룹 <b>대표코드</b>(그룹 내 최소 유형코드),
 * {@code memberCodes}=그룹 전체 코드. 계산은 {@link #groupIndex()} 한 곳에서만 한다.
 * <ul>
 *   <li><b>파라미터 값은 여전히 코드</b>다 — 표시명 문자열을 필터 파라미터로 올리지 않는다
 *       ({@code LS_LABEL_PRESET.EVNT_TYPE_CD} 는 코드 컬럼이고, 표시명이 바뀌면 북마크·프리셋이 깨진다).</li>
 *   <li><b>비대표 코드로 들어온 기존 북마크</b>도 {@link #codesForFilterKey} 가 그룹 전체로 해석한다.</li>
 *   <li><b>영구 병합이 아니다</b> — 관제가 이름을 보내거나 운영자가 표시명을 지정하면 그 유형만
 *       자기 이름을 얻어 그룹이 <b>자동으로 쪼개진다</b>. 즉 "이름이 아직 없는 동안의 접기"다.</li>
 * </ul>
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
 *       아닌 것을 <b>표시명 그룹</b>으로 접어 대표코드 오름차순으로 반환한다.</li>
 *   <li><b>codeLabelMap()</b> — 등록된 전체 유형(수집/비수집 무관)의 코드 → <b>표시명</b> 맵.
 *       표시명이 코드와 같은(= 이름이 하나도 없는) 유형은 담지 않는다({@link #resolveLabel} 가
 *       원문 폴백을 책임진다).</li>
 *   <li><b>resolveLabel()</b> — 임의 코드 1건의 라벨 해석(미등록/이름없음은 원문 폴백).</li>
 *   <li><b>filterKeyOf() / codesForFilterKey() / validFilterKeys()</b> — 프리셋 매핑·목록 필터가
 *       쓰는 <b>키 ↔ 코드 변환 + 등록 여부 판정</b>. 노출 유형은 그룹 대표코드로 접히고, 그 밖의
 *       등록 코드는 자기 자신이 키다. "등록되지 않은 값은 매칭 0건" fail-safe 계약은 그대로다.</li>
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
 * 장수명 캐시한다. 무효화 트리거는 <b>셋</b>이다 — ① 제외 대분류 설정 변경
 * ({@code SystemConfigService.update}) ② <b>신규 유형 자동등록</b>({@link EventTypeAutoRegistrar})
 * ③ <b>관리 화면 표시명·수집여부 정정</b>({@link EventTypeAdminService}).
 *
 * <p>무효화는 {@link EventTypeCacheEvictor} 가 캐시를 <b>통째로 clear</b> 하므로 캐시 키를 새로 추가해도
 * 자동으로 대상에 포함된다(키별 evict 목록을 따로 관리하지 않는다 — 그룹 인덱스처럼 키가 늘어날 때
 * 무효화가 조용히 빠지는 사고를 구조적으로 막는다).
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
     * 필터 드롭다운용 <b>이벤트유형</b> 옵션 목록 — <b>표시명 그룹</b> 단위(대표코드 오름차순).
     *
     * <p>등록된 유형 중 수집대상({@code CLCT_YN='Y'})이고 제외 대분류(설정
     * {@link ConfigKeys#EVENT_EXCLUDED_CLASS_CODES}, 기본 '08'=배회)가 아닌 것을 <b>표시명으로 묶어</b>
     * 그룹당 1행을 반환한다({@code categoryKey}=대표코드, {@code memberCodes}=그룹 전체 코드).
     * 이름이 없는 유형은 라벨이 유형코드로 폴백되므로 서로 다른 코드끼리 뭉치지 않는다.
     *
     * <p>결과는 {@link #groupIndex()} 캐시에서 나오므로, 설정 변경/신규 자동등록/표시명 정정 시
     * 캐시가 비워져 즉시 반영된다.
     *
     * <p>[req: R3] 같은 표시명은 옵션 1건으로 접는다. [req: R4] 노출 값은 이름이 아니라 코드다.
     */
    public List<EventTypeResponse> filterOptions() {
        return groupIndexViaProxy().options();
    }

    /**
     * <b>표시명 그룹 인덱스</b> — 옵션·필터 키 판정이 공유하는 단일 계산 결과(장수명 캐시).
     *
     * <p>{@code findAll()} 1회 + 카테고리명 1회 로드로 전부 만든다(유형마다 조회하면 N+1). 그룹핑에
     * 쓰는 표시명은 {@link EventTypeDisplayNamePolicy} 가 해석한 값을 <b>그대로</b> 쓴다 — 폴백 규칙을
     * 여기서 다시 구현하면 화면·산출물이 조용히 갈라진다(이 저장소의 반복 결함 패턴).
     *
     * <p><b>public 인 이유</b>: {@code @Cacheable} AOP 프록시를 타야 하고, 내부 호출은
     * {@link #self} 프록시를 경유한다(자기호출은 캐시를 우회한다).
     *
     * <p>[req: R3] [req: R4] [req: R5]
     */
    @Cacheable(value = CacheConfig.CACHE_EVENT_TYPE, key = "'groupIndex'")
    public EventTypeGroupIndex groupIndex() {
        List<LsEvntType> all = loadTypesSortedByCode();
        if (all.isEmpty()) {
            return EventTypeGroupIndex.EMPTY;
        }
        return indexOf(buildGroupedOptions(all), registeredCodesOf(all));
    }

    /** 등록 유형(코드 non-null)을 <b>유형코드 오름차순</b>으로 1회 로드 — 대표코드 결정의 기준 순서. */
    private List<LsEvntType> loadTypesSortedByCode() {
        List<LsEvntType> all = new ArrayList<>();
        for (LsEvntType type : evntTypeRepository.findAll()) {
            if (type.getEvntTypeCd() != null) {
                all.add(type);
            }
        }
        all.sort(Comparator.comparing(LsEvntType::getEvntTypeCd));
        return all;
    }

    /** 등록된 전체 유형코드(수집/비수집·제외 무관) — 등록 여부 판정의 원천. */
    private static Set<String> registeredCodesOf(List<LsEvntType> all) {
        Set<String> codes = new LinkedHashSet<>(all.size());
        for (LsEvntType type : all) {
            codes.add(type.getEvntTypeCd());
        }
        return Collections.unmodifiableSet(codes);
    }

    /**
     * 노출 유형(수집대상 + 제외 대분류 아님)을 <b>표시명으로 묶어</b> 옵션을 만든다(대표코드 오름차순).
     *
     * <p>입력이 코드 오름차순이므로 각 그룹의 <b>첫 원소가 최소 코드</b> = 대표코드다(정렬 기반 —
     * 실행마다 흔들리면 프리셋 매핑이 조용히 어긋난다).
     */
    private List<EventTypeResponse> buildGroupedOptions(List<LsEvntType> sortedByCode) {
        Set<String> excluded = excludedClassCodesFailSafe();
        Map<String, String> categoryNames = loadCategoryNames();

        Map<String, List<String>> membersByGroupKey = new LinkedHashMap<>();
        Map<String, String> labelByGroupKey = new LinkedHashMap<>();
        for (LsEvntType type : sortedByCode) {
            if (!type.isCollected() || isExcluded(type, excluded)) {
                // 비수집·제외 유형은 그룹에 넣지 않는다 — 그룹 멤버가 되면 노출 옵션의 memberCodes 로
                //   새어 들어가 목록 필터·통계 합산에 비노출 코드가 섞인다.
                continue;
            }
            String code = type.getEvntTypeCd();
            String label = labelOf(type, categoryNames);
            String groupKey = groupKeyOf(label, code);
            membersByGroupKey.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(code);
            labelByGroupKey.putIfAbsent(groupKey, displayLabelOf(label, code));
        }

        List<EventTypeResponse> options = new ArrayList<>(membersByGroupKey.size());
        for (Map.Entry<String, List<String>> entry : membersByGroupKey.entrySet()) {
            List<String> members = entry.getValue();
            options.add(EventTypeResponse.from(members.get(0), labelByGroupKey.get(entry.getKey()), members));
        }
        options.sort(Comparator.comparing(EventTypeResponse::categoryKey));
        return options;
    }

    /**
     * 옵션 목록에서 역방향 조회 인덱스를 만든다 — 코드→대표코드, 대표코드→그룹 전체 코드.
     *
     * <p>결과는 전부 불변 컬렉션이다: 캐시에 담겨 여러 요청·스레드가 <b>같은 인스턴스</b>를 공유하므로
     * 호출자가 변형할 수 있으면 캐시가 오염된다.
     */
    private static EventTypeGroupIndex indexOf(List<EventTypeResponse> options, Set<String> registered) {
        Map<String, String> keyByCode = new LinkedHashMap<>();
        Map<String, Set<String>> codesByKey = new LinkedHashMap<>();
        for (EventTypeResponse option : options) {
            String representative = option.categoryKey();
            codesByKey.put(representative,
                    Collections.unmodifiableSet(new LinkedHashSet<>(option.memberCodes())));
            for (String member : option.memberCodes()) {
                keyByCode.put(member, representative);
            }
        }
        return new EventTypeGroupIndex(List.copyOf(options),
                Collections.unmodifiableMap(keyByCode),
                Collections.unmodifiableMap(codesByKey),
                registered);
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
     * <p>{@link #groupIndex()} 캐시에서 나오므로 매 판정마다 재조회하지 않는다.
     */
    public Set<String> registeredCodes() {
        return groupIndexViaProxy().registeredCodes();
    }

    /**
     * 영상 EV-코드 → <b>필터/프리셋 키</b> 변환 — 노출 유형은 <b>자기 그룹의 대표코드</b>로 접힌다.
     *
     * <p>[req: R5] 프리셋이 그룹 전체에 걸리는 근거가 이 접기다: 같은 표시명의 상세 코드들이 전부
     * 같은 대표코드로 변환되므로, 대표코드에 저장된 프리셋 1건이 그룹 내 모든 영상에 적용된다.
     * (V168 이 구 카테고리 프리셋을 "그 카테고리의 최소 코드"로 정정해 뒀고, 그 값이 곧 현재의
     * 그룹 대표코드다 — 즉 V168 이 <b>의도된 동작 축소</b>로 남겨둔 부분이 여기서 복구된다.)
     *
     * <p>노출 대상이 아닌 등록 코드(비수집·제외 대분류)는 그룹이 없으므로 <b>자기 자신</b>이 키다
     * (종전 동작 유지). 미등록/null/blank 는 빈 Optional 이다(fail-safe — 호출자가 "프리셋 미적용"으로
     * 처리). 입력값을 trim 하지 않는 것도 종전과 같다.
     *
     * <p>구 이름은 {@code categoryKeyOf} 였다. 이제 카테고리로 변환하지 않으므로 이름을 바꿨다 —
     * 동작이 바뀐 메서드에 옛 이름을 남기면 호출부가 옛 의미로 읽는다(이 저장소의 드리프트 사고 패턴).
     */
    public Optional<String> filterKeyOf(String evntTypeCd) {
        if (evntTypeCd == null || evntTypeCd.isBlank()) {
            return Optional.empty();
        }
        EventTypeGroupIndex index = groupIndexViaProxy();
        String representative = index.keyByCode().get(evntTypeCd);
        if (representative != null) {
            return Optional.of(representative);
        }
        return index.registeredCodes().contains(evntTypeCd) ? Optional.of(evntTypeCd) : Optional.empty();
    }

    /**
     * 필터 키 → 그 키가 실제로 매칭하는 <b>영상 EV-코드 집합</b> — {@link #filterKeyOf} 의 역방향.
     *
     * <p>목록 필터(영상 처리 현황 {@code GET /v1/videos?eventTypeCd=...})가 쓴다. 호출부는 이 집합을
     * DB {@code IN} 으로 넘겨 필터·집계를 전체 기준으로 성립시킨다.
     *
     * <p>[req: R4] <b>대표코드든 비대표 코드든</b> 같은 그룹 전체를 돌려준다 — 그룹 도입 이전에
     * 만들어진 북마크 URL({@code ?eventTypeCd=EV01000103})이 0건이 되면 안 된다(하위호환).
     * 미등록/null/blank 는 <b>빈 집합</b>이며, 호출자는 이를 "매칭 0건"(예외 아님)으로 처리해야 한다.
     */
    public Set<String> codesForFilterKey(String filterKey) {
        if (filterKey == null || filterKey.isBlank()) {
            return Set.of();
        }
        EventTypeGroupIndex index = groupIndexViaProxy();
        String representative = index.keyByCode().get(filterKey);
        if (representative != null) {
            return index.codesByKey().get(representative);
        }
        return index.registeredCodes().contains(filterKey) ? Set.of(filterKey) : Set.of();
    }

    /**
     * 유효 필터 키 집합 — {@link #filterOptions()} 가 노출하는 그룹의 <b>멤버 코드 전부</b>.
     *
     * <p>프리셋 {@code eventTypeCd} 검증에 사용한다(드롭다운에 노출되는 이벤트유형만 프리셋 매핑 허용).
     *
     * <p>★대표코드만 반환하면 <b>그룹 도입 이전에 저장된 프리셋</b>(비대표 코드 보유)이 검증에서
     * 탈락해 저장·수정이 400 이 된다. 노출 그룹에 속한 코드는 모두 유효 키로 인정한다.
     * {@link #groupIndex()} 캐시를 경유하므로 별도 DB 재조회가 없다.
     */
    public Set<String> validFilterKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (EventTypeResponse option : groupIndexViaProxy().options()) {
            keys.addAll(option.memberCodes());
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

    /**
     * <b>그룹 키</b> — 표시명을 trim 한 값. 표시명이 없거나 공백뿐이면 <b>코드 기반 고유 키</b>로
     * 폴백한다.
     *
     * <p>공백을 그대로 키로 쓰면 서로 다른 유형이 한 덩어리로 뭉친다(라벨은 코드로 폴백되는데
     * 그룹만 합쳐지는 모순). 실제로는 {@link #labelOf} 최종 폴백이 유형코드라 null/공백이 나오기
     * 어렵지만, 표시명 판정이 바뀌어도 뭉침 사고가 나지 않게 방어한다.
     */
    private static String groupKeyOf(String label, String code) {
        String trimmed = label == null ? null : label.trim();
        return (trimmed == null || trimmed.isEmpty()) ? "CODE" + code : trimmed;
    }

    /** 옵션에 노출할 라벨 — 표시명(trim). 없거나 공백뿐이면 유형코드(빈 라벨 금지). */
    private static String displayLabelOf(String label, String code) {
        String trimmed = label == null ? null : label.trim();
        return (trimmed == null || trimmed.isEmpty()) ? code : trimmed;
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

    /** {@link #groupIndex()} 를 캐시 프록시 경유로 호출(@Cacheable 적중). self==null 시 this 폴백. */
    private EventTypeGroupIndex groupIndexViaProxy() {
        return (self != null ? self : this).groupIndex();
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
