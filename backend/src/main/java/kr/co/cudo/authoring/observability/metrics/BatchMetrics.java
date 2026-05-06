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
 */
@Component
public class BatchMetrics {

    private final Counter processedSuccess;
    private final Counter processedFailed;
    private final Timer duration;

    public BatchMetrics(MeterRegistry registry) {
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
}
