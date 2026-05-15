package kr.co.cudo.authoring.sysconfig;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /v1/manage/health 엔드포인트 — REVIEWER 의 시스템 설정 화면용 외부 의존성 헬스 요약.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ManageHealthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Test
    @DisplayName("REVIEWER가_GET_manage_health_호출시_외부_DB_컴포넌트_상태_반환")
    void reviewerCanFetchManageHealth() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/manage/health")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").exists())
                .andExpect(jsonPath("$.data.components.deidentify").exists())
                .andExpect(jsonPath("$.data.components.aiServer").exists())
                .andExpect(jsonPath("$.data.components.gitea").exists())
                .andExpect(jsonPath("$.data.components.database").exists())
                .andExpect(jsonPath("$.data.components.database.status").value("UP"));
    }

    @Test
    @DisplayName("WORKER가_GET_manage_health_호출시_403")
    void workerForbiddenOnManageHealth() throws Exception {
        String workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/manage/health")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }
}
