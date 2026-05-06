package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;
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
        assertThatCode(() -> machine.verify(LsPjtDataStts.STTS_ASSIGNED, LsPjtDataStts.STTS_PENDING)).doesNotThrowAnyException();
        assertThatCode(() -> machine.verify(LsPjtDataStts.STTS_PENDING, LsPjtDataStts.STTS_IN_REVIEW)).doesNotThrowAnyException();
        assertThatCode(() -> machine.verify(LsPjtDataStts.STTS_IN_REVIEW, LsPjtDataStts.STTS_APPROVED)).doesNotThrowAnyException();
        assertThatCode(() -> machine.verify(LsPjtDataStts.STTS_IN_REVIEW, LsPjtDataStts.STTS_REJECTED)).doesNotThrowAnyException();
        assertThatCode(() -> machine.verify(LsPjtDataStts.STTS_REJECTED, LsPjtDataStts.STTS_PENDING)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ReviewStateMachine_PENDING에서_APPROVED_직접_전이_불가")
    void pendingToApprovedRejected() {
        assertThatThrownBy(() -> machine.verify(LsPjtDataStts.STTS_PENDING, LsPjtDataStts.STTS_APPROVED))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("ReviewStateMachine_APPROVED는_최종_상태_409_CONFLICT")
    void approvedIsTerminal() {
        assertThatThrownBy(() -> machine.verify(LsPjtDataStts.STTS_APPROVED, LsPjtDataStts.STTS_IN_REVIEW))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> machine.verify(LsPjtDataStts.STTS_APPROVED, LsPjtDataStts.STTS_REJECTED))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
    }
}
