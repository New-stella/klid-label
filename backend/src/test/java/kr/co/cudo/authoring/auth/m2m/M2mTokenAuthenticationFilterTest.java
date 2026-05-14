package kr.co.cudo.authoring.auth.m2m;

import kr.co.cudo.authoring.auth.StubControllers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(StubControllers.class)
class M2mTokenAuthenticationFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Value("${authoring.integration.control-server.m2m-token}")
    private String controlToken;

    @Value("${authoring.integration.learning-data.m2m-token}")
    private String learningDataToken;

    @Test
    @DisplayName("유효한_M2M_토큰으로만_integration_control_videos_호출_가능")
    void validM2mTokenAccepted() throws Exception {
        mockMvc.perform(post("/v1/integration/control/test")
                        .header("X-M2M-Token", controlToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("잘못된_M2M_토큰은_401_또는_403_반환")
    void invalidM2mTokenRejected() throws Exception {
        mockMvc.perform(post("/v1/integration/control/test")
                        .header("X-M2M-Token", "wrong-token"))
                .andExpect(M2mTokenAuthenticationFilterTest::expect401Or403);
    }

    @Test
    @DisplayName("M2M_토큰_헤더_누락시_401_또는_403_반환")
    void missingM2mTokenRejected() throws Exception {
        mockMvc.perform(post("/v1/integration/control/test"))
                .andExpect(M2mTokenAuthenticationFilterTest::expect401Or403);
    }

    // ---------------------------------------------------------------------
    // Phase 4 — 경로별 권한 분리 (control vs learning-data)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("learning_data_경로_LEARNING_DATA_토큰으로_정상_통과")
    void learningDataPathWithLearningDataTokenPasses() throws Exception {
        mockMvc.perform(get("/v1/export-api/test")
                        .header("X-M2M-Token", learningDataToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("learning_data_경로에_CONTROL_토큰_제출시_차단")
    void learningDataPathRejectsControlToken() throws Exception {
        mockMvc.perform(get("/v1/export-api/test")
                        .header("X-M2M-Token", controlToken))
                .andExpect(M2mTokenAuthenticationFilterTest::expect401Or403);
    }

    @Test
    @DisplayName("control_경로에_LEARNING_DATA_토큰_제출시_차단")
    void controlPathRejectsLearningDataToken() throws Exception {
        mockMvc.perform(post("/v1/integration/control/test")
                        .header("X-M2M-Token", learningDataToken))
                .andExpect(M2mTokenAuthenticationFilterTest::expect401Or403);
    }

    @Test
    @DisplayName("learning_data_경로_토큰_헤더_누락시_차단")
    void learningDataPathRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/v1/export-api/test"))
                .andExpect(M2mTokenAuthenticationFilterTest::expect401Or403);
    }

    private static void expect401Or403(MvcResult result) {
        int s = result.getResponse().getStatus();
        if (s != 401 && s != 403) {
            throw new AssertionError("expected 401 or 403, got " + s);
        }
    }
}
