package kr.co.cudo.authoring.common.security.webhook;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 웹훅 가드 만료 행 <b>주기 정리</b> — {@code LS_WHK_SIGN_USE} / {@code LS_WHK_FAIL_NMTM} (DEV_FIX H-5).
 *
 * <h3>왜 기회적 정리만으로는 부족했나</h3>
 * <p>기존 정리는 {@code JdbcWebhookGuardStore.consume()}(=<b>서명 검증 성공 이후</b>)에서만 카운트했다.
 * 그래서 ①성공 콜백이 한 건도 없으면 정리가 영원히 돌지 않고, ②실패 카운터 행을 쓰는
 * {@code recordFailure()} 는 카운트조차 하지 않아, 무서명 VLM 경로에 서로 다른 IP 로 실패 요청만
 * 살포하면 {@code LS_WHK_FAIL_NMTM} 이 <b>무한 증가</b>했다. 증강 콜백은 하루 수 건이라 기회적 임계
 * (100회)에 도달하는 데 수주가 걸려 정상 운영에서도 정리가 사실상 멈춰 있었다.
 *
 * <h3>2노드 동시 실행 안전</h3>
 * <p>정리는 조건부 {@code DELETE ... WHERE EXPD_DT < CURRENT_TIMESTAMP} 뿐이라 Active-Active 두 노드가
 * 동시에 실행해도 무해하다(Quartz 클러스터링·분산 락 불필요).
 *
 * <h3>왜 {@code @Scheduled} 가 아닌 전용 executor 인가</h3>
 * <p>본 애플리케이션은 {@code @EnableScheduling} 을 특정 기능 플래그가 켜질 때만 활성화한다
 * ({@code WorkLockSweepConfig} / {@code ControlNotifySchedulingConfig}). 여기서 무조건적
 * {@code @EnableScheduling} 을 추가하면 조건 없이 등록돼 있는 다른 {@code @Scheduled} 잡
 * (TUS 정리·포털 스윕)까지 전 환경·전 테스트에서 함께 발화한다. 부작용 없이 이 정리만 돌리기 위해
 * 데몬 스레드 1개짜리 전용 스케줄러를 쓴다.
 */
@Slf4j
@Component
public class WebhookGuardPurgeJob {

    private final JdbcWebhookGuardStore store;
    private final long intervalMs;
    private final long initialDelayMs;
    private final boolean enabled;

    private ScheduledExecutorService scheduler;

    public WebhookGuardPurgeJob(
            JdbcWebhookGuardStore store,
            @Value("${webhook.guard.purge.enabled:true}") boolean enabled,
            @Value("${webhook.guard.purge.interval-ms:600000}") long intervalMs,
            @Value("${webhook.guard.purge.initial-delay-ms:300000}") long initialDelayMs) {
        this.store = store;
        this.enabled = enabled;
        this.intervalMs = Math.max(60_000L, intervalMs);
        this.initialDelayMs = Math.max(0L, initialDelayMs);
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("[Webhook] guard purge job disabled");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "webhook-guard-purge");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::run, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("[Webhook] guard purge job scheduled intervalMs={}", intervalMs);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 만료 행 정리 1회 — 예외를 삼켜 스케줄러 스레드 사망을 막는다({@code TusUploadCleanupJob} 패턴).
     * 운영/테스트에서 직접 호출 가능.
     *
     * @return 삭제된 행 수
     */
    public int run() {
        try {
            int purged = store.purgeExpired();
            if (purged > 0) {
                log.info("[Webhook] guard rows purged count={}", purged);
            }
            return purged;
        } catch (Throwable e) {
            // RuntimeException 만 잡으면 Error(예: LinkageError/OOM 파생) 가 스케줄러 스레드를 죽여
            // scheduleWithFixedDelay 가 조용히 영구 정지한다 — 만료 행이 무한 누적된다.
            log.error("[Webhook] guard purge failed reason={}", e.getClass().getSimpleName());
            return 0;
        }
    }
}
