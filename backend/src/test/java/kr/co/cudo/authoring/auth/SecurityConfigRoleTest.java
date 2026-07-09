package kr.co.cudo.authoring.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(StubControllers.class)
class SecurityConfigRoleTest {

    @Autowired
    private MockMvc mockMvc;

    @Value("${authoring.jwt.secret}")
    private String secret;

    @Value("${authoring.jwt.issuer}")
    private String issuer;

    @Test
    @DisplayName("INTERNAL_채널_REVIEWER_토큰으로_manage_API_접근_가능")
    void reviewerAccessesManage() throws Exception {
        // Phase 3 — 인가 역할은 LS_USER_ROLE 에서 해석되므로 시드된 숫자 sub(1=REVIEWER) 사용.
        String token = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("WORKER_토큰으로_manage_API_호출시_403")
    void workerForbiddenOnManage() throws Exception {
        // 100=WORKER (LS 시드). WORKER 역할이라 manage(REVIEWER 전용) 접근 시 403.
        String token = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PORTAL_USER_토큰으로_내부_API_호출시_403")
    void portalUserForbiddenOnInternal() throws Exception {
        String token = JwtTestSupport.token(secret, "user-3", "PORTAL_USER", "PORTAL", issuer, 60);
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("iss_불일치_토큰_401")
    void unknownIssuerReturns401() throws Exception {
        String token = JwtTestSupport.token(secret, "user-1", "REVIEWER", "INTERNAL", "evil-issuer", 60);
        mockMvc.perform(get("/v1/manage/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
