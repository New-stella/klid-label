package kr.co.cudo.authoring.version.fallback;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase 3 — Gitea fallback 영속 큐 retry 스케줄러 활성화 설정.
 *
 * <p>{@code gitea.fallback.enabled=true} 일 때만 {@link EnableScheduling} 활성화.
 * stg/prd 환경에서 true 로 오버라이드, local/dev 는 비활성.
 *
 * <p>{@code @EnableScheduling} 이 컨텍스트에 한 번만 등록되면 충분하므로,
 * {@code authoring.gitea.retry.enabled=true} 와 {@code gitea.fallback.enabled=true}
 * 가 동시에 활성화돼도 충돌 없이 정상 동작한다 (Spring 이 중복 빈을 거부하지 않음 —
 * 두 Config 모두 같은 어노테이션 기반 활성화).
 */
@Configuration
@ConditionalOnProperty(name = "gitea.fallback.enabled", havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class GiteaFallbackSchedulingConfig {
}
