package kr.co.cudo.authoring.marking.listener;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.runner.AsyncBatchRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.Set;

/**
 * 마킹 완료 이벤트 수신 → 배치 파이프라인 트리거.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 로 마킹 트랜잭션 커밋 이후에만 실행.
 * 이미 배치 큐에 들어갔거나 처리 중/완료 상태인 영상은 중복 실행을 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkingBatchBridge {

    private static final Set<String> SKIP_STATUSES = Set.of(
            LsRawDataStatus.STTS_BATCH_QUEUED, "PROCESSING", "COMPLETED");

    private final LsRawDataStatusRepository statusRepository;
    private final BatchStatusService batchStatusService;
    private final AsyncBatchRunner asyncBatchRunner;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMarkingCompleted(MarkingCompletedEvent event) {
        Long rawSn = event.rawSn();
        log.info("[MarkingBatchBridge] handling marking completed rawSn={}", rawSn);

        Optional<LsRawDataStatus> opt = statusRepository.findById(rawSn);
        if (opt.isEmpty()) {
            log.warn("[MarkingBatchBridge] no status row rawSn={} — skipping", rawSn);
            return;
        }

        LsRawDataStatus stts = opt.get();
        if (SKIP_STATUSES.contains(stts.getDataSttsCd())) {
            log.info("[MarkingBatchBridge] already {} rawSn={} — skipping", stts.getDataSttsCd(), rawSn);
            return;
        }

        stts.markBatchQueued();
        batchStatusService.markStage(rawSn, BatchStage.PENDING);
        asyncBatchRunner.runAsync(rawSn);
        log.info("[MarkingBatchBridge] enqueued rawSn={}", rawSn);
    }
}
