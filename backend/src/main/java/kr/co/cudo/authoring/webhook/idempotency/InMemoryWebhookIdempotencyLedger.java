package kr.co.cudo.authoring.webhook.idempotency;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Webhook idempotency 원장의 in-memory 구현 — 단위 테스트 전용.
 *
 * <p>운영에서는 {@link PersistentWebhookIdempotencyLedger} 를 사용한다.
 * 컴포넌트 자동 등록은 {@code authoring.webhook.idempotency.in-memory=true} 일 때만.
 *
 * <p>테스트 코드는 빈 등록 여부와 무관하게 {@code new InMemoryWebhookIdempotencyLedger()} 로 직접 인스턴스화 가능.
 */
@Component
@ConditionalOnProperty(name = "authoring.webhook.idempotency.in-memory", havingValue = "true")
public class InMemoryWebhookIdempotencyLedger implements WebhookIdempotencyLedger {

    /**
     * key = idempotencyKey, value = (State, externalJobId).
     */
    private final Map<String, Entry> ledger = new ConcurrentHashMap<>();

    @Override
    public void recordIssued(String idempotencyKey, String externalJobId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        ledger.putIfAbsent(idempotencyKey,
                new Entry(State.ISSUED, externalJobId, null, LocalDateTime.now()));
    }

    @Override
    public void recordIssued(String idempotencyKey, String channel, String externalJobId, Long rawSn) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        ledger.putIfAbsent(idempotencyKey,
                new Entry(State.ISSUED, externalJobId, rawSn, LocalDateTime.now(), channel));
    }

    @Override
    public void markProcessed(String idempotencyKey, String externalJobId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        // rawSn 매핑 + 발급 시각은 PROCESSED 전이 후에도 보존 (발급 시점 매핑 유지)
        Entry prev = ledger.get(idempotencyKey);
        Long rawSn = prev == null ? null : prev.rawSn();
        LocalDateTime issuedAt = prev == null ? null : prev.issuedAt();
        String channel = prev == null ? null : prev.channel();
        ledger.put(idempotencyKey, new Entry(State.PROCESSED, externalJobId, rawSn, issuedAt, channel));
    }

    /**
     * 미결 위탁 존재 판정 (@req R1) — in-memory 상태 모델에는 수락(ACCEPTED) 단계가 없으므로
     * {@code ISSUED} 행만 본다. 인터페이스 디폴트({@code false})를 그대로 쓰지 않는 이유: 테스트 구현이
     * 조용히 fail-open 이면 이 가드가 단위 테스트에서 전혀 검증되지 않는다.
     */
    @Override
    public boolean hasOutstandingSubmit(String channel, Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        return ledger.values().stream()
                .anyMatch(e -> e.state() == State.ISSUED && rawSn.equals(e.rawSn()));
    }

    @Override
    public Optional<Entry> lookup(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return Optional.ofNullable(ledger.get(idempotencyKey));
    }

    @Override
    public void clear() {
        ledger.clear();
    }
}
