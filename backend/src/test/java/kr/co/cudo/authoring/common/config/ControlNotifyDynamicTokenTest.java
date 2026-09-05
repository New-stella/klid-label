package kr.co.cudo.authoring.common.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.common.client.ControlNotifyTokenProvider;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-063 ⑥ 회귀 가드 — 관제 outbound 통지 {@code x-access-token} 이 <b>발송 시점 동적 발급</b>된다.
 *
 * <p>정적 20년 토큰(CWE-798)을 대체한 것으로, 매 통지마다 새 HS256 서비스 토큰이 붙는지, 정적 override 가
 * 여전히 우선하는지, 발급 불가 시 헤더가 생략되는지를 실왕복(MockWebServer)으로 고정한다.
 */
class ControlNotifyDynamicTokenTest {

    private static final String SECRET_91B =
            "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890";
    private final SecretKey key = Keys.hmacShaKeyFor(SECRET_91B.getBytes(StandardCharsets.UTF_8));
    private final JwtKeyResolver keyResolver = () -> key;

    private MockWebServer server;
    private final WebClientConfig config = new WebClientConfig();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private RecordedRequest post(WebClient client) throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(202));
        client.post().uri("/api/data-set/v2/jobs/1/notify-completed")
                .bodyValue("{}")
                .retrieve()
                .toBodilessEntity()
                .block();
        return server.takeRequest(5, TimeUnit.SECONDS);
    }

    private Jws<Claims> parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }

    @Test
    @DisplayName("정적_토큰_없이_시크릿만_있으면_매_통지에_새_HS256_토큰이_붙는다")
    void dynamicIssuesFreshHs256PerRequest() throws Exception {
        ControlNotifyTokenProvider provider =
                new ControlNotifyTokenProvider(keyResolver, "klid-auth", "klid-authoring-notify", 300);
        WebClient client = config.controlNotifyWebClient(
                server.url("/").toString(), "", true, null, provider);

        RecordedRequest first = post(client);
        RecordedRequest second = post(client);

        String t1 = first.getHeader("x-access-token");
        String t2 = second.getHeader("x-access-token");
        assertThat(t1).isNotNull();
        assertThat(t2).isNotNull();
        // 두 통지의 토큰 문자열이 다르다(정적 고정이 아니라 매번 발급).
        assertThat(t1).isNotEqualTo(t2);

        Jws<Claims> jws = parse(t1);
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS256"); // HS512 아님
        Claims claims = jws.getPayload();
        assertThat(claims.getIssuer()).isEqualTo("klid-auth");
        assertThat(claims.getExpiration().toInstant()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("정적_토큰이_있으면_그_값이_그대로_나간다 — override_발급기_미호출")
    void staticTokenOverridesDynamic() throws Exception {
        ControlNotifyTokenProvider provider =
                new ControlNotifyTokenProvider(keyResolver, "klid-auth", "klid-authoring-notify", 300);
        WebClient client = config.controlNotifyWebClient(
                server.url("/").toString(), "control-issued-token", true, null, provider);

        RecordedRequest recorded = post(client);

        assertThat(recorded.getHeader("x-access-token")).isEqualTo("control-issued-token");
    }

    @Test
    @DisplayName("시크릿도_정적토큰도_없으면_헤더가_생략된다 — fail-safe_통지는_죽지_않는다")
    void omitsHeaderWhenCannotIssueAndNoStatic() throws Exception {
        ControlNotifyTokenProvider noKeyProvider =
                new ControlNotifyTokenProvider((JwtKeyResolver) null, "klid-auth", "klid-authoring-notify", 300);
        WebClient client = config.controlNotifyWebClient(
                server.url("/").toString(), "", true, null, noKeyProvider);

        RecordedRequest recorded = post(client);

        assertThat(recorded).isNotNull(); // 통지 자체는 나갔다
        assertThat(recorded.getHeader("x-access-token")).isNull();
    }
}
