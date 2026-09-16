package kr.co.cudo.authoring.assignment.domain;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 배정 목록(WORKER 작업목록, {@code GET /v1/assignments}) <b>워크플로 상태</b> —
 * FE {@code AssignmentStatus} 값이자 서버 필터({@code workStatus}) 값.
 *
 * <p>이 값은 DB 컬럼이 아니라 {@code LS_RAW_DATA_STATUS.DATA_STTS_CD}(워크플로 상태 row)와
 * <b>그 영상의 사용자 라벨 저장 이력 존재 여부</b>로 계산된다. 화면 표시를 만드는 정방향({@link #of})과
 * WHERE 절을 만드는 역방향(필터)이 어긋나면 "작업중으로 걸렀는데 화면엔 대기로 표시"되는 결함이
 * 되므로, 두 방향을 이 enum 한 곳에서 <b>단일 원천</b>으로 정의한다.
 *
 * <p>{@link #matches(String, boolean)} 는 {@link #of} 를 그대로 되짚어 판정하므로 정/역이 어긋날
 * 구조적 여지가 없다. 같은 조건을 SQL 로 옮긴 것이
 * {@code AssignmentQueryRepository.workStatusPredicate} 이며, 그쪽은 이 enum 이 노출하는
 * {@link #statusIn()}/{@link #requiresSaveHistory()}/{@link #reviewStageCodes()} 만 읽는다.
 *
 * <p><b>왜 '작업중'을 저장 이력으로 판정하는가</b> — 배정 직후와 작업 착수 이후를 구분하는 별도 컬럼이
 * 없다(스키마 변경 없이 파생, R5 사용자 확정 2026-07-30). 라벨 <b>존재</b>는 쓸 수 없다 — 배치
 * 파이프라인이 배정 전에 오토라벨({@code AUTO_LBL_YN='Y'})을 적재하므로 전 영상이 '작업중' 이 된다.
 * {@code LS_DATA_LBL_HSTRY} 의 저장 이벤트는 사용자 저장 경로에서만 남으므로 "사람이 손을 댔는가" 를
 * 정확히 표현한다(판별식은 {@code AssignmentQueryRepository.hasSaveHistoryExists} 참조).
 */
public enum AssignmentWorkStatus {

    /** 대기 — 배정만 되고 사용자 라벨 저장 이력이 없음. 상태 row 가 없는 레거시/미지 코드도 여기로 떨어진다. */
    PENDING(Set.of(), Boolean.FALSE),

    /** 작업중 — 검수 단계 진입 전이면서 사용자 라벨 저장 이력이 1건 이상 존재. */
    IN_PROGRESS(Set.of(), Boolean.TRUE),

    /** 검수대기 — 작업자가 검수 제출(PENDING) 했거나 검수 진행 중(IN_REVIEW). */
    REVIEW_PENDING(Set.of("PENDING", "IN_REVIEW"), null),

    /** 완료 — 검수 승인(APPROVED). */
    COMPLETED(Set.of("APPROVED"), null),

    /** 반려 — 검수 반려(REJECTED). */
    REJECTED(Set.of("REJECTED"), null);

    /**
     * 검수 단계 코드 전체 — 각 상수의 {@link #statusIn} 에서 <b>파생</b>한다.
     *
     * <p>기저 상태({@link #PENDING}/{@link #IN_PROGRESS})는 "검수 단계가 아닌 모든 것"이라
     * 이 집합의 여집합으로 정의된다. 하드코딩하면 상수 추가 시 두 정의가 조용히 어긋나
     * 어떤 상태에도 속하지 않는 행(필터 결과에서 사라지는 행)이 생긴다.
     */
    private static final Set<String> REVIEW_STAGE_CODES = Arrays.stream(values())
            .flatMap(s -> s.statusIn.stream())
            .collect(Collectors.toUnmodifiableSet());

    private final Set<String> statusIn;
    private final Boolean requiresSaveHistory;

    AssignmentWorkStatus(Set<String> statusIn, Boolean requiresSaveHistory) {
        this.statusIn = statusIn;
        this.requiresSaveHistory = requiresSaveHistory;
    }

    /** 이 상태에 해당하는 워크플로 상태 코드 화이트리스트. 비어 있으면 "검수 단계가 아닌 모든 코드". */
    public Set<String> statusIn() {
        return statusIn;
    }

    /**
     * 라벨 저장 이력 요구 — {@code TRUE}=있어야 함, {@code FALSE}=없어야 함, {@code null}=무관.
     *
     * <p>검수 단계(REVIEW_PENDING/COMPLETED/REJECTED)는 반드시 {@code null} 이다. 이 상태들에
     * 저장 이력 조건을 함께 걸면(제출했는데 이력이 없을 수 없다는 직관과 달리) 조건이 겹쳐 결과가
     * 조용히 줄어들 뿐 아니라, 표시 상태와도 어긋난다.
     */
    public Boolean requiresSaveHistory() {
        return requiresSaveHistory;
    }

    /** 검수 단계로 분류되는 워크플로 상태 코드 집합 (기저 상태의 여집합 기준). */
    public static Set<String> reviewStageCodes() {
        return REVIEW_STAGE_CODES;
    }

    /**
     * <b>배정 해제를 거부하는 워크플로 상태 코드</b> — 검수 대기·검수 중·승인.
     * [@design ADR-069] [@design API-259] [@design AC-1123]
     *
     * <p>배정이 그 워크플로의 전제라, 풀면 검수 흐름이 <b>주인 없는 상태</b>가 된다.
     *
     * <p>★<b>반려({@link #REJECTED})는 여기 들지 않는다.</b> 워크플로가 작업자에게 되돌아온 상태라
     * 그 작업 자체를 접을 수 있어야 한다. 「반려도 검수 축이니 함께 막자」로 넓히면, 잘못 배정한
     * 반려 건이 영영 풀리지 않고 <b>「반려 → 해제 → 제외」 경로가 통째로 막힌다</b>.
     * 같은 이유로 {@link #reviewStageCodes()} 를 그대로 쓰면 안 된다 — 그 집합에는 반려가 들어 있다.
     *
     * <p>{@link #REVIEW_PENDING}·{@link #COMPLETED} 의 {@link #statusIn} 에서 <b>파생</b>한다.
     * 코드를 손으로 나열하면 상수 정의가 바뀔 때 두 정의가 조용히 어긋난다.
     */
    private static final Set<String> UNASSIGN_BLOCKING_CODES =
            Stream.concat(REVIEW_PENDING.statusIn.stream(), COMPLETED.statusIn.stream())
                    .collect(Collectors.toUnmodifiableSet());

    /** @see #UNASSIGN_BLOCKING_CODES */
    public static Set<String> unassignBlockingCodes() {
        return UNASSIGN_BLOCKING_CODES;
    }

    /**
     * 정방향 매핑 — (워크플로 상태 코드, 라벨 저장 이력 존재 여부) → 화면 표시 상태.
     *
     * @param dataSttsCd     {@code LS_RAW_DATA_STATUS.DATA_STTS_CD} (row 가 없으면 null)
     * @param hasSaveHistory 그 영상에 사용자 라벨 저장 이력이 1건 이상 존재하는가
     */
    public static AssignmentWorkStatus of(String dataSttsCd, boolean hasSaveHistory) {
        if (dataSttsCd != null) {
            for (AssignmentWorkStatus s : values()) {
                if (s.statusIn.contains(dataSttsCd)) {
                    return s;
                }
            }
        }
        // ASSIGNED · 미지 코드 · 상태 row 없음 → 저장 이력 유무로 대기/작업중을 가른다.
        return hasSaveHistory ? IN_PROGRESS : PENDING;
    }

    /**
     * 필터 조건이 주어진 (상태 코드, 저장 이력 존재) 조합을 포함하는가 — {@link #of} 의 역방향.
     *
     * <p>정방향을 그대로 되짚으므로 두 방향이 어긋날 수 없다(필터 결과 ≡ 화면 표시).
     */
    public boolean matches(String dataSttsCd, boolean hasSaveHistory) {
        return of(dataSttsCd, hasSaveHistory) == this;
    }

    /**
     * 외부 입력 코드 파싱 — <b>fail-closed</b>. 정의된 코드(대문자 정규 표기)만 허용하고,
     * 값이 있는데 정의에 없으면 <b>400 으로 거부</b>한다.
     *
     * <p>빈 값(null/공백)만 {@link Optional#empty()} 로 떨어져 "필터 미적용" 을 뜻한다.
     *
     * <p><b>왜 빈 값 반환이 아니라 예외인가</b> — 호출측이 {@code ifPresent} 로 WHERE 절을 붙이므로,
     * 미지 값에 빈 값을 돌려주면 <b>필터가 통째로 사라져 요청보다 넓은 결과가 200 으로</b> 나간다
     * (fail-open). 허용값이 컨트롤러 {@code @Pattern} 정규식과 이 enum 두 곳에 정의돼 있어 둘이
     * 어긋나는 순간(상수 rename 등) 정규식은 통과시키고 필터만 증발한다. 정규식은 <b>1차 방어</b>이고
     * 최종 판정은 여기서 한다(정렬 축이 양방향 fail-closed 인 것과 대칭).
     *
     * <p>보안(CWE-209): 메시지에 입력값을 반사하지 않는다.
     *
     * @throws CustomException 정의되지 않은 코드({@link ErrorCode#INVALID_INPUT}, 400)
     */
    public static Optional<AssignmentWorkStatus> parse(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        for (AssignmentWorkStatus s : values()) {
            if (s.name().equals(code)) {
                return Optional.of(s);
            }
        }
        throw new CustomException(ErrorCode.INVALID_INPUT, "허용되지 않은 workStatus 값입니다.");
    }
}
