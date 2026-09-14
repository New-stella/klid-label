package kr.co.cudo.authoring.common.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import kr.co.cudo.authoring.common.client.ControlAccountClient.RefreshResult;
import kr.co.cudo.authoring.common.client.ControlAccountClient.RefreshResult.Outcome;
import kr.co.cudo.authoring.common.config.WebClientConfig;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 관제 계정 세션 창구 클라이언트 — 호출 계약·결과 분류·재시도 없음·주소 즉시 반영 (@design INT-015 · API-247 · API-246).
 *
 * <p>실소켓(MockWebServer)으로 관제를 흉내 내고, 운영과 같은 빈 조립({@link WebClientConfig#controlAccountWebClient})을
 * 그대로 쓴다 — 전송 계층 재전송 차단·주소 재작성 필터가 빈에 있기 때문이다.
 */
class ControlAccountClientTest {

    private static final String REFRESH_IN = "eyJhbGciOiJIUzUxMiJ9.eyJ0eXBlIjoicmVmcmVzaCJ9.refresh-in";
    private static final String ACCESS_IN = "eyJhbGciOiJIUzUxMiJ9.eyJ0eXBlIjoiYWNjZXNzIn0.access-in";

    private MockWebServer control;

    @BeforeEach
    void start() throws IOException {
        control = new MockWebServer();
        control.start();
    }

    @AfterEach
    void stop() throws IOException {
        control.shutdown();
    }

    private static String baseOf(MockWebServer server) {
        return "http://" + server.getHostName() + ":" + server.getPort();
    }

    /** 설정에 저장된 override 를 돌려주는 리졸버 — {@code null} 이면 「저장값 없음」. */
    private static IntegrationEndpointResolver resolverReturning(String overrideUrl) {
        SystemConfigService configService = mock(SystemConfigService.class);
        when(configService.findString(anyString())).thenReturn(Optional.ofNullable(overrideUrl));
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configService);
        return new IntegrationEndpointResolver(provider);
    }

    /** 키마다 다른 저장값을 돌려주는 리졸버 — 계정 창구와 통지 수신처를 <b>가려</b> 읽는지 확인한다. */
    private static IntegrationEndpointResolver resolverByKey(Map<String, String> overrides) {
        SystemConfigService configService = mock(SystemConfigService.class);
        when(configService.findString(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(overrides.get(inv.<String>getArgument(0))));
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configService);
        return new IntegrationEndpointResolver(provider);
    }

    /** 갱신 서킷만 지정한다 — 로그아웃은 별도의 새 서킷(운영과 같은 분리 형상). */
    private static ControlAccountClient client(String bootDefault, IntegrationEndpointResolver resolver,
                                               CircuitBreaker refreshCb, long timeoutMs) {
        return client(bootDefault, resolver, refreshCb, CircuitBreaker.ofDefaults("logout"), timeoutMs);
    }

    private static ControlAccountClient client(String bootDefault, IntegrationEndpointResolver resolver,
                                               CircuitBreaker refreshCb, CircuitBreaker logoutCb,
                                               long timeoutMs) {
        WebClient webClient = new WebClientConfig().controlAccountWebClient(bootDefault, resolver);
        return new ControlAccountClient(webClient, refreshCb, logoutCb, timeoutMs, timeoutMs);
    }

    /**
     * application.yml 의 {@code controlAccount}·{@code controlAccountLogout} 과 <b>같은 제외 규칙</b>을 가진 서킷 —
     * 창을 작게 잡아, 주소 미설정이 실패로 집계되면 금방 열리게 한다.
     * (yml 이 실제로 그 규칙으로 바인딩됐는지는 {@code ControlSessionRelayIT} 가 값으로 확인한다.)
     */
    private static CircuitBreaker ymlLikeBreaker(String name) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50)
                .ignoreExceptions(NonRetryableExternalException.class)
                .build());
    }

    private ControlAccountClient client() {
        return client(baseOf(control), resolverReturning(null), CircuitBreaker.ofDefaults("t"), 2_000);
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    private static final String OK_BODY =
            "{\"error\":0,\"message\":\"success\",\"data\":{\"session_token\":\"NEW-S\",\"refresh_token\":\"NEW-R\"}}";

    // ─────────────────────────────── 갱신 — 성공 계약 ───────────────────────────────

    @Test
    @DisplayName("★갱신_성공_시_관제_토큰쌍을_그대로_옮기고_refresh_토큰을_x_access_token_으로_보낸다")
    void refreshSuccessCarriesRefreshTokenInHeader() throws Exception {
        control.enqueue(json(200, OK_BODY));

        RefreshResult result = client().refresh(REFRESH_IN);

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.sessionToken()).isEqualTo("NEW-S");
        assertThat(result.refreshToken()).isEqualTo("NEW-R");
        RecordedRequest req = control.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo(ControlAccountClient.REFRESH_PATH);
        // ★ access 가 아니라 refresh 토큰, Bearer 스킴 없이 원문 그대로.
        assertThat(req.getHeader("x-access-token")).isEqualTo(REFRESH_IN);
        assertThat(req.getHeader("Authorization")).isNull();
        assertThat(req.getBodySize()).isZero();
    }

    @Test
    @DisplayName("★새_refresh_토큰_없이_와도_성공이다_성공_판정은_session_token_하나다")
    void successWithoutNewRefreshToken() {
        // 관제가 error 0 과 session_token 은 주면서 refresh_token 을 주지 않는 경계 (API-247 v4 · AC-1106).
        // 일시 장애로 내리면 브라우저가 갱신을 되풀이한다 — 성공으로 받고 refresh 칸만 비운다.
        control.enqueue(json(200, "{\"error\":0,\"data\":{\"session_token\":\"NEW-S\"}}"));

        RefreshResult result = client().refresh(REFRESH_IN);

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.sessionToken()).isEqualTo("NEW-S");
        assertThat(result.refreshToken()).as("새 refresh 토큰이 없으면 null 이다 — 지어내지 않는다").isNull();
    }

    @Test
    @DisplayName("refresh_token_이_빈_문자열이면_null_로_본다_없는_것과_같다")
    void blankNewRefreshTokenIsNull() {
        control.enqueue(json(200, "{\"error\":0,\"data\":{\"session_token\":\"NEW-S\",\"refresh_token\":\"  \"}}"));

        RefreshResult result = client().refresh(REFRESH_IN);

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.refreshToken()).isNull();
    }

    // ─────────────────────────────── 갱신 — 거절 ───────────────────────────────

    @Test
    @DisplayName("관제_HTTP_401_은_거절이다")
    void http401IsRejected() {
        control.enqueue(json(401, "{\"error\":900205,\"message\":\"리프레시 토큰이 없습니다.\"}"));
        assertThat(client().refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.REJECTED);
    }

    @Test
    @DisplayName("본문_없는_401_도_거절이다")
    void bodyless401IsRejected() {
        control.enqueue(new MockResponse().setResponseCode(401));
        assertThat(client().refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.REJECTED);
    }

    @Test
    @DisplayName("HTTP_200_이어도_error_가_0_이_아니면_거절이다")
    void nonZeroErrorIsRejected() {
        control.enqueue(json(200, "{\"error\":900205,\"message\":\"리프레시 토큰이 없습니다.\"}"));
        assertThat(client().refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.REJECTED);
    }

    // ─────────────────────────────── 갱신 — 일시 장애 ───────────────────────────────

    @Test
    @DisplayName("관제_5xx_는_일시장애다")
    void upstream5xxIsUnavailable() {
        control.enqueue(json(502, "{\"error\":0}"));
        assertThat(client().refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("JSON_이_아닌_응답은_형식불일치_일시장애다")
    void nonJsonIsUnavailable() {
        control.enqueue(new MockResponse().setResponseCode(200).setBody("<html>gateway</html>"));
        assertThat(client().refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("error_0_인데_session_token_이_없으면_형식불일치_일시장애다")
    void missingSessionTokenIsUnavailable() {
        control.enqueue(json(200, "{\"error\":0,\"data\":{\"refresh_token\":\"R\"}}"));
        assertThat(client().refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("session_token_이_문자열이_아니면_형식불일치_일시장애다")
    void nonTextualSessionTokenIsUnavailable() {
        control.enqueue(json(200, "{\"error\":0,\"data\":{\"session_token\":123}}"));
        assertThat(client().refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("error_필드가_없으면_형식불일치_일시장애다")
    void missingErrorFieldIsUnavailable() {
        control.enqueue(json(200, "{\"data\":{\"session_token\":\"S\"}}"));
        assertThat(client().refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("타임아웃은_일시장애이며_설정한_시간_안에_끝난다")
    void timeoutIsUnavailable() {
        control.enqueue(json(200, OK_BODY).setHeadersDelay(3, TimeUnit.SECONDS));
        ControlAccountClient c = client(baseOf(control), resolverReturning(null),
                CircuitBreaker.ofDefaults("t"), 300);

        long started = System.nanoTime();
        RefreshResult result = c.refresh(REFRESH_IN);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(elapsedMs).as("타임아웃이 관제 지연(3초)보다 먼저 끊어야 한다").isLessThan(2_500);
    }

    @Test
    @DisplayName("연결_실패는_일시장애다")
    void connectionFailureIsUnavailable() throws IOException {
        String dead = baseOf(control);
        control.shutdown();
        ControlAccountClient c = client(dead, resolverReturning(null), CircuitBreaker.ofDefaults("t"), 2_000);
        assertThat(c.refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.UNAVAILABLE);
        control = new MockWebServer(); // AfterEach 가 다시 닫을 수 있게
    }

    @Test
    @DisplayName("주소가_설정되지_않았으면_예외없이_일시장애다")
    void blankAddressIsUnavailable() {
        ControlAccountClient c = client("", resolverReturning(null), CircuitBreaker.ofDefaults("t"), 2_000);
        assertThat(c.refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    // ─────────────────────────────── ★ 자동 재시도 없음 ───────────────────────────────

    @Test
    @DisplayName("★응답_전에_연결이_끊겨도_재전송하지_않는다_비멱등")
    void noRetransmitOnDisconnect() throws Exception {
        control.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        control.enqueue(json(200, OK_BODY));

        RefreshResult result = client().refresh(REFRESH_IN);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(control.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(control.takeRequest(700, TimeUnit.MILLISECONDS))
                .as("갱신은 refresh 토큰을 교체하므로 두 번째 요청이 나가면 이중 교체가 된다")
                .isNull();
        assertThat(control.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★5xx_에도_재시도하지_않는다_관제_호출은_1회")
    void noRetryOn5xx() throws Exception {
        control.enqueue(json(503, "{}"));
        control.enqueue(json(200, OK_BODY));

        assertThat(client().refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.UNAVAILABLE);

        assertThat(control.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(control.takeRequest(700, TimeUnit.MILLISECONDS)).isNull();
        assertThat(control.getRequestCount()).isEqualTo(1);
    }

    // ─────────────────────────────── 서킷 ───────────────────────────────

    @Test
    @DisplayName("서킷이_열려_있으면_관제를_부르지_않고_일시장애다")
    void openCircuitIsUnavailableWithoutCall() {
        CircuitBreaker cb = CircuitBreaker.ofDefaults("open");
        cb.transitionToOpenState();
        ControlAccountClient c = client(baseOf(control), resolverReturning(null), cb, 2_000);

        assertThat(c.refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(control.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("★관제의_거절은_서킷_실패로_집계되지_않는다_일시장애만_집계된다")
    void rejectionsDoNotOpenCircuit() {
        CircuitBreaker cb = CircuitBreaker.of("count", CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50).build());
        ControlAccountClient c = client(baseOf(control), resolverReturning(null), cb, 2_000);

        for (int i = 0; i < 4; i++) {
            control.enqueue(json(200, "{\"error\":900205}"));
            assertThat(c.refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.REJECTED);
        }
        assertThat(cb.getState()).as("정상 판정(거절)이 서킷을 열면 멀쩡한 사용자의 연장까지 막힌다")
                .isEqualTo(CircuitBreaker.State.CLOSED);

        for (int i = 0; i < 4; i++) {
            control.enqueue(json(500, "{}"));
            c.refresh(REFRESH_IN);
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ─────────────────────────────── ★ 주소 즉시 반영 ───────────────────────────────

    @Test
    @DisplayName("★관리자가_바꾼_관제_계정_창구_주소로_다음_호출이_나간다_배포_기본값이_아니라")
    void savedControlAddressIsUsed() throws Exception {
        try (MockWebServer moved = new MockWebServer()) {
            moved.start();
            moved.enqueue(json(200, OK_BODY));
            ControlAccountClient c = client(baseOf(control),
                    resolverByKey(Map.of(ConfigKeys.CONTROL_ACCOUNT_URL, baseOf(moved))),
                    CircuitBreaker.ofDefaults("t"), 2_000);

            assertThat(c.refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.SUCCESS);

            RecordedRequest req = moved.takeRequest(2, TimeUnit.SECONDS);
            assertThat(req).as("저장한 주소가 요청을 받아야 한다").isNotNull();
            assertThat(req.getPath()).isEqualTo(ControlAccountClient.REFRESH_PATH);
            assertThat(req.getHeader("x-access-token")).isEqualTo(REFRESH_IN);
            assertThat(control.getRequestCount()).as("배포 기본값으로는 나가지 않는다").isZero();
        }
    }

    // ─────────────────────────────── 로그아웃 ───────────────────────────────

    @Test
    @DisplayName("★로그아웃은_access_토큰을_x_access_token_으로_보낸다")
    void logoutCarriesAccessToken() throws Exception {
        control.enqueue(json(200, "{\"error\":0,\"message\":\"success\"}"));

        assertThat(client().logout(ACCESS_IN)).isTrue();

        RecordedRequest req = control.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo(ControlAccountClient.LOGOUT_PATH);
        assertThat(req.getHeader("x-access-token")).isEqualTo(ACCESS_IN);
        assertThat(req.getBodySize()).isZero();
    }

    @Test
    @DisplayName("로그아웃_실패는_예외를_던지지_않는다_5xx_401_연결실패")
    void logoutFailuresAreSwallowed() throws IOException {
        control.enqueue(json(500, "{}"));
        control.enqueue(json(401, "{\"error\":1111}"));
        ControlAccountClient c = client();

        assertThat(c.logout(ACCESS_IN)).isFalse();
        assertThat(c.logout(ACCESS_IN)).isFalse();

        String dead = baseOf(control);
        control.shutdown();
        ControlAccountClient deadClient = client(dead, resolverReturning(null), CircuitBreaker.ofDefaults("t"), 2_000);
        assertThat(deadClient.logout(ACCESS_IN)).isFalse();
        control = new MockWebServer();
    }

    @Test
    @DisplayName("공백_토큰은_관제를_부르지_않는다")
    void blankTokensDoNotCall() {
        ControlAccountClient c = client();
        assertThat(c.logout("  ")).isFalse();
        assertThat(c.refresh(" ").outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(control.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("결과_toString_에_토큰이_실리지_않는다")
    void resultToStringHidesTokens() {
        RefreshResult r = new RefreshResult(Outcome.SUCCESS, "SECRET-S", "SECRET-R");
        assertThat(r.toString()).doesNotContain("SECRET-S").doesNotContain("SECRET-R");
        assertThat(Duration.ofMillis(ControlAccountClient.DEFAULT_TIMEOUT_MS)).isEqualTo(Duration.ofSeconds(3));
    }

    // ─────────────────────────────── 경계 ───────────────────────────────

    @Test
    @DisplayName("★로그아웃은_관제가_늦어도_자기_상한에서_끊는다_block_여유까지_기다리지_않는다")
    void logoutStopsAtItsOwnTimeout() {
        // 상한이 두 겹이다 — 호출 타임아웃(3s)이 먼저 끊어야 하고, block 여유(+2s)는 안전망일 뿐이다.
        // ⚠ 상한을 「중앙값」이 아니라 두 겹이 갈리는 경계로 고정한다 — .timeout(...) 이 사라지면
        //   block 이 5초에 끊어 이 단언이 반드시 깨진다.
        // NO_RESPONSE — 관제가 응답을 영영 주지 않는다. 지연(delay)으로 흉내 내면 서버 종료가 그 지연만큼
        // 기다려 시험이 상한의 두 배를 잡아먹는다(실측).
        control.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        ControlAccountClient c = client(baseOf(control), resolverReturning(null),
                CircuitBreaker.ofDefaults("t"), ControlAccountClient.DEFAULT_TIMEOUT_MS);

        long started = System.nanoTime();
        boolean confirmed = c.logout(ACCESS_IN);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(confirmed).as("응답을 못 봤으므로 확인되지 않았다").isFalse();
        assertThat(elapsedMs).as("호출 타임아웃 3초가 먼저 끊어야 한다 — block 여유(5초)까지 가면 안 된다")
                .isBetween(2_500L, 4_500L);
        // 결과가 false 여도 창구는 204 다 — 그 매핑은 ControlSessionControllerTest 가 고정한다.
    }

    @Test
    @DisplayName("★코덱_상한을_넘는_과대_응답은_성공으로_새지_않고_일시장애다")
    void oversizedBodyIsUnavailable() {
        // 기본 코덱 상한(256KB)을 넘기면 본문을 다 읽지 못한다. 그때 「성공 판정에 필요한 필드를
        // 못 봤다」를 성공이나 거절로 흘리면 안 된다 — 우리가 관측하지 못한 것이므로 일시 장애다.
        String padding = "x".repeat(300 * 1024);
        control.enqueue(json(200, "{\"error\":0,\"message\":\"" + padding
                + "\",\"data\":{\"session_token\":\"NEW-S\",\"refresh_token\":\"NEW-R\"}}"));

        RefreshResult result = client().refresh(REFRESH_IN);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(result.sessionToken()).isNull();
    }

    // ─────────────── ★ 주소 — 관제 계정 창구 단독, 통지 수신처로 폴백 없음 (2026-09-14 · INT-015 v8) ───────────────

    @Test
    @DisplayName("★계정_창구_WebClient_의_배포_주소는_authoring.control-account.url_이고_기본값이_비어_있다")
    void accountWebClientReadsAccountPropertyWithBlankDefault() throws NoSuchMethodException {
        // 배포 기본값 축 — 리졸버(저장값) 축은 아래 시험들이 문다. 키를 통지로 되돌리거나 기본값에
        // 로컬 주소를 두면(미설정이 설정된 것처럼 보인다) 여기서 깨진다.
        Parameter base = WebClientConfig.class
                .getMethod("controlAccountWebClient", String.class, IntegrationEndpointResolver.class)
                .getParameters()[0];
        Value value = base.getAnnotation(Value.class);
        assertThat(value).isNotNull();
        assertThat(value.value()).isEqualTo("${authoring.control-account.url:}");
    }

    @Test
    @DisplayName("★갱신·로그아웃은_관제_계정_창구_저장값으로_나가고_통지_수신처_저장값으로는_나가지_않는다")
    void relaysUseAccountAddressNotNotifyAddress() throws Exception {
        try (MockWebServer notify = new MockWebServer()) {
            notify.start();
            control.enqueue(json(200, OK_BODY));
            control.enqueue(json(200, "{\"error\":0,\"message\":\"success\"}"));
            // 배포 기본값은 비어 있다 — 관리자가 설정 화면에서 두 주소를 서로 다른 값으로 둔 상태.
            ControlAccountClient c = client("", resolverByKey(Map.of(
                            ConfigKeys.CONTROL_ACCOUNT_URL, baseOf(control),
                            ConfigKeys.CONTROL_NOTIFY_URL, baseOf(notify))),
                    CircuitBreaker.ofDefaults("t"), 2_000);

            assertThat(c.refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.SUCCESS);
            assertThat(c.logout(ACCESS_IN)).isTrue();

            RecordedRequest refresh = control.takeRequest(2, TimeUnit.SECONDS);
            RecordedRequest logout = control.takeRequest(2, TimeUnit.SECONDS);
            assertThat(refresh).isNotNull();
            assertThat(refresh.getPath()).isEqualTo(ControlAccountClient.REFRESH_PATH);
            assertThat(logout).isNotNull();
            assertThat(logout.getPath()).isEqualTo(ControlAccountClient.LOGOUT_PATH);
            assertThat(notify.getRequestCount())
                    .as("통지 수신처는 데이터셋 창구 WAS 일 수 있다 — 거기로 가면 계정 창구가 404 다")
                    .isZero();
        }
    }

    @Test
    @DisplayName("★계정_창구_주소가_비면_통지_주소가_있어도_관제를_부르지_않고_갱신은_일시장애_로그아웃은_미확인이며_서킷_실패로_세지_않는다")
    void blankAccountAddressDoesNotFallBackAndIsNotCountedAsFailure() {
        CircuitBreaker refreshCb = ymlLikeBreaker("refresh");
        CircuitBreaker logoutCb = ymlLikeBreaker("logout");
        ControlAccountClient c = client("",
                resolverByKey(Map.of(ConfigKeys.CONTROL_NOTIFY_URL, baseOf(control))),
                refreshCb, logoutCb, 2_000);

        for (int i = 0; i < 4; i++) {
            assertThat(c.refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.UNAVAILABLE);
            assertThat(c.logout(ACCESS_IN)).isFalse();
        }

        assertThat(control.getRequestCount())
                .as("통지 수신처로 폴백하면 이 서버가 요청을 받는다").isZero();
        for (CircuitBreaker cb : List.of(refreshCb, logoutCb)) {
            assertThat(cb.getState()).as(cb.getName()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(cb.getMetrics().getNumberOfFailedCalls()).as(cb.getName()).isZero();
            assertThat(cb.getMetrics().getNumberOfBufferedCalls())
                    .as("결정적 설정 상태는 집계 자체에서 빠진다 — " + cb.getName()).isZero();
        }
    }

    @Test
    @DisplayName("★주소_미설정_실패는_WARN_에_분류_문자열만_남기고_토큰·주소를_싣지_않는다")
    void blankAddressWarnCarriesClassificationOnly() {
        Logger logger = (Logger) LoggerFactory.getLogger(ControlAccountClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ControlAccountClient c = client("",
                    resolverByKey(Map.of(ConfigKeys.CONTROL_NOTIFY_URL, baseOf(control))),
                    CircuitBreaker.ofDefaults("t"), 2_000);
            c.refresh(REFRESH_IN);
            c.logout(ACCESS_IN);

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertThat(logs)
                    .contains("refresh unavailable cause=" + ControlAccountClient.CAUSE_ADDRESS_NOT_CONFIGURED)
                    .contains("logout relay failed cause=" + ControlAccountClient.CAUSE_ADDRESS_NOT_CONFIGURED)
                    .doesNotContain(REFRESH_IN)
                    .doesNotContain(ACCESS_IN)
                    .doesNotContain(baseOf(control));
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ─────────────── ★ 서킷 — 갱신·로그아웃 분리 (2026-09-14 · AC-1105 · AC-1106) ───────────────

    @Test
    @DisplayName("★갱신_서킷이_열려도_로그아웃은_관제로_나간다")
    void openRefreshCircuitDoesNotBlockLogout() throws Exception {
        CircuitBreaker refreshCb = CircuitBreaker.ofDefaults("refresh");
        CircuitBreaker logoutCb = CircuitBreaker.ofDefaults("logout");
        refreshCb.transitionToOpenState();
        control.enqueue(json(200, "{\"error\":0,\"message\":\"success\"}"));
        ControlAccountClient c = client(baseOf(control), resolverReturning(null), refreshCb, logoutCb, 2_000);

        assertThat(c.refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(control.getRequestCount()).as("열린 갱신 서킷은 갱신만 막는다").isZero();

        assertThat(c.logout(ACCESS_IN)).isTrue();
        RecordedRequest req = control.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).as("갱신 누적 실패가 로그아웃까지 막으면 관제 서버 세션이 닫히지 않는다").isNotNull();
        assertThat(req.getPath()).isEqualTo(ControlAccountClient.LOGOUT_PATH);
    }

    @Test
    @DisplayName("로그아웃_서킷이_열려도_갱신은_관제로_나간다")
    void openLogoutCircuitDoesNotBlockRefresh() throws Exception {
        CircuitBreaker refreshCb = CircuitBreaker.ofDefaults("refresh");
        CircuitBreaker logoutCb = CircuitBreaker.ofDefaults("logout");
        logoutCb.transitionToOpenState();
        control.enqueue(json(200, OK_BODY));
        ControlAccountClient c = client(baseOf(control), resolverReturning(null), refreshCb, logoutCb, 2_000);

        assertThat(c.logout(ACCESS_IN)).isFalse();
        assertThat(control.getRequestCount()).isZero();

        assertThat(c.refresh(REFRESH_IN).outcome()).isEqualTo(Outcome.SUCCESS);
        RecordedRequest req = control.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo(ControlAccountClient.REFRESH_PATH);
    }
}
