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
            // process() 가 자체 catch 로 enqueueIfRetryable 을 호출하지 못하고 예외를 throw 하는 경로
            // (예: loadRaw 의 NOT_FOUND, markRawDataProcessing 의 상태머신 위반 — try 블록 진입 전 단계)가
            // 존재한다. pollReady() 가 이미 nextAttemptAt=null 로 "처리중" 표시했으므로, 여기서 재무장하지
            // 않으면 엔트리가 영구 잔존하여 재시도가 무음 중단된다(BE-2). 따라서 예외 시 큐를 재무장한다.
            // maxAttempts 초과면 enqueueIfRetryable 가 false 반환 → FAILED 고정(정상 종료).
            boolean reArmed = retryQueue.enqueueIfRetryable(rawSn);
            log.error("[BatchRetry] unexpected failure rawSn={} reArmed={} err={}",
                    rawSn, reArmed, e.getMessage());
        }
    }
}
