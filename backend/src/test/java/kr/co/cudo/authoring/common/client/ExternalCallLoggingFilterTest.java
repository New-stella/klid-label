package kr.co.cudo.authoring.common.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 외부 연동 호출 공통 로그 필터 — 수용기준 1~10 (CO-20260916-외부연동-호출로그 §3).
 *
 * <p>판정은 전부 <b>실제로 남은 로그 이벤트</b>로 한다(ListAppender). 필터가 관측 외 동작을 하지
 * 않는다는 것은 <b>하류가 같은 응답·같은 예외를 받는지</b>로 확인한다.
 */
class ExternalCallLoggingFilterTest {

    private static final String URL = "http://gpu01:9500/v1/videovlm-klid/describe";

    private Logger filterLogger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void attachAppender() {
        filterLogger = (Logger) LoggerFactory.getLogger(ExternalCallLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
        originalLevel = filterLogger.getLevel();
        filterLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        filterLogger.detachAppender(appender);
        filterLogger.setLevel(originalLevel);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    private List<ILoggingEvent> eventsContaining(String fragment) {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains(fragment))
                .collect(Collectors.toList());
    }

    private static ClientRequest request(String url) {
        return ClientRequest.create(HttpMethod.POST, URI.create(url)).build();
    }

    private static ExchangeFunction respond(HttpStatus status, String body) {
        return req -> Mono.just(ClientResponse.create(status)
                .header("Content-Type", "application/json")
                .body(body)
                .build());
    }

    private static ClientResponse exchange(String integration, boolean logErrorBody,
                                           ClientRequest request, ExchangeFunction downstream) {
        return ExternalCallLoggingFilter.of(integration, logErrorBody)
                .filter(request, downstream)
                .block(Duration.ofSeconds(5));
    }

    private static String bodyOf(ClientResponse response) {
        return response.bodyToMono(String.class).defaultIfEmpty("").block(Duration.ofSeconds(5));
    }

    // ── 완료 로그 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("2xx_응답은_INFO_한줄만_남기고_본문을_읽지_않는다 — 수용기준10")
    void 성공응답은_INFO_한줄이고_본문을_읽지_않는다() {
        // given — 본문 구독 여부를 관측할 수 있는 응답
        AtomicBoolean bodySubscribed = new AtomicBoolean(false);
        Flux<DataBuffer> body = Flux.defer(() -> {
            bodySubscribed.set(true);
            return Flux.just(DefaultDataBufferFactory.sharedInstance
                    .wrap("{\"access_token\":\"abc\"}".getBytes(StandardCharsets.UTF_8)));
        });
        ExchangeFunction downstream = req -> Mono.just(
                ClientResponse.create(HttpStatus.OK).body(body).build());

        // when
        ClientResponse response = exchange("VLM", true, request(URL), downstream);

        // then
        assertThat(bodySubscribed).as("필터가 2xx 본문을 읽으면 안 된다").isFalse();
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .startsWith("[ExternalCall] completed integration=VLM method=POST "
                        + "target=gpu01:9500/v1/videovlm-klid/describe status=200 elapsedMs=");
        // 하류는 원래 본문을 그대로 읽는다
        assertThat(bodyOf(response)).isEqualTo("{\"access_token\":\"abc\"}");
    }

    @Test
    @DisplayName("4xx_응답은_완료로그를_WARN으로_남기고_오류본문을_한줄로_남긴다 — 수용기준1")
    void 사백응답은_상태와_detail이_한줄로_남는다() {
        // when
        ClientResponse response = exchange("VLM", true, request(URL),
                respond(HttpStatus.BAD_REQUEST, "{\"detail\":\"path not allowed\"}"));

        // then
        List<ILoggingEvent> completed = eventsContaining("[ExternalCall] completed");
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(completed.get(0).getFormattedMessage()).contains("status=400");

        List<ILoggingEvent> errorBody = eventsContaining("[ExternalCall] error body");
        assertThat(errorBody).hasSize(1);
        assertThat(errorBody.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(errorBody.get(0).getFormattedMessage())
                .isEqualTo("[ExternalCall] error body integration=VLM status=400 length=29 "
                        + "body={\"detail\":\"path not allowed\"}");
        // 하류는 같은 본문을 다시 읽을 수 있다
        assertThat(bodyOf(response)).isEqualTo("{\"detail\":\"path not allowed\"}");
    }

    @Test
    @DisplayName("5xx_응답도_오류본문을_남긴다")
    void 오백응답도_오류본문을_남긴다() {
        exchange("AI_SERVER", true, request("http://ai:9300/v1/detect"),
                respond(HttpStatus.INTERNAL_SERVER_ERROR, "boom"));

        assertThat(eventsContaining("[ExternalCall] completed").get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(messages()).contains(
                "[ExternalCall] error body integration=AI_SERVER status=500 length=4 body=boom");
    }

    @Test
    @DisplayName("실제_VlmClient가_필터를_지나도_400이면_NonRetryable을_내고_detail이_로그에_남는다 — 수용기준1")
    void 실제_VlmClient가_필터를_지나도_비재시도_예외를_낸다() throws IOException {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"detail\":\"path not allowed\"}"));
            VlmClient client = vlmClientThroughFilter(server);

            assertThatThrownBy(() -> client.submitDescribe(vlmRequest()).block(Duration.ofSeconds(5)))
                    .isInstanceOf(NonRetryableExternalException.class)
                    .satisfies(e -> assertThat(((NonRetryableExternalException) e).getStatusCode())
                            .isEqualTo(400));

            assertThat(eventsContaining("[ExternalCall] error body"))
                    .singleElement()
                    .satisfies(e -> assertThat(e.getFormattedMessage())
                            .contains("integration=VLM status=400")
                            .contains("body={\"detail\":\"path not allowed\"}"));
        }
    }

    @Test
    @DisplayName("★필터가_본문을_읽은_뒤에도_VlmClient가_vendorCode_40001을_파싱한다 — 수용기준2")
    void 필터가_본문을_읽어도_벤더코드가_파싱된다() throws IOException {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"code\":40001,\"detail\":\"unsupported event_type\"}"));
            VlmClient client = vlmClientThroughFilter(server);

            assertThatThrownBy(() -> client.submitDescribe(vlmRequest()).block(Duration.ofSeconds(5)))
                    .isInstanceOf(NonRetryableExternalException.class)
                    .satisfies(e -> assertThat(((NonRetryableExternalException) e).getVendorCode())
                            .as("본문을 되돌리지 않으면 하류가 빈 본문을 받아 벤더 코드가 사라진다")
                            .isEqualTo(40001));
            // 필터가 실제로 본문을 읽었다(이 시험이 필터 없이 통과하는 것이 아님을 고정)
            assertThat(eventsContaining("[ExternalCall] error body")).singleElement()
                    .satisfies(e -> assertThat(e.getFormattedMessage()).contains("40001"));
        }
    }

    private static VlmClient vlmClientThroughFilter(MockWebServer server) {
        return vlmClientThroughFilter(server, 5L);
    }

    private static VlmClient vlmClientThroughFilter(MockWebServer server, long timeoutSeconds) {
        WebClient webClient = WebClient.builder()
                .baseUrl(server.url("/").toString())
                .filter(ExternalCallLoggingFilter.of("VLM", true))
                .build();
        return new VlmClient(webClient, circuitBreakers(), singleAttempt(), timeoutSeconds);
    }

    private static CircuitBreakerRegistry circuitBreakers() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .ignoreExceptions(NonRetryableExternalException.class, RateLimitedExternalException.class)
                .build());
    }

    private static RetryRegistry singleAttempt() {
        return RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
    }

    private static VlmTimeseriesRequest vlmRequest() {
        return VlmTimeseriesRequest.ofFrameInterval("req-1", "fall", "/data/videos/deid.mp4",
                "http://localhost:8080/api/v1/vlm/callback");
    }

    // ── 오류 본문 가공 ────────────────────────────────────────────────────

    @Test
    @DisplayName("1000자를_넘는_오류본문은_1000자로_잘리고_truncated_true가_붙는다 — 수용기준3")
    void 긴_오류본문은_잘린다() {
        String longBody = "x".repeat(1500);

        ClientResponse response = exchange("VLM", true, request(URL),
                respond(HttpStatus.BAD_REQUEST, longBody));

        String msg = eventsContaining("[ExternalCall] error body").get(0).getFormattedMessage();
        assertThat(msg).isEqualTo("[ExternalCall] error body integration=VLM status=400 length=1500 "
                + "truncated=true body=" + "x".repeat(1000));
        // 자른 것은 로그뿐이다 — 하류는 전체 본문을 받는다
        assertThat(bodyOf(response)).hasSize(1500);
    }

    @Test
    @DisplayName("오류본문의_개행과_제어문자는_로그에서_밑줄로_치환된다 — 수용기준4")
    void 오류본문의_개행은_치환된다() {
        exchange("VLM", true, request(URL),
                respond(HttpStatus.BAD_REQUEST, "line1\nline2\r\tend x"));

        String msg = eventsContaining("[ExternalCall] error body").get(0).getFormattedMessage();
        assertThat(msg).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t")
                .doesNotContain(" ")
                .endsWith("body=line1_line2__end_x");
    }

    @Test
    @DisplayName("오류본문의_토큰류_JSON_값과_Bearer_값은_가려진다 — 수용기준5")
    void 오류본문의_자격증명은_가려진다() {
        String body = "{\"access_token\":\"abc\",\"Refresh\":\"r3f\",\"sessionId\":12345,"
                + "\"API_KEY\":\"k-1\",\"userPassword\":\"pw!\",\"clientSecret\":\"s\\\"q\","
                + "\"authorization\":\"Bearer zz.yy\",\"note\":\"sent Bearer eyJhbGci.x-y\","
                + "\"detail\":\"keep me\"}";

        exchange("VLM", true, request(URL), respond(HttpStatus.UNAUTHORIZED, body));

        String msg = eventsContaining("[ExternalCall] error body").get(0).getFormattedMessage();
        assertThat(msg)
                .doesNotContain("abc").doesNotContain("r3f").doesNotContain("12345")
                .doesNotContain("k-1").doesNotContain("pw!").doesNotContain("s\\\"q")
                .doesNotContain("zz.yy").doesNotContain("eyJhbGci")
                .contains("\"access_token\":\"***\"")
                .contains("\"sessionId\":\"***\"")
                .contains("Bearer ***")
                .contains("\"detail\":\"keep me\"");
    }

    @Test
    @DisplayName("본문_기록을_끈_연동은_401_500에도_본문을_읽지_않고_상태만_남긴다 — 수용기준8")
    void 본문기록을_끄면_상태만_남는다() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.INTERNAL_SERVER_ERROR)) {
            appender.list.clear();
            AtomicBoolean bodySubscribed = new AtomicBoolean(false);
            Flux<DataBuffer> body = Flux.defer(() -> {
                bodySubscribed.set(true);
                return Flux.just(DefaultDataBufferFactory.sharedInstance
                        .wrap("{\"session_token\":\"tok-xyz\"}".getBytes(StandardCharsets.UTF_8)));
            });
            ExchangeFunction downstream = req -> Mono.just(
                    ClientResponse.create(status).body(body).build());

            ClientResponse response = exchange("CONTROL_ACCOUNT", false,
                    request("http://ctl:8080/api/account/auth/refresh"), downstream);

            assertThat(bodySubscribed).as("본문을 읽지도 않아야 한다 status=" + status).isFalse();
            assertThat(eventsContaining("[ExternalCall] error body")).isEmpty();
            assertThat(appender.list).singleElement().satisfies(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage())
                        .contains("integration=CONTROL_ACCOUNT")
                        .contains("status=" + status.value())
                        .doesNotContain("tok-xyz");
            });
            // 하류는 원래 본문을 그대로 받는다
            assertThat(bodyOf(response)).contains("tok-xyz");
        }
    }

    @Test
    @DisplayName("오류본문_읽기가_실패하면_본문로그만_생략하고_원래_응답을_넘긴다")
    void 본문읽기_실패는_관측만_생략한다() {
        ClientResponse original = ClientResponse.create(HttpStatus.BAD_REQUEST)
                .body(Flux.error(new IllegalStateException("broken body")))
                .build();

        ClientResponse response = exchange("VLM", true, request(URL), req -> Mono.just(original));

        assertThat(response).isSameAs(original);
        assertThat(eventsContaining("[ExternalCall] error body")).isEmpty();
        assertThat(eventsContaining("[ExternalCall] completed")).hasSize(1);
        assertThat(messages()).noneMatch(m -> m.contains("broken body"));
    }

    // ── QA 재수정 — 마스킹이 요청 결과를 바꾸지 않는다 ──────────────────────

    @Test
    @DisplayName("★자격증명_키에_10만자_값이_실려도_하류가_400과_본문_전체를_받고_값은_가려진다 — 스택고갈_방지")
    void 거대한_자격증명_값도_스택을_고갈시키지_않는다() throws IOException {
        // given — 1000자 안에서 시작해 64KB 경계를 넘는 값 + 그 뒤의 벤더 코드
        String hugeValue = "z".repeat(100_000);
        String body = "{\"access_token\":\"" + hugeValue + "\",\"code\":40001}";
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(body));
            VlmClient client = vlmClientThroughFilter(server);

            // when / then — 하류가 본문 끝의 코드까지 읽었다 = 본문 전체를 온전히 받았다
            assertThatThrownBy(() -> client.submitDescribe(vlmRequest()).block(Duration.ofSeconds(5)))
                    .isInstanceOf(NonRetryableExternalException.class)
                    .satisfies(e -> {
                        NonRetryableExternalException ex = (NonRetryableExternalException) e;
                        assertThat(ex.getStatusCode()).isEqualTo(400);
                        assertThat(ex.getVendorCode()).isEqualTo(40001);
                    });
        }

        // 마스킹이 실제로 수행돼 본문 로그가 남았고(생략 경로가 아님), 원 값은 어디에도 없다
        assertThat(eventsContaining("[ExternalCall] error body integration=VLM")).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("length=" + body.length() + " truncated=true")
                        .contains("body={\"access_token\":\"***\""));
        assertThat(messages()).noneMatch(m -> m.contains("zzzzzzzzzz"));
        assertThat(eventsContaining("error body skipped")).isEmpty();
    }

    @Test
    @DisplayName("★키워드가_반복되는_1MB_오류본문도_짧은_시간에_처리되고_하류가_온전히_받는다 — CWE-1333")
    void 키워드_반복_1MB_본문은_선형시간에_처리된다() {
        // given — 닫힌 따옴표 뒤에 콜론이 없는 긴 키 후보(키워드만 반복) — 무제한 키 조각이면 제곱 시간
        String body = "{\"detail\":\"" + "token".repeat(200_000) + "\"}";

        // when
        long started = System.nanoTime();
        // 기본 버퍼 상한(256KB)으로는 하류조차 못 읽는다 — ai-server 빈과 같은 32MB 상한으로 만든다.
        ExchangeStrategies large = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
        ExchangeFunction downstream = req -> Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST, large)
                .header("Content-Type", "application/json")
                .body(body)
                .build());
        ClientResponse response = exchange("VLM", true, request(URL), downstream);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        // then
        assertThat(elapsedMs).as("마스킹이 입력 크기에 선형이어야 한다").isLessThan(500);
        assertThat(bodyOf(response)).isEqualTo(body);
        assertThat(eventsContaining("[ExternalCall] error body")).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("length=" + body.length() + " truncated=true"));
    }

    @Test
    @DisplayName("64KB_경계에서_닫히지_않은_자격증명_값도_가려진다")
    void 경계에서_잘린_자격증명_값도_가려진다() {
        String body = "{\"note\":\"x\",\"refresh\":\"" + "q".repeat(70_000) + "\"}";

        exchange("VLM", true, request(URL), respond(HttpStatus.BAD_REQUEST, body));

        assertThat(messages()).noneMatch(m -> m.contains("qqqqqqqqqq"));
        assertThat(eventsContaining("[ExternalCall] error body")).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("\"refresh\":\"***\""));
    }

    @Test
    @DisplayName("★마스킹이_StackOverflowError로_실패해도_본문로그만_생략하고_응답은_하류로_넘긴다")
    void 마스킹이_실패하면_본문로그만_생략한다() {
        String body = "{\"detail\":\"x\"}";
        UnaryOperator<String> brokenSanitizer = s -> {
            throw new StackOverflowError();
        };

        ClientResponse response = ExternalCallLoggingFilter.of("VLM", true, brokenSanitizer)
                .filter(request(URL), respond(HttpStatus.BAD_REQUEST, body))
                .block(Duration.ofSeconds(5));

        assertThat(bodyOf(response)).isEqualTo(body);
        assertThat(eventsContaining("[ExternalCall] error body integration")).isEmpty();
        assertThat(eventsContaining("[ExternalCall] error body skipped")).singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getFormattedMessage()).isEqualTo(
                            "[ExternalCall] error body skipped integration=VLM status=400 "
                                    + "cause=StackOverflowError");
                });
    }

    @Test
    @DisplayName("마스킹이_런타임예외로_실패해도_본문로그만_생략한다")
    void 마스킹_런타임예외도_생략한다() {
        ClientResponse response = ExternalCallLoggingFilter.of("VLM", true, s -> {
                    throw new IllegalStateException("secret-in-message");
                })
                .filter(request(URL), respond(HttpStatus.BAD_GATEWAY, "bad"))
                .block(Duration.ofSeconds(5));

        assertThat(bodyOf(response)).isEqualTo("bad");
        assertThat(messages()).contains(
                "[ExternalCall] error body skipped integration=VLM status=502 cause=IllegalStateException");
        assertThat(messages()).noneMatch(m -> m.contains("secret-in-message"));
    }

    // ── QA 3차 — 객체·배열 값 · 비정형 이스케이프 ────────────────────────────

    private String errorBodyLogOf(String body) {
        appender.list.clear();
        exchange("VLM", true, request(URL), respond(HttpStatus.BAD_REQUEST, body));
        return eventsContaining("[ExternalCall] error body integration=VLM").get(0).getFormattedMessage();
    }

    @Test
    @DisplayName("자격증명_키의_객체_배열_중첩_값도_통째로_가려지고_뒤_멤버는_남는다")
    void 객체_배열_값도_가려진다() {
        List<String> bodies = List.of(
                "{\"access_token\":{\"value\":\"SECRETV\"},\"detail\":\"keep\"}",
                "{\"tokens\":[\"SECRETV\",\"S2\"],\"detail\":\"keep\"}",
                "{\"session\":{\"inner\":{\"deep\":[1,{\"x\":\"SECRETV\"}]}},\"detail\":\"keep\"}",
                "{\"refresh\":[{\"v\":\"SECRETV\"},{\"w\":\"}]\\\"SECRETV\"}],\"detail\":\"keep\"}");
        for (String body : bodies) {
            assertThat(errorBodyLogOf(body)).as(body)
                    .doesNotContain("SECRETV").doesNotContain("S2")
                    .contains("\":\"***\"")
                    .endsWith(",\"detail\":\"keep\"}");
        }
    }

    @Test
    @DisplayName("짝이_맞지_않는_객체_값은_입력_끝까지_과마스킹한다_뒤_멤버도_사라진다")
    void 짝이_안맞으면_끝까지_가린다() {
        // 64KB 절단 등으로 닫는 괄호가 없으면 경계를 알 수 없다 — 유출보다 관측 손실을 택한다.
        String msg = errorBodyLogOf("{\"token\":{\"v\":\"SECRETV\",\"detail\":\"lost\"");

        assertThat(msg).doesNotContain("SECRETV").doesNotContain("lost")
                .endsWith("body={\"token\":\"***\"");
    }

    @Test
    @DisplayName("자격증명_키가_없는_대표_벤더_오류본문은_그대로다")
    void 벤더_오류본문은_무변경() {
        assertThat(errorBodyLogOf("{\"detail\":\"path not allowed\",\"code\":40001}"))
                .endsWith("body={\"detail\":\"path not allowed\",\"code\":40001}");
    }

    @Test
    @DisplayName("입력_끝_역슬래시와_역슬래시_뒤_개행도_값이_새지_않는다")
    void 비정형_이스케이프도_가려진다() {
        assertThat(errorBodyLogOf("{\"access_token\":\"SECRETV\\")).doesNotContain("SECRETV");
        assertThat(errorBodyLogOf("{\"access_token\":\"SECRETV\\\nX\"}"))
                .doesNotContain("SECRETV").doesNotContain("X\"");
    }

    @Test
    @DisplayName("괄호가_반복되는_1MB_적대입력도_짧은_시간에_스택_안전하게_처리된다")
    void 괄호_반복_적대입력() {
        ExchangeStrategies large = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
        for (String unit : List.of("{\"token\":[", "{\"token\":{")) {
            String body = unit.repeat(1_048_576 / unit.length());
            appender.list.clear();
            long started = System.nanoTime();
            ClientResponse response = exchange("VLM", true, request(URL),
                    req -> Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST, large).body(body).build()));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(elapsedMs).as(unit).isLessThan(500);
            assertThat(bodyOf(response)).hasSize(body.length());
            assertThat(eventsContaining("[ExternalCall] error body integration=VLM")).as(unit).hasSize(1);
            assertThat(eventsContaining("error body skipped")).as(unit).isEmpty();
        }
    }

    @Test
    @DisplayName("괄호_종류가_어긋나거나_값이_닫는괄호로_시작하거나_닫는괄호가_남으면_입력_끝까지_가린다")
    void 비정형_괄호는_끝까지_가린다() {
        List<String> bodies = List.of(
                "{\"token\":[\"a\"},SECRET]}",
                "{\"token\":]SECRET}",
                "{\"token\":}SECRET",
                "{\"token\":,SECRET}",
                "{\"token\":[\"a\"]]]SECRET}",
                "{\"token\":\"a\"]SECRET}",
                "{\"token\":abc]SECRET}");
        for (String body : bodies) {
            assertThat(errorBodyLogOf(body)).as(body)
                    .doesNotContain("SECRET")
                    .endsWith("\":\"***\"");
        }
    }

    @Test
    @DisplayName("FastAPI_422_형태는_그대로다 — 값_문자열의_x-access-token은_키가_아니다")
    void FastAPI_422는_무변경() {
        String body = "{\"detail\":[{\"loc\":[\"body\",\"x-access-token\"],\"msg\":\"field required\","
                + "\"type\":\"missing\"}]}";
        assertThat(errorBodyLogOf(body)).endsWith("body=" + body);
    }

    @Test
    @DisplayName("깊은_중첩_1MB도_선형시간에_처리되고_자격증명_값은_가려진다")
    void 깊은_중첩_1MB() {
        ExchangeStrategies large = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
        List<String> bodies = List.of(
                "{\"token\":" + "[".repeat(1_048_576) + "\"SECRET\"",
                "{\"a\":" + "[{\"b\":".repeat(1_048_576 / 6) + "{\"token\":\"SECRET\"}");
        for (String body : bodies) {
            appender.list.clear();
            long started = System.nanoTime();
            exchange("VLM", true, request(URL),
                    req -> Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST, large).body(body).build()));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(elapsedMs).isLessThan(500);
            assertThat(eventsContaining("[ExternalCall] error body integration=VLM")).singleElement()
                    .satisfies(e -> assertThat(e.getFormattedMessage()).doesNotContain("SECRET"));
        }
    }

    // ── 주기 점검 표식 (NFR-038 v3) ─────────────────────────────────────────

    private static ClientRequest probeRequest(String url) {
        return ClientRequest.create(HttpMethod.GET, URI.create(url))
                .attribute(ExternalCallLoggingFilter.PERIODIC_PROBE_ATTRIBUTE, Boolean.TRUE)
                .build();
    }

    @Test
    @DisplayName("주기점검_표식이_있는_성공은_DEBUG로만_남는다")
    void 점검_성공은_DEBUG다() {
        exchange("VLM", true, probeRequest("http://gpu01:9500/v1/videovlm-klid/status"),
                respond(HttpStatus.OK, "{}"));

        assertThat(appender.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(e.getFormattedMessage()).startsWith("[ExternalCall] completed integration=VLM method=GET");
        });
    }

    @Test
    @DisplayName("주기점검_표식이_있어도_500은_WARN이고_오류본문도_남는다")
    void 점검_실패는_WARN이다() {
        exchange("VLM", true, probeRequest("http://gpu01:9500/v1/videovlm-klid/status"),
                respond(HttpStatus.INTERNAL_SERVER_ERROR, "down"));

        assertThat(eventsContaining("[ExternalCall] completed")).singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
        assertThat(eventsContaining("[ExternalCall] error body")).singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    @DisplayName("주기점검_표식이_있어도_연결실패는_WARN이다")
    void 점검_연결실패는_WARN이다() {
        ExternalCallLoggingFilter.of("VLM", true)
                .filter(probeRequest(URL), r -> Mono.error(new IllegalStateException("x")))
                .onErrorResume(e -> Mono.empty())
                .block(Duration.ofSeconds(5));

        assertThat(appender.list).singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    @DisplayName("표식_값이_TRUE가_아니면_점검으로_보지_않는다")
    void 표식값이_거짓이면_INFO다() {
        ClientRequest req = ClientRequest.create(HttpMethod.GET, URI.create(URL))
                .attribute(ExternalCallLoggingFilter.PERIODIC_PROBE_ATTRIBUTE, Boolean.FALSE)
                .build();

        exchange("VLM", true, req, respond(HttpStatus.OK, "{}"));

        assertThat(appender.list).singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
    }

    // ── 취소 (NFR-038 v3) ──────────────────────────────────────────────────

    private void cancelWhilePending(String integration, ClientRequest req) {
        reactor.core.Disposable d = ExternalCallLoggingFilter.of(integration, true)
                .filter(req, r -> Mono.never())
                .subscribe();
        d.dispose();
    }

    @Test
    @DisplayName("응답_전에_취소되면_cancelled를_WARN으로_남긴다")
    void 취소는_WARN이다() {
        cancelWhilePending("VLM", request(URL));

        assertThat(appender.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).startsWith(
                    "[ExternalCall] cancelled integration=VLM method=POST "
                            + "target=gpu01:9500/v1/videovlm-klid/describe elapsedMs=");
        });
    }

    @Test
    @DisplayName("AI_추론_취소는_사용자_조작이_잦아_INFO다")
    void AI추론_취소는_INFO다() {
        cancelWhilePending("AI_SERVER", request("http://ai:9300/v1/sam2"));

        assertThat(appender.list).singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
    }

    @Test
    @DisplayName("AI_추론이라도_주기점검의_취소는_WARN이다")
    void AI추론_점검_취소는_WARN이다() {
        cancelWhilePending("AI_SERVER", probeRequest("http://ai:9300/health"));

        assertThat(appender.list).singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    @DisplayName("완료로그를_남긴_뒤_본문을_읽다_취소되면_취소로그를_중복으로_남기지_않는다")
    void 완료후_취소는_한줄만_남는다() {
        ExchangeFunction slowBody = req -> Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)
                .body(Flux.never())
                .build());

        reactor.core.Disposable d = ExternalCallLoggingFilter.of("VLM", true)
                .filter(request(URL), slowBody)
                .subscribe();
        d.dispose();

        assertThat(appender.list).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .startsWith("[ExternalCall] completed integration=VLM"));
    }

    @Test
    @DisplayName("오류로_끝난_호출은_취소로그를_남기지_않는다")
    void 실패후_취소로그없음() {
        ExternalCallLoggingFilter.of("VLM", true)
                .filter(request(URL), r -> Mono.error(new IllegalStateException("x")))
                .subscribe(v -> { }, e -> { })
                .dispose();

        assertThat(appender.list).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .startsWith("[ExternalCall] failed"));
    }

    @Test
    @DisplayName("★VlmClient의_timeout으로_끊긴_위탁은_cancelled_WARN이_남고_예외_전파는_필터가_없을때와_같다")
    void 클라이언트_시간초과는_cancelled로_남는다() throws IOException {
        Class<? extends Throwable> withFilter;
        Class<? extends Throwable> withoutFilter;
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeadersDelay(3, java.util.concurrent.TimeUnit.SECONDS)
                    .setBody("{}"));
            server.enqueue(new MockResponse().setHeadersDelay(3, java.util.concurrent.TimeUnit.SECONDS)
                    .setBody("{}"));
            withFilter = thrownBy(vlmClientThroughFilter(server, 1L));
            withoutFilter = thrownBy(new VlmClient(
                    WebClient.builder().baseUrl(server.url("/").toString()).build(),
                    circuitBreakers(), singleAttempt(), 1L));
        }

        assertThat(withFilter).isEqualTo(withoutFilter);
        assertThat(eventsContaining("[ExternalCall] cancelled integration=VLM method=POST")).singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
        assertThat(eventsContaining("[ExternalCall] completed")).isEmpty();
    }

    private static Class<? extends Throwable> thrownBy(VlmClient client) {
        try {
            client.submitDescribe(vlmRequest()).block(Duration.ofSeconds(10));
        } catch (Throwable t) {
            return reactor.core.Exceptions.unwrap(t).getClass();
        }
        throw new AssertionError("시간 초과 예외가 나야 한다");
    }

    // ── 대상·헤더 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("target에는_스킴_userinfo_쿼리_프래그먼트가_없다 — 수용기준6")
    void 대상에는_쿼리가_없다() {
        exchange("PORTAL_MATERIALS", true,
                request("https://svc:pw@portal-int:8443/api/materials/7?key=secret&x=1#frag"),
                respond(HttpStatus.OK, "{}"));

        String msg = messages().get(0);
        assertThat(msg).contains("target=portal-int:8443/api/materials/7 ")
                .doesNotContain("secret").doesNotContain("key=")
                .doesNotContain("svc").doesNotContain("pw")
                .doesNotContain("frag").doesNotContain("https");
    }

    @Test
    @DisplayName("포트가_없는_주소는_호스트와_경로만_남긴다")
    void 포트없는_주소() {
        exchange("DEIDENTIFY", true, request("http://kpst-host/api/v1/progress"),
                respond(HttpStatus.OK, "{}"));

        assertThat(messages().get(0)).contains("target=kpst-host/api/v1/progress ");
    }

    @Test
    @DisplayName("인증_헤더_값은_성공_실패_어느_로그에도_나오지_않는다 — 수용기준7")
    void 헤더값은_기록되지_않는다() {
        ClientRequest req = ClientRequest.create(HttpMethod.POST, URI.create(URL))
                .header("Authorization", "Bearer hdr-authz-value")
                .header("x-access-token", "hdr-access-value")
                .header("X-API-Key", "hdr-apikey-value")
                .header("x-api-key", "hdr-portal-key")
                .build();

        exchange("VLM", true, req, respond(HttpStatus.OK, "{}"));
        exchange("VLM", true, req, respond(HttpStatus.BAD_REQUEST, "{\"detail\":\"no\"}"));
        ExternalCallLoggingFilter.of("VLM", true)
                .filter(req, r -> Mono.error(new ConnectException("refused")))
                .onErrorResume(e -> Mono.empty())
                .block(Duration.ofSeconds(5));

        assertThat(appender.list).hasSizeGreaterThanOrEqualTo(4);
        assertThat(messages()).allSatisfy(m -> assertThat(m)
                .doesNotContain("hdr-authz-value").doesNotContain("hdr-access-value")
                .doesNotContain("hdr-apikey-value").doesNotContain("hdr-portal-key"));
    }

    // ── 실패 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("연결실패는_failed_로그에_예외_단순클래스명만_남기고_예외를_그대로_전파한다 — 수용기준9")
    void 연결실패는_failed_로그를_남기고_예외를_전파한다() {
        ConnectException cause = new ConnectException("Connection refused: gpu01/10.0.0.9:9500");

        assertThatThrownBy(() -> ExternalCallLoggingFilter.of("VLM", true)
                .filter(request(URL), r -> Mono.error(cause))
                .block(Duration.ofSeconds(5)))
                .satisfies(e -> assertThat(reactor.core.Exceptions.unwrap(e))
                        .as("관측이 예외를 바꾸면 안 된다").isSameAs(cause));

        assertThat(appender.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage())
                    .startsWith("[ExternalCall] failed integration=VLM method=POST "
                            + "target=gpu01:9500/v1/videovlm-klid/describe elapsedMs=")
                    .endsWith(" cause=ConnectException")
                    .doesNotContain("Connection refused")
                    .doesNotContain("10.0.0.9");
            assertThat(e.getThrowableProxy()).as("스택·메시지를 싣지 않는다").isNull();
        });
    }

    @Test
    @DisplayName("응답_타임아웃도_failed로_남는다 — 수용기준9")
    void 타임아웃은_failed로_남는다() {
        assertThatThrownBy(() -> ExternalCallLoggingFilter.of("AI_SERVER", true)
                .filter(request("http://ai:9300/v1/sam2"),
                        r -> Mono.<ClientResponse>never().timeout(Duration.ofMillis(20)))
                .block(Duration.ofSeconds(5)))
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);

        assertThat(eventsContaining("[ExternalCall] failed")).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).endsWith("cause=TimeoutException"));
    }

    @Test
    @DisplayName("재구독_재시도마다_한줄씩_남고_소요시간은_구독시점부터_잰다")
    void 재구독마다_한줄씩_남는다() {
        Mono<ClientResponse> call = ExternalCallLoggingFilter.of("VLM", true)
                .filter(request(URL), respond(HttpStatus.OK, "{}"));

        call.block(Duration.ofSeconds(5));
        call.block(Duration.ofSeconds(5));

        assertThat(eventsContaining("[ExternalCall] completed")).hasSize(2);
    }
}
