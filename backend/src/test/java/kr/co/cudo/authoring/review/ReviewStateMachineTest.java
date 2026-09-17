package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.review.service.ReviewStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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

    // ---------------------------------------------------- 승인 자격의 상태 축 (ADR-067 · API-250)

    /**
     * ★<b>일괄 승인 자격의 상태 축은 이 판정 하나가 소유한다.</b> 목록 표시(자격 여부)와 일괄 승인
     * 창구가 같은 메서드를 부르므로, 여기가 흔들리면 「단건은 되는데 일괄에는 안 보이는」 어긋남이 난다.
     */
    @Test
    @DisplayName("★재검수_건은_승인_상태에_머물러도_승인_자격이_있다_검수진행으로_좁히면_영영_빠진다")
    void recheckOnApprovedStillAllowsApproval() {
        assertThat(machine.allowsApproval(LsRawDataStatus.STTS_APPROVED, true)).isTrue();
        assertThat(machine.isReapproval(LsRawDataStatus.STTS_APPROVED, true)).isTrue();
    }

    @Test
    @DisplayName("검수_진행_상태는_승인_자격이_있다")
    void inReviewAllowsApproval() {
        assertThat(machine.allowsApproval(LsRawDataStatus.STTS_IN_REVIEW, false)).isTrue();
        // 검수 진행은 재승인 갈래가 아니다 — 정상 전이를 타야 한다.
        assertThat(machine.isReapproval(LsRawDataStatus.STTS_IN_REVIEW, false)).isFalse();
    }

    @Test
    @DisplayName("재검토_표시가_없는_승인_영상과_검수_대기_반려는_승인_자격이_없다")
    void otherStatesDoNotAllowApproval() {
        // 표시가 없는 승인 영상을 다시 승인하면 무수정 재확정이 된다 — 열어 두지 않는다.
        assertThat(machine.allowsApproval(LsRawDataStatus.STTS_APPROVED, false)).isFalse();
        // 검수 대기에서 승인으로 직행할 수 없다(검수 진행을 거쳐야 한다).
        assertThat(machine.allowsApproval(LsRawDataStatus.STTS_PENDING, false)).isFalse();
        assertThat(machine.allowsApproval(LsRawDataStatus.STTS_REJECTED, false)).isFalse();
        assertThat(machine.allowsApproval(LsRawDataStatus.STTS_ASSIGNED, false)).isFalse();
        // 값역 밖 상태도 조용히 허용되지 않는다(fail-closed).
        assertThat(machine.allowsApproval("UNKNOWN_STATE", true)).isFalse();
    }
}
