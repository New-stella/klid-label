package kr.co.cudo.authoring.review.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Phase 7 — 검수 상태 전이 검증.
 *
 * <pre>
 * 허용 전이:
 *   PENDING   → IN_REVIEW   (REVIEWER startReview)
 *   PENDING   → ASSIGNED    (WORKER 제출 취소 — 검수 시작 전에만 가능)
 *   IN_REVIEW → APPROVED    (REVIEWER approve)
 *   IN_REVIEW → REJECTED    (REVIEWER reject)
 *   REJECTED  → PENDING     (WORKER  재제출)
 *   ASSIGNED  → PENDING     (WORKER  최초 제출 — Phase 2 배정 직후 상태)
 *   APPROVED  → PENDING     (WORKER  재검수 재제출 — 검수완료 후 수정→재검수→재승인. 동일 작업 ID 유지, 버전업 아님)
 *
 * 불허:
 *   PENDING   → APPROVED   (IN_REVIEW 거쳐야 함)
 *   APPROVED  → IN_REVIEW / REJECTED (직행 불가 — 재검수는 반드시 PENDING 재제출부터 시작)
 * </pre>
 *
 * <p>재검수 정책(CLAUDE.md "검수완료 후 수정→재검수→재승인 시 새 버전 적층"이 SoT): APPROVED 영상도
 * WORKER 가 PENDING 으로 재제출하면 새 검수 사이클이 시작된다. 재승인 시 {@code VersionService.commitApproved}
 * 가 변경분에 대해 새 APPROVED 스냅샷을 적층(diff/롤백 활성화)한다. 동일 작업 ID(=RAW_SN) 유지 — 버전업이 아니다.
 *
 * <p>참고: 검수 종결 시 영속되는 상태는 {@code APPROVED} 다. {@code COMPLETED}(STTS_COMPLETED)는 배치
 * 파이프라인 상태이며 검수 종결값으로 쓰이지 않는다(DTO 표시 계층에서만 APPROVED→"COMPLETED" 라벨로 매핑).
 * 따라서 재검수 재제출의 출발 상태는 APPROVED 한 곳으로 충분하다.
 *
 * 잘못된 전이 시 {@link ErrorCode#INVALID_INPUT} 예외 발생.
 * APPROVED → (PENDING 외) 시도는 비즈니스적으로 충돌 → {@link ErrorCode#CONFLICT}.
 */
@Component
public class ReviewStateMachine {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            LsRawDataStatus.STTS_ASSIGNED,  Set.of(LsRawDataStatus.STTS_PENDING),
            // PENDING → IN_REVIEW (REVIEWER 검수 시작) / PENDING → ASSIGNED (WORKER 제출 취소)
            LsRawDataStatus.STTS_PENDING,   Set.of(LsRawDataStatus.STTS_IN_REVIEW, LsRawDataStatus.STTS_ASSIGNED),
            LsRawDataStatus.STTS_IN_REVIEW, Set.of(LsRawDataStatus.STTS_APPROVED, LsRawDataStatus.STTS_REJECTED),
            LsRawDataStatus.STTS_REJECTED,  Set.of(LsRawDataStatus.STTS_PENDING),
            // 재검수: APPROVED → PENDING (WORKER 재제출)만 허용. 그 외 APPROVED 출발 전이는 409(CONFLICT).
            LsRawDataStatus.STTS_APPROVED,  Set.of(LsRawDataStatus.STTS_PENDING)
    );

    public void verify(String from, String to) {
        // APPROVED 는 PENDING(재검수 재제출)만 허용. IN_REVIEW/REJECTED 직행 등은 충돌(409).
        if (LsRawDataStatus.STTS_APPROVED.equals(from) && !LsRawDataStatus.STTS_PENDING.equals(to)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 APPROVED 된 영상은 재검수 재제출(PENDING) 외 상태 변경이 불가합니다.");
        }
        Set<String> allowed = ALLOWED.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않은 상태 전이입니다 (" + from + " → " + to + ").");
        }
    }

    /**
     * <b>재검수 재승인 갈래인가</b> — 승인된 영상에 재검토 표시가 서 있으면 상태 전이 없이 다시 승인한다.
     *
     * <p>이 갈래에서 상태를 내리지 않는 이유는 관제 조회 뷰가 <b>라이브 승인 상태</b>로 행을 거르기
     * 때문이다. 상태를 되돌리면 이미 완료로 통지한 영상이 관제에서 예고 없이 사라진다. 같은 이유로
     * 이 갈래 전용 상태값을 새로 만들지도 않는다.
     *
     * @design ADR-067
     * @design API-013
     */
    public boolean isReapproval(String from, boolean needsRecheck) {
        return LsRawDataStatus.STTS_APPROVED.equals(from) && needsRecheck;
    }

    /**
     * <b>단건 승인이 이 상태를 받아들이는가</b> — 승인 자격의 상태 축 <b>단일 판정</b>.
     *
     * <p>일괄 승인 자격과 목록의 자격 표시가 이 메서드를 그대로 쓴다. 두 곳에 규칙을 두면 한쪽만
     * 고쳐질 때 같은 영상이 창구에 따라 다르게 판정된다.
     *
     * <p>★<b>「검수 진행 중인 것만」으로 좁히면 안 된다.</b> 수정 뒤 재검수를 기다리는 영상은 승인
     * 상태에 머무르므로(위 {@link #isReapproval}) 그 축으로 좁히는 순간 재검수 건이 영영 일괄 승인에
     * 담기지 않는다.
     *
     * @design ADR-067
     * @design API-250
     * @design API-008
     */
    public boolean allowsApproval(String from, boolean needsRecheck) {
        if (isReapproval(from, needsRecheck)) {
            return true;
        }
        Set<String> allowed = ALLOWED.get(from);
        return allowed != null && allowed.contains(LsRawDataStatus.STTS_APPROVED);
    }
}
