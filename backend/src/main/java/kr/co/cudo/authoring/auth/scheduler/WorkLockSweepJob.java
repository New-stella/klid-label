package kr.co.cudo.authoring.auth.scheduler;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * R4 — 만료 작업락 sweeper Job.
 *
 * <p>{@code authoring.work-lock.sweep.enabled=true} 일 때만 빈 등록(테스트 기본 비활성). 주기적으로
 * {@link WorkLockService#sweepExpiredLocks()} 를 호출해 만료된 LOCKED 락을 회수한다.
 *
 * <p>2노드 Active-Active 환경에서 {@code @Scheduled} 는 양 노드 모두 발화하지만, sweep 은 건별 조건부·멱등
 * release 라 동시 발화해도 안전하다({@link WorkLockService#sweepExpiredLocks()} 참조).
 *
 * <p>{@link #run()} 은 {@code TusUploadCleanupJob} 패턴대로 {@link RuntimeException} 을 삼켜 Job 자체를
 * 방어한다(스케줄러 스레드 사망 방지).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "authoring.work-lock.sweep.enabled", havingValue = "true")
@RequiredArgsConstructor
public class WorkLockSweepJob {

    private final WorkLockService workLockService;

    @Scheduled(fixedDelayString = "${authoring.work-lock.sweep.interval-ms:600000}",
               initialDelayString = "${authoring.work-lock.sweep.initial-delay-ms:60000}")
    public void run() {
        try {
            int reclaimed = workLockService.sweepExpiredLocks();
            if (reclaimed > 0) {
                log.info("[WorkLockSweep] expired locks reclaimed count={}", reclaimed);
            }
        } catch (RuntimeException e) {
            log.error("[WorkLockSweep] sweep failed reason={}", e.getClass().getSimpleName());
        }
    }
}
