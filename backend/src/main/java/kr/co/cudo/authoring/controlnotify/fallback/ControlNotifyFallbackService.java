package kr.co.cudo.authoring.controlnotify.fallback;

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

/**
 * Phase 3 -- 관제서버 통지 fallback 큐 적재/처리 서비스.
 *
 * <p>외부 통지 영속 fallback 큐(idempotency + dead-letter + 백오프 재시도) 패턴.
 * 환경변수 {@code authoring.control-notify.enabled} 가 true 일 때만 적재.
 *
 * <h3>호출 시점</h3>
 * <ul>
 *   <li>ControlNotifyService.sendCompleted / sendModified 의 catch 블록 -- 관제서버 호출 실패 시.</li>
 * </ul>
 */
@Slf4j
@Service
public class ControlNotifyFallbackService {

    /**
     * CWE-770 -- 큐 깊이 상한.
     * 관제서버 장기 장애 시 무한 적재로 DB 스토리지 압박을 막기 위한 hard cap.
     */
    public static final long MAX_QUEUE_DEPTH = 10000L;

    /** 깊이 측정 시 합산할 활성 상태 목록 (DEAD_LETTER/SUCCEEDED 제외). */
    private static final List<String> ACTIVE_STATUSES = List.of(
            LsControlNotifyFallback.STATUS_PENDING,
            LsControlNotifyFallback.STATUS_RETRYING);

    private final LsControlNotifyFallbackRepository repository;
    private final boolean enabled;
    @Nullable
    private final MeterRegistry meterRegistry;

    @Autowired
    public ControlNotifyFallbackService(LsControlNotifyFallbackRepository repository,
                                        @Value("${authoring.control-notify.enabled:false}") boolean enabled,
                                        @Nullable MeterRegistry meterRegistry) {
        this.repository = repository;
        this.enabled = enabled;
        this.meterRegistry = meterRegistry;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * fallback 적재.
     *
     * @return 적재된 idempotencyKey. enabled=false 면 {@link Optional#empty()}.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Optional<String> enqueuePending(String idempotencyKey,
                                            String eventType,
                                            Long rawSn,
                                            String payloadJson) {
        if (!enabled) {
            return Optional.empty();
        }
        // CWE-770: 큐 깊이 상한
        long active = repository.countBySttsCdIn(ACTIVE_STATUSES);
        if (active >= MAX_QUEUE_DEPTH) {
            log.error("[ControlNotifyFallback] queue full active={} max={} -- rejecting enqueue rawSn={}",
                    active, MAX_QUEUE_DEPTH, rawSn);
            incrementCounter("control.notify.fallback.queue.full");
            throw new IllegalStateException(
                    "Control notify fallback queue is full (" + active + " >= " + MAX_QUEUE_DEPTH + ")");
        }
        try {
            LsControlNotifyFallback entity =
                    LsControlNotifyFallback.pending(idempotencyKey, eventType, rawSn, payloadJson);
            repository.save(entity);
            log.warn("[ControlNotifyFallback] enqueued eventType={} rawSn={} key={}",
                    eventType, rawSn, idempotencyKey);
            incrementCounter("control.notify.fallback.enqueued");
            return Optional.of(idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            // 동일 idempotencyKey 충돌 -- 이미 적재됨. 멱등 반환.
            log.info("[ControlNotifyFallback] idempotencyKey already queued (idempotent) key={}", idempotencyKey);
            return Optional.of(idempotencyKey);
        }
    }

    /**
     * 즉시 발송 성공 관찰 행 적재.
     *
     * <p>{@code STTS_CD=SUCCEEDED}(터미널) + {@code SEND_RSLT_CD=SUCCESS} 행을 INSERT 해
     * 성공 발송을 DB 로 관찰 가능하게 한다. 터미널 상태라 재시도 잡·depth 게이지에 영향 없음.
     *
     * <p>CWE-362: 동일 {@code idmpKey} 중복(다중 인스턴스) 시 unique 충돌을 삼켜 멱등 반환.
     *
     * @return 적재된 idempotencyKey. enabled=false 면 {@link Optional#empty()}.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Optional<String> recordImmediateSuccess(String idempotencyKey,
                                                   String eventType,
                                                   Long rawSn,
                                                   String payloadJson) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            LsControlNotifyFallback entity =
                    LsControlNotifyFallback.succeeded(idempotencyKey, eventType, rawSn, payloadJson);
            repository.save(entity);
            log.info("[ControlNotifyFallback] send success recorded eventType={} rawSn={} key={}",
                    eventType, rawSn, idempotencyKey);
            incrementCounter("control.notify.fallback.send_success");
            return Optional.of(idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            // 동일 idempotencyKey 충돌 -- 이미 적재됨. 멱등 반환.
            log.info("[ControlNotifyFallback] success row already recorded (idempotent) key={}", idempotencyKey);
            return Optional.of(idempotencyKey);
        }
    }

    /**
     * 재시도 항목을 RETRYING 으로 변경.
     *
     * <p>CWE-362 -- 원자 CAS UPDATE. 멀티 인스턴스 동시 실행 시 한 인스턴스만 성공.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Optional<LsControlNotifyFallback> claimForRetry(Long queueSn) {
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
            log.info("[ControlNotifyFallback] succeeded queueSn={}", queueSn);
            incrementCounter("control.notify.fallback.succeeded");
        });
    }

    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markFailedAndSchedule(Long queueSn, String error) {
        repository.findById(queueSn).ifPresent(item -> {
            boolean dead = item.failAndSchedule(error);
            if (dead) {
                log.error("[ControlNotifyFallback] dead-letter queueSn={} retryCount={}",
                        queueSn, item.getRtryCnt());
                incrementCounter("control.notify.fallback.dead_letter");
            } else {
                log.warn("[ControlNotifyFallback] retry scheduled queueSn={} retryCount={} nextRetryAt={}",
                        queueSn, item.getRtryCnt(), item.getNextRtryDt());
                incrementCounter("control.notify.fallback.retry_scheduled");
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
