package kr.co.cudo.authoring.controlnotify.listener;

import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyDebouncer;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Phase 3 -- ControlNotifyEventListener 단위 테스트.
 */
class ControlNotifyEventListenerTest {

    private ControlNotifyService notifyService;
    private ControlNotifyDebouncer debouncer;
    private ControlNotifyEventListener listener;

    @BeforeEach
    void setUp() {
        notifyService = mock(ControlNotifyService.class);
        debouncer = mock(ControlNotifyDebouncer.class);
        listener = new ControlNotifyEventListener(notifyService, debouncer);
    }

    @Test
    @DisplayName("ReviewApprovedEvent_수신시_sendCompleted_호출")
    void onReviewApproved_callsSendCompleted() {
        // given
        ReviewApprovedEvent event = new ReviewApprovedEvent(100L, 1L, Instant.now());

        // when
        listener.onReviewApproved(event);

        // then
        verify(notifyService).sendCompleted(event);
    }

    @Test
    @DisplayName("TaskModifiedEvent_수신시_debouncer_accumulate_호출")
    void onTaskModified_callsDebouncerAccumulate() {
        // given
        TaskModifiedEvent event = new TaskModifiedEvent(100L, 1L, "LABEL_ADDED", 10L);

        // when
        listener.onTaskModified(event);

        // then
        verify(debouncer).accumulate(event);
    }
}
