package kr.co.cudo.authoring.common.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@org.springframework.context.annotation.Import(GlobalExceptionHandlerTest.TestConfig.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("CustomException_발생시_ApiResponse_errorCode_포함_응답")
    void customExceptionReturnsErrorCode() throws Exception {
        mockMvc.perform(get("/test/custom-ex").header("X-Test-Bypass", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("MethodArgumentNotValidException_발생시_400과_INVALID_INPUT_반환")
    void validationFailureReturnsInvalidInput() throws Exception {
        TestController.SampleReq req = new TestController.SampleReq("");
        mockMvc.perform(post("/test/validate")
                        .header("X-Test-Bypass", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("ErrorCode가_없는_RuntimeException도_500_INTERNAL_ERROR_변환")
    void unhandledExceptionReturnsInternalError() throws Exception {
        mockMvc.perform(get("/test/runtime-ex").header("X-Test-Bypass", "1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        TestController testController() {
            return new TestController();
        }

        @Bean
        @org.springframework.core.annotation.Order(0)
        SecurityFilterChain testSecurity(HttpSecurity http) throws Exception {
            http.securityMatcher("/test/**")
                    .csrf(c -> c.disable())
                    .authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {
        @org.springframework.web.bind.annotation.GetMapping("/custom-ex")
        public String customEx() {
            throw new CustomException(ErrorCode.NOT_FOUND, "리소스 없음");
        }

        @org.springframework.web.bind.annotation.GetMapping("/runtime-ex")
        public String runtimeEx() {
            throw new RuntimeException("뭔가 터짐");
        }

        @PostMapping("/validate")
        public String validate(@Valid @RequestBody SampleReq req) {
            return "OK";
        }

        public record SampleReq(@NotBlank String name) {}
    }
}
