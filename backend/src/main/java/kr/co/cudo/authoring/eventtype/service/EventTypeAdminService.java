package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetResolution;
import kr.co.cudo.authoring.batch.policy.PresetResolutionStatus;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.eventtype.dto.EventTypeAdminResponse;
import kr.co.cudo.authoring.eventtype.dto.EventTypeUpdateRequest;
import kr.co.cudo.authoring.eventtype.dto.PresetLinkStatus;
import kr.co.cudo.authoring.eventtype.dto.PresetLinkStatusFilter;
import kr.co.cudo.authoring.eventtype.entity.LsEvntType;
import kr.co.cudo.authoring.eventtype.repository.LsEvntTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 이벤트유형 <b>관리</b> 서비스 — REVIEWER 가 자동등록된 유형의 <b>이름·수집여부를 정정</b>한다.
 *
 * <h3>왜 필요한가 (Critical — 이것이 없으면 자동등록 설계가 성립하지 않는다)</h3>
 * <p>자동등록은 <b>관제 칸</b>({@code EVNT_NM})만 갱신하고 <b>운영자 칸</b>({@code OPTR_INDCT_NM})은
 * 건드리지 않는다. 그 분리가 의미를 가지려면 <b>사람이 표시명을 정할 통로</b>가 있어야 하고, 이
 * 서비스가 그 통로다. 표시명을 <b>빈 문자열로 지우면</b> 관제 수신명으로 자연 복귀하므로 되돌릴 수
 * 없는 차단이 생기지 않는다.
 *
 * <h3>할 수 있는 것 / 없는 것</h3>
 * <ul>
 *   <li>가능 — <b>운영자 표시명</b>({@code OPTR_INDCT_NM}) 지정·해제(빈 문자열 = 관제값 복귀),
 *       수집여부({@code CLCT_YN}) 토글.</li>
 *   <li><b>불가 — 관제 수신명</b>({@code EVNT_NM}) 수정. 관제 칸이라 다음 인입이 덮어쓴다
 *       (응답에는 "관제 원본"으로 노출만 한다).</li>
 *   <li><b>불가 — 신규 생성</b>. 등록의 유일한 출처는 <b>인입 소비 시점 자동등록</b>이라는 확정
 *       설계와 배치되므로 생성 API 를 두지 않는다(화면에 없는 유형은 관제가 보낸 적이 없는 유형이다).</li>
 *   <li><b>불가 — 유형코드(PK)·분류코드 수정</b>. 전자는 영상이 참조하는 식별자이고 후자는 관제가
 *       보내는 사실이라 화면에서 지어내면 제외 필터 판정이 근거를 잃는다.</li>
 * </ul>
 *
 * <p><b>캐시</b>: 값이 <b>실제로 바뀐 경우에만</b> {@link EventTypeCacheEvictor#evictAfterCommit()}
 * 로 무효화한다(자동등록과 같은 규약 — 커밋 이후 evict).
 *
 * <p><b>보안</b>: 인가는 컨트롤러의 {@code @PreAuthorize("hasRole('REVIEWER')")} + SecurityConfig
 * {@code /v1/manage/**} 매처가 담당한다. 입력 검증은 DTO 의 Bean Validation(길이·공백·허용값)이
 * 1차, 이 서비스의 정규화가 2차다. 로그에는 <b>요청자 식별자(actor)와 유형코드</b>만 정제해 남긴다
 * (CWE-117) — <b>바뀐 값 자체는 싣지 않는다</b>(무엇이 바뀌었는지는 boolean 으로 충분하고, 값 원문을
 * 로그에 흘리면 관제 자유텍스트가 그대로 남는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class EventTypeAdminService {

    /** {@code EVNT_TYPE_CD} 컬럼 길이(코드V20) — 로그 절단 기준. */
    private static final int CODE_LOG_LIMIT = 20;

    /** actor(토큰 subject) 로그 절단 기준 — 사용자번호 규격을 넉넉히 덮는 길이. */
    private static final int ACTOR_LOG_LIMIT = 64;

    private final LsEvntTypeRepository repository;
    private final EventTypeCacheEvictor cacheEvictor;
    /** 카테고리명 인덱스는 조회 서비스가 소유한다 — 표시명 판정 원천을 하나로 유지한다. */
    private final EventTypeService eventTypeService;
    /**
     * 프리셋 연결 상태 판정의 <b>단일 진실원</b> — 배치(오토라벨 보류)와 같은 메서드를 쓴다.
     * 여기서 프리셋을 다시 조회해 실효성을 재유도하지 말 것(한쪽만 갱신되는 드리프트).
     */
    private final PresetLabelLookupService presetLabelLookupService;

    /**
     * 등록된 <b>전체</b> 이벤트유형을 유형코드 오름차순으로 반환한다.
     *
     * <p>필터 옵션과 달리 <b>비수집·제외 대분류도 포함</b>한다 — 관리 화면은 "왜 안 보이는지"를 보고
     * 고치는 화면이라 걸러 내보내면 고칠 대상이 화면에서 사라진다.
     *
     * <p>페이징을 두지 않는 이유: 이 테이블은 <b>코드 체계</b>라 행수가 수십 규모이고, 조회는
     * REVIEWER 전용 관리 화면 1곳뿐이다. 인입 자동등록으로 예상 밖 증가가 관측되면 그때 페이징을
     * 도입한다(현재는 무제한 조회 금지 규칙의 취지인 "대용량 전량 스캔"에 해당하지 않는다).
     */
    public List<EventTypeAdminResponse> list() {
        return list(null);
    }

    /**
     * 등록된 이벤트유형을 유형코드 오름차순으로 반환하되, <b>프리셋 연결 상태</b>를 함께 싣고
     * 필요하면 그 상태로 거른다. [@design API-185] [@design AC-116]
     *
     * <h3>연결 상태는 파생값이다</h3>
     * <p>저장하지 않고 응답 시점에 {@link PresetLabelLookupService#resolve(String)} 결과에서 파생한다.
     * 그래서 프리셋을 등록하면 <b>다음 조회부터 곧바로</b> 반영된다. 캐시({@code CACHE_EVENT_TYPE})에
     * 실으면 프리셋을 등록해도 최대 캐시 수명만큼 옛 상태가 보이므로 <b>싣지 않는다</b>.
     *
     * <h3>★그룹 축 — 프리셋은 대표코드에 걸린다</h3>
     * <p>표시명이 같은 유형들은 한 그룹으로 접히고 프리셋은 그 그룹의 <b>대표코드</b>에 걸린다. 그래서
     * 판정 키는 유형코드가 아니라 {@link EventTypeService#filterKeyOf(String)} 가 돌려주는 대표코드다 —
     * 코드 단위로 직접 조회하면 같은 그룹의 <b>비대표 유형이 전부 미연결로</b> 잘못 표시된다.
     *
     * <h3>N+1 회피 — 그룹당 1회만 해석한다</h3>
     * <p>같은 그룹의 유형들은 대표코드가 같으므로 해석 결과도 같다. 따라서 <b>대표코드로 메모이즈</b>해
     * 그룹당 한 번만 {@code resolve} 를 호출한다(유형 수가 아니라 그룹 수만큼). 이것은 <b>판정 자체를
     * 배치화한 것이 아니라 같은 입력의 재호출을 접은 것</b>이라 단일 진실원을 깨지 않는다.
     * {@code resolve} 는 다건 입력을 받지 않으므로 그룹 수만큼의 조회는 남는다 — 이 테이블은 코드
     * 체계라 행수가 수십 규모이고 조회처가 REVIEWER 관리 화면 1곳뿐이라 수용한다.
     *
     * @param filter 연결 상태 거르기. <b>null(미지정)이면 전체</b> — 서버 기본값을 두지 않는다
     *               (기존 호출의 결과·정렬 계약 불변)
     */
    public List<EventTypeAdminResponse> list(PresetLinkStatusFilter filter) {
        Map<String, String> categoryNames = eventTypeService.categoryNameIndex();
        // 그룹 대표코드 → 해석 결과. 요청 단위 메모이즈라 캐시가 아니다(다음 요청은 다시 판정한다).
        Map<String, PresetResolution> resolutionByGroupKey = new HashMap<>();

        List<LsEvntType> types = new ArrayList<>(repository.findAll());
        types.sort(Comparator.comparing(LsEvntType::getEvntTypeCd,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<EventTypeAdminResponse> rows = new ArrayList<>(types.size());
        for (LsEvntType type : types) {
            PresetResolution resolution = resolveForGroup(type.getEvntTypeCd(), resolutionByGroupKey);
            if (filter != null && !filter.matches(resolution)) {
                continue;
            }
            rows.add(EventTypeAdminResponse.from(type, categoryNameOf(type, categoryNames),
                    PresetLinkStatus.from(resolution.status())));
        }
        return List.copyOf(rows);
    }

    /**
     * 그 유형이 속한 <b>그룹 대표코드</b> 기준으로 프리셋을 해석한다(그룹당 1회 메모이즈).
     *
     * <p>{@code filterKeyOf} 가 비면 등록되지 않은 코드라는 뜻이므로 코드 자체를 키로 넘긴다 —
     * {@code resolve} 가 같은 판정을 다시 해 {@link PresetResolutionStatus#EVENT_TYPE_UNREGISTERED}
     * 를 돌려준다(여기서 사유를 지어내지 않는다). 이 목록은 등록된 유형만 담으므로 정상적으로는
     * 도달하지 않고, 그룹 인덱스 캐시와 마스터 조회 사이의 좁은 경합 창에서만 나온다.
     */
    private PresetResolution resolveForGroup(String evntTypeCd,
                                             Map<String, PresetResolution> memo) {
        if (evntTypeCd == null || evntTypeCd.isBlank()) {
            // PK 라 정상 데이터에는 없다. 메모 키로 쓸 수 없으므로 접지 않고 그대로 판정한다.
            return presetLabelLookupService.resolve(evntTypeCd);
        }
        String groupKey = eventTypeService.filterKeyOf(evntTypeCd).orElse(evntTypeCd);
        return memo.computeIfAbsent(groupKey, presetLabelLookupService::resolve);
    }

    /**
     * 요청자 식별자(감사 로그용) — {@code TokenClaims.sub()}.
     *
     * <p><b>왜 필요한가</b>: "무엇이 바뀌었나"만 남기면 운영 사고 시("누가 표시명을 이상하게
     * 바꿨나") 로그만으로 역추적이 불가능하다.
     *
     * <p><b>왜 로그까지만인가</b>: 이 변경은 <b>PII 축이 아니고 완전히 되돌릴 수 있다</b>(표시명을
     * 비우면 관제값으로 복귀, 수집여부는 재토글). 영구 DB 감사({@code LS_TASK_EVNT_LOG})는 그
     * 수준의 비가역성·민감도를 가진 변경에만 쓴다 — 여기에 붙이면 과잉이다.
     *
     * <p>인증 컨텍스트가 없으면(내부 호출·테스트) {@code unknown} 을 남긴다 — 로그 때문에 기능이
     * 실패하면 안 된다. 값은 {@link LogSanitizer} 를 태운다(CWE-117 — 토큰 subject 도 외부 입력이다).
     */
    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TokenClaims claims) {
            return LogSanitizer.sanitize(claims.sub(), ACTOR_LOG_LIMIT);
        }
        return "unknown";
    }

    /** 카테고리명 조회 — 조인 키 조합 규칙은 {@link EventTypeService} 와 동일해야 한다. */
    private static String categoryNameOf(LsEvntType type, Map<String, String> categoryNames) {
        if (type.getEvntClsfCd() == null || type.getEvntCtgryCd() == null) {
            return null;
        }
        return categoryNames.get(type.getEvntClsfCd() + "\u001f" + type.getEvntCtgryCd());
    }

    /**
     * 이벤트유형 1건을 부분 수정한다.
     *
     * @param evntTypeCd 대상 유형코드(PK)
     * @param request    수정 요청 — null 필드는 "바꾸지 않음"
     * @return 수정 후 상태
     * @throws CustomException 대상 미존재(404) · 수정 항목 없음(400)
     */
    @Transactional("controlTransactionManager")
    public EventTypeAdminResponse update(String evntTypeCd, EventTypeUpdateRequest request) {
        if (request == null || request.isEmpty()) {
            // 아무것도 안 바꾸는 요청은 오작동 신호다 — 조용히 200 을 주면 화면이 "저장됐다"고 믿는다.
            throw new CustomException(ErrorCode.INVALID_INPUT, "수정할 항목이 없습니다.");
        }
        LsEvntType type = repository.findById(evntTypeCd)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "이벤트유형을 찾을 수 없습니다."));

        boolean changed = type.applyManagement(request.optrIndctNm(), request.clctYn());
        if (changed) {
            // 변경이 있을 때만 캐시를 비운다(장수명 캐시 무력화 방지). 커밋 이후에 비워지도록 위임 —
            //   여기서 즉시 비우면 다른 요청이 미커밋 상태를 읽어 옛 값으로 캐시를 다시 채운다.
            cacheEvictor.evictAfterCommit();
            log.info("[EventType] 이벤트유형 정정 actor={} code={} indctNmChanged={} clctChanged={}",
                    currentActor(), LogSanitizer.sanitize(evntTypeCd, CODE_LOG_LIMIT),
                    request.optrIndctNm() != null, request.clctYn() != null);
        }
        return EventTypeAdminResponse.from(type,
                categoryNameOf(type, eventTypeService.categoryNameIndex()));
    }

}
