package kr.co.cudo.authoring.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("M2M_엔드포인트는_인증_없이_접근시_401_또는_403_반환")
    void m2mEndpointIsProtected() throws Exception {
        mockMvc.perform(post("/v1/integration/control/videos"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s != 401 && s != 403) {
                        throw new AssertionError("expected 401 or 403, got " + s);
                    }
                });
    }

    @Test
    @DisplayName("legacy_integration_경로도_인증_없이_접근시_401_또는_403_반환")
    void legacyIntegrationPathIsProtected() throws Exception {
        mockMvc.perform(post("/integration/control/videos"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s != 401 && s != 403) {
                        throw new AssertionError("expected 401 or 403, got " + s);
                    }
                });
    }

    @Test
    @DisplayName("HSTS_보안_헤더가_HTTPS_응답에_포함된다")
    void hstsHeaderPresentOnSecureRequest() throws Exception {
        mockMvc.perform(get("/health").secure(true))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String hsts = result.getResponse().getHeader("Strict-Transport-Security");
                    if (hsts == null || !hsts.contains("max-age=31536000") || !hsts.contains("includeSubDomains")) {
                        throw new AssertionError("HSTS header missing or malformed: " + hsts);
                    }
                });
    }
}
