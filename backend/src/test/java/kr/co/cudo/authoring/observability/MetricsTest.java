package kr.co.cudo.authoring.observability;

import io.micrometer.core.instrument.MeterRegistry;
import kr.co.cudo.authoring.observability.metrics.BatchMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 12 — Micrometer 메트릭 등록 및 카운트 증가 검증.
 */
@SpringBootTest
@ActiveProfiles("local")
class MetricsTest {

    @Autowired private BatchMetrics batchMetrics;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    @DisplayName("Metrics_batch_processed_카운트_증가")
    void batchProcessedCounterIncreases() {
        double before = meterRegistry.counter("batch.processed", "status", "success").count();
        batchMetrics.incrementSuccess();
        batchMetrics.incrementSuccess();
        double after = meterRegistry.counter("batch.processed", "status", "success").count();
        assertThat(after - before).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Metrics_batch_duration_타이머_기록")
    void batchDurationTimerRecords() {
        long before = meterRegistry.timer("batch.duration").count();
        batchMetrics.recordDuration(Duration.ofMillis(120));
        long after = meterRegistry.timer("batch.duration").count();
        assertThat(after - before).isEqualTo(1L);
    }
}
