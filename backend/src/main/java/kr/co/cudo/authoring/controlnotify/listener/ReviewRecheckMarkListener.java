package kr.co.cudo.authoring.controlnotify.listener;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 7a-1 — {@link TaskModifiedEvent#needsRecheck()} 축을 소비해 재검토 표시({@code REVLT_YN='Y'})를
 * 세우는 전용 리스너.
 *
 * <p><b>왜 {@link TaskModifiedAccumulateListener} 에 합치지 않았는가</b>: 그 리스너는 이미
 * {@code TaskModifiedAccumulateListenerTest} 가 <b>단일 인자 생성자</b>({@code debouncer} 하나)를
 * 전제로 고정돼 있다. 여기에 {@link ReviewApprovalGate} 의존성을 더하면 그 회귀 가드의 생성자
 * 시그니처가 깨져 <b>이번 단계에서 절대 깨지면 안 되는 기존 테스트</b>를 건드리게 된다. 두 리스너
 * 모두 같은 이벤트를 독립적으로 구독하는 것은 이 패키지의 기존 관례({@code ControlNotifyEventListener}
 * 와 {@code TaskModifiedAccumulateListener} 가 각자 다른 이벤트를 구독하는 것)와도 어긋나지 않는다 —
 * 책임을 분리해 한쪽 변경이 다른 쪽 테스트를 흔들지 않게 한다.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} — 수정 트랜잭션이 <b>커밋된 뒤에만</b> 표시를
 * 세운다. 롤백되면 이 리스너 자체가 호출되지 않으므로 표시도 남지 않는다(요구사항 "롤백 시 표시가
 * 남지 않는다").
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewRecheckMarkListener {

    private final ReviewApprovalGate approvalGate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskModified(TaskModifiedEvent event) {
        if (!event.needsRecheck()) {
            return;
        }
        approvalGate.markNeedsRecheck(event.rawSn());
    }
}
