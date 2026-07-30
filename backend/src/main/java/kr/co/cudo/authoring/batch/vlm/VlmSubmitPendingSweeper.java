package kr.co.cudo.authoring.batch.vlm;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.co.cudo.authoring.batch.runner.VlmWithheldResumeRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * VLM <b>미결 위탁 회수 스윕</b> — 논블로킹 제출의 안전망 (Phase C-1).
 *
 * <h3>막는 실패 모드 (이게 없으면 fire-and-forget 이다)</h3>
 * <p>논블로킹 제출은 상관키를 제출 <b>전에</b> {@code LS_WEBHOOK_IDEMPOTENCY} 에 ISSUED 로 선커밋하고,
 * ACK 는 in-memory subscription 의 완료 핸들러가 받는다. 그 노드가 죽거나 재기동되면
 * <b>subscription 이 통째로 사라진다</b> — ACK 도 실패 신호도 오지 않고, 실패 행이 없으니 배치 재시도
 * 큐도 실패 회수기도 이를 집지 못한다. 결과는 시계열 메타의 <b>무증상 영구 결손</b>이다.
 * 본 스윕이 그 유일한 회수 경로다.
 *
 * <h3>2노드 Active-Active 정합 — 원자 클레임</h3>
 * <p>후보를 읽고 처리하는 사이 다른 노드가 같은 후보를 집으면 같은 영상이 두 번 재위탁된다.
 * 그래서 처리 전 {@link VlmSubmitReclaimTxService#claim} 의 조건부 UPDATE 로 소유권을 선점하고,
 * 실패(0행)한 노드는 조용히 건너뛴다. Quartz 클러스터링은 <b>트리거 중복만</b> 막고 잡 내부 레이스는
 * 막지 못하므로 이 CAS 가 별도로 필요하다.
 *
 * <h3>★ 두 개의 창 — ACK 창 / 콜백 창 (H1)</h3>
 * <p>미결에는 성질이 다른 두 종류가 있고, <b>하나의 임계로 덮으면 정상 위탁을 뺏는다</b>.
 * <ul>
 *   <li><b>ACK 창</b>({@code stale-timeout-minutes}, 기본 30분) — 원장 {@code ISSUED}. 수락 응답조차
 *       관측하지 못한 건. 외부 클라이언트 타임아웃 + 재시도(수십 초)를 덮으면 충분하다.</li>
 *   <li><b>콜백 창</b>({@code callback-timeout-minutes}, 기본 360분) — 원장 {@code ACCEPTED}. 벤더가
 *       요청을 받아들였고 결과 콜백만 남은 건. describe 는 영상 길이에 따라 <b>수십 분</b>이 걸리므로
 *       ACK 창을 적용하면 진행 중인 정상 위탁을 회수해 같은 비식별 영상을 중복 위탁한다(H1 의 실체).</li>
 * </ul>
 * <p>두 창을 구분할 수 있는 근거는 완료 핸들러가 ACK 수신 시 원장을 {@code ISSUED → ACCEPTED} 로
 * 전이하기 때문이다({@code VlmSubmitOutcomeRecorder}). 그 전이가 없으면 이 구분이 성립하지 않는다
 * (KPST 가 {@code prjId} 유무로 같은 문제를 푸는 것의 대칭).
 *
 * <h3>오회수 방지 — 임계 + 하한 clamp</h3>
 * <p>회수 대상은 <b>마지막 갱신이 각 창의 임계를 넘긴</b> 행뿐이며, 임계는
 * {@link #MIN_STALE_TIMEOUT_MINUTES} / {@link #MIN_CALLBACK_TIMEOUT_MINUTES} 로 하한 clamp 한다
 * (오설정으로 임계가 무너지면 이중 위탁이 전면화된다 —
 * {@code BatchRetryStaleReclaimSweeper} 와 동일한 fail-safe 원칙).
 *
 * <h3>무한 재위탁 금지</h3>
 * <p>회수는 재위탁을 부르고 재위탁은 새 ISSUED 를 만든다. 벤더가 계속 무응답이면 스윕 주기마다 영원히
 * 반복되므로, 그 영상의 회수 이력이 예산({@code max-reclaims})을 넘으면 기록만 남기고 재개하지 않는다.
 *
 * <h3>★ 상태 강등 없음</h3>
 * <p>회수는 감사 행 적재 + 재개 트리거만 한다. 배치 단계·작업 상태는 건드리지 않는다 — 파이프라인은
 * 이미 다음 단계로 진행해 완료됐을 수 있고, 그걸 FAILED 로 되돌리면 라벨링·검수 동선이 역행한다.
 *
 * <h3>왜 {@code @Scheduled} 가 아닌 전용 executor 인가</h3>
 * <p>본 애플리케이션은 {@code @EnableScheduling} 을 특정 기능 플래그가 켜질 때만 활성화한다. 무조건적
 * {@code @EnableScheduling} 은 게이팅 없이 등록된 <b>남의</b> {@code @Scheduled} 잡까지 전 환경·전
 * 테스트에서 깨운다. 그래서 데몬 스레드 1개짜리 전용 스케줄러를 쓴다
 * ({@code BatchRetryStaleReclaimSweeper}/{@code AugmentJobExpirySweeper} 동형).
 *
 * <h3>토글 독립</h3>
 * <p>자기 토글({@code authoring.batch.vlm.submit-reclaim.enabled}, 기본 true)만 본다 — 무관한 토글에
 * 얹혀 운영에서만 조용히 멈추던 사고 패턴을 피한다. VLM 이 꺼진 환경에서는 ISSUED 행이 생기지 않아
 * 후보 0건으로 무해하게 돈다.
 */
@Slf4j
@Component
public class VlmSubmitPendingSweeper {

    /** 오설정(0/음수) 시 안전 폴백 — 무갱신 경과 임계(분). */
    private static final int DEFAULT_STALE_TIMEOUT_MINUTES = 30;
    /**
     * 임계 하한(분). 이보다 낮추면 <b>ACK 대기 중</b>인 정상 위탁까지 회수돼 같은 영상이 중복 위탁된다.
     * 외부 클라이언트 타임아웃(수십 초) + Resilience4j 재시도 여유를 충분히 덮는 값이어야 한다.
     */
    private static final int MIN_STALE_TIMEOUT_MINUTES = 10;
    /**
     * 콜백 창 기본값(분) — ACK 수신 후 결과 콜백을 기다리는 상한. VLM describe 는 영상 길이에 비례해
     * 수십 분이 걸릴 수 있으므로 넉넉히 6시간을 둔다(짧게 잡으면 분석 중인 건을 회수해 중복 위탁).
     */
    private static final int DEFAULT_CALLBACK_TIMEOUT_MINUTES = 360;
    /**
     * 콜백 창 하한(분). ACK 창(수십 분)보다 반드시 커야 의미가 있다 — 이보다 낮추면 "정상 분석 중"인
     * 위탁을 뺏어 H1 이 그대로 재발한다.
     */
    private static final int MIN_CALLBACK_TIMEOUT_MINUTES = 60;
    /** 오설정 시 안전 폴백 — tick 당 회수 건수 상한(CWE-770). */
    private static final int DEFAULT_BATCH_SIZE = 50;
    /** 오설정 시 안전 폴백 — 영상당 회수 예산. */
    private static final int DEFAULT_MAX_RECLAIMS = 3;
    /** 스케줄 주기 하한(ms) — 과도한 폴링으로 DB 를 두드리지 않게 한다. */
    private static final long MIN_INTERVAL_MS = 60_000L;

    private final VlmSubmitReclaimTxService reclaimTxService;
    private final BatchStatusService batchStatusService;
    private final VlmWithheldResumeRunner resumeRunner;

    private final boolean enabled;
    private final long intervalMs;
    private final long initialDelayMs;
    private final int staleTimeoutMinutes;
    private final int callbackTimeoutMinutes;
    private final int batchSize;
    private final int maxReclaims;

    private ScheduledExecutorService scheduler;

    public VlmSubmitPendingSweeper(
            VlmSubmitReclaimTxService reclaimTxService,
            BatchStatusService batchStatusService,
            VlmWithheldResumeRunner resumeRunner,
            @Value("${authoring.batch.vlm.submit-reclaim.enabled:true}") boolean enabled,
            @Value("${authoring.batch.vlm.submit-reclaim.interval-ms:900000}") long intervalMs,
            @Value("${authoring.batch.vlm.submit-reclaim.initial-delay-ms:300000}") long initialDelayMs,
            @Value("${authoring.batch.vlm.submit-reclaim.stale-timeout-minutes:30}") int staleTimeoutMinutes,
            @Value("${authoring.batch.vlm.submit-reclaim.callback-timeout-minutes:360}") int callbackTimeoutMinutes,
            @Value("${authoring.batch.vlm.submit-reclaim.batch-size:50}") int batchSize,
            @Value("${authoring.batch.vlm.submit-reclaim.max-reclaims:3}") int maxReclaims) {
        this.reclaimTxService = reclaimTxService;
        this.batchStatusService = batchStatusService;
        this.resumeRunner = resumeRunner;
        this.enabled = enabled;
        this.intervalMs = Math.max(MIN_INTERVAL_MS, intervalMs);
        this.initialDelayMs = Math.max(0L, initialDelayMs);
        this.staleTimeoutMinutes = staleTimeoutMinutes < 1
                ? DEFAULT_STALE_TIMEOUT_MINUTES
                : Math.max(MIN_STALE_TIMEOUT_MINUTES, staleTimeoutMinutes);
        this.callbackTimeoutMinutes = callbackTimeoutMinutes < 1
                ? DEFAULT_CALLBACK_TIMEOUT_MINUTES
                : Math.max(MIN_CALLBACK_TIMEOUT_MINUTES, callbackTimeoutMinutes);
        this.batchSize = batchSize < 1 ? DEFAULT_BATCH_SIZE : batchSize;
        this.maxReclaims = maxReclaims < 1 ? DEFAULT_MAX_RECLAIMS : maxReclaims;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("[Vlm][Reclaim] pending submit reclaim sweep disabled");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vlm-submit-reclaim");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::run, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("[Vlm][Reclaim] pending submit reclaim scheduled intervalMs={} staleTimeoutMinutes={}",
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

    /** 적용 중인 ACK 창 임계(분) — 하한 clamp 적용 후 값. */
    public int staleTimeoutMinutes() {
        return staleTimeoutMinutes;
    }

    /** 적용 중인 콜백 창 임계(분) — 하한 clamp 적용 후 값(H1). */
    public int callbackTimeoutMinutes() {
        return callbackTimeoutMinutes;
    }

    /**
     * 스윕 1회 — 예외를 삼켜 스케줄러 스레드 사망을 막는다(죽으면 회수가 조용히 영구 정지한다).
     *
     * <p>{@code RuntimeException} 만 잡으면 Error 계열이 스레드를 죽여 {@code scheduleWithFixedDelay} 가
     * 영구 정지하므로 {@code Throwable} 을 잡는다.
     *
     * @return 이번 tick 이 <b>클레임에 성공한</b> 건수(다른 노드가 가져간 건은 제외)
     */
    public int run() {
        try {
            LocalDateTime now = LocalDateTime.now();
            // ① ACK 창 — 수락 응답조차 관측하지 못한 건(원장 ISSUED).
            LocalDateTime ackCutoff = now.minusMinutes(staleTimeoutMinutes);
            int reclaimed = sweepPass(
                    reclaimTxService.findCandidates(ackCutoff, batchSize), ackCutoff, false);
            // ② 콜백 창 — ACK 는 받았으나 결과 콜백이 오지 않는 건(원장 ACCEPTED, H1). 임계가 다르므로
            //    별도 패스다. 이 패스가 없으면 "수락됐는데 결과가 영영 안 오는" 건이 무한 대기로 남는다.
            LocalDateTime callbackCutoff = now.minusMinutes(callbackTimeoutMinutes);
            reclaimed += sweepPass(
                    reclaimTxService.findAcceptedCandidates(callbackCutoff, batchSize),
                    callbackCutoff, true);
            if (reclaimed > 0) {
                log.warn("[Vlm][Reclaim] pending VLM submit reclaimed={} ackWindowMin={} callbackWindowMin={}",
                        reclaimed, staleTimeoutMinutes, callbackTimeoutMinutes);
            }
            return reclaimed;
        } catch (Throwable e) {
            log.error("[Vlm][Reclaim] sweep failed reason={}", e.getClass().getSimpleName());
            return 0;
        }
    }

    /** 한 창(패스)의 후보 목록을 회수한다. */
    private int sweepPass(List<VlmSubmitReclaimTxService.Candidate> candidates,
                          LocalDateTime cutoff, boolean acked) {
        int reclaimed = 0;
        for (VlmSubmitReclaimTxService.Candidate candidate : candidates) {
            if (reclaimOne(candidate, cutoff, acked)) {
                reclaimed++;
            }
        }
        return reclaimed;
    }

    /**
     * 후보 1건 회수 — 클레임 성공 시에만 기록·재개한다. 건별 예외는 스윕 전체를 멈추지 않는다.
     *
     * @param acked ACK 를 받은 건(콜백 창)인가 — 클레임 술어와 감사 사유가 갈린다(H1).
     */
    private boolean reclaimOne(VlmSubmitReclaimTxService.Candidate candidate,
                               LocalDateTime cutoff, boolean acked) {
        try {
            // CWE-362 — 조건부 원자 UPDATE. 0행이면 다른 노드가 가져갔거나 그 사이 콜백이 처리했다.
            boolean claimed = acked
                    ? reclaimTxService.claimAccepted(candidate.idmpKey(), cutoff)
                    : reclaimTxService.claim(candidate.idmpKey(), cutoff);
            if (!claimed) {
                return false;
            }
            Long rawSn = candidate.rawSn();
            // 회수 사실을 먼저 남긴다 — 재개 판정(isWithheld)이 이 감사 행을 근거로 삼는다.
            // 사유는 두 창을 구분해 남긴다: ACK 미수신(외부에 작업이 없을 수 있음) vs 콜백 미수신
            // (외부에 작업이 실재할 수 있음 — 운영이 벤더 상태를 확인해야 하는 건).
            batchStatusService.recordVlmSkippedInNewTx(rawSn, acked
                    ? VlmTimeseriesStep.SKIP_REASON_CALLBACK_MISSING
                    : VlmTimeseriesStep.SKIP_REASON_ACK_MISSING);

            if (reclaimTxService.reclaimBudgetExceeded(rawSn, maxReclaims)) {
                log.warn("[Vlm][Reclaim] resume suppressed — reclaim budget exceeded rawSn={} max={}",
                        rawSn, maxReclaims);
                return true;
            }
            // 재개는 비동기 + 멱등(시계열 메타 0건일 때만 실제 재위탁). 상태 강등은 하지 않는다.
            resumeRunner.resumeAsync(rawSn);
            return true;
        } catch (RuntimeException e) {
            log.warn("[Vlm][Reclaim] reclaim failed rawSn={} reason={}",
                    candidate.rawSn(), e.getClass().getSimpleName());
            return false;
        }
    }
}
