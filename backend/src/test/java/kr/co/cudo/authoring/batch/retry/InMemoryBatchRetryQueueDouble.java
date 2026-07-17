package kr.co.cudo.authoring.batch.retry;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트 전용 in-memory {@link BatchRetryQueue} 테스트 더블.
 *
 * <p>운영 큐는 B2 에서 DB 영속({@code LS_BAT_RTY_WTNG})으로 전환되어 DB/트랜잭션이 필요하다.
 * {@link kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator} 순수 단위 테스트(비-Spring)는
 * 실 DB 없이 큐와의 상호작용(enqueue/clear/retryCount)만 검증하면 되므로, 구 in-memory 동작을 그대로
 * 재현하는 본 더블로 대체한다. DB 영속·2노드 정합은 {@link BatchRetryQueueIT}(실 PostgreSQL) 가 검증한다.
 *
 * <p>{@code super(null, …)} 로 repository 는 사용하지 않으며(모든 public 메서드 override), 지수백오프·
 * 최대 시도 초과 거부·retryCount(거부 시도 포함) 의미를 구 구현과 동일하게 유지한다.
 */
public class InMemoryBatchRetryQueueDouble extends BatchRetryQueue {

    private final int maxAttempts;
    private final int initialDelaySec;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    public InMemoryBatchRetryQueueDouble(int maxAttempts, int initialDelaySec) {
        super(null, maxAttempts, initialDelaySec);
        this.maxAttempts = maxAttempts;
        this.initialDelaySec = initialDelaySec;
    }

    @Override
    public boolean enqueueIfRetryable(Long rawSn) {
        if (rawSn == null) return false;
        Entry e = entries.computeIfAbsent(rawSn, k -> new Entry());
        int attempt = e.retryCount.incrementAndGet();
        if (attempt > maxAttempts) {
            return false;
        }
        int shift = Math.min(attempt - 1, 30);
        long delaySec = (long) initialDelaySec * (1L << shift);
        e.nextAttemptAt = Instant.now().plusSeconds(delaySec);
        return true;
    }

    @Override
    public Optional<Long> pollReady() {
        Instant now = Instant.now();
        return entries.entrySet().stream()
                .filter(en -> en.getValue().nextAttemptAt != null && !en.getValue().nextAttemptAt.isAfter(now))
                .min(Comparator.comparing(en -> en.getValue().nextAttemptAt))
                .map(Map.Entry::getKey)
                .map(rawSn -> {
                    entries.computeIfPresent(rawSn, (k, v) -> {
                        v.nextAttemptAt = null;
                        return v;
                    });
                    return rawSn;
                });
    }

    @Override
    public int retryCount(Long rawSn) {
        Entry e = entries.get(rawSn);
        return e == null ? 0 : e.retryCount.get();
    }

    @Override
    public void clear(Long rawSn) {
        entries.remove(rawSn);
    }

    @Override
    public void clearIfIdle(Long rawSn) {
        // in-memory 더블은 RETRYING 부기를 별도로 추적하지 않으므로 clear 와 동일하게 제거한다.
        entries.remove(rawSn);
    }

    @Override
    public int maxAttempts() {
        return maxAttempts;
    }

    private static final class Entry {
        final AtomicInteger retryCount = new AtomicInteger(0);
        volatile Instant nextAttemptAt;
    }
}
