package kr.co.cudo.authoring.observability.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/** 포털 서버 헬스 체크 (Phase 12). */
@Component("portalServerHealth")
public class PortalServerHealthIndicator implements HealthIndicator {

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;

    public PortalServerHealthIndicator(@Qualifier("portalServerWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Health health() {
        try {
            webClient.get()
                    .uri("/health")
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(PING_TIMEOUT)
                    .block();
            return Health.up().withDetail("service", "portal-server").build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("service", "portal-server")
                    .withDetail("error", e.getClass().getSimpleName())
                    .build();
        }
    }
}
