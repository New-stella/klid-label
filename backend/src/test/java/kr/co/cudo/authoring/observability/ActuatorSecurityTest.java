package kr.co.cudo.authoring.observability;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 12 — Actuator 보안 매처 검증.
 *  - /actuator/health : 누구나 접근 가능 (permitAll).
 *  - /actuator/metrics, /actuator/prometheus : REVIEWER 만 (운영 prd 는 노출 자체 차단 — 별도).
 *  - /actuator/info : permitAll.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ActuatorSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Test
    @DisplayName("Actuator_health_엔드포인트는_인증_없이_접근_가능")
    void healthIsPublic() throws Exception {
        // Phase 12 — health 는 permitAll. 외부 시스템 5종이 down 이면 503,
        // 모두 up 이면 200 — 둘 다 "인증 통과"이며 401/403 은 아니어야 한다.
        int status = mockMvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isIn(200, 503);
    }

    @Test
    @DisplayName("Actuator_metrics_엔드포인트는_인증_없으면_401")
    void metricsRequiresAuth() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Actuator_metrics_엔드포인트는_WORKER_접근시_403")
    void metricsForbiddenForWorker() throws Exception {
        String workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }
}
