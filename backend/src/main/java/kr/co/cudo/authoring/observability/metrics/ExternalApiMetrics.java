package kr.co.cudo.authoring.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 외부 시스템 API 호출 메트릭 (Phase 12).
 *  - external.api.duration (Timer, tag service=control/portal/deid/gitea/ai/generate)
 */
@Component
public class ExternalApiMetrics {

    private final MeterRegistry registry;

    public ExternalApiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer durationTimer(String service) {
        return Timer.builder("external.api.duration")
                .tag("service", service)
                .description("외부 API 호출 소요 시간")
                .register(registry);
    }
}
