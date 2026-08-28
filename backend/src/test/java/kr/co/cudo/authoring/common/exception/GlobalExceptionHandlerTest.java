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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    @Test
    @DisplayName("매핑되지_않은_API_경로_요청_시_404_NOT_FOUND_반환_500_금지")
    void noResourceFoundReturnsNotFound() throws Exception {
        // Spring 이 매핑되지 않은 경로에 대해 던지는 NoResourceFoundException 을
        // 핸들러가 404 NOT_FOUND 로 정규화하는지 검증.
        mockMvc.perform(get("/test/no-resource").header("X-Test-Bypass", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("경로는_있고_메서드만_다르면_405_METHOD_NOT_ALLOWED_반환_500_금지")
    void methodNotSupportedReturns405WithAllowHeader() throws Exception {
        // 2026-07-30 배포 검증에서 실측된 결함 — 전용 핸들러가 없어 @ExceptionHandler(Exception.class) 로
        // 떨어지면서 500 + ERROR 스택트레이스가 됐다. 정상 오요청이 서버 장애로 보고되고 로그가 오염된다.
        // Allow 헤더는 RFC 9110 이 405 응답에 필수로 요구한다.
        mockMvc.perform(post("/test/custom-ex").header("X-Test-Bypass", "1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("본문은_JSON만_받는_창구에_다른_미디어타입으로_보내면_415_UNSUPPORTED_MEDIA_TYPE_반환_500_금지")
    void mediaTypeNotSupportedReturns415() throws Exception {
        // 405·404·400 과 같은 계열이다 — 전용 핸들러가 없으면 @ExceptionHandler(Exception.class) 로
        // 떨어져 500 + ERROR 스택트레이스가 된다. 이 저장소에는 그 실사고 전례가 있다(검수 승인 창구가
        // 바디 없는 POST 를 415 로 거부했는데 그것이 500 으로 표면화돼 승인 전 구간이 통째로 막혔다).
        // Accept-Post 헤더는 RFC 9110 이 415 응답에 권고한다 — 클라이언트가 무엇을 보내야 하는지 알 길이
        // 그것뿐이다.
        mockMvc.perform(post("/test/validate")
                        .header("X-Test-Bypass", "1")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name=x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept-Post",
                        org.hamcrest.Matchers.containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
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

        @org.springframework.web.bind.annotation.GetMapping("/no-resource")
        public String noResource() throws org.springframework.web.servlet.resource.NoResourceFoundException {
            // Spring 이 정적 리소스를 못 찾을 때 던지는 예외를 직접 시뮬레이션.
            throw new org.springframework.web.servlet.resource.NoResourceFoundException(
                    org.springframework.http.HttpMethod.GET, "v1/missing");
        }

        @PostMapping("/validate")
        public String validate(@Valid @RequestBody SampleReq req) {
            return "OK";
        }

        public record SampleReq(@NotBlank String name) {}
    }
}
