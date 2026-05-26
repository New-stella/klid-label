package kr.co.cudo.authoring.controlnotify.fallback;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase 3 -- 관제서버 통지 fallback 큐 retry 스케줄러 활성화 설정.
 *
 * <p>{@code authoring.control-notify.enabled=true} 일 때만 {@link EnableScheduling} 활성화.
 */
@Configuration
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class ControlNotifySchedulingConfig {
}
