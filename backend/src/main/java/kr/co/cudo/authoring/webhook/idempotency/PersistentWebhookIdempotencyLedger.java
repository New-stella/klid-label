package kr.co.cudo.authoring.webhook.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Webhook idempotency 원장의 영속 구현 — Phase 2 보강 (DEV_FIX 1차) 운영 기본.
 *
 * <p>{@code LS_WEBHOOK_IDEMPOTENCY} 테이블 기반. 재시작/멀티 인스턴스 환경에서도
 * 발급(ISSUED)/처리완료(PROCESSED) 상태가 유지된다.
 *
 * <p>UNIQUE 제약(PK = IDEMPOTENCY_KEY) 위반은 멱등 반환 (예외 미발생) — 동시 markProcessed 호출
 * 안전 (CWE-362).
 *
 * <p>채널 추정: 이 영속 ledger 는 호출 경로(서비스) 가 명시적으로 채널을 주입하지 않는다.
 * Phase 2 단독 도입 시 추정 가능한 채널은 ResultService 호출자가 결정하지만,
 * 인터페이스 호환을 위해 채널 정보는 발급 시점에 별도로 알 수 없으면 "UNKNOWN" 으로 기록한다.
 * 운영에서는 클라이언트(VlmClient/DeidentifyClient/...) 발급 시 채널을 명시할 수 있도록 후속 보강 가능.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class PersistentWebhookIdempotencyLedger implements WebhookIdempotencyLedger {

    private static final String CHANNEL_UNKNOWN = "UNKNOWN";

    private final LsWebhookIdempotencyRepository repository;

    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordIssued(String idempotencyKey, String externalJobId) {
        recordIssued(idempotencyKey, CHANNEL_UNKNOWN, externalJobId);
    }

    /**
     * Phase 3 — channel 명시 발급 기록. 호출자(DeidentifyClient/VlmClient)가 채널을 명시한다.
     */
    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordIssued(String idempotencyKey, String channel, String externalJobId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        String safeChannel = (channel == null || channel.isBlank()) ? CHANNEL_UNKNOWN : channel;
        try {
            if (repository.existsById(idempotencyKey)) {
                return; // 불변 보장 — 이미 발급된 키는 다시 기록하지 않음
            }
            repository.save(LsWebhookIdempotency.issue(idempotencyKey, safeChannel, externalJobId));
        } catch (DataIntegrityViolationException e) {
            // 동시 발급 — 멱등 반환
            log.debug("[WebhookLedger] recordIssued unique violation (idempotent)");
        }
    }

    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(String idempotencyKey, String externalJobId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        try {
            Optional<LsWebhookIdempotency> existing = repository.findById(idempotencyKey);
            if (existing.isPresent()) {
                LsWebhookIdempotency entity = existing.get();
                entity.markProcessed(externalJobId);
                // dirty checking 으로 자동 갱신 — 명시적 save 불필요
                return;
            }
            // 발급 기록 없이 markProcessed 직접 호출 (방어적 경로). PROCESSED 로 신규 기록.
            LsWebhookIdempotency entity = LsWebhookIdempotency.issue(idempotencyKey, CHANNEL_UNKNOWN, externalJobId);
            entity.markProcessed(externalJobId);
            repository.save(entity);
        } catch (DataIntegrityViolationException e) {
            // 동시 markProcessed — 이미 다른 트랜잭션이 PROCESSED 로 갱신함. 멱등 반환.
            log.debug("[WebhookLedger] markProcessed unique violation (idempotent)");
        }
    }

    @Override
    @Transactional(value = "controlTransactionManager", readOnly = true, propagation = Propagation.SUPPORTS)
    public Optional<Entry> lookup(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return repository.findById(idempotencyKey)
                .map(e -> new Entry(
                        LsWebhookIdempotency.STATE_PROCESSED.equals(e.getSttsCd()) ? State.PROCESSED : State.ISSUED,
                        e.getOtsdJobId()));
    }

    /**
     * fail-secure — 실수로 호출되어도 데이터 보호. 테스트는 {@code InMemoryWebhookIdempotencyLedger.clear()} 사용.
     *
     * <p>DEV_FIX 2차 N-1 (CWE-732): 운영 영속 ledger 는 다른 서비스가
     * {@link WebhookIdempotencyLedger} 를 주입받아 실수로 {@code clear()} 를 호출하면
     * 전체 ledger 가 삭제되는 footgun 이 있었다. 이를 차단하기 위해
     * {@code UnsupportedOperationException} 으로 즉시 거부한다.
     *
     * <p>{@code @Transactional} 미적용 — 트랜잭션 시작 전 throw.
     */
    @Override
    public void clear() {
        throw new UnsupportedOperationException(
                "운영 영속 ledger 는 clear 를 지원하지 않습니다. 테스트는 InMemory 구현체를 사용하세요.");
    }
}
