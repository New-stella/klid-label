package kr.co.cudo.authoring.batch.retry;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.co.cudo.authoring.observability.metrics.BatchMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 배치 재시도 큐 <b>stale RETRYING 회수 스윕</b> (B-ISSUE-83, Phase 9-C).
 *
 * <h3>막는 실패 모드</h3>
 * <p>{@code LS_BAT_RTY_WTNG.STTS_CD='RETRYING'} 은 폴링 노드가 원자 클레임
 * ({@code LsBatRtyWtngRepository#claimAtomically})으로 찍은 "처리 중" 표시다. {@code PENDING} 복귀는
 * {@code BatchRetryQuartzJob#execute} 가 <b>정상적으로 예외를 받을 때만</b> 일어나므로, 그 노드가
 * 처리 도중 죽으면(kill -9 · OOM · 순단) 아무도 되돌리지 않아 항목이 <b>영구 RETRYING</b> 으로 남는다.
 * 배포 토폴로지가 2노드 Active-Active 인데 살아 있는 노드조차 그 항목을 집을 수 없어, 해당 영상의
 * 배치 재시도가 <b>무음으로 영구 중단</b>된다(FAILED 상태 그대로 방치).
 *
 * <h3>오회수 방지 — 임계 + 하한 clamp</h3>
 * <p>정상 처리 중인 RETRYING 을 뺏으면 같은 영상이 두 노드에서 동시에 처리된다. 그래서 회수 대상은
 * <b>마지막 갱신({@code MDFCN_DT})이 {@code stale-timeout-minutes} 를 넘긴</b> 행뿐이다(클레임 시각이
 * MDFCN_DT 에 찍힌다). 임계는 {@link #MIN_STALE_TIMEOUT_MINUTES} 로 <b>하한 clamp</b> 한다 — 0/음수/과소
 * 설정으로 임계가 무너지면 방금 클레임한 정상 항목까지 전부 회수돼 이중 처리가 전면화되기 때문이다
 * (Phase 8-A {@code AugmentJobExpirySweeper} · Phase 9-A 가드와 동일한 fail-safe 원칙).
 *
 * <p>기본 임계 {@link #DEFAULT_STALE_TIMEOUT_MINUTES}분은 배치 1건 처리(비식별·VLM·프레임추출·오토라벨)가
 * 가장 오래 걸리는 경우를 충분히 덮도록 넉넉하게 잡았다. MDFCN_DT 는 처리 중에 갱신되지 않으므로
 * 임계는 "최장 처리 시간"보다 커야 한다.
 *
 * <h3>무한 부활 금지</h3>
 * <p>회수는 죽은 시도를 1회로 계상하고({@code RTY_NMTM+1}), 재시도 상한에 도달한 항목은 복귀시키지
 * 않고 {@code EXHAUSTED} 로 종결한다({@code BatchRetryQueue#sweepStaleRetrying}). 같은 항목에서 계속
 * 죽는 노드가 있어도 최대 {@code MAX_RTY_NMTM} 회에서 멈춘다.
 *
 * <h3>왜 {@code @Scheduled} 가 아닌 전용 executor 인가</h3>
 * <p>본 애플리케이션은 {@code @EnableScheduling} 을 특정 기능 플래그가 켜질 때만 활성화한다. 무조건적
 * {@code @EnableScheduling} 을 추가하면 게이팅 없이 등록된 <b>남의</b> {@code @Scheduled} 잡까지 전 환경·전
 * 테스트에서 함께 깨운다. 그래서 데몬 스레드 1개짜리 전용 스케줄러를 쓴다
 * ({@code AugmentJobExpirySweeper}/{@code ControlNotifyDebouncer} 동형).
 *
 * <h3>토글 독립</h3>
 * <p>이 스윕은 <b>자기 토글</b>({@code authoring.batch.retry.stale-reclaim.enabled}, 기본 true)만 본다.
 * {@code authoring.batch.enabled}·{@code control-notify.enabled} 같은 남의 스위치에 얹지 않는다 —
 * 과거 이 리포에서 무관한 토글에 종속돼 운영에서만 무증상 중단된 사고가 있었다. 배치가 꺼진 환경에서는
 * RETRYING 행이 생기지 않아 후보 0건으로 무해하게 돈다.
 */
@Slf4j
@Component
public class BatchRetryStaleReclaimSweeper {

    /** 오설정(0/음수) 시 안전 폴백 — 무갱신 경과 임계(분). 배치 1건 최장 처리 시간보다 넉넉해야 한다. */
    private static final int DEFAULT_STALE_TIMEOUT_MINUTES = 180;
    /**
     * 임계 하한(분). 이보다 낮추면 <b>정상 처리 중</b>인 RETRYING 까지 회수돼 같은 영상이 두 노드에서
     * 동시에 처리된다. 오설정으로도 그 상태에 들어가지 않도록 바닥을 둔다(fail-safe).
     */
    private static final int MIN_STALE_TIMEOUT_MINUTES = 30;
    /** 오설정 시 안전 폴백 — tick 당 회수 건수 상한(CWE-770). */
    private static final int DEFAULT_BATCH_SIZE = 50;
    /** 스케줄 주기 하한(ms) — 과도한 폴링으로 DB 를 두드리지 않게 한다. */
    private static final long MIN_INTERVAL_MS = 60_000L;

    private final BatchRetryQueue retryQueue;
    private final BatchMetrics metrics;

    private final boolean enabled;
    private final long intervalMs;
    private final long initialDelayMs;
    private final int staleTimeoutMinutes;
    private final int batchSize;

    private ScheduledExecutorService scheduler;

    public BatchRetryStaleReclaimSweeper(
            BatchRetryQueue retryQueue,
            BatchMetrics metrics,
            @Value("${authoring.batch.retry.stale-reclaim.enabled:true}") boolean enabled,
            @Value("${authoring.batch.retry.stale-reclaim.interval-ms:900000}") long intervalMs,
            @Value("${authoring.batch.retry.stale-reclaim.initial-delay-ms:300000}") long initialDelayMs,
            @Value("${authoring.batch.retry.stale-reclaim.stale-timeout-minutes:180}") int staleTimeoutMinutes,
            @Value("${authoring.batch.retry.stale-reclaim.batch-size:50}") int batchSize) {
        this.retryQueue = retryQueue;
        this.metrics = metrics;
        this.enabled = enabled;
        this.intervalMs = Math.max(MIN_INTERVAL_MS, intervalMs);
        this.initialDelayMs = Math.max(0L, initialDelayMs);
        this.staleTimeoutMinutes = staleTimeoutMinutes < 1
                ? DEFAULT_STALE_TIMEOUT_MINUTES
                : Math.max(MIN_STALE_TIMEOUT_MINUTES, staleTimeoutMinutes);
        this.batchSize = batchSize < 1 ? DEFAULT_BATCH_SIZE : batchSize;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("[BatchRetry][Reclaim] stale RETRYING reclaim sweep disabled");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "batch-retry-stale-reclaim");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::run, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("[BatchRetry][Reclaim] stale reclaim scheduled intervalMs={} staleTimeoutMinutes={}",
                intervalMs, staleTimeoutMinutes);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** 전용 스케줄러가 떠 있는가 — 배선 고정 테스트가 컨텍스트 경유로 확인한다. */
    public boolean isScheduled() {
        return scheduler != null;
    }

    /** 적용 중인 stale 임계(분) — 하한 clamp 적용 후 값. */
    public int staleTimeoutMinutes() {
        return staleTimeoutMinutes;
    }

    /**
     * 스윕 1회 — 예외를 삼켜 스케줄러 스레드 사망을 막는다(죽으면 회수가 조용히 영구 정지한다).
     *
     * <p>{@code RuntimeException} 만 잡으면 Error 계열이 스레드를 죽여 {@code scheduleWithFixedDelay} 가
     * 영구 정지하므로 {@code Throwable} 을 잡는다.
     *
     * @return 이번 tick 이 회수/종결한 건수
     */
    public int run() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleTimeoutMinutes);
            BatchRetryQueue.StaleReclaimResult result = retryQueue.sweepStaleRetrying(cutoff, batchSize);
            if (result.total() > 0) {
                metrics.incrementRetryStaleReclaimed(result.reclaimed());
                metrics.incrementRetryStaleExhausted(result.exhausted());
                log.warn("[BatchRetry][Reclaim] stale RETRYING reclaimed={} exhausted={} staleTimeoutMinutes={}",
                        result.reclaimed(), result.exhausted(), staleTimeoutMinutes);
            }
            return result.total();
        } catch (Throwable e) {
            log.error("[BatchRetry][Reclaim] sweep failed reason={}", e.getClass().getSimpleName());
            return 0;
        }
    }
}
