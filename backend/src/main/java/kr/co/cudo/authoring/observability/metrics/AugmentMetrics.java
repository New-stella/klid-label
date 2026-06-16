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
 *   <li>{@code augment.ledger.issue} (Counter, tag status=success|failure) —
 *       멱등 키 발급(recordIssued) 성공/실패 집계. failure 는 콜백 미수신 위험(양성) 신호.</li>
 * </ul>
 */
@Component
public class AugmentMetrics {

    private static final String EXTERNAL_REQUEST = "augment.external.request";
    private static final String LEDGER_ISSUE = "augment.ledger.issue";
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

    public void ledgerIssueSuccess() {
        registry.counter(LEDGER_ISSUE, TAG_STATUS, STATUS_SUCCESS).increment();
    }

    public void ledgerIssueFailure() {
        registry.counter(LEDGER_ISSUE, TAG_STATUS, STATUS_FAILURE).increment();
    }
}
