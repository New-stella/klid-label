package kr.co.cudo.authoring.augment.listener;

import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 증강 요청 건별 AFTER_COMMIT 리스너 — 고아 ledger 키 제거 (DEV_FIX HIGH #1).
 *
 * <p>{@code AugmentRequestService.request()} 의 요청 트랜잭션이 <b>커밋된 이후에만</b>
 * 멱등 키 발급 + 외부 콜백 컨텍스트 전달을 수행한다. 요청 트랜잭션이 롤백되면 본 리스너는
 * 발화하지 않으므로 LS_DATA_AUG 행 없이 LS_WEBHOOK_IDEMPOTENCY 키만 고아로 남는 일이 없다.
 *
 * <p>분리 이유(self-invocation 주의): {@code @TransactionalEventListener(AFTER_COMMIT)} 는
 * 발행 서비스와 <b>별도 빈</b>으로 두어야 정상 동작하므로 본 클래스를 별도 {@code @Component} 로 둔다.
 *
 * <p>건별 격리: ledger.recordIssued / externalClient.requestAugment 호출은 try/catch 로
 * 격리하며, 한 건의 실패가 다른 건을 막지 않는다. ledger 발급 실패 시 aug 행은 남고 콜백이
 * 안 올 수 있으나, 이는 재시도 가능한 양성 상태이므로 고아 키보다 안전하다 (WARN 로그 + 메트릭).
 *
 * <p>본 AFTER_COMMIT 구조는 Phase 2(dev 콜백 시뮬레이션)에서 그대로 재사용된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AugmentRequestBridge {

    private final WebhookIdempotencyLedger ledger;
    private final ExternalAugmentClient externalClient;
    private final AugmentMetrics metrics;

    /**
     * 요청 트랜잭션 커밋 후 건별 멱등 키 발급 + 외부 콜백 컨텍스트 전달.
     * 발행 측이 트랜잭션 컨텍스트 안에서 이벤트를 publish 하므로 AFTER_COMMIT 으로 수신한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAugmentRequested(AugmentRequestedItemEvent event) {
        Long originAugSn = event.originAugSn();

        // 1) 멱등 키 발급 (allowlist 등록) — 커밋 확정 후이므로 고아 키가 발생하지 않는다.
        boolean issued = false;
        try {
            ledger.recordIssued(event.idempotencyKey(),
                    LsWebhookIdempotency.CHANNEL_AUGMENT, event.externalJobId());
            metrics.ledgerIssueSuccess();
            issued = true;
        } catch (Exception e) {
            // ledger 발급 실패 — aug 행은 유지되나 콜백 매칭이 안 될 수 있음(재시도 가능 양성 상태).
            metrics.ledgerIssueFailure();
            log.warn("[Augment] ledger issue failed (continue) originAugSn={} err={}",
                    originAugSn, sanitize(e.getMessage()));
        }

        // 2) 외부 콜백 컨텍스트 전달 (best-effort). ledger 발급 실패 시 콜백이 매칭되지 않으므로
        //    외부 호출을 건너뛰어 불필요한 외부 작업 트리거를 막는다.
        if (!issued) {
            return;
        }
        try {
            externalClient.requestAugment(originAugSn, event.augType(),
                    event.idempotencyKey(), event.externalJobId(), event.callbackUrl());
            metrics.externalRequestSuccess();
        } catch (Exception e) {
            metrics.externalRequestFailure();
            log.warn("[Augment] external request failed (continue) originAugSn={} err={}",
                    originAugSn, sanitize(e.getMessage()));
        }
    }

    /** Log Injection (CWE-117) 방어 — CR/LF 제거. */
    private static String sanitize(String value) {
        if (value == null) return null;
        return value.replace('\n', '_').replace('\r', '_');
    }
}
