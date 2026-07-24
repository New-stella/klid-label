package kr.co.cudo.authoring.dataset.export.listener;

import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
import kr.co.cudo.authoring.dataset.export.event.DatasetReExportEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 4 — 검수 승인 → 학습데이터 파일 산출 트리거 브릿지.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 로 <b>검수 승인 트랜잭션이 커밋된 이후에만</b>
 * 산출을 시작한다(롤백 시 미호출). 리스너는 얇게 위임만 하고, 실제 산출은
 * {@link AsyncDatasetExportRunner#runAsync(Long, boolean)} 가 별도 스레드에서 수행한다
 * ({@code IngestDeidentifyBridge}/{@code ControlNotifyEventListener} 와 동일 구조).
 *
 * <p>같은 {@code ReviewApprovedEvent} 를 소비하는 기존 리스너({@code ControlNotifyEventListener})는
 * 유지되며, 본 리스너는 <b>추가</b> 소비자다(리스너 간 독립).
 *
 * <p>{@code authoring.dataset-export.enabled} 로 토글한다(기본 활성 — {@code matchIfMissing=true}).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "authoring.dataset-export", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DatasetExportBridge {

    private final AsyncDatasetExportRunner runner;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewApproved(ReviewApprovedEvent event) {
        Long rawSn = event.rawSn();
        log.info("[DatasetExportBridge] review approved rawSn={} — triggering dataset export (force regenerate)", rawSn);
        // R6 — 승인마다 내용 변경 여부와 무관하게 전량 재생성(force=true). 멱등 skip 미적용.
        runner.runAsync(rawSn, true);
    }

    /**
     * 재동결(예: event_annotation 지연 승인)로 동결 스냅샷이 갱신된 뒤 export 재생성을 트리거한다.
     * {@code onReviewApproved} 와 동일하게 AFTER_COMMIT 로 재동결 커밋 후에만 산출을 시작하며,
     * TASK_COMPLETED 재발행 없이 export 만 갱신한다(통지는 발행 측이 TASK_MODIFIED 로 별도 처리).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReExport(DatasetReExportEvent event) {
        Long rawSn = event.rawSn();
        log.info("[DatasetExportBridge] re-export requested rawSn={} — triggering dataset export", rawSn);
        // 재동결 경로(R6 스코프 밖) — 현행 멱등 skip 유지(force=false). 동일 해시면 재산출하지 않는다.
        runner.runAsync(rawSn, false);
    }
}
