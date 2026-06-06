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
            LsRawDataStatus.STTS_PENDING,   Set.of(LsRawDataStatus.STTS_IN_REVIEW),
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
}
