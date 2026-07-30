package kr.co.cudo.authoring.assignment.domain;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;

import java.util.Optional;
import java.util.Set;

/**
 * 작업목록(SCR-TASK-001) <b>워크플로 상태</b> — 화면 뱃지 값이자 서버 필터({@code workStatus}) 값.
 *
 * <p>이 값은 DB 컬럼이 아니라 {@code LS_RAW_DATA_STATUS.DATA_STTS_CD}(워크플로 상태 row) 와
 * LABELER 배정 유무로 <b>계산</b>된다. 화면 표시를 만드는 정방향({@link #of})과 WHERE 절을 만드는
 * 역방향(필터 조건: {@link #requiresLabeler()}/{@link #statusIn()}/{@link #statusNotIn()}/
 * {@link #matchesNullStatus()})이 어긋나면 <b>필터 결과와 화면 뱃지가 불일치</b>하는 결함이 되므로,
 * 두 방향을 이 enum 한 곳에서 <b>단일 원천</b>으로 정의한다(HIGH-3). 동치성은
 * {@code BoardWorkStatusTest} 가 모든 상태 × 배정 조합에서 전수 검증한다.
 *
 * <p>{@link #matches(String, boolean)} 는 Java 측 판정이고, 같은 조건을 SQL 로 옮긴 것이
 * {@code TaskBoardQueryRepository.workStatusPredicate} 다. 조건을 바꿀 때는 반드시 이 enum 의
 * 필드만 고치고 양쪽이 그 필드를 읽도록 유지한다.
 */
public enum BoardWorkStatus {

    /** 미배정 — LABELER 배정이 없으면 워크플로 상태와 무관하게 미배정으로 표시된다. */
    UNASSIGNED(false, Set.of(), Set.of(), true),

    /**
     * 진행중(작업 대기/진행) — 배정은 있으나 검수 단계에 진입하지 않은 모든 상태.
     * 상태 row 가 없는 레거시 행(null)과 미래에 추가될 미지 코드도 여기로 떨어진다(기본 분기).
     */
    PENDING(true, Set.of(), Set.of("PENDING", "IN_REVIEW", "APPROVED", "REJECTED"), true),

    /** 검수대기 — 작업자가 검수 제출(PENDING) 했거나 검수 진행 중(IN_REVIEW). */
    REVIEW_PENDING(true, Set.of("PENDING", "IN_REVIEW"), Set.of(), false),

    /** 완료 — 검수 승인(APPROVED). */
    COMPLETED(true, Set.of("APPROVED"), Set.of(), false),

    /** 반려 — 검수 반려(REJECTED). */
    REJECTED(true, Set.of("REJECTED"), Set.of(), false);

    private final boolean requiresLabeler;
    private final Set<String> statusIn;
    private final Set<String> statusNotIn;
    private final boolean matchesNullStatus;

    BoardWorkStatus(boolean requiresLabeler, Set<String> statusIn, Set<String> statusNotIn,
                    boolean matchesNullStatus) {
        this.requiresLabeler = requiresLabeler;
        this.statusIn = statusIn;
        this.statusNotIn = statusNotIn;
        this.matchesNullStatus = matchesNullStatus;
    }

    /** LABELER 배정이 있어야(true) / 없어야(false) 이 상태로 표시된다. */
    public boolean requiresLabeler() {
        return requiresLabeler;
    }

    /** 이 상태에 해당하는 워크플로 상태 코드 화이트리스트 (비어 있으면 코드 제약 없음). */
    public Set<String> statusIn() {
        return statusIn;
    }

    /** 이 상태에서 제외되는 워크플로 상태 코드 (비어 있으면 제약 없음). {@link #statusIn()} 과 배타적으로 쓴다. */
    public Set<String> statusNotIn() {
        return statusNotIn;
    }

    /** 워크플로 상태 row 가 없는(=코드 null) 영상이 이 상태로 표시되는가. */
    public boolean matchesNullStatus() {
        return matchesNullStatus;
    }

    /**
     * 정방향 매핑 — (워크플로 상태 코드, LABELER 배정 유무) → 화면 표시 상태.
     *
     * @param dataSttsCd LS_RAW_DATA_STATUS.DATA_STTS_CD (row 가 없으면 null)
     * @param hasLabeler LABELER 배정 존재 여부
     */
    public static BoardWorkStatus of(String dataSttsCd, boolean hasLabeler) {
        if (!hasLabeler) {
            return UNASSIGNED;
        }
        if (dataSttsCd == null) {
            return PENDING;
        }
        return switch (dataSttsCd) {
            case "PENDING", "IN_REVIEW" -> REVIEW_PENDING;
            case "APPROVED" -> COMPLETED;
            case "REJECTED" -> REJECTED;
            default -> PENDING; // ASSIGNED 및 그 밖의 코드
        };
    }

    /** 이 상태의 필터 조건이 주어진 (상태 코드, 배정 유무) 조합을 포함하는가 — {@link #of} 의 역방향. */
    public boolean matches(String dataSttsCd, boolean hasLabeler) {
        if (hasLabeler != requiresLabeler) {
            return false;
        }
        if (dataSttsCd == null) {
            return matchesNullStatus;
        }
        if (!statusIn.isEmpty()) {
            return statusIn.contains(dataSttsCd);
        }
        if (!statusNotIn.isEmpty()) {
            return !statusNotIn.contains(dataSttsCd);
        }
        return true;
    }

    /**
     * 외부 입력 코드 파싱 — 정의된 코드(대문자 정규 표기)만 허용한다.
     *
     * <p><b>빈 값(null/공백)만</b> {@link Optional#empty()} 로 떨어져 "필터 미적용" 을 뜻하고,
     * <b>정의되지 않은 코드는 400 으로 거부</b>한다({@code AssignmentWorkStatus.parse} 와 동일 계약).
     *
     * <p>구 구현은 미정의 코드도 {@code empty} 로 흘려 <b>필터가 조용히 사라졌다</b>(fail-open).
     * 지금은 컨트롤러 {@code @Pattern} 이 앞에서 막아 도달하지 않지만, 그 정규식과 이 enum 은 <b>서로 다른
     * 곳에 손으로 적힌 두 목록</b>이라 한쪽만 늘어나는 순간(예: enum 에 상태 추가 후 정규식 미갱신의 역방향)
     * "필터를 걸었는데 전체가 반환되는" 조용한 오답이 된다. 필터 축이 사라지는 실패는 화면상 정상으로 보여
     * 발견이 늦으므로 fail-closed 로 뒤를 받친다(가드: {@code BoardWorkStatusContractTest}).
     *
     * @throws CustomException 정의되지 않은 코드({@link ErrorCode#INVALID_INPUT}, 400)
     */
    public static Optional<BoardWorkStatus> parse(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        for (BoardWorkStatus s : values()) {
            if (s.name().equals(code)) {
                return Optional.of(s);
            }
        }
        throw new CustomException(ErrorCode.INVALID_INPUT, "허용되지 않은 workStatus 값입니다.");
    }
}
