package kr.co.cudo.authoring.controlnotify.listener;

import kr.co.cudo.authoring.controlnotify.service.ControlNotifyService;
import kr.co.cudo.authoring.dataset.export.event.DatasetExportCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Phase 3 -- ControlNotifyEventListener 단위 테스트.
 *
 * <p>HIGH-E(Phase 5C) 이후 이 리스너는 <b>완료 통지 전용</b>이다(수정 축적은
 * {@link TaskModifiedAccumulateListener} 로 분리). 통지 토글 종속.
 */
class ControlNotifyEventListenerTest {

    private ControlNotifyService notifyService;
    private ControlNotifyEventListener listener;

    @BeforeEach
    void setUp() {
        notifyService = mock(ControlNotifyService.class);
        listener = new ControlNotifyEventListener(notifyService);
    }

    @Test
    @DisplayName("DatasetExportCompletedEvent_수신시_sendCompleted_호출 (C-2 — export 종결 후 통지)")
    void onExportCompleted_callsSendCompleted() {
        // given — export 러너가 산출을 마친 뒤 발행하는 완료 이벤트
        DatasetExportCompletedEvent event = new DatasetExportCompletedEvent(100L);

        // when
        listener.onExportCompleted(event);

        // then — rawSn 기준 완료 통지 발송
        verify(notifyService).sendCompleted(100L);
    }
}
