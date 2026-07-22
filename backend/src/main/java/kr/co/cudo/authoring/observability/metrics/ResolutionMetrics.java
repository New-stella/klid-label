package kr.co.cudo.authoring.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 해상도 파생영상 확정 관찰성 메트릭 — 락-I/O 분리 리팩터(#3 cleanup 실패 잔존 감시).
 *
 * <ul>
 *   <li>{@code resolution.cleanup.failed} (Counter) — Phase B 산출 아티팩트(파생 비디오/리스케일 프레임)
 *       cleanup 후에도 파일이 잔존(삭제 실패)한 건수. 증가 시 스토리지 고아 파일 누적 신호이므로
 *       운영자가 수동 정리·알림 대상으로 삼는다.</li>
 * </ul>
 */
@Component
public class ResolutionMetrics {

    private static final String CLEANUP_FAILED = "resolution.cleanup.failed";

    private final MeterRegistry registry;

    public ResolutionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Phase B cleanup 후에도 파생 아티팩트가 잔존(삭제 실패)했을 때 1 증가. */
    public void cleanupFailed() {
        registry.counter(CLEANUP_FAILED).increment();
    }
}
