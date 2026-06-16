package kr.co.cudo.authoring.observability.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * local 전용 mock 비식별 모드(application-local.yml). true 면 실 비식별 서버가 부재하므로
     * 외부 핑 없이 UP(mock) 으로 리포트한다 — 외부 0개 자족 환경에서 집계 /health 가
     * 부재 서버 핑 실패로 DOWN 되는 것을 방지. (DeidentifyStep 과 동일 프로퍼티 — 운영은 false)
     */
    @Value("${authoring.integration.deidentify.mock-mode:false}")
    private boolean mockMode;

    public DeidentifyHealthIndicator(@Qualifier("deidentifyWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Health health() {
        if (mockMode) {
            return Health.up()
                    .withDetail("service", "deidentify")
                    .withDetail("mode", "mock")
                    .build();
        }
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
