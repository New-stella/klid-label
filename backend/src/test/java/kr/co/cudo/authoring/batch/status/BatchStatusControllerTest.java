package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BatchStatusControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BatchStatusService statusService;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @BeforeEach
    void seed() {
        statusService.markStage(5001L, BatchStage.YOLO);
        statusService.markStage(5002L, BatchStage.SAM2);
        statusService.markStage(5003L, BatchStage.COMPLETED);
    }

    @Test
    @DisplayName("REVIEWER가_GET_batch_status_호출시_최근_N건_반환")
    void reviewerCanRead() throws Exception {
        String token = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/batch/status")
                        .param("limit", "10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
    }

    @Test
    @DisplayName("WORKER도_GET_batch_status_호출_가능")
    void workerCanRead() throws Exception {
        String token = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/batch/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PORTAL_USER는_GET_batch_status_호출시_403")
    void portalUserForbidden() throws Exception {
        String token = JwtTestSupport.token(secret, "200", "PORTAL_USER", "PORTAL", issuer, 60);
        mockMvc.perform(get("/v1/batch/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("인증_없으면_401")
    void unauthenticated401() throws Exception {
        mockMvc.perform(get("/v1/batch/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("limit_상한_초과시_400")
    void limitOverMaxRejected() throws Exception {
        String token = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/batch/status")
                        .param("limit", "501")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }
}
