package kr.co.cudo.authoring.version.fallback;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3 — Gitea fallback 큐 적재/처리 서비스.
 *
 * <p>ccarch {@code if-gitea-contents} 의 "동기 REST + fallback 큐" 명세 구현.
 * 환경변수 {@code gitea.fallback.enabled} 가 true 일 때만 적재. false 면 즉시 예외 전파.
 *
 * <h3>호출 시점</h3>
 * <ul>
 *   <li>VersionService.commitLabels 의 catch 블록 — Gitea CircuitBreaker open 또는 호출 실패 시.
 *       기존 in-memory {@link kr.co.cudo.authoring.version.async.GiteaCommitFallbackQueue} 와 병행:
 *       enabled=false 인 환경(dev) 에서는 영속 큐 비활성, in-memory 큐만 동작. 회귀 없음.</li>
 * </ul>
 */
@Slf4j
@Service
public class GiteaFallbackQueueService {

    /**
     * MEDIUM-3 (CWE-770) — 큐 깊이 상한.
     * Gitea 장기 장애 시 무한 적재로 DB 스토리지 압박을 막기 위한 hard cap.
     * PENDING + RETRYING 상태 행의 합이 이 값 이상이면 신규 적재 거부.
     */
    public static final long MAX_QUEUE_DEPTH = 10000L;

    /** 깊이 측정 시 합산할 활성 상태 목록 (DEAD_LETTER/SUCCEEDED 제외). */
    private static final List<String> ACTIVE_STATUSES = List.of(
            LsGiteaFallbackQueue.STATUS_PENDING,
            LsGiteaFallbackQueue.STATUS_RETRYING);

    private final LsGiteaFallbackQueueRepository repository;
    private final boolean enabled;
    @Nullable
    private final MeterRegistry meterRegistry;

    @Autowired
    public GiteaFallbackQueueService(LsGiteaFallbackQueueRepository repository,
                                     @Value("${gitea.fallback.enabled:false}") boolean enabled,
                                     @Nullable MeterRegistry meterRegistry) {
        this.repository = repository;
        this.enabled = enabled;
        this.meterRegistry = meterRegistry;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * fallback 적재. idempotencyKey 가 null/blank 이면 UUID 자동 발급.
     *
     * @return 적재된 idempotencyKey (호출자가 응답에 포함하기 위함). enabled=false 면 {@link Optional#empty()}.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Optional<String> enqueuePut(@Nullable String idempotencyKey,
                                       String path,
                                       String branch,
                                       String commitMessage,
                                       String author,
                                       String contentBase64) {
        if (!enabled) {
            return Optional.empty();
        }
        // MEDIUM-3 (CWE-770): 큐 깊이 상한 — Gitea 장기 장애 시 무한 적재 차단.
        long active = repository.countByStatusIn(ACTIVE_STATUSES);
        if (active >= MAX_QUEUE_DEPTH) {
            log.error("[GiteaFallback] queue full active={} max={} — rejecting enqueue path={}",
                    active, MAX_QUEUE_DEPTH, path);
            incrementCounter("gitea.fallback.queue.full");
            throw new IllegalStateException(
                    "Gitea fallback queue is full (" + active + " >= " + MAX_QUEUE_DEPTH + ")");
        }
        String key = (idempotencyKey == null || idempotencyKey.isBlank())
                ? UUID.randomUUID().toString() : idempotencyKey;
        try {
            LsGiteaFallbackQueue entity =
                    LsGiteaFallbackQueue.putPending(key, path, branch, commitMessage, author, contentBase64);
            repository.save(entity);
            log.warn("[GiteaFallback] enqueued PUT path={} idempotencyKey={} contentLen={}",
                    path, key, contentBase64 == null ? 0 : contentBase64.length());
            incrementCounter("gitea.fallback.enqueued");
            return Optional.of(key);
        } catch (DataIntegrityViolationException e) {
            // S-4: 동일 idempotencyKey 충돌 — 이미 적재됨. 멱등 반환.
            log.info("[GiteaFallback] idempotencyKey already queued (idempotent) key={}", key);
            return Optional.of(key);
        }
    }

    /**
     * Quartz job 호출용 — 항목을 RETRYING 으로 변경.
     *
     * <p>HIGH-1 (CWE-362) — Read-Then-Write race 차단을 위한 원자 CAS UPDATE.
     * 멀티 인스턴스/Quartz 동시 실행 시 한 인스턴스만 UPDATE=1 반환 → 중복 commit 차단.
     * UPDATE=0 이면 다른 인스턴스가 이미 처리 중 → {@link Optional#empty()} 반환.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Optional<LsGiteaFallbackQueue> claimForRetry(Long queueSn) {
        int updated = repository.claimAtomically(queueSn, LocalDateTime.now());
        if (updated != 1) {
            return Optional.empty();
        }
        return repository.findById(queueSn);
    }

    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(Long queueSn) {
        repository.findById(queueSn).ifPresent(item -> {
            item.markSucceeded();
            log.info("[GiteaFallback] succeeded queueSn={}", queueSn);
            incrementCounter("gitea.fallback.succeeded");
        });
    }

    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markFailedAndSchedule(Long queueSn, String error) {
        repository.findById(queueSn).ifPresent(item -> {
            boolean dead = item.failAndSchedule(error);
            if (dead) {
                log.error("[GiteaFallback] dead-letter queueSn={} retryCount={}",
                        queueSn, item.getRetryCount());
                incrementCounter("gitea.fallback.dead_letter");
            } else {
                log.warn("[GiteaFallback] retry scheduled queueSn={} retryCount={} nextRetryAt={}",
                        queueSn, item.getRetryCount(), item.getNextRetryAt());
                incrementCounter("gitea.fallback.retry_scheduled");
            }
        });
    }

    private void incrementCounter(String name) {
        if (meterRegistry != null) {
            try {
                meterRegistry.counter(name).increment();
            } catch (Exception ignored) {
                // metric 실패는 비즈니스 흐름에 영향 없음
            }
        }
    }
}
