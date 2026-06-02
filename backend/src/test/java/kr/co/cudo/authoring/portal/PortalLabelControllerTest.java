package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 11 — 포털 라벨링/Channel 분리 테스트.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PortalLabelControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String alicePortalToken;
    private String reviewerInternalToken;

    @BeforeEach
    void setUp() {
        alicePortalToken      = JwtTestSupport.token(secret, "alice", "PORTAL_USER", "PORTAL",   issuer, 60);
        reviewerInternalToken = JwtTestSupport.token(secret, "1",     "REVIEWER",    "INTERNAL", issuer, 60);
    }

    @Test
    @DisplayName("PORTAL_USER가_내부_API_users_호출시_403")
    void portalUserBlockedFromInternalApi() throws Exception {
        // /v1/users/workers 는 REVIEWER 전용 → PORTAL_USER 토큰은 403
        // /v1/manage/* 도 REVIEWER 전용 (Channel 분리 확인)
        mockMvc.perform(get("/v1/users/workers")
                        .header("Authorization", "Bearer " + alicePortalToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("버전관리_API는_PORTAL_USER에게_미노출_403")
    void versionApiHiddenFromPortalUser() throws Exception {
        // /v1/frames/{srcSn}/versions 는 INTERNAL 채널의 REVIEWER/WORKER 만.
        // PORTAL_USER 는 hasAnyRole('REVIEWER','WORKER') 미일치 → 403.
        mockMvc.perform(get("/v1/frames/9999/versions")
                        .header("Authorization", "Bearer " + alicePortalToken))
                .andExpect(status().isForbidden());

        // sanity: REVIEWER 토큰은 (프레임 미존재이지만) 200/4xx 비-403 응답
        mockMvc.perform(get("/v1/frames/9999/versions")
                        .header("Authorization", "Bearer " + reviewerInternalToken))
                .andExpect(status().is(org.hamcrest.Matchers.not(org.hamcrest.Matchers.equalTo(403))));
    }
}
