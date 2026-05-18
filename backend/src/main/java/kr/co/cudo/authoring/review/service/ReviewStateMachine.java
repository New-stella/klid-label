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
 *
 * 불허:
 *   PENDING   → APPROVED   (IN_REVIEW 거쳐야 함)
 *   APPROVED  → *          (최종 상태)
 * </pre>
 *
 * 잘못된 전이 시 {@link ErrorCode#INVALID_INPUT} 예외 발생.
 * APPROVED → * 시도는 비즈니스적으로 충돌 → {@link ErrorCode#CONFLICT}.
 */
@Component
public class ReviewStateMachine {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            LsRawDataStatus.STTS_ASSIGNED,  Set.of(LsRawDataStatus.STTS_PENDING),
            LsRawDataStatus.STTS_PENDING,   Set.of(LsRawDataStatus.STTS_IN_REVIEW),
            LsRawDataStatus.STTS_IN_REVIEW, Set.of(LsRawDataStatus.STTS_APPROVED, LsRawDataStatus.STTS_REJECTED),
            LsRawDataStatus.STTS_REJECTED,  Set.of(LsRawDataStatus.STTS_PENDING)
    );

    public void verify(String from, String to) {
        if (LsRawDataStatus.STTS_APPROVED.equals(from)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 APPROVED 된 영상은 상태 변경이 불가합니다.");
        }
        Set<String> allowed = ALLOWED.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않은 상태 전이입니다 (" + from + " → " + to + ").");
        }
    }
}
