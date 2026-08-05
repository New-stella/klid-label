package kr.co.cudo.authoring.assignment.dto;

import kr.co.cudo.authoring.assignment.domain.AssignmentWorkStatus;
import kr.co.cudo.authoring.common.util.ControlCharNormalizer;

import java.util.Optional;
import java.util.Set;

/**
 * 배정 목록({@code GET /v1/assignments}) 서버 검색 조건.
 *
 * <p><b>인가 축과 필터 축을 분리한다 (R6 / CWE-639 IDOR)</b> — 두 축이 한 필드에 섞이면
 * "요청 파라미터가 인가 범위를 결정"하게 되어, 필터 조합 하나만 새 조건으로 추가돼도 타인 배정이
 * 노출된다.
 * <ul>
 *   <li>{@code selfUserNo} — <b>인가</b>. WORKER 요청이면 서비스가 토큰 subject 로 채운다.
 *       non-null 이면 이 값 하나로 조회 대상이 고정된다.</li>
 *   <li>{@code workerIdFilter} — <b>필터</b>. REVIEWER 가 특정 작업자를 골라 보는 용도이며
 *       WORKER 요청에서는 존재 자체가 지워진다(아래 compact 생성자).</li>
 * </ul>
 * 두 값의 OR/폴백 조합은 어디에도 두지 않는다 — 리포지토리는 {@code selfUserNo} 가 있으면
 * {@code workerIdFilter} 를 <b>보지 않는다</b>.
 *
 * <p><b>하위호환</b>: 신규 필터가 하나도 오지 않으면(모두 null) 조건이 걸리지 않아 변경 전과 동일한
 * 결과 집합을 반환한다. 이를 위해 빈 문자열·공백만 입력을 null 로 정규화한다 — {@code q=""} 가
 * LIKE 조건으로 살아나면 기본 호출 결과가 달라진다.
 *
 * <p><b>정규화 규칙의 단일 원천은 {@link ControlCharNormalizer}</b> 다 — 이벤트유형 옵션 조회/비교가
 * 쓰는 SQL 표현식({@code ControlCharNormalizer#normalizedAsJava})과 같은 규칙이라야 "옵션에서 고른
 * 값을 그대로 필터로 되돌려 보내면 매칭된다" 가 데이터와 무관하게 성립한다. 여기에 규칙을 복제하지 말 것.
 *
 * <p><b>{@code eventTypeCds} — 이벤트유형 그룹 확장 결과 (R6)</b>: 셀렉트 옵션이 <b>표시명 그룹
 * 대표코드</b>로 접히므로 필터도 그룹 전체 코드를 봐야 한다. 확장 판정은 마스터(캐시) 조회가 필요해
 * 조건 객체가 스스로 할 수 없으므로 <b>서비스의 인가 단일 지점</b>({@code scopeForActor})에서 채우고
 * ({@link #withEventTypeGroup}) 리포지토리는 {@link #eventTypeMatchCodes()} 만 본다. 확장이 없는
 * 조건은 종전과 같이 입력 코드 1건으로 매칭된다.
 *
 * @param eventTypeCds 확장된 그룹 코드 집합. 비어 있으면 확장 없음(= 입력 코드 단건 매칭)
 */
public record AssignmentSearchCondition(
        Long selfUserNo,
        Long workerIdFilter,
        String q,
        String workStatus,
        String eventTypeCd,
        Set<String> eventTypeCds
) {

    public AssignmentSearchCondition {
        // 인가 축이 지정되면 필터 축을 물리적으로 제거한다 — 호출자가 실수로 둘 다 넘겨도
        // 필터 값이 조건 객체 밖으로 나가지 못한다(방어를 리포지토리 구현에만 의존하지 않는다).
        if (selfUserNo != null) {
            workerIdFilter = null;
        }
        q = ControlCharNormalizer.normalizeOrNull(q);
        workStatus = ControlCharNormalizer.normalizeOrNull(workStatus);
        eventTypeCd = ControlCharNormalizer.normalizeOrNull(eventTypeCd);
        // 확장은 입력 코드에 종속된 파생값이다 — 입력이 없으면 확장도 버린다. 불변 복사로 담는다.
        eventTypeCds = (eventTypeCd == null || eventTypeCds == null)
                ? Set.of() : Set.copyOf(eventTypeCds);
    }

    /** 그룹 확장 없이 만드는 조건 — 기존 시그니처(하위호환). */
    public AssignmentSearchCondition(Long selfUserNo, Long workerIdFilter, String q,
                                     String workStatus, String eventTypeCd) {
        this(selfUserNo, workerIdFilter, q, workStatus, eventTypeCd, Set.of());
    }

    /** 필터 없는 기본 조건 (파라미터를 하나도 보내지 않은 기존 호출). */
    public static AssignmentSearchCondition none() {
        return new AssignmentSearchCondition(null, null, null, null, null);
    }

    /** 컨트롤러 입력(전부 optional)으로부터 조건을 만든다 — 인가 축은 서비스가 채운다. */
    public static AssignmentSearchCondition ofRequest(Long workerIdFilter, String q,
                                                      String workStatus, String eventTypeCd) {
        return new AssignmentSearchCondition(null, workerIdFilter, q, workStatus, eventTypeCd);
    }

    /**
     * 이벤트유형 <b>그룹 확장 결과</b>를 채운 조건을 만든다 (R6).
     *
     * <p>인가 축({@code selfUserNo}/{@code workerIdFilter})은 그대로 보존한다 — 이 메서드는 필터 축만
     * 다룬다(인가 판정을 여기로 옮기지 말 것).
     */
    public AssignmentSearchCondition withEventTypeGroup(Set<String> codes) {
        return new AssignmentSearchCondition(selfUserNo, workerIdFilter, q, workStatus, eventTypeCd, codes);
    }

    /**
     * 이벤트유형 필터가 실제로 매칭할 코드 집합 — <b>리포지토리는 이 값만 본다</b>.
     * 확장이 채워졌으면 그룹 전체, 아니면 입력 코드 1건. 필터 미적용이면 빈 집합이다.
     */
    public Set<String> eventTypeMatchCodes() {
        if (eventTypeCd == null) {
            return Set.of();
        }
        return eventTypeCds.isEmpty() ? Set.of(eventTypeCd) : eventTypeCds;
    }

    /**
     * 조회 범위를 본인으로 고정한다 (WORKER). 요청이 보낸 {@code workerIdFilter} 는 <b>읽지 않고</b>
     * 버린다 — 값을 참조하는 순간 "타인 지정 시 어떻게 할지"라는 분기가 생기고, 그 분기가 IDOR 의
     * 입구가 된다.
     */
    public AssignmentSearchCondition scopedToSelf(Long selfUserNo) {
        return new AssignmentSearchCondition(selfUserNo, null, q, workStatus, eventTypeCd, eventTypeCds);
    }

    /**
     * 워크플로 상태 필터(파싱 결과). 미지정(빈 값/공백/제어문자만)이면 빈 값 = 필터 미적용.
     *
     * <p>값이 있는데 정의되지 않은 코드면 <b>400 으로 거부</b>된다({@link AssignmentWorkStatus#parse}) —
     * 조용히 필터를 떨어뜨리면 요청보다 넓은 결과가 200 으로 나간다(fail-open).
     */
    public Optional<AssignmentWorkStatus> workStatusFilter() {
        return AssignmentWorkStatus.parse(workStatus);
    }

}
