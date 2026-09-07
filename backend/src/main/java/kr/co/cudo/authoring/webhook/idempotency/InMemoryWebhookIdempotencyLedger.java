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
 * <p>⚠ <b>그 토글을 켜면 AI 서버 노드 분산이 사실상 꺼진다</b> — 이 구현은 장비 식별자를 받는
 * 5-인자 발급 기록을 갖지 않아 인터페이스 디폴트를 타고, 그 디폴트는 {@code srvrId} 를 <b>조용히
 * 버린다</b>. 그러면 장비별 부하 집계가 전 장비 0건이 되어 선택기가 동률 처리로 <b>항상 첫 장비</b>를
 * 고른다(요청이 한 대로 몰린다). 단위 테스트 전용 토글이므로 고치지 않고 사실만 남긴다 — 운영
 * 프로파일에서 켜지 말 것.
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
        recordIssued(idempotencyKey, channel, externalJobId, rawSn, null, null);
    }

    /**
     * 추가 질문 축 — <b>보낸 질문 문구</b>까지 보관한다.
     *
     * <p>디폴트 구현이 그 값을 조용히 버리면, 결과 수신부가 「보낸 값」을 그대로 넘기는 배선이
     * 단위 시험에서 <b>전혀 검증되지 않는다</b>(장비 식별자가 실제로 그런 상태다 — 클래스 javadoc 참조).
     * 질문 축은 산출물에 그대로 실리는 값이라 그 사각을 만들지 않는다.
     *
     * <p>{@code srvrId} 는 이 구현의 상태 모델에 자리가 없어 종전대로 받지 않는다.
     */
    @Override
    public void recordIssued(String idempotencyKey, String channel, String externalJobId, Long rawSn,
                             String srvrId, String qstnCn) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        ledger.putIfAbsent(idempotencyKey,
                new Entry(State.ISSUED, externalJobId, rawSn, LocalDateTime.now(), channel, qstnCn));
    }

    @Override
    public void markProcessed(String idempotencyKey, String externalJobId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;
        // rawSn 매핑 + 발급 시각은 PROCESSED 전이 후에도 보존 (발급 시점 매핑 유지)
        Entry prev = ledger.get(idempotencyKey);
        Long rawSn = prev == null ? null : prev.rawSn();
        LocalDateTime issuedAt = prev == null ? null : prev.issuedAt();
        String channel = prev == null ? null : prev.channel();
        String qstnCn = prev == null ? null : prev.qstnCn();
        ledger.put(idempotencyKey,
                new Entry(State.PROCESSED, externalJobId, rawSn, issuedAt, channel, qstnCn));
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
