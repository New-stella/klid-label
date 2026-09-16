package kr.co.cudo.authoring.common.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.augment.integration.AugmentApiWebClientConfig;
import kr.co.cudo.authoring.common.client.ControlNotifyTokenProvider;
import kr.co.cudo.authoring.common.client.ExternalCallLoggingFilter;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointExchangeFilter;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 외부 연동 호출 로그 필터 배선 — 수용기준 11·12 (CO-20260916-외부연동-호출로그).
 *
 * <p>두 축으로 본다.
 * <ol>
 *   <li><b>구조</b> — 각 빈의 필터 목록에서 로그 필터가 <b>맨 마지막</b>이다(재작성·전송 가드·조건부
 *       자격증명 필터보다 안쪽). 토큰이 있을 때만 붙는 필터가 있는 빈은 그 변형까지 본다.</li>
 *   <li><b>실동작</b> — 실제 소켓으로 호출해 로그가 남고, 주소 override 가 적용된 요청은
 *       <b>재작성된</b> host:port 가 기록된다.</li>
 * </ol>
 */
class ExternalCallLoggingWiringTest {

    /** 응답하지 않는 배포 기본값 — 재작성이 안 되면 여기로 나가 로그 target 이 이 값이 된다. */
    private static final String UNREACHABLE_BOOT_DEFAULT = "http://127.0.0.1:9";

    private final WebClientConfig cfg = new WebClientConfig();
    private MockWebServer server;
    private Logger filterLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        filterLogger = (Logger) LoggerFactory.getLogger(ExternalCallLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() throws IOException {
        filterLogger.detachAppender(appender);
        server.shutdown();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static IntegrationEndpointResolver resolverReturning(String overrideUrl) {
        SystemConfigService configService = mock(SystemConfigService.class);
        when(configService.findString(anyString())).thenReturn(Optional.ofNullable(overrideUrl));
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configService);
        return new IntegrationEndpointResolver(provider);
    }

    private String base() {
        return server.url("/").toString();
    }

    private String hostPort() {
        return server.getHostName() + ":" + server.getPort();
    }

    private static List<ExchangeFilterFunction> filtersOf(WebClient client) {
        List<ExchangeFilterFunction> captured = new ArrayList<>();
        client.mutate().filters(captured::addAll);
        return captured;
    }

    /** 로그 필터가 정확히 1개이고 맨 마지막이며 연동 이름·본문 기록 여부가 기대와 같다. */
    private static void assertLoggingFilterIsLast(WebClient client, String integration, boolean logErrorBody) {
        List<ExchangeFilterFunction> filters = filtersOf(client);
        assertThat(filters).as("필터 목록").isNotEmpty();
        assertThat(filters.stream().filter(ExternalCallLoggingFilter.Filter.class::isInstance).count())
                .as("로그 필터는 정확히 1개 — integration=" + integration).isEqualTo(1);
        assertThat(filters.get(filters.size() - 1))
                .as("로그 필터는 재작성·가드·자격증명 필터보다 뒤(맨 마지막)여야 한다 — integration=" + integration)
                .isInstanceOfSatisfying(ExternalCallLoggingFilter.Filter.class, f -> {
                    assertThat(f.integration()).isEqualTo(integration);
                    assertThat(f.logErrorBody()).isEqualTo(logErrorBody);
                });
    }

    private static void call(WebClient client) {
        client.get().uri("/probe")
                .retrieve()
                .toBodilessEntity()
                .onErrorResume(e -> Mono.empty())
                .block(Duration.ofSeconds(5));
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    private void enqueueError() {
        server.enqueue(new MockResponse().setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":\"wired\",\"session_token\":\"tok-secret\"}"));
    }

    /** 실제 호출 → 완료 로그 target 이 이 서버이고, 본문 기록 여부가 기대와 같다. */
    private void assertLogsCall(WebClient client, String integration, boolean logErrorBody) {
        appender.list.clear();
        enqueueError();

        call(client);

        assertThat(messages()).as("완료 로그 — integration=" + integration).anySatisfy(m -> assertThat(m)
                .startsWith("[ExternalCall] completed integration=" + integration + " method=GET target="
                        + hostPort() + "/probe status=500 elapsedMs="));
        List<String> bodies = messages().stream()
                .filter(m -> m.startsWith("[ExternalCall] error body integration=" + integration))
                .collect(Collectors.toList());
        if (logErrorBody) {
            assertThat(bodies).singleElement().satisfies(m -> assertThat(m)
                    .contains("\"detail\":\"wired\"").doesNotContain("tok-secret"));
        } else {
            assertThat(bodies).as("본문을 기록하지 않는 연동").isEmpty();
        }
        assertThat(messages()).noneMatch(m -> m.contains("tok-secret"));
    }

    // ── 수용기준 12 — 8개 빈(비식별·시계열·AI·관제 통지·관제 계정·포털 소재·증강) ─────────

    @Test
    @DisplayName("AI_추론_빈에_로그필터가_맨_마지막에_붙고_실제_호출이_기록된다")
    void AI추론_빈_배선() {
        WebClient client = cfg.aiServerWebClient(base(), null);
        assertLoggingFilterIsLast(client, "AI_SERVER", true);
        assertLogsCall(client, "AI_SERVER", true);
    }

    @Test
    @DisplayName("시계열_빈은_토큰이_있어_자격증명_필터가_붙어도_로그필터가_그보다_뒤다")
    void 시계열_빈_배선() {
        WebClient noToken = cfg.vlmWebClient(base(), "", new VlmUrlPolicy(), null);
        assertLoggingFilterIsLast(noToken, "VLM", true);
        assertLogsCall(noToken, "VLM", true);

        WebClient withToken = cfg.vlmWebClient(base(), "vlm-api-key", new VlmUrlPolicy(), null);
        assertLoggingFilterIsLast(withToken, "VLM", true);
        assertLogsCall(withToken, "VLM", true);
        assertThat(messages()).noneMatch(m -> m.contains("vlm-api-key"));
    }

    @Test
    @DisplayName("관제_통지_빈은_정적토큰_동적발급_토큰없음_세_형상_모두_로그필터가_맨_마지막이다")
    void 관제통지_빈_배선() {
        WebClient none = cfg.controlNotifyWebClient(base(), "", false, null, null);
        assertLoggingFilterIsLast(none, "CONTROL_NOTIFY", true);
        assertLogsCall(none, "CONTROL_NOTIFY", true);

        WebClient staticToken = cfg.controlNotifyWebClient(base(), "static-notify-token", true, null, null);
        assertLoggingFilterIsLast(staticToken, "CONTROL_NOTIFY", true);
        assertLogsCall(staticToken, "CONTROL_NOTIFY", true);

        ControlNotifyTokenProvider provider = mock(ControlNotifyTokenProvider.class);
        when(provider.canIssue()).thenReturn(true);
        when(provider.issue()).thenReturn("dynamic-notify-token");
        WebClient dynamic = cfg.controlNotifyWebClient(base(), "", true, null, provider);
        assertLoggingFilterIsLast(dynamic, "CONTROL_NOTIFY", true);
        assertLogsCall(dynamic, "CONTROL_NOTIFY", true);

        assertThat(messages()).noneMatch(m -> m.contains("notify-token"));
    }

    @Test
    @DisplayName("관제_계정_창구_빈은_로그필터가_붙되_오류본문을_기록하지_않는다 — 수용기준8")
    void 관제계정_빈_배선() {
        WebClient client = cfg.controlAccountWebClient(base(), null);
        assertLoggingFilterIsLast(client, "CONTROL_ACCOUNT", false);
        assertLogsCall(client, "CONTROL_ACCOUNT", false);
    }

    @Test
    @DisplayName("포털_소재_빈은_API키가_있어도_없어도_로그필터가_맨_마지막이다")
    void 포털소재_빈_배선() {
        WebClient noKey = cfg.portalMaterialsWebClient(base(), "");
        assertLoggingFilterIsLast(noKey, ExternalCallLoggingFilter.PORTAL_MATERIALS, true);
        assertLogsCall(noKey, ExternalCallLoggingFilter.PORTAL_MATERIALS, true);

        WebClient withKey = cfg.portalMaterialsWebClient(base(), "portal-api-key-value");
        assertLoggingFilterIsLast(withKey, ExternalCallLoggingFilter.PORTAL_MATERIALS, true);
        assertLogsCall(withKey, ExternalCallLoggingFilter.PORTAL_MATERIALS, true);
        assertThat(messages()).noneMatch(m -> m.contains("portal-api-key-value"));
    }

    @Test
    @DisplayName("비식별_위탁_빈에_로그필터가_맨_마지막에_붙는다")
    void 비식별_빈_배선() {
        KpstWebClientConfig kpst = new KpstWebClientConfig();
        ExternalEndpointAddress address = kpst.kpstDeidEndpointAddress(base(), "", null);
        assertThat(address.usable()).as("시험 전제 — 목 서버 주소가 사용 가능해야 한다").isTrue();

        WebClient client = kpst.kpstDeidWebClient(address, "", null);
        assertLoggingFilterIsLast(client, "DEIDENTIFY", true);
        assertLogsCall(client, "DEIDENTIFY", true);
    }

    @Test
    @DisplayName("증강_위탁_빈에_로그필터가_맨_마지막에_붙는다")
    void 증강_빈_배선() {
        String base = base();
        WebClient client = new AugmentApiWebClientConfig().augmentApiWebClient(base, new AugmentUrlPolicy(),
                new GenAiIntegrationWiringGuard(base, "0.0.0.0/0", new AugmentUrlPolicy()), null);
        assertLoggingFilterIsLast(client, "AUGMENT", true);
        assertLogsCall(client, "AUGMENT", true);
    }

    @Test
    @DisplayName("죽은_빈_deidentifyWebClient는_건드리지_않는다")
    void 죽은빈은_무변경() {
        WebClient client = cfg.deidentifyWebClient(base());
        assertThat(filtersOf(client)).noneMatch(ExternalCallLoggingFilter.Filter.class::isInstance);
    }

    // ── 비식별 진행 조회(reactor-netty HttpClient) — 훅 배선 ──────────────────

    private HttpClient progressClient(String baseUrl) {
        KpstWebClientConfig kpst = new KpstWebClientConfig();
        return kpst.kpstDeidProgressHttpClient(kpst.kpstDeidEndpointAddress(baseUrl, "", null), "");
    }

    private static void awaitMessages(ListAppender<ILoggingEvent> appender, int count) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (appender.list.size() < count && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    @Test
    @DisplayName("★비식별_진행조회_HttpClient도_호출마다_completed_한줄을_남긴다_쿼리는_빼고")
    void 진행조회_완료로그() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"progress\":10}"));

        String body = progressClient(base()).get().uri("/api/v1/progress?token=q-secret")
                .responseSingle((res, content) -> content.asString())
                .block(Duration.ofSeconds(5));

        assertThat(body).isEqualTo("{\"progress\":10}");
        assertThat(messages()).singleElement().satisfies(m -> assertThat(m)
                .startsWith("[ExternalCall] completed integration=DEIDENTIFY method=GET target="
                        + hostPort() + "/api/v1/progress status=200 elapsedMs=")
                .doesNotContain("q-secret"));
    }

    @Test
    @DisplayName("비식별_진행조회의_오류응답은_WARN이고_본문은_기록하지_않는다")
    void 진행조회_오류응답() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"detail\":\"progress-body\"}"));

        String body = progressClient(base()).get().uri("/api/v1/progress")
                .responseSingle((res, content) -> content.asString())
                .block(Duration.ofSeconds(5));

        assertThat(body).as("본문은 호출부가 그대로 소비한다").contains("progress-body");
        assertThat(appender.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(e.getFormattedMessage()).contains("status=500").doesNotContain("progress-body");
        });
    }

    @Test
    @DisplayName("비식별_진행조회의_연결실패는_failed로_남고_예외는_그대로다")
    void 진행조회_연결실패() throws IOException {
        MockWebServer closed = new MockWebServer();
        closed.start();
        String deadBase = closed.url("/").toString();
        String deadHostPort = closed.getHostName() + ":" + closed.getPort();
        closed.shutdown();

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> progressClient(deadBase)
                .get().uri("/api/v1/progress")
                .responseSingle((res, content) -> content.asString())
                .block(Duration.ofSeconds(5)));

        assertThat(reactor.core.Exceptions.unwrap(thrown)).isInstanceOf(java.net.ConnectException.class);
        assertThat(messages()).singleElement().satisfies(m -> assertThat(m)
                .startsWith("[ExternalCall] failed integration=DEIDENTIFY method=GET target="
                        + deadHostPort + "/api/v1/progress elapsedMs=")
                .containsPattern("cause=\\w*ConnectException$"));
    }

    @Test
    @DisplayName("비식별_진행조회가_응답_전에_취소되면_cancelled_WARN_한줄이다")
    void 진행조회_취소() {
        server.enqueue(new MockResponse().setHeadersDelay(3, java.util.concurrent.TimeUnit.SECONDS)
                .setBody("{}"));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> progressClient(base())
                .get().uri("/api/v1/progress")
                .responseSingle((res, content) -> content.asString())
                .timeout(Duration.ofMillis(300))
                .block(Duration.ofSeconds(5)));
        awaitMessages(appender, 1);

        assertThat(reactor.core.Exceptions.unwrap(thrown))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(appender.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(e.getFormattedMessage()).startsWith(
                    "[ExternalCall] cancelled integration=DEIDENTIFY method=GET target="
                            + hostPort() + "/api/v1/progress elapsedMs=");
        });
    }

    @Test
    @DisplayName("비식별_진행조회가_본문_수신_중_취소되면_완료로그_한줄만_남는다")
    void 진행조회_완료후_취소() throws InterruptedException {
        server.enqueue(new MockResponse().setBody("x".repeat(10))
                .setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS));

        org.assertj.core.api.Assertions.catchThrowable(() -> progressClient(base())
                .get().uri("/api/v1/progress")
                .responseSingle((res, content) -> content.asString())
                .timeout(Duration.ofMillis(500))
                .block(Duration.ofSeconds(5)));
        Thread.sleep(500);

        assertThat(messages()).singleElement()
                .satisfies(m -> assertThat(m).startsWith("[ExternalCall] completed integration=DEIDENTIFY"));
    }

    // ── 수용기준 11 — 재작성된 host 가 기록된다 ─────────────────────────────

    @Test
    @DisplayName("★주소_override가_적용된_요청은_재작성된_host_port가_target에_기록된다 — 수용기준11")
    void 재작성된_주소가_기록된다() {
        List<WebClient> clients = List.of(
                cfg.aiServerWebClient(UNREACHABLE_BOOT_DEFAULT, resolverReturning(base())),
                cfg.vlmWebClient(UNREACHABLE_BOOT_DEFAULT, "", new VlmUrlPolicy(), resolverReturning(base())),
                cfg.controlNotifyWebClient(UNREACHABLE_BOOT_DEFAULT, "", false,
                        resolverReturning(base()), null),
                cfg.controlAccountWebClient(UNREACHABLE_BOOT_DEFAULT, resolverReturning(base())),
                kpstClient(UNREACHABLE_BOOT_DEFAULT, resolverReturning(base())),
                augmentClient(UNREACHABLE_BOOT_DEFAULT, resolverReturning(base())));

        for (WebClient client : clients) {
            appender.list.clear();
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

            call(client);

            assertThat(messages()).singleElement().satisfies(m -> assertThat(m)
                    .contains("target=" + hostPort() + "/probe ")
                    .contains("status=200")
                    .doesNotContain("127.0.0.1:9/"));
        }
    }

    @Test
    @DisplayName("★고정_장비로_핀된_요청은_설정_override가_아니라_핀된_host가_기록된다 — 수용기준11")
    void 핀된_장비가_기록된다() {
        // given — 설정 override 는 응답하지 않는 다른 주소를 가리킨다. 요청은 절대 URI + 핀 표식.
        WebClient client = cfg.vlmWebClient(UNREACHABLE_BOOT_DEFAULT, "", new VlmUrlPolicy(),
                resolverReturning("http://127.0.0.1:7"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        // when
        client.post().uri(java.net.URI.create(base() + "v1/videovlm-klid/describe"))
                .attributes(a -> a.put(IntegrationEndpointExchangeFilter.EXPLICIT_TARGET_ATTRIBUTE, Boolean.TRUE))
                .bodyValue("{}")
                .retrieve().toBodilessEntity()
                .onErrorResume(e -> Mono.empty())
                .block(Duration.ofSeconds(5));

        // then — 실제로 핀된 서버가 받았고, 로그도 그 host 를 기록했다
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(messages()).singleElement().satisfies(m -> assertThat(m)
                .contains("integration=VLM method=POST target=" + hostPort() + "/v1/videovlm-klid/describe ")
                .doesNotContain("127.0.0.1:7")
                .doesNotContain("127.0.0.1:9/"));
    }

    private static WebClient kpstClient(String bootDefault, IntegrationEndpointResolver resolver) {
        KpstWebClientConfig kpst = new KpstWebClientConfig();
        return kpst.kpstDeidWebClient(kpst.kpstDeidEndpointAddress(bootDefault, "", null), "", resolver);
    }

    private static WebClient augmentClient(String bootDefault, IntegrationEndpointResolver resolver) {
        return new AugmentApiWebClientConfig().augmentApiWebClient(bootDefault, new AugmentUrlPolicy(),
                new GenAiIntegrationWiringGuard(bootDefault, "0.0.0.0/0", new AugmentUrlPolicy()), resolver);
    }
}
