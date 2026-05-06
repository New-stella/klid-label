package kr.co.cudo.authoring.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * AI 추론(YOLO/SAM2/VLM) 호출 메트릭 (Phase 12).
 *  - ai.inference.duration (Timer, tag model)
 *  - ai.inference.failed   (Counter, tag model)
 */
@Component
public class AiInferenceMetrics {

    private final MeterRegistry registry;

    public AiInferenceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer durationTimer(String model) {
        return Timer.builder("ai.inference.duration")
                .tag("model", model)
                .description("AI 추론 모델 호출 소요 시간")
                .register(registry);
    }

    public Counter failedCounter(String model) {
        return Counter.builder("ai.inference.failed")
                .tag("model", model)
                .description("AI 추론 모델 호출 실패 건수")
                .register(registry);
    }
}
