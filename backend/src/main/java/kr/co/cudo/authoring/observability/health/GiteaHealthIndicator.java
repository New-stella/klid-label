package kr.co.cudo.authoring.observability.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/** Gitea 헬스 체크 (Phase 12). Gitea 표준 엔드포인트 /api/healthz 호출. */
@Component("giteaHealth")
public class GiteaHealthIndicator implements HealthIndicator {

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;

    public GiteaHealthIndicator(@Qualifier("giteaWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Health health() {
        try {
            webClient.get()
                    .uri("/api/healthz")
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(PING_TIMEOUT)
                    .block();
            return Health.up().withDetail("service", "gitea").build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("service", "gitea")
                    .withDetail("error", e.getClass().getSimpleName())
                    .build();
        }
    }
}
