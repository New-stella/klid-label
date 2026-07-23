package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.review.service.ReviewStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewStateMachineTest {

    private final ReviewStateMachine machine = new ReviewStateMachine();

    @Test
    @DisplayName("ReviewStateMachine_허용_전이_검증_PENDING_to_IN_REVIEW_등")
    void allowedTransitions() {
        assertThatCode(() -> machine.verify(LsRawDataStatus.STTS_ASSIGNED, LsRawDataStatus.STTS_PENDING)).doesNotThrowAnyException();
        assertThatCode(() -> machine.verify(LsRawDataStatus.STTS_PENDING, LsRawDataStatus.STTS_IN_REVIEW)).doesNotThrowAnyException();
        assertThatCode(() -> machine.verify(LsRawDataStatus.STTS_IN_REVIEW, LsRawDataStatus.STTS_APPROVED)).doesNotThrowAnyException();
        assertThatCode(() -> machine.verify(LsRawDataStatus.STTS_IN_REVIEW, LsRawDataStatus.STTS_REJECTED)).doesNotThrowAnyException();
        assertThatCode(() -> machine.verify(LsRawDataStatus.STTS_REJECTED, LsRawDataStatus.STTS_PENDING)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ReviewStateMachine_제출취소_허용_PENDING에서_ASSIGNED_복귀는_가능")
    void pendingToAssignedCancelSubmitAllowed() {
        // WORKER 가 검수 시작 전(PENDING)에 제출을 취소하면 ASSIGNED 로 복귀할 수 있다.
        assertThatCode(() -> machine.verify(LsRawDataStatus.STTS_PENDING, LsRawDataStatus.STTS_ASSIGNED))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ReviewStateMachine_제출취소_IN_REVIEW에서_ASSIGNED_직행은_불가_INVALID_INPUT")
    void inReviewToAssignedCancelForbidden() {
        // 검수 시작(IN_REVIEW) 후에는 취소(ASSIGNED 복귀) 불가.
        assertThatThrownBy(() -> machine.verify(LsRawDataStatus.STTS_IN_REVIEW, LsRawDataStatus.STTS_ASSIGNED))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("ReviewStateMachine_제출취소_APPROVED에서_ASSIGNED_직행은_409_CONFLICT")
    void approvedToAssignedCancelConflict() {
        // 승인(APPROVED) 후 취소(ASSIGNED) 시도는 충돌(409).
        assertThatThrownBy(() -> machine.verify(LsRawDataStatus.STTS_APPROVED, LsRawDataStatus.STTS_ASSIGNED))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("ReviewStateMachine_재검수_허용_APPROVED에서_PENDING_재제출은_가능")
    void approvedToPendingReReviewAllowed() {
        // 검수완료(APPROVED) 영상도 PENDING 으로 재제출하면 새 검수 사이클을 시작할 수 있다 (CLAUDE.md SoT).
        assertThatCode(() -> machine.verify(LsRawDataStatus.STTS_APPROVED, LsRawDataStatus.STTS_PENDING))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ReviewStateMachine_PENDING에서_APPROVED_직접_전이_불가")
    void pendingToApprovedRejected() {
        assertThatThrownBy(() -> machine.verify(LsRawDataStatus.STTS_PENDING, LsRawDataStatus.STTS_APPROVED))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("ReviewStateMachine_APPROVED에서_PENDING_외_직행은_409_CONFLICT")
    void approvedNonPendingIsConflict() {
        // 재검수는 반드시 PENDING 재제출부터 시작 — IN_REVIEW/REJECTED 직행은 충돌(409).
        assertThatThrownBy(() -> machine.verify(LsRawDataStatus.STTS_APPROVED, LsRawDataStatus.STTS_IN_REVIEW))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> machine.verify(LsRawDataStatus.STTS_APPROVED, LsRawDataStatus.STTS_REJECTED))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
    }
}
