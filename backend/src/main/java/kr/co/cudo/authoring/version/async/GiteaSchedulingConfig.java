package kr.co.cudo.authoring.version.async;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase 8 — Gitea 재시도 스케줄러 활성화 설정.
 *
 * <p>{@code authoring.gitea.retry.enabled=true} 일 때만 {@link EnableScheduling} 활성화하여
 * {@link GiteaCommitRetryJob#run()} 이 주기적으로 실행되도록 한다.
 *
 * <p>local/dev/stg 는 기본 비활성. prd 환경(application-prd.yml) 에서만 true 로 오버라이드.
 */
@Configuration
@ConditionalOnProperty(name = "authoring.gitea.retry.enabled", havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class GiteaSchedulingConfig {
}
