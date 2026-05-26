package kr.co.cudo.authoring.controlnotify.listener;

import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyDebouncer;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 3 -- 관제서버 outbound 통지 이벤트 리스너.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} -- 트랜잭션 커밋 이후에만 통지 전송.
 * 롤백 시 리스너 미호출.
 */
@Component
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ControlNotifyEventListener {

    private final ControlNotifyService notifyService;
    private final ControlNotifyDebouncer debouncer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewApproved(ReviewApprovedEvent event) {
        notifyService.sendCompleted(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskModified(TaskModifiedEvent event) {
        debouncer.accumulate(event);
    }
}
