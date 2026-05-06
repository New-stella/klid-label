package kr.co.cudo.authoring.generate;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.generate.dto.BackgroundGenerateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BackgroundGenerateControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerToken   = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
    }

    @Test
    @DisplayName("BackgroundGenerate_WORKER가_요청시_403_FORBIDDEN")
    void workerCannotRequestBackground() throws Exception {
        BackgroundGenerateRequest req = new BackgroundGenerateRequest("WILDFIRE", "산불 배경");

        mockMvc.perform(post("/v1/generate/background")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("BackgroundGenerate_genType_WILDFIRE_정상_요청_mock_ack")
    void wildfireRequestSucceeds() throws Exception {
        BackgroundGenerateRequest req = new BackgroundGenerateRequest("WILDFIRE", "산불 배경");

        mockMvc.perform(post("/v1/generate/background")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.genType").value("WILDFIRE"))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
    }

    @Test
    @DisplayName("BackgroundGenerate_genType_FLOOD_정상_요청_mock_ack")
    void floodRequestSucceeds() throws Exception {
        BackgroundGenerateRequest req = new BackgroundGenerateRequest("FLOOD", "홍수 배경");

        mockMvc.perform(post("/v1/generate/background")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.genType").value("FLOOD"));
    }

    @Test
    @DisplayName("BackgroundGenerate_genType_INVALID_시_INVALID_INPUT_400")
    void invalidGenTypeReturns400() throws Exception {
        BackgroundGenerateRequest req = new BackgroundGenerateRequest("EARTHQUAKE", "지진 배경");

        mockMvc.perform(post("/v1/generate/background")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("BackgroundGenerate_genType_누락시_INVALID_INPUT_400")
    void missingGenTypeReturns400() throws Exception {
        BackgroundGenerateRequest req = new BackgroundGenerateRequest("", "프롬프트");

        mockMvc.perform(post("/v1/generate/background")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }
}
