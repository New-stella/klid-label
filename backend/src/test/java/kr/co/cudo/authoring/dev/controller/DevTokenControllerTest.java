package kr.co.cudo.authoring.dev.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DevTokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("REVIEWER_INTERNAL_토큰_발급_201_+_authorization_header_필드_제공")
    void issueReviewerSuccess() throws Exception {
        Map<String, Object> body = Map.of(
                "role", "REVIEWER",
                "channel", "INTERNAL",
                "expSeconds", 3600
        );

        mockMvc.perform(post("/v1/dev/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresAt").isNumber())
                .andExpect(jsonPath("$.data.authorizationHeader").exists())
                .andExpect(jsonPath("$.data.claims.role").value("REVIEWER"))
                .andExpect(jsonPath("$.data.claims.channel").value("INTERNAL"));
    }

    @Test
    @DisplayName("PORTAL_USER_INTERNAL_조합은_400_INVALID_INPUT")
    void portalUserWithInternalReturns400() throws Exception {
        Map<String, Object> body = Map.of(
                "role", "PORTAL_USER",
                "channel", "INTERNAL"
        );

        mockMvc.perform(post("/v1/dev/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("REVIEWER_PORTAL_조합은_400_INVALID_INPUT")
    void reviewerWithPortalReturns400() throws Exception {
        Map<String, Object> body = Map.of(
                "role", "REVIEWER",
                "channel", "PORTAL"
        );

        mockMvc.perform(post("/v1/dev/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("expSeconds_60미만은_BeanValidation_400")
    void expSecondsBelowMinReturns400() throws Exception {
        Map<String, Object> body = Map.of(
                "role", "REVIEWER",
                "channel", "INTERNAL",
                "expSeconds", 30
        );

        mockMvc.perform(post("/v1/dev/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("expSeconds_86400초과는_BeanValidation_400")
    void expSecondsAboveMaxReturns400() throws Exception {
        Map<String, Object> body = Map.of(
                "role", "REVIEWER",
                "channel", "INTERNAL",
                "expSeconds", 100000
        );

        mockMvc.perform(post("/v1/dev/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("role_누락시_BeanValidation_400")
    void roleMissingReturns400() throws Exception {
        Map<String, Object> body = Map.of("channel", "INTERNAL");

        mockMvc.perform(post("/v1/dev/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("발급된_토큰을_GET_v1_me_호출시_정상_인증")
    void issuedTokenWorksOnMeEndpoint() throws Exception {
        // Phase 3 — /me 의 role 은 LS_USER_ROLE(sub=1 → REVIEWER) 에서 해석된다(JWT role 클레임 아님).
        // 따라서 dev 토큰 sub 도 시드된 숫자 userNo(1) 로 발급해야 인가 역할이 REVIEWER 로 해석된다.
        Map<String, Object> body = Map.of(
                "role", "REVIEWER",
                "channel", "INTERNAL",
                "userNo", "1",
                "name", "검수자A"
        );

        MvcResult issued = mockMvc.perform(post("/v1/dev/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String json = issued.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = objectMapper.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        String authHeader = (String) data.get("authorizationHeader");
        assertThat(authHeader).startsWith("Bearer ");

        mockMvc.perform(get("/v1/me").header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("1"))
                .andExpect(jsonPath("$.data.role").value("REVIEWER"))
                .andExpect(jsonPath("$.data.channel").value("INTERNAL"))
                .andExpect(jsonPath("$.data.name").value("검수자A"));
    }

    @Test
    @DisplayName("dev_tokens_endpoint는_인증_없이_접근_가능_local_프로파일")
    void devTokensEndpointPermitAllInLocal() throws Exception {
        Map<String, Object> body = Map.of(
                "role", "WORKER",
                "channel", "INTERNAL"
        );

        // No Authorization header → still 201
        mockMvc.perform(post("/v1/dev/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }
}
