package kr.co.cudo.authoring.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 외부 증강 요청 관찰성 메트릭 (콜백 충실 플로우 Phase 1 — DEV_FIX MEDIUM-3).
 *
 * <ul>
 *   <li>{@code augment.external.request} (Counter, tag status=success|failure) —
 *       AFTER_COMMIT 리스너에서 {@code ExternalAugmentClient.requestAugment} 호출 결과 집계.
 *       장애 시 failure 카운트로 외부 연동 가용성을 모니터링한다.</li>
 *   <li>{@code augment.cleanup.failed} (Counter) — 프레임 재추출 Phase B 산출 아티팩트(재추출 프레임
 *       디렉토리) cleanup 후에도 파일이 잔존(삭제 실패)한 건수. 증가 시 스토리지 고아 파일 누적 신호이므로
 *       운영자가 수동 정리·알림 대상으로 삼는다(커넥션-점유 분리 리팩터 — 증강 경로).</li>
 * </ul>
 */
@Component
public class AugmentMetrics {

    private static final String EXTERNAL_REQUEST = "augment.external.request";
    private static final String CLEANUP_FAILED = "augment.cleanup.failed";
    private static final String TAG_STATUS = "status";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILURE = "failure";

    private final MeterRegistry registry;

    public AugmentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void externalRequestSuccess() {
        registry.counter(EXTERNAL_REQUEST, TAG_STATUS, STATUS_SUCCESS).increment();
    }

    public void externalRequestFailure() {
        registry.counter(EXTERNAL_REQUEST, TAG_STATUS, STATUS_FAILURE).increment();
    }

    /** 프레임 재추출 Phase B cleanup 후에도 아티팩트가 잔존(삭제 실패)했을 때 1 증가. */
    public void cleanupFailed() {
        registry.counter(CLEANUP_FAILED).increment();
    }
}
