package kr.co.cudo.authoring.dataset.export.listener;

import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
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
 * {@link AsyncDatasetExportRunner#runAsync(Long)} 가 별도 스레드에서 수행한다
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
        log.info("[DatasetExportBridge] review approved rawSn={} — triggering dataset export", rawSn);
        runner.runAsync(rawSn);
    }
}
