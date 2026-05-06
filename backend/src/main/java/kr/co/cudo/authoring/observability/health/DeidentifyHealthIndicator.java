package kr.co.cudo.authoring.observability.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/** 비식별 서버 헬스 체크 (Phase 12). */
@Component("deidentifyHealth")
public class DeidentifyHealthIndicator implements HealthIndicator {

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;

    public DeidentifyHealthIndicator(@Qualifier("deidentifyWebClient") WebClient webClient) {
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
            return Health.up().withDetail("service", "deidentify").build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("service", "deidentify")
                    .withDetail("error", e.getClass().getSimpleName())
                    .build();
        }
    }
}
