package kr.co.cudo.authoring.controlnotify.listener;

import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyDebouncer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * HIGH-E(Phase 5C) — TaskModifiedAccumulateListener 단위 테스트.
 *
 * <p>수정 이벤트 축적이 통지 리스너에서 분리되어 <b>항상 활성</b> 리스너가 소비함을 회귀 방어한다.
 */
class TaskModifiedAccumulateListenerTest {

    private ControlNotifyDebouncer debouncer;
    private TaskModifiedAccumulateListener listener;

    @BeforeEach
    void setUp() {
        debouncer = mock(ControlNotifyDebouncer.class);
        listener = new TaskModifiedAccumulateListener(debouncer);
    }

    @Test
    @DisplayName("TaskModifiedEvent_수신시_debouncer_accumulate_호출")
    void onTaskModified_callsDebouncerAccumulate() {
        // given
        TaskModifiedEvent event = new TaskModifiedEvent(100L, 1L, "LABEL_ADDED", 10L, true);

        // when
        listener.onTaskModified(event);

        // then
        verify(debouncer).accumulate(event);
    }
}
