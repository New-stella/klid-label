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
    private String validToken;

    @Test
    @DisplayName("유효한_M2M_토큰으로만_integration_control_videos_호출_가능")
    void validM2mTokenAccepted() throws Exception {
        mockMvc.perform(post("/v1/integration/control/test")
                        .header("X-M2M-Token", validToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("잘못된_M2M_토큰은_401_또는_403_반환")
    void invalidM2mTokenRejected() throws Exception {
        mockMvc.perform(post("/v1/integration/control/test")
                        .header("X-M2M-Token", "wrong-token"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s != 401 && s != 403) {
                        throw new AssertionError("expected 401 or 403, got " + s);
                    }
                });
    }

    @Test
    @DisplayName("M2M_토큰_헤더_누락시_401_또는_403_반환")
    void missingM2mTokenRejected() throws Exception {
        mockMvc.perform(post("/v1/integration/control/test"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s != 401 && s != 403) {
                        throw new AssertionError("expected 401 or 403, got " + s);
                    }
                });
    }
}
