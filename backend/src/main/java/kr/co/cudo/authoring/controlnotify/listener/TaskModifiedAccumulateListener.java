package kr.co.cudo.authoring.controlnotify.listener;

import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyDebouncer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * HIGH-E(Phase 5C) — 승인 후 수정({@link TaskModifiedEvent}) 축적 리스너(<b>항상 활성</b>).
 *
 * <p>구 구현은 이 소비를 통지 토글({@code authoring.control-notify.enabled})에 종속된
 * {@code ControlNotifyEventListener} 안에 뒀다. 그 결과 dev/stg/prd(토글 false)에서는 리스너 자체가 없어
 * 승인 후 라벨/촬영환경 수정 이벤트({@code exportRegenerated=true})가 <b>소비자 없이 드롭</b>돼 export
 * 재생성이 전혀 일어나지 않았다(데이터마트 동기화 요구가 무관한 통지 토글에 종속됨).
 *
 * <p>이 리스너는 토글과 무관하게 항상 이벤트를 {@link ControlNotifyDebouncer} 에 축적한다. 디바운서(항상
 * 활성)가 flush 시 export 재생성을 트리거하고(토글 무관), 통지({@code sendModified})만 토글 종속으로
 * export SUCCEEDED 이후 실행한다. 승인 경로({@code DatasetExportBridge} 항상 활성 → export → 완료 이벤트 →
 * 통지 리스너 토글 소비)와 대칭이다.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} — 수정 트랜잭션 커밋 이후에만 축적한다(롤백 시
 * 미호출). 실제 재산출·통지 순서는 디바운스 flush 가 export → 통지로 직렬화한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskModifiedAccumulateListener {

    private final ControlNotifyDebouncer debouncer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskModified(TaskModifiedEvent event) {
        debouncer.accumulate(event);
    }
}
