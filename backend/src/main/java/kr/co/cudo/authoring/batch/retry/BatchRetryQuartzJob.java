package kr.co.cudo.authoring.batch.retry;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

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

    /**
     * M-1 — 재시도가 무의미한 "영구 실패" ErrorCode 집합.
     *
     * <p>영상이 DB 에서 사라진 {@link ErrorCode#NOT_FOUND} 같은 결정적 실패는 재처리해도 동일 결과이므로
     * 큐에서 즉시 제거하고 FAILED 로 확정한다(헛재시도 + 지수백오프 지연 제거). 보수적으로 명백한 것만
     * 포함하며, 일시적/예측 외 실패는 기존대로 재무장하여 재시도 기회를 보존한다.
     */
    private static final Set<ErrorCode> PERMANENT_FAILURE_CODES = EnumSet.of(ErrorCode.NOT_FOUND);

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
            // M-1 — 영구 실패(예: 영상이 DB 에 없는 NOT_FOUND)는 재처리해도 동일 결과이므로 재무장하지 않고
            // 큐에서 제거 + FAILED 확정한다. maxAttempts(3회)만큼 무의미하게 재시도하며 지수백오프로 수십 분간
            // 감지가 지연되던 결함을 제거한다.
            if (isPermanentFailure(e)) {
                retryQueue.clear(rawSn);
                log.error("[BatchRetry] permanent failure -- FAILED fixed rawSn={} code={}",
                        rawSn, ((CustomException) e).getErrorCode());
                return;
            }
            // 그 외(일시적/예측 외) 실패는 재무장한다. process() 가 자체 catch 로 enqueueIfRetryable 을
            // 호출하지 못하고 예외를 throw 하는 경로(예: markRawDataProcessing 상태머신 위반 — try 블록 진입
            // 전 단계)가 존재한다. pollReady() 가 이미 nextAttemptAt=null 로 "처리중" 표시했으므로, 여기서
            // 재무장하지 않으면 엔트리가 영구 잔존하여 재시도가 무음 중단된다(BE-2).
            // maxAttempts 초과면 enqueueIfRetryable 가 false 반환 → FAILED 고정(정상 종료).
            boolean reArmed = retryQueue.enqueueIfRetryable(rawSn);
            log.error("[BatchRetry] unexpected failure rawSn={} reArmed={} err={}",
                    rawSn, reArmed, e.getMessage());
        }
    }

    /**
     * 예외가 재시도 무의미한 영구 실패인지 판정한다.
     *
     * <p>{@link CustomException} 이고 그 {@link ErrorCode} 가 {@link #PERMANENT_FAILURE_CODES} 에 속할 때만
     * true. 그 외(런타임/예측 외/일시적 외부 오류)는 재시도 기회를 보존하기 위해 false.
     */
    private boolean isPermanentFailure(RuntimeException e) {
        return e instanceof CustomException ce
                && PERMANENT_FAILURE_CODES.contains(ce.getErrorCode());
    }
}
