package kr.co.cudo.authoring.assignment.dto;

import kr.co.cudo.authoring.assignment.domain.BoardWorkStatus;

import java.util.Optional;
import java.util.Set;

/**
 * 작업목록(GET /v1/tasks/board) 서버 검색 조건.
 *
 * <p>모든 필터는 <b>선택(optional)</b> 이며 서로 AND 로 결합된다. 특히 {@code status}(배치 상태 축,
 * {@code LS_DATA_RAW.DATA_STTS_CD})와 {@code workStatus}(워크플로 상태 축, 계산값)는 <b>독립 축</b>이라
 * 한 파라미터로 겹치지 않는다.
 *
 * <p><b>R8 하위호환</b>: 신규 필터가 하나도 오지 않으면(=모두 null) 조건이 전혀 걸리지 않아 변경 전과
 * 동일한 결과 집합을 반환한다. 이를 보장하기 위해 compact 생성자에서 <b>빈 문자열·공백만 입력을
 * null 로 정규화</b>한다(HIGH-4) — {@code q=""} 같은 값이 LIKE 조건으로 살아나면 기본 호출 결과가
 * 달라진다.
 *
 * <p>이 정규화가 <b>모든 필터에 실제로 도달</b>하려면 컨트롤러의 값 제약(코드 allowlist {@code @Pattern})도
 * 빈 문자열·공백을 통과시켜야 한다. 그렇지 않으면 {@code workStatus=} 만 400 이 되어 {@code q=} /
 * {@code eventTypeCd=}(200, 필터 미적용) 와 blank 처리 의미가 갈린다. 여기서 "공백"의 정의는
 * {@link String#trim()} 과 동일하게 <b>U+0020 이하 문자</b>이며, U+00A0 같은 그 밖의 문자는 공백이 아니라
 * 일반 입력값으로 취급된다(코드 allowlist 가 있는 필터에서는 미정의 코드 = 400).
 *
 * <p><b>{@code eventTypeCds} — 이벤트유형 그룹 확장 결과 (R6)</b>: 셀렉트 옵션이 <b>표시명 그룹
 * 대표코드</b>로 접히므로, 필터도 그 대표코드가 뜻하는 <b>그룹 전체 코드</b>를 봐야 한다. 확장 판정은
 * 마스터(캐시) 조회가 필요해 조건 객체가 스스로 할 수 없으므로 <b>서비스가 한 곳에서</b> 채워 넣고
 * ({@link #withEventTypeGroup}) 리포지토리는 {@link #eventTypeMatchCodes()} 만 본다. 확장이 채워지지
 * 않은 조건(리포지토리 직접 호출 등)은 종전과 같이 <b>입력 코드 1건</b>으로 매칭된다.
 *
 * @param eventTypeCds 확장된 그룹 코드 집합. 비어 있으면 확장 없음(= 입력 코드 단건 매칭)
 */
public record TaskBoardSearchCondition(
        String status,
        String workStatus,
        String q,
        String eventTypeCd,
        Long workerId,
        Set<String> eventTypeCds
) {

    /**
     * 미배정 필터 — 배치 상태 무관, LABELER 배정이 없는 모든 영상을 반환하는 가상 status.
     *
     * <p><b>★ UNASSIGNED 는 두 축에서 서로 다른 집합을 뜻한다 (혼동 주의)</b>:
     * <ul>
     *   <li>{@code status=UNASSIGNED} (이 가상값) — 배치 상태 필터를 <b>끄고</b> 미배정 <b>전체</b></li>
     *   <li>{@code workStatus=UNASSIGNED} — 현재 배치 상태 필터 <b>안에서의</b> 미배정</li>
     * </ul>
     * KPI 집계({@code GET /v1/tasks/board/summary})의 {@code unassigned} 버킷은 <b>후자</b>를 센다.
     * 따라서 FE 가 미배정 카드를 클릭할 때는 {@code status} 를 바꾸지 말고 {@code workStatus=UNASSIGNED}
     * 를 추가해야 카드 숫자와 목록 {@code totalElements} 가 일치한다. 이 계약은
     * {@code TaskBoardSummaryTest} 가 두 축이 섞인 픽스처로 고정한다(문서만으로는 드리프트한다).
     */
    public static final String STATUS_UNASSIGNED = "UNASSIGNED";

    /** 배치 상태 기본값 — 기존 계약(파라미터 미전송 시 처리 완료 영상). */
    public static final String DEFAULT_BATCH_STATUS = "COMPLETED";

    public TaskBoardSearchCondition {
        status = normalizeOrDefault(status);
        workStatus = normalize(workStatus);
        q = normalize(q);
        eventTypeCd = normalize(eventTypeCd);
        // 확장은 입력 코드에 종속된 파생값이다 — 입력이 없으면(정규화로 null 이 되면) 확장도 버린다.
        //   불변 복사로 담아 캐시/공유 컬렉션이 호출자에게 변형되지 않게 한다.
        eventTypeCds = (eventTypeCd == null || eventTypeCds == null)
                ? Set.of() : Set.copyOf(eventTypeCds);
    }

    /** 그룹 확장 없이 만드는 조건 — 컨트롤러/테스트가 쓰는 기존 시그니처(하위호환). */
    public TaskBoardSearchCondition(String status, String workStatus, String q, String eventTypeCd,
                                    Long workerId) {
        this(status, workStatus, q, eventTypeCd, workerId, Set.of());
    }

    /** 필터 없는 기본 조건(배치 상태 COMPLETED). */
    public static TaskBoardSearchCondition defaults() {
        return new TaskBoardSearchCondition(null, null, null, null, null);
    }

    /**
     * 이벤트유형 <b>그룹 확장 결과</b>를 채운 조건을 만든다 (R6).
     *
     * <p>서비스가 {@code EventTypeFilterSupport.matchCodesFor} 로 얻은 집합을 그대로 넘긴다.
     * 빈 집합이면 확장 없음(= 입력 코드 단건 매칭)이다.
     */
    public TaskBoardSearchCondition withEventTypeGroup(Set<String> codes) {
        return new TaskBoardSearchCondition(status, workStatus, q, eventTypeCd, workerId, codes);
    }

    /**
     * 이벤트유형 필터가 실제로 매칭할 코드 집합 — <b>리포지토리는 이 값만 본다</b>.
     *
     * <p>확장이 채워졌으면 그룹 전체, 아니면 입력 코드 1건. 필터 미적용이면 <b>빈 집합</b>이다
     * (null 을 돌려주지 않는다).
     */
    public Set<String> eventTypeMatchCodes() {
        if (eventTypeCd == null) {
            return Set.of();
        }
        return eventTypeCds.isEmpty() ? Set.of(eventTypeCd) : eventTypeCds;
    }

    /** 미배정 가상 status 여부 — true 면 배치 상태 필터를 걸지 않고 LABELER 미배정만 반환한다. */
    public boolean unassignedOnly() {
        return STATUS_UNASSIGNED.equals(status);
    }

    /** 실제로 적용할 배치 상태 필터 — 미배정 가상 status 일 때는 배치 상태를 거르지 않는다. */
    public String batchStatusFilter() {
        return unassignedOnly() ? null : status;
    }

    /** 워크플로 상태 필터(파싱 결과). 미지정/미정의 코드면 빈 값 = 필터 미적용. */
    public Optional<BoardWorkStatus> workStatusFilter() {
        return BoardWorkStatus.parse(workStatus);
    }

    /**
     * 배치 상태 축({@code status})만 남기고 나머지 필터를 제거한 조건.
     *
     * <p>이벤트유형 옵션 조회({@code GET /v1/tasks/board/event-types})용 — 사용자가 검색어/작업자 등을
     * 건 뒤 셀렉트 옵션이 사라지면 되돌아갈 수 없으므로 그 필터들은 옵션 목록에 반영하지 않는다.
     * 별도 WHERE 를 새로 조립하는 대신 이 조건을 기존 조립기에 통과시켜 {@code status} 해석
     * (기본값 · {@code UNASSIGNED} 가상 status)이 목록과 동일하게 유지되게 한다.
     *
     * <p><b>왜 남겨 두는가 (현재는 no-op)</b> — 컨트롤러가 {@code status} 만 파라미터로 선언하므로
     * 지금은 이 메서드가 지울 non-null 필드가 없다. 그럼에도 유지하는 이유는, 옵션 엔드포인트에
     * FE 편의로 파라미터가 추가되는 순간(예: 검색어 전달) <b>방어가 저절로 살아나야</b> 하기 때문이다.
     * 컨트롤러 시그니처 하나에만 의존하면 파라미터 추가 시 옵션이 조용히 좁아진다.
     * 이 no-op 이 실제로 방어로 동작하는지는 {@code TaskBoardSearchConditionTest}(직접 단언)와
     * {@code TaskBoardSummaryTest}(리포지토리 직접 호출 — 모든 필터를 채운 조건을 넘겨도 옵션이
     * 좁아지지 않음)가 검증한다. 컨트롤러 경유 MockMvc 테스트는 미선언 파라미터가 조건 객체에
     * 애초에 도달하지 못해 <b>어떤 구현에서도 통과</b>하므로 이 방어의 근거가 될 수 없다.
     */
    public TaskBoardSearchCondition statusOnly() {
        return new TaskBoardSearchCondition(status, null, null, null, null);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeOrDefault(String raw) {
        String normalized = normalize(raw);
        return normalized == null ? DEFAULT_BATCH_STATUS : normalized;
    }
}
