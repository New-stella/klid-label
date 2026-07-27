package kr.co.cudo.authoring.auth.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 권한 자가부여 시도 카운터({@code LS_AUTHRT_GRANT_ATMPT})의 만료 행 <b>주기 정리</b> (DEV_FIX H-4).
 *
 * <h3>왜 기회적 정리만으로는 부족한가</h3>
 * <p>{@link JdbcRoleClaimAttemptStore} 의 정리는 {@code writeCounter % 100 == 0}, 즉 <b>role-claim
 * 시도가 100회 누적될 때마다 1회</b>만 발화한다. role-claim 은 신규 사용자 온보딩 경로라 정상 운영
 * 트래픽이 하루 수 건 수준이며, 임계에 도달하는 데 수개월이 걸린다 — 그동안 만료 행은 계속 쌓이기만 한다.
 * 이는 Phase 1 에서 웹훅 가드가 겪은 것과 동일한 실패 모드로,
 * {@code kr.co.cudo.authoring.common.security.webhook.WebhookGuardPurgeJob} 이 이미 같은 이유로
 * 기회적 정리를 전용 스케줄러로 대체했다. 본 잡은 그 패턴을 그대로 따른다(기회적 정리는 보조로 유지).
 *
 * <h3>2노드 동시 실행 안전</h3>
 * <p>정리는 조건부 {@code DELETE ... WHERE EXPD_DT < CURRENT_TIMESTAMP} 뿐이라 Active-Active 두 노드가
 * 동시에 실행해도 무해하다(Quartz 클러스터링·분산 락 불필요).
 *
 * <h3>왜 {@code @Scheduled} 가 아닌 전용 executor 인가</h3>
 * <p>본 애플리케이션은 {@code @EnableScheduling} 을 특정 기능 플래그가 켜질 때만 활성화한다. 여기서
 * 무조건적 {@code @EnableScheduling} 을 추가하면 조건 없이 등록돼 있는 다른 {@code @Scheduled} 잡까지
 * 전 환경·전 테스트에서 함께 발화한다. 부작용 없이 이 정리만 돌리기 위해 데몬 스레드 1개짜리 전용
 * 스케줄러를 쓴다({@code WebhookGuardPurgeJob} 동형).
 */
@Slf4j
@Component
public class RoleClaimAttemptPurgeJob {

    private final JdbcRoleClaimAttemptStore store;
    private final long intervalMs;
    private final long initialDelayMs;
    private final boolean enabled;

    private ScheduledExecutorService scheduler;

    public RoleClaimAttemptPurgeJob(
            JdbcRoleClaimAttemptStore store,
            @Value("${authoring.auth.role-claim.purge.enabled:true}") boolean enabled,
            @Value("${authoring.auth.role-claim.purge.interval-ms:600000}") long intervalMs,
            @Value("${authoring.auth.role-claim.purge.initial-delay-ms:300000}") long initialDelayMs) {
        this.store = store;
        this.enabled = enabled;
        this.intervalMs = Math.max(60_000L, intervalMs);
        this.initialDelayMs = Math.max(0L, initialDelayMs);
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("[RoleClaim] attempt purge job disabled");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "role-claim-attempt-purge");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::run, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("[RoleClaim] attempt purge job scheduled intervalMs={}", intervalMs);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 만료 행 정리 1회 — 예외를 삼켜 스케줄러 스레드 사망을 막는다.
     * 운영/테스트에서 직접 호출 가능.
     *
     * @return 삭제된 행 수
     */
    public int run() {
        try {
            int purged = store.purgeExpired();
            if (purged > 0) {
                log.info("[RoleClaim] attempt rows purged count={}", purged);
            }
            return purged;
        } catch (Throwable e) {
            // RuntimeException 만 잡으면 Error(예: LinkageError/OOM 파생) 가 스케줄러 스레드를 죽여
            // scheduleWithFixedDelay 가 조용히 영구 정지한다 — 만료 행이 무한 누적된다.
            log.error("[RoleClaim] attempt purge failed reason={}", e.getClass().getSimpleName());
            return 0;
        }
    }
}
