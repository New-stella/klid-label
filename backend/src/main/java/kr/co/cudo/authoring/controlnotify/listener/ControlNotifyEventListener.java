package kr.co.cudo.authoring.controlnotify.listener;

import kr.co.cudo.authoring.controlnotify.service.ControlNotifyService;
import kr.co.cudo.authoring.dataset.export.event.DatasetExportCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Phase 3 -- 관제서버 outbound <b>통지</b> 이벤트 리스너(통지 토글 종속).
 *
 * <h3>C-2 — 완료 통지는 export 종결 이후에 발송한다(순서 보장)</h3>
 * 승인 통지({@code TASK_COMPLETED})를 {@code ReviewApprovedEvent} 로 즉시 보내면 export({@code @Async})보다
 * 먼저 나가, 관제가 조회하는 {@code V_COMPLETED_VIDEO.OUTPUT_PATH_NM}(최신 SUCCEEDED)이 이번 승인의 새
 * 버전 폴더를 아직 못 담아 <b>구 버전</b>을 픽업한다(N-3). 그래서 승인 통지는
 * {@code ReviewApprovedEvent} 가 아니라 export 러너가 산출을 마친 뒤 발행하는
 * {@link DatasetExportCompletedEvent} 를 소비해 발송한다 — export → 통지 순서가 이벤트 계층에서 보장된다.
 *
 * <h3>HIGH-E(Phase 5C) — 수정 축적({@code onTaskModified})은 이 클래스에서 분리됐다</h3>
 * 수정 이벤트 축적 → export 재생성 트리거는 통지 토글과 무관해야 하므로(데이터마트 동기화 요구),
 * {@code TaskModifiedEvent} 소비는 항상 활성인 {@link TaskModifiedAccumulateListener} 로 이관했다. 이 리스너는
 * <b>통지 전용</b>(export SUCCEEDED 후 완료 통지)이며 계속 통지 토글({@code control-notify.enabled})에 종속된다.
 */
@Component
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ControlNotifyEventListener {

    private final ControlNotifyService notifyService;

    /**
     * C-2 — 승인 export 가 종결된 뒤 완료 통지를 발송한다. 완료 이벤트는 일반 {@code ApplicationEvent} 라
     * 발행 스레드(export 러너)에서 동기 소비되므로, 이 통지는 export SUCCEEDED 이후에만 나간다.
     */
    @EventListener
    public void onExportCompleted(DatasetExportCompletedEvent event) {
        notifyService.sendCompleted(event.rawSn());
    }
}
