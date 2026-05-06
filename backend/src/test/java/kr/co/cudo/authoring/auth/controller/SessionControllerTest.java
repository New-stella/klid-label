package kr.co.cudo.authoring.auth.controller;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Value("${authoring.jwt.secret}")
    private String secret;

    @Value("${authoring.jwt.issuer}")
    private String issuer;

    @Test
    @DisplayName("INTERNAL_채널_REVIEWER_토큰으로_me_API_호출시_사용자_정보_반환")
    void meReturnsClaims() throws Exception {
        String token = JwtTestSupport.tokenWithName(
                secret, "user-1", "REVIEWER", "INTERNAL", issuer, "검수자A", 60);

        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value("user-1"))
                .andExpect(jsonPath("$.data.role").value("REVIEWER"))
                .andExpect(jsonPath("$.data.channel").value("INTERNAL"))
                .andExpect(jsonPath("$.data.name").value("검수자A"));
    }

    @Test
    @DisplayName("JWT_없이_me_API_호출시_401_반환")
    void meWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("만료된_JWT로_me_API_호출시_401_반환")
    void meWithExpiredTokenReturns401() throws Exception {
        String token = JwtTestSupport.expiredToken(secret, "user-1", "REVIEWER", "INTERNAL", issuer);

        mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
