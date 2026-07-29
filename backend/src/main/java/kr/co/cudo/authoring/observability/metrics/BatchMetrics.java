package kr.co.cudo.authoring.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 배치 파이프라인 메트릭 (Phase 12).
 * <p>
 * 메트릭:
 *  - batch.processed (Counter, tag status=success/failed)
 *  - batch.duration  (Timer)
 *  - batch.retry.stale (Counter, tag outcome=reclaimed/exhausted) — B-ISSUE-83 stale RETRYING 회수 관측
 */
@Component
public class BatchMetrics {

    private final Counter processedSuccess;
    private final Counter processedFailed;
    private final Timer duration;
    private final Counter retryStaleReclaimed;
    private final Counter retryStaleExhausted;

    public BatchMetrics(MeterRegistry registry) {
        this.retryStaleReclaimed = Counter.builder("batch.retry.stale")
                .tag("outcome", "reclaimed")
                .description("노드 사망으로 영구 RETRYING 이 된 재시도 항목을 PENDING 으로 회수한 건수")
                .register(registry);
        this.retryStaleExhausted = Counter.builder("batch.retry.stale")
                .tag("outcome", "exhausted")
                .description("stale RETRYING 회수 중 재시도 상한 초과로 EXHAUSTED 종결한 건수")
                .register(registry);
        this.processedSuccess = Counter.builder("batch.processed")
                .tag("status", "success")
                .description("배치 처리 성공 건수")
                .register(registry);
        this.processedFailed = Counter.builder("batch.processed")
                .tag("status", "failed")
                .description("배치 처리 실패 건수")
                .register(registry);
        this.duration = Timer.builder("batch.duration")
                .description("배치 1건 처리 소요 시간")
                .register(registry);
    }

    public void incrementSuccess() { processedSuccess.increment(); }

    public void incrementFailed()  { processedFailed.increment(); }

    public void recordDuration(Duration d) { duration.record(d); }

    /** B-ISSUE-83 — stale RETRYING 을 PENDING 으로 회수한 건수(0 이 아니면 노드 비정상 종료가 있었다는 신호). */
    public void incrementRetryStaleReclaimed(int count) {
        if (count > 0) {
            retryStaleReclaimed.increment(count);
        }
    }

    /** B-ISSUE-83 — stale RETRYING 중 재시도 상한 초과로 종결한 건수(무한 부활 차단 관측). */
    public void incrementRetryStaleExhausted(int count) {
        if (count > 0) {
            retryStaleExhausted.increment(count);
        }
    }
}
