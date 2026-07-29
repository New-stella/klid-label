package kr.co.cudo.authoring.augment.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 증강 외부 위탁 job <b>만료 스윕</b> — 비종결로 떠 있는 job 을 회수해 종결을 보장한다 (Phase 8-A).
 *
 * <h3>막는 실패 모드</h3>
 * <p>{@code LS_DATA_AUG_JOB} 이 비종결(RECEIVED/RUNNING)로 영원히 남으면 롤업
 * ({@code AugmentJobRollup})의 pending 집합이 비지 않아 <b>증강 1건이 PENDING 에 영구 고착</b>된다.
 * 비종결로 남는 경로는 셋이다:
 * <ol>
 *   <li><b>취소</b> — 「생성형 AI API 연동명세서 v1.1」의 웹훅은 <b>진행·결과</b> 이벤트
 *       (RUNNING/SUCCEEDED/FAILED)만 발신한다. 취소({@code CANCELED})는 취소 API 응답으로만
 *       통보되므로, 우리가 호출하지 않은 취소(벤더 측 운영 취소)는 우리에게 도달하지 않는다.</li>
 *   <li><b>콜백 검증 실패(400)</b> — 재전송 여지를 남기려 상태를 바꾸지 않는데, 외부가 재시도를
 *       포기하면 그대로 비종결로 남는다.</li>
 *   <li><b>외부 무응답</b> — 위탁 후 아무 웹훅도 오지 않는 경우.</li>
 * </ol>
 *
 * <h3>회수 축은 둘이다 (적대검증 2차 MEDIUM-2)</h3>
 * <p>위 job 축만 훑으면 <b>job 행이 0건인 PENDING 증강</b>을 아무도 집지 못한다. 위탁 전 실패 롤업이
 * 예외로 끝나면(DB 순단·커넥션 고갈 등) job 도 콜백도 없이 PENDING 만 남는데, 이를 <b>깨울 주체가
 * 없다</b>(보류 재개 리스너는 폐기됐다). 그래서 {@link #sweepOrphanPendingAugments} 축을 함께 돈다.
 * 위탁 직후 짧은 창만 회수 대상에서 제외한다(비식별 신고 구간은 제외하지 않는다).
 *
 * <h3>왜 {@code @Scheduled} 가 아닌 전용 executor 인가</h3>
 * <p>본 애플리케이션은 {@code @EnableScheduling} 을 특정 기능 플래그가 켜질 때만 활성화한다. 무조건적
 * {@code @EnableScheduling} 을 추가하면 게이팅 없이 등록된 <b>남의</b> {@code @Scheduled} 잡
 * ({@code PortalUploadSweepJob}·{@code TusUploadCleanupJob})까지 전 환경·전 테스트에서 함께 깨운다.
 * 그래서 데몬 스레드 1개짜리 전용 스케줄러를 쓴다({@code RoleClaimAttemptPurgeJob} 동형).
 *
 * <h3>토글 독립</h3>
 * <p>이 스윕은 <b>자기 토글</b>({@code authoring.augment.job-expiry.enabled}, 전 프로파일 기본 true)만
 * 본다. 외부 위탁 모드({@code authoring.augment.external.mode})·관제 통지 토글
 * ({@code authoring.control-notify.enabled}) 같은 남의 스위치에 얹지 않는다 — 과거 이 리포에서
 * 무관한 토글에 종속돼 운영에서만 무증상 중단된 사고가 있었다. 위탁이 noop 인 환경에서는 후보가
 * 0건이라 스윕이 무해하게 돈다.
 */
@Slf4j
@Component
public class AugmentJobExpirySweeper {

    /** 오설정(0/음수) 시 안전 폴백 — 무갱신 경과 임계(분). */
    private static final int DEFAULT_IDLE_TIMEOUT_MINUTES = 360;
    /** 오설정 시 안전 폴백 — tick 당 회수 건수 상한(CWE-770). */
    private static final int DEFAULT_BATCH_SIZE = 50;
    /**
     * 임계 하한(분). 0 이하로 내리면 <b>방금 위탁한 정상 job</b>까지 즉시 만료돼 증강이 전멸한다.
     * 오설정으로도 그 상태에 들어가지 않도록 바닥을 둔다(fail-safe).
     */
    private static final int MIN_IDLE_TIMEOUT_MINUTES = 5;
    /** 스케줄 주기 하한(ms) — 과도한 폴링으로 DB 를 두드리지 않게 한다. */
    private static final long MIN_INTERVAL_MS = 60_000L;

    private final LsDataAugJobRepository jobRepository;
    private final LsDataAugRepository augRepository;
    private final AugmentJobExpiryTxService expiryTxService;

    private final boolean enabled;
    private final long intervalMs;
    private final long initialDelayMs;
    private final int idleTimeoutMinutes;
    private final int batchSize;

    private ScheduledExecutorService scheduler;

    public AugmentJobExpirySweeper(
            LsDataAugJobRepository jobRepository,
            LsDataAugRepository augRepository,
            AugmentJobExpiryTxService expiryTxService,
            @Value("${authoring.augment.job-expiry.enabled:true}") boolean enabled,
            @Value("${authoring.augment.job-expiry.interval-ms:900000}") long intervalMs,
            @Value("${authoring.augment.job-expiry.initial-delay-ms:300000}") long initialDelayMs,
            @Value("${authoring.augment.job-expiry.idle-timeout-minutes:360}") int idleTimeoutMinutes,
            @Value("${authoring.augment.job-expiry.batch-size:50}") int batchSize) {
        this.jobRepository = jobRepository;
        this.augRepository = augRepository;
        this.expiryTxService = expiryTxService;
        this.enabled = enabled;
        this.intervalMs = Math.max(MIN_INTERVAL_MS, intervalMs);
        this.initialDelayMs = Math.max(0L, initialDelayMs);
        this.idleTimeoutMinutes = idleTimeoutMinutes < 1
                ? DEFAULT_IDLE_TIMEOUT_MINUTES
                : Math.max(MIN_IDLE_TIMEOUT_MINUTES, idleTimeoutMinutes);
        this.batchSize = batchSize < 1 ? DEFAULT_BATCH_SIZE : batchSize;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("[Augment][Expiry] job expiry sweep disabled");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "augment-job-expiry-sweep");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::run, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("[Augment][Expiry] job expiry sweep scheduled intervalMs={} idleTimeoutMinutes={}",
                intervalMs, idleTimeoutMinutes);
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

    /**
     * 스윕 1회 — 예외를 삼켜 스케줄러 스레드 사망을 막는다(죽으면 회수가 조용히 영구 정지한다).
     *
     * <p>회수 축은 <b>둘</b>이다: ①비종결 job({@link #sweep}) ②job 행 0건 장기 PENDING 증강
     * ({@link #sweepOrphanPendingAugments} — 적대검증 2차 MEDIUM-2). 두 축은 후보 집합이 서로 겹치지
     * 않으며, 한쪽이 터져도 다른 쪽은 돌아야 하므로 각각 격리해 실행한다.
     *
     * @return 이번 tick 이 회수한 건수(job 만료 + 고아 증강)
     */
    public int run() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(idleTimeoutMinutes);
        return runIsolated(() -> sweep(cutoff)) + runIsolated(() -> sweepOrphanPendingAugments(cutoff));
    }

    /**
     * 축 1개를 실행하되 예외를 삼킨다.
     *
     * <p>{@code RuntimeException} 만 잡으면 Error 계열이 스레드를 죽여 {@code scheduleWithFixedDelay} 가
     * 영구 정지한다 — 그래서 {@code Throwable} 을 잡는다.
     */
    private int runIsolated(java.util.function.IntSupplier axis) {
        try {
            return axis.getAsInt();
        } catch (Throwable e) {
            log.error("[Augment][Expiry] sweep failed reason={}", e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * 회수 축 ② — <b>job 행이 한 건도 없는</b> 장기 PENDING 외부 증강을 실패로 확정한다 (MEDIUM-2).
     *
     * <p>기존 축(비종결 job)이 집지 못하는 사각지대다: 위탁 전 롤업이 예외로 끝나면 job 도 콜백도 없는
     * PENDING 이 남고, 이를 <b>깨울 주체가 없다</b>(보류 재개 리스너는 폐기됐다).
     *
     * <p>비식별 신고 구간도 회수 대상이다(2026-07-29 — 구 "정책 보류" 제외는 폐기된 전제의 잔재).
     * 위탁 직후 짧은 창만 회수하지 않는다 — 후보 SQL 이 {@code REG_DT >= cutoff} 를 제외하고,
     * 회수 트랜잭션이 잠금 하에 다시 판정한다({@link AugmentJobExpiryTxService#expireOrphanPending}).
     *
     * @param cutoff 이 시각 이전에 요청된 증강만 대상(= now - 무갱신 경과 임계, 최소 5분)
     * @return 회수한 증강 수
     */
    public int sweepOrphanPendingAugments(LocalDateTime cutoff) {
        List<Long> candidates = augRepository.findOrphanPendingAugSns(
                LsDataAug.STTS_PENDING, AugmentPrompts.EXTERNAL_AUG_TYPES, cutoff, batchSize);
        if (candidates.isEmpty()) {
            log.debug("[Augment][Expiry] no orphan pending augment");
            return 0;
        }
        int reclaimed = 0;
        for (Long dataAugSn : candidates) {
            // 회수 1건 = 독립 트랜잭션(건별 격리). 한 건 실패가 나머지 회수를 막지 않는다.
            try {
                if (expiryTxService.expireOrphanPending(dataAugSn)) {
                    reclaimed++;
                }
            } catch (RuntimeException e) {
                log.error("[Augment][Expiry] orphan reclaim failed dataAugSn={} reason={}",
                        dataAugSn, e.getClass().getSimpleName());
            }
        }
        if (reclaimed > 0) {
            log.warn("[Augment][Expiry] reclaimed orphan pending augments count={} candidates={} "
                    + "idleTimeoutMinutes={}", reclaimed, candidates.size(), idleTimeoutMinutes);
        }
        return reclaimed;
    }

    /**
     * 주어진 임계 시각 기준으로 비종결 job 을 회수한다.
     *
     * @param cutoff 이 시각 이전부터 갱신이 멈춘 job 만 대상(= now - 무갱신 경과 임계)
     * @return 만료 종결한 job 수(클레임에 성공한 건만 센다)
     */
    public int sweep(LocalDateTime cutoff) {
        List<Object[]> anchors = jobRepository.findExpirableAnchors(cutoff, batchSize);
        if (anchors.isEmpty()) {
            log.debug("[Augment][Expiry] no expirable job");
            return 0;
        }
        int expired = 0;
        for (Object[] anchor : anchors) {
            long augJobSn = ((Number) anchor[0]).longValue();
            long dataAugSn = ((Number) anchor[1]).longValue();
            // 회수 1건 = 독립 트랜잭션. 한 건이 실패해도 나머지 회수를 막지 않는다 —
            //   후보 정렬이 오래된 순이라, 여기서 중단하면 문제 행 1건이 뒤 전부를 영구히 막는다.
            try {
                if (expiryTxService.expire(augJobSn, dataAugSn, cutoff)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("[Augment][Expiry] expire failed augJobSn={} reason={}",
                        augJobSn, e.getClass().getSimpleName());
            }
        }
        if (expired > 0) {
            log.warn("[Augment][Expiry] expired non-terminal jobs count={} candidates={} idleTimeoutMinutes={}",
                    expired, anchors.size(), idleTimeoutMinutes);
        }
        return expired;
    }
}
