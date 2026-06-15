package kr.co.cudo.authoring.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Swagger(Springdoc OpenAPI) 인증 흐름 회귀 테스트.
 *
 * <p>FE 개발자가 Swagger 에서 dev-token 발급 → Authorize → 보호 API 호출이
 * 매끄럽게 동작하는지(인증 인프라 + OpenAPI SecurityScheme 노출)를 통합 검증한다.
 *
 * <p>{@code local} 프로파일은 dev-token 엔드포인트가 활성(@Profile("!prd") + permitAll)이며,
 * DB 는 Testcontainers PostgreSQL 위에서 기동된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class SwaggerAuthIntegrationTest {

    // server.servlet.context-path=/api 는 TestRestTemplate 가 baseUrl 에 자동 포함하므로
    // 경로에는 context-path 를 직접 붙이지 않는다 (붙이면 /api/api/... 로 미스매치 → 401).
    private static final String API_DOCS = "/v3/api-docs";
    private static final String DEV_TOKENS = "/v1/dev/tokens";
    // REVIEWER 전용 GET — 외부 의존성(deidentify/ai-server/DB) 실패를 삼키고 항상 200 을 반환하므로
    // 토큰만으로 안정적 200 단언이 가능하다 (DB 데이터 의존 없음).
    private static final String PROTECTED_REVIEWER = "/v1/manage/health";
    // springdoc 의 swagger-ui.html 진입점 — SecurityConfig permitAll 에 추가되어 인증 없이 접근 가능해야 한다.
    private static final String SWAGGER_UI_HTML = "/swagger-ui.html";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("T1_OpenAPI_문서에_bearerAuth_보안스키마가_bearer_JWT로_노출된다")
    void openApiDocExposesBearerAuthScheme() throws Exception {
        // given / when
        ResponseEntity<String> res = restTemplate.getForEntity(API_DOCS, String.class);

        // then
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(res.getBody());
        JsonNode bearerAuth = root.path("components").path("securitySchemes").path("bearerAuth");
        assertThat(bearerAuth.isMissingNode()).isFalse();
        assertThat(bearerAuth.path("type").asText()).isEqualTo("http");
        assertThat(bearerAuth.path("scheme").asText()).isEqualTo("bearer");
        assertThat(bearerAuth.path("bearerFormat").asText()).isEqualTo("JWT");
    }

    @Test
    @DisplayName("T2_dev_tokens_REVIEWER_INTERNAL_요청시_201과_token을_반환한다")
    void devTokenIssueReturns201WithToken() throws Exception {
        // given
        HttpEntity<Map<String, Object>> req = jsonBody(Map.of(
                "role", "REVIEWER",
                "channel", "INTERNAL"));

        // when
        ResponseEntity<String> res = restTemplate.postForEntity(DEV_TOKENS, req, String.class);

        // then
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(res.getBody()).path("data");
        assertThat(data.path("token").asText()).isNotBlank();
        assertThat(data.path("tokenType").asText()).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("T3_발급된_토큰으로_보호_API_호출시_인증레이어를_통과한다_토큰없으면_401")
    void issuedTokenPassesAuthLayer() throws Exception {
        // given — 토큰 없이 보호 API 호출 → 인증 거부
        ResponseEntity<String> unauth = restTemplate.getForEntity(PROTECTED_REVIEWER, String.class);
        assertThat(unauth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // and — 토큰 발급
        String token = issueReviewerToken();

        // when — Authorization: Bearer {token} 헤더로 동일 API 호출
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> authed = restTemplate.exchange(
                PROTECTED_REVIEWER, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        // then — 토큰으로 인증/인가 통과하여 보호 API 가 200 을 반환한다.
        // (PROTECTED_REVIEWER 는 외부 의존성 실패를 삼키고 항상 200 이므로 명확히 단언 가능)
        assertThat(authed.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("T4_role_channel_불일치_PORTAL_USER_INTERNAL은_400_INVALID_INPUT")
    void mismatchedRoleChannelReturns400() throws Exception {
        // given
        HttpEntity<Map<String, Object>> req = jsonBody(Map.of(
                "role", "PORTAL_USER",
                "channel", "INTERNAL"));

        // when
        ResponseEntity<String> res = restTemplate.postForEntity(DEV_TOKENS, req, String.class);

        // then
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(res.getBody());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("errorCode").asText()).isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("T5_swagger_ui_html_진입점이_인증없이_접근가능하다_401아님")
    void swaggerUiHtmlIsAccessibleWithoutAuth() {
        // given / when — 인증 토큰 없이 swagger-ui.html 진입점 호출
        // (context-path /api 는 TestRestTemplate baseUrl 에 자동 포함)
        ResponseEntity<String> res = restTemplate.getForEntity(SWAGGER_UI_HTML, String.class);

        // then — SecurityConfig permitAll 에 swagger-ui.html 이 반영되어 인증 차단이 없어야 한다.
        // springdoc 은 200(OK) 또는 /swagger-ui/index.html 로의 리다이렉트(3xx)를 줄 수 있으므로
        // 인증/인가 차단 코드(401/403)가 아님을 결정적으로 단언한다.
        assertThat(res.getStatusCode())
                .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    private String issueReviewerToken() throws Exception {
        HttpEntity<Map<String, Object>> req = jsonBody(Map.of(
                "role", "REVIEWER",
                "channel", "INTERNAL"));
        ResponseEntity<String> res = restTemplate.postForEntity(DEV_TOKENS, req, String.class);
        return objectMapper.readTree(res.getBody()).path("data").path("token").asText();
    }

    private HttpEntity<Map<String, Object>> jsonBody(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
