package kr.co.cudo.authoring.observability.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * ai-server (YOLO/SAM2/VLM) 헬스 체크 (Phase 12).
 * <p>
 * GET /health 호출. 짧은 타임아웃(2s) + 실패해도 응답에 외부 시스템 내부 정보를 노출하지 않는다.
 * (CWE-209 방어: 외부 응답 본문/스택트레이스 미노출).
 */
@Component("aiServerHealth")
public class AiServerHealthIndicator implements HealthIndicator {

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;

    public AiServerHealthIndicator(@Qualifier("aiServerWebClient") WebClient webClient) {
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
            return Health.up().withDetail("service", "ai-server").build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("service", "ai-server")
                    .withDetail("error", e.getClass().getSimpleName())
                    .build();
        }
    }
}
