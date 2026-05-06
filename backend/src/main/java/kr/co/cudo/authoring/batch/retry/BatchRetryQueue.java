package kr.co.cudo.authoring.batch.retry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 실패 영상 재시도 큐 (Phase 5).
 * <p>
 * - 영상별 재시도 횟수 + 다음 재시도 가능 시각을 보관.
 * - 최대 N회까지 지수백오프 (60s, 120s, 240s, ...).
 * - 단일 인스턴스 in-memory 큐 (운영은 Redis/RabbitMQ로 확장 가능).
 *
 * 보안:
 * - Race Condition: ConcurrentHashMap + AtomicInteger.
 */
@Slf4j
@Component
public class BatchRetryQueue {

    private final int maxAttempts;
    private final int initialDelaySec;

    /** rawSn → 재시도 메타. */
    private final Map<Long, RetryEntry> entries = new ConcurrentHashMap<>();

    public BatchRetryQueue(@Value("${authoring.batch.retry.max-attempts:3}") int maxAttempts,
                            @Value("${authoring.batch.retry.initial-delay-sec:60}") int initialDelaySec) {
        this.maxAttempts = maxAttempts;
        this.initialDelaySec = initialDelaySec;
    }

    /**
     * 재시도 등록.
     * - 이미 maxAttempts 도달했으면 false 반환 (큐 등록 거부 — FAILED 고정).
     * - 등록 성공 시 retryCount++ 와 nextAttemptAt 갱신.
     */
    public boolean enqueueIfRetryable(Long rawSn) {
        if (rawSn == null) return false;
        RetryEntry e = entries.computeIfAbsent(rawSn, RetryEntry::new);
        int attempt = e.retryCount.incrementAndGet();
        if (attempt > maxAttempts) {
            log.warn("[BatchRetry] max attempts exceeded rawSn={} attempt={} max={}",
                    rawSn, attempt, maxAttempts);
            return false;
        }
        // 지수백오프 — attempt 가 커져도 overflow 안 되도록 shift 를 30 으로 캡 (최대 ~34시간).
        int shift = Math.min(attempt - 1, 30);
        long delaySec = (long) initialDelaySec * (1L << shift); // 60, 120, 240, ...
        e.nextAttemptAt = Instant.now().plusSeconds(delaySec);
        log.info("[BatchRetry] enqueued rawSn={} attempt={} delaySec={}", rawSn, attempt, delaySec);
        return true;
    }

    /** 재시도 가능 시각 도래한 가장 오래된 1건을 dequeue. 없으면 empty. */
    public Optional<Long> pollReady() {
        Instant now = Instant.now();
        return entries.entrySet().stream()
                .filter(en -> en.getValue().nextAttemptAt != null && !en.getValue().nextAttemptAt.isAfter(now))
                .min(Comparator.comparing(en -> en.getValue().nextAttemptAt))
                .map(Map.Entry::getKey)
                .map(rawSn -> {
                    entries.computeIfPresent(rawSn, (k, v) -> {
                        v.nextAttemptAt = null; // 처리 중 표시
                        return v;
                    });
                    return rawSn;
                });
    }

    public int retryCount(Long rawSn) {
        RetryEntry e = entries.get(rawSn);
        return e == null ? 0 : e.retryCount.get();
    }

    /** 성공 처리 시 호출 — 재시도 메타 제거. */
    public void clear(Long rawSn) {
        entries.remove(rawSn);
    }

    /** 디버그/모니터링용 — 현재 큐 상태 스냅샷. */
    public Map<Long, Integer> snapshot() {
        Map<Long, Integer> snap = new LinkedHashMap<>();
        for (Map.Entry<Long, RetryEntry> e : entries.entrySet()) {
            snap.put(e.getKey(), e.getValue().retryCount.get());
        }
        return snap;
    }

    /** 외부에서 동시성 검증 시 사용. 패키지 외부에는 노출하지 않는다. */
    Map<Long, RetryEntry> rawEntries() {
        return new HashMap<>(entries);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    static final class RetryEntry {
        final Long rawSn;
        final AtomicInteger retryCount = new AtomicInteger(0);
        volatile Instant nextAttemptAt;

        RetryEntry(Long rawSn) { this.rawSn = rawSn; }
    }
}
