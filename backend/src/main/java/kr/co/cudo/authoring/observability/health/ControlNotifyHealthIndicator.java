package kr.co.cudo.authoring.observability.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Phase 5 -- 관제서버 outbound 통지 헬스 체크.
 *
 * <p>{@code authoring.control-notify.enabled=true} 일 때만 Bean 등록.
 * 관제서버 {@code /health} 엔드포인트를 2초 타임아웃으로 핑한다.
 *
 * <p>보안:
 * <ul>
 *   <li>CWE-209: 에러 상세에 내부 경로/스택트레이스 미노출 (클래스명만).</li>
 * </ul>
 */
@Component("controlNotifyHealth")
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
public class ControlNotifyHealthIndicator implements HealthIndicator {

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;

    public ControlNotifyHealthIndicator(@Qualifier("controlNotifyWebClient") WebClient webClient) {
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
            return Health.up().withDetail("service", "control-notify").build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("service", "control-notify")
                    .withDetail("error", e.getClass().getSimpleName())
                    .build();
        }
    }
}
