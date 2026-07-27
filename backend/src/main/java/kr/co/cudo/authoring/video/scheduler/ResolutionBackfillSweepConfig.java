package kr.co.cudo.authoring.video.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 해상도 파생 유예 삭제 스윕 스케줄러 활성화 설정({@code WorkLockSweepConfig} 패턴).
 *
 * <p>{@code authoring.resolution-backfill.sweep.enabled=true} 일 때만 {@link EnableScheduling} 을
 * 활성화한다. local/test 는 기본 비활성이라 {@link ResolutionBackfillSweepJob} 의 {@code @Scheduled} 가
 * 발화하지 않는다(테스트는 {@code sweepPendingDeletions()} 를 직접 호출해 검증).
 */
@Configuration
@ConditionalOnProperty(name = "authoring.resolution-backfill.sweep.enabled", havingValue = "true",
        matchIfMissing = false)
@EnableScheduling
public class ResolutionBackfillSweepConfig {
}
