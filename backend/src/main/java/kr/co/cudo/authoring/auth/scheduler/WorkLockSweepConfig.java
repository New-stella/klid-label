package kr.co.cudo.authoring.auth.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * R4 — 만료 작업락 sweeper 스케줄러 활성화 설정.
 *
 * <p>{@code authoring.work-lock.sweep.enabled=true} 일 때만 {@link EnableScheduling} 을 활성화한다
 * ({@code ControlNotifySchedulingConfig} 패턴). 테스트/로컬은 기본 비활성이라 {@link WorkLockSweepJob}
 * 의 {@code @Scheduled} 가 발화하지 않는다(테스트는 sweepExpiredLocks() 를 직접 호출해 검증).
 */
@Configuration
@ConditionalOnProperty(name = "authoring.work-lock.sweep.enabled", havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class WorkLockSweepConfig {
}
