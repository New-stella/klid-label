package kr.co.cudo.authoring.webhook.idempotency;

import java.util.Optional;

/**
 * Webhook 멱등성 원장 — Phase 2 도입, Phase 2 보강 (DEV_FIX 1차) 에서 인터페이스화.
 *
 * <p>외부 시스템(Deidentify/VLM/Augment) 위탁 시 본 도구가 발급한 {@code idempotencyKey} 를
 * "발급(issued)" 상태로 기록한 뒤, 결과 webhook 수신 시 다음 두 가지를 판별한다:
 *
 * <ol>
 *   <li><b>allowlist (S-1 보강)</b>: 발급한 적 없는 idempotencyKey 의 결과 인계는 거부 (UNAUTHORIZED)</li>
 *   <li><b>idempotency (S-2)</b>: 이미 처리(processed) 된 키의 재인계는 적재 스킵하고 200 OK 반환</li>
 * </ol>
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>{@link PersistentWebhookIdempotencyLedger} — {@code LS_WEBHOOK_IDEMPOTENCY} 영속 (운영 기본)</li>
 *   <li>{@link InMemoryWebhookIdempotencyLedger} — ConcurrentHashMap (단위 테스트 전용)</li>
 * </ul>
 *
 * <p>두 구현 모두 동일한 메서드 시그니처를 유지하여 기존 ResultService 코드는 변경 없이 사용할 수 있다.
 */
public interface WebhookIdempotencyLedger {

    enum State {
        ISSUED,
        PROCESSED
    }

    record Entry(State state, String externalJobId) {}

    /**
     * 외부 위탁 시 발급한 idempotencyKey 를 등록한다.
     * 동일 키가 이미 존재하면 무시 (불변 보장).
     */
    void recordIssued(String idempotencyKey, String externalJobId);

    /**
     * 처리 완료 마킹 — 이후 동일 키 재인계는 멱등 200 OK 로 응답되도록 한다.
     * UNIQUE 제약 위반(동시 호출)은 멱등 반환 (예외 미발생).
     */
    void markProcessed(String idempotencyKey, String externalJobId);

    Optional<Entry> lookup(String idempotencyKey);

    /** 발급 여부만 빠르게 확인. */
    default boolean isIssued(String idempotencyKey) {
        return lookup(idempotencyKey).isPresent();
    }

    /** 이미 처리 완료된 키인지 확인. */
    default boolean isProcessed(String idempotencyKey) {
        return lookup(idempotencyKey).map(e -> e.state() == State.PROCESSED).orElse(false);
    }

    /** 테스트 전용 — 상태 초기화. 운영 구현에서는 no-op 가능. */
    void clear();
}
