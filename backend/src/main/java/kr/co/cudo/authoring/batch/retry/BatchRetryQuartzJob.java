package kr.co.cudo.authoring.batch.retry;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

/**
 * 재시도 큐 처리 Quartz Job (Phase 5).
 *  - BATCH_RETRY_INTERVAL_SEC (기본 300초/5분) 간격 발화.
 *  - retryQueue.pollReady() 로 재시도 가능 1건 가져와 orchestrator.process() 호출.
 *  - 재시도 횟수 초과 (max-attempts 도달) 영상은 enqueueIfRetryable() 가 false 반환 → FAILED 고정.
 */
@Slf4j
@DisallowConcurrentExecution
public class BatchRetryQuartzJob implements Job {

    public static final String JOB_NAME = "batchRetryJob";
    public static final String JOB_GROUP = "batch";
    public static final String TRIGGER_NAME = "batchRetryTrigger";

    @Autowired
    private BatchRetryQueue retryQueue;

    @Autowired
    private BatchOrchestrator orchestrator;

    @Override
    public void execute(JobExecutionContext context) {
        Optional<Long> picked = retryQueue.pollReady();
        if (picked.isEmpty()) {
            return;
        }
        Long rawSn = picked.get();
        log.info("[BatchRetry] re-processing rawSn={} attempt={}", rawSn, retryQueue.retryCount(rawSn));
        try {
            orchestrator.process(rawSn);
        } catch (RuntimeException e) {
            log.error("[BatchRetry] unexpected failure rawSn={} err={}", rawSn, e.getMessage());
        }
    }
}
