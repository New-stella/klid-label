package kr.co.cudo.authoring.controlnotify.listener;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 7a-1 — {@link ReviewRecheckMarkListener} 단위 테스트.
 *
 * <p>{@link TaskModifiedEvent#needsRecheck()} 축만 소비해 표시를 세운다. 기존
 * {@code TaskModifiedAccumulateListener} 의 축적 동작과는 완전히 독립된 별도 리스너이므로, 이 클래스는
 * {@link ReviewApprovalGate} 호출 여부만 검증한다(디바운서는 이 리스너의 관심사가 아니다).
 */
class ReviewRecheckMarkListenerTest {

    private ReviewApprovalGate approvalGate;
    private ReviewRecheckMarkListener listener;

    @BeforeEach
    void setUp() {
        approvalGate = mock(ReviewApprovalGate.class);
        listener = new ReviewRecheckMarkListener(approvalGate);
    }

    @Test
    @DisplayName("needsRecheck_true_이면_markNeedsRecheck_가_호출된다")
    void needsRecheckTrue_marksNeedsRecheck() {
        TaskModifiedEvent event = new TaskModifiedEvent(
                100L, 1L, ChangeType.LABEL_ADDED, 10L, true, true);

        listener.onTaskModified(event);

        verify(approvalGate).markNeedsRecheck(100L);
    }

    @Test
    @DisplayName("needsRecheck_false_이면_markNeedsRecheck_가_호출되지_않는다")
    void needsRecheckFalse_doesNotMarkNeedsRecheck() {
        TaskModifiedEvent event = new TaskModifiedEvent(
                100L, 1L, ChangeType.LABEL_ADDED, 10L, true, false);

        listener.onTaskModified(event);

        verify(approvalGate, never()).markNeedsRecheck(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("4_arg_이벤트(needsRecheck_기본값_false)도_markNeedsRecheck_를_호출하지_않는다")
    void legacyFourArgEvent_doesNotMarkNeedsRecheck() {
        TaskModifiedEvent event = new TaskModifiedEvent(100L, 1L, ChangeType.LABEL_ADDED, 10L);

        listener.onTaskModified(event);

        verify(approvalGate, never()).markNeedsRecheck(org.mockito.ArgumentMatchers.anyLong());
    }
}
