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
 * 운영에서는 클라이언트(VlmClient/ExternalAugmentClient/...) 발급 시 채널을 명시할 수 있도록 후속 보강 가능.
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
     * Phase 3 — channel 명시 발급 기록. 호출자(VlmClient/ExternalAugmentClient)가 채널을 명시한다.
     */
    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordIssued(String idempotencyKey, String channel, String externalJobId) {
        recordIssued(idempotencyKey, channel, externalJobId, null);
    }

    /**
     * VLM describe 콜백 정합 — channel + rawSn 명시 발급 기록.
     * 위탁 요청 시 {@code (request_id, rawSn)} 매핑을 영속해 결과 수신부의 역조회를 지원한다.
     */
    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordIssued(String idempotencyKey, String channel, String externalJobId, Long rawSn) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        String safeChannel = (channel == null || channel.isBlank()) ? CHANNEL_UNKNOWN : channel;
        try {
            if (repository.existsById(idempotencyKey)) {
                return; // 불변 보장 — 이미 발급된 키는 다시 기록하지 않음
            }
            repository.save(LsWebhookIdempotency.issue(idempotencyKey, safeChannel, externalJobId, rawSn));
        } catch (DataIntegrityViolationException e) {
            // 동시 발급 — 멱등 반환
            log.debug("[WebhookLedger] recordIssued unique violation (idempotent)");
        }
    }

    /**
     * H1 — 위탁 수락(ACK) 기록. 완료 핸들러가 <b>전용 풀 스레드</b>(ambient tx 없음)에서 호출하므로
     * {@code REQUIRES_NEW} 로 독립 커밋한다. 조건부 UPDATE 라 이미 진행/종결된 행은 0행 no-op 이다.
     */
    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordAckReceived(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        int applied = repository.claimAckReceived(idempotencyKey, java.time.LocalDateTime.now());
        if (applied != 1) {
            // 정상 케이스 — 콜백이 ACK 보다 먼저 처리(PROCESSED)했거나 스위퍼가 이미 회수(FAILED)했다.
            log.debug("[WebhookLedger] ack ignored (already settled)");
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

    /**
     * VLM 결과 수신 등 side-effect 와 원자적으로 커밋되어야 하는 경로용 — 호출자 트랜잭션에 참여(REQUIRED).
     * 정상 흐름에서는 이미 ISSUED 로 발급된 행을 dirty-update 하므로 INSERT/DIVE 가 없다.
     * 예외 발생 시 catch 하지 않고 전파하여 outer 트랜잭션과 함께 롤백되게 한다(데이터 유실 방지, C-1).
     */
    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRED)
    public void markProcessedInTx(String idempotencyKey, String externalJobId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        LsWebhookIdempotency entity = repository.findById(idempotencyKey)
                .orElseGet(() -> LsWebhookIdempotency.issue(idempotencyKey, CHANNEL_UNKNOWN, externalJobId));
        entity.markProcessed(externalJobId);
        repository.save(entity);
    }

    @Override
    @Transactional(value = "controlTransactionManager", readOnly = true, propagation = Propagation.SUPPORTS)
    public Optional<Entry> lookup(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return repository.findById(idempotencyKey).map(this::toEntry);
    }

    /**
     * 처리 목적 조회 — 비관적 락(FOR UPDATE)으로 동일 idempotencyKey 동시 콜백을 직렬화한다(CWE-362).
     * 호출자 트랜잭션에 참여(REQUIRED)해야 락이 해당 트랜잭션 커밋까지 유지된다.
     */
    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRED)
    public Optional<Entry> lookupForProcessing(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return repository.findByIdmpKeyForUpdate(idempotencyKey).map(this::toEntry);
    }

    /**
     * 원장 행 → 콜백 수신부용 Entry.
     *
     * <p><b>비-PROCESSED 는 전부 {@code ISSUED} 로 매핑</b>한다(불변 계약): 스위퍼가 회수 표식으로 쓰는
     * {@code FAILED} 도, ACK 수신 표식인 {@code ACCEPTED}(H1)도 여기서는 "발급됨"이다. 그래야 지각 콜백이
     * 401 로 거부되지 않고 정상 처리된다.
     */
    private Entry toEntry(LsWebhookIdempotency e) {
        return new Entry(
                LsWebhookIdempotency.STATE_PROCESSED.equals(e.getSttsCd()) ? State.PROCESSED : State.ISSUED,
                e.getOtsdJobId(),
                e.getRawSn(),
                e.getRegDt());
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
