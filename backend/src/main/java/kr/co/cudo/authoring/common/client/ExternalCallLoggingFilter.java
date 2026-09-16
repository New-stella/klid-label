package kr.co.cudo.authoring.common.client;

import io.netty.util.AttributeKey;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.context.ContextView;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * 외부 연동 호출 공통 로그 필터 — 외부향 {@code WebClient} 빈마다 <b>맨 마지막(가장 안쪽)</b>에 단다.
 *
 * <p>호출마다 한 줄을 남긴다.
 * <pre>
 * [ExternalCall] completed integration=VLM method=POST target=gpu01:9500/v1/... status=400 elapsedMs=163
 * [ExternalCall] error body integration=VLM status=400 length=87 body={"detail":"..."}
 * [ExternalCall] failed integration=VLM method=POST target=... elapsedMs=... cause=ConnectException
 * </pre>
 *
 * <h3>★ 관측만 한다 — 요청 결과를 바꾸지 않는다</h3>
 * <ul>
 *   <li>4xx·5xx 일 때만 본문을 읽고, 읽은 본문을 {@link ClientResponse#mutate()} 로 <b>되돌려</b>
 *       하류({@code onStatus} 의 벤더 코드 파싱 등)가 같은 본문을 다시 읽게 한다. 되돌리지 않으면
 *       하류가 빈 본문을 받아 벤더 코드 판독이 <b>조용히</b> 깨진다.</li>
 *   <li>본문 읽기가 실패하면 본문 로그만 생략하고 <b>원래 응답을 그대로</b> 넘긴다.</li>
 *   <li>연결 실패·타임아웃은 기록만 하고 <b>같은 예외</b>를 그대로 전파한다.</li>
 * </ul>
 *
 * <h3>★ 남기지 않는 것</h3>
 * <ul>
 *   <li><b>헤더 값</b> — {@code Authorization}·{@code x-access-token}·{@code X-API-Key} 등 어떤 헤더도
 *       읽지 않는다(CWE-532).</li>
 *   <li><b>스킴·userinfo·쿼리·프래그먼트</b> — 대상은 {@code host:port + path} 만. 쿼리에 키가 실릴 수
 *       있다(CWE-532).</li>
 *   <li><b>예외 메시지 원문</b> — 단순 클래스명만(CWE-209). 연결 오류 메시지에는 내부 주소가 섞인다.</li>
 *   <li><b>정상 응답 본문</b> — 2xx·3xx 는 본문을 읽지도 않는다.</li>
 * </ul>
 *
 * <p>오류 본문은 자격증명류 JSON 값·{@code Bearer} 값을 가리고, 제어문자를 {@code _} 로 바꾸고(CWE-117),
 * {@value #MAX_BODY_CHARS}자로 자른다. 읽는 크기는 해당 빈의 {@code maxInMemorySize} 를 그대로 따른다.
 * 이 마스킹은 <b>로그 레이아웃의 전역 마스킹({@code LogMaskingPatterns})과 별개로</b> 먼저 건다 — 값이
 * 따옴표 없는 스칼라인 경우와 {@code session}·{@code access} 낱말을 이 창구에서 확실히 덮기 위해서다.
 *
 * <p>⚠ 세션 자격증명이 응답에 실릴 수 있는 창구(관제 계정 창구)는 {@code logErrorBody=false} 로
 * 달아 <b>본문을 읽지도 않는다.</b>
 *
 * <p>요청 본문 DEBUG 기록은 두지 않았다 — 요청 본문은 {@code BodyInserter} 라 꺼내려면 요청을
 * 재구성해 나가는 바이트를 가로채야 하고, 그 자체가 「관측 외 동작」이 될 위험이 있다(보류).
 *
 * @design NFR-038
 */
public final class ExternalCallLoggingFilter {

    private static final Logger log = LoggerFactory.getLogger(ExternalCallLoggingFilter.class);

    /**
     * 포털 소재 조달의 연동 이름 — 그 축은 운영 설정 화면 대상이 아니라 {@code IntegrationEndpoint}
     * 열거에 없다. 나머지 연동은 열거의 {@code name()} 을 쓴다(값 복제 금지).
     */
    public static final String PORTAL_MATERIALS = "PORTAL_MATERIALS";

    /**
     * 주기 점검 호출 표식(요청 속성, 값 {@code Boolean.TRUE}) — 상태 점검·헬스처럼 주기적으로 반복되는
     * 호출에 호출부가 단다. 표식이 있으면 <b>성공(2xx·3xx) 완료 로그만 DEBUG</b> 로 낮추고, 오류 응답·
     * 연결 실패·취소는 표식과 무관하게 평소 레벨로 남긴다(NFR-038 v3 — 실제 호출 기록을 덮지 않되
     * 실패는 항상 남긴다).
     */
    public static final String PERIODIC_PROBE_ATTRIBUTE =
            ExternalCallLoggingFilter.class.getName() + ".periodicProbe";

    /** 취소 로그 레벨 분기용 연동 이름. */
    private static final String AI_SERVER = IntegrationEndpoint.AI_SERVER.name();

    /** 오류 본문 로그 최대 글자 수. */
    static final int MAX_BODY_CHARS = 1000;

    private static final String MASK = "***";

    /** 제어문자 + 유니코드 줄·문단 구분자 — 로그 한 줄을 여러 줄로 위조하는 통로(CWE-117). */
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029\\u0085]");

    /**
     * 마스킹·치환을 적용할 오류 본문 길이 상한(64KB) — 전역 마스커({@code LogMaskingPatterns.MAX_MASK_LENGTH})와
     * 같은 값이다. 본문은 버퍼 상한(최대 32MB)까지 올 수 있어 전체를 훑으면 그 자체가 부하가 된다.
     *
     * <p>잘라도 되는 이유: 출력은 <b>앞 {@value #MAX_BODY_CHARS}자</b>뿐이라 64KB 뒤는 어차피 나가지 않는다.
     * 단 <b>앞쪽에서 시작해 이 경계를 넘는 값</b>은 닫는 따옴표·괄호가 잘려 나가므로, 마스킹이
     * <b>닫힘이 없으면 입력 끝까지</b> 가려야 앞 1000자 안의 토큰 앞부분이 새지 않는다
     * ({@link #maskSensitiveMembers}).
     */
    static final int MAX_MASK_CHARS = 64 * 1024;

    /** 자격증명으로 보는 키 낱말(대소문자 무시, 키 이름 어디에든 들어 있으면 해당). */
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "token|access|refresh|secret|password|api_key|apikey|authorization|session",
            Pattern.CASE_INSENSITIVE);

    /** {@code Bearer <값>} 꼴 — 모두 소유 수량자(되돌아갈 이유가 없다). */
    private static final Pattern BEARER_VALUE = Pattern.compile(
            "(?i)\\bbearer\\s++[A-Za-z0-9\\-._~+/]++=*+");

    private ExternalCallLoggingFilter() {
    }

    /**
     * @param integration  로그의 연동 이름(상수만 넘긴다 — 치환하지 않는다).
     * @param logErrorBody {@code false} 면 오류 응답 본문을 읽지 않고 상태 코드만 남긴다.
     */
    public static ExchangeFilterFunction of(String integration, boolean logErrorBody) {
        return new Filter(integration, logErrorBody, ExternalCallLoggingFilter::sanitize);
    }

    /** 시험 전용 — 마스킹 실패 경로를 결정적으로 재현하기 위해 마스커를 주입한다. */
    static ExchangeFilterFunction of(String integration, boolean logErrorBody, UnaryOperator<String> sanitizer) {
        return new Filter(integration, logErrorBody, sanitizer);
    }

    /**
     * 필터 인스턴스. 이름 있는 타입으로 두는 이유는 <b>배선 시험</b>이 빈의 필터 목록에서 이 필터가
     * <b>맨 마지막</b>(가장 안쪽 — 재작성·가드·자격증명 필터 뒤)에 있는지 확인하기 위해서다.
     */
    public static final class Filter implements ExchangeFilterFunction {

        private final String integration;
        private final boolean logErrorBody;
        private final UnaryOperator<String> sanitizer;

        private Filter(String integration, boolean logErrorBody, UnaryOperator<String> sanitizer) {
            this.integration = Objects.requireNonNull(integration, "integration");
            this.logErrorBody = logErrorBody;
            this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        }

        public String integration() {
            return integration;
        }

        public boolean logErrorBody() {
            return logErrorBody;
        }

        @Override
        public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
            return Mono.defer(() -> {
                // 소요 시간은 <구독 시점>부터 잰다 — 재시도로 재구독되면 회차마다 새로 잰다.
                long startedAt = System.nanoTime();
                String method = request.method().name();
                String target = targetOf(request);
                boolean probe = isPeriodicProbe(request);
                // ★ 한 호출에 결과 로그는 한 줄 — 완료·실패를 찍은 뒤 하류가 취소해도(본문 읽기 중 시간 초과 등)
                //   취소 로그를 덧찍지 않는다.
                AtomicBoolean reported = new AtomicBoolean(false);
                return next.exchange(request)
                        .doOnError(ex -> {
                            if (reported.compareAndSet(false, true)) {
                                log.warn("[ExternalCall] failed integration={} method={} target={} "
                                                + "elapsedMs={} cause={}",
                                        integration, method, target, elapsedMs(startedAt),
                                        ex.getClass().getSimpleName());
                            }
                        })
                        .flatMap(response -> {
                            if (reported.compareAndSet(false, true)) {
                                logCompleted(integration, method, target, response.statusCode(),
                                        startedAt, probe);
                            }
                            if (!logErrorBody || !response.statusCode().isError()) {
                                return Mono.just(response);
                            }
                            return withLoggedErrorBody(integration, response, sanitizer);
                        })
                        // 호출자의 timeout()·사용자 취소는 필터에 <오류가 아니라 취소>로 도착한다.
                        .doOnCancel(() -> {
                            if (reported.compareAndSet(false, true)) {
                                logCancelled(integration, method, target, startedAt, probe);
                            }
                        });
            });
        }
    }

    private static boolean isPeriodicProbe(ClientRequest request) {
        return request.attribute(PERIODIC_PROBE_ATTRIBUTE).map(Boolean.TRUE::equals).orElse(false);
    }

    /**
     * 취소 로그 레벨 — AI 추론은 사용자 조작 취소가 잦아 INFO, 나머지는 WARN.
     * 주기 점검의 취소는 <b>점검 실패</b>로 보아 연동과 무관하게 WARN 이다(점검에는 사용자 취소가 없다).
     */
    private static void logCancelled(String integration, String method, String target,
                                     long startedAt, boolean probe) {
        String format = "[ExternalCall] cancelled integration={} method={} target={} elapsedMs={}";
        if (!probe && AI_SERVER.equals(integration)) {
            log.info(format, integration, method, target, elapsedMs(startedAt));
        } else {
            log.warn(format, integration, method, target, elapsedMs(startedAt));
        }
    }

    /** 오류 응답은 WARN, 성공은 INFO — 주기 점검의 성공만 DEBUG. */
    private static void logCompleted(String integration, String method, String target,
                                     HttpStatusCode status, long startedAt, boolean probe) {
        String format = "[ExternalCall] completed integration={} method={} target={} status={} elapsedMs={}";
        if (status.isError()) {
            log.warn(format, integration, method, target, status.value(), elapsedMs(startedAt));
        } else if (probe) {
            log.debug(format, integration, method, target, status.value(), elapsedMs(startedAt));
        } else {
            log.info(format, integration, method, target, status.value(), elapsedMs(startedAt));
        }
    }

    /** 오류 본문을 한 번 읽어 기록하고, 같은 본문을 실은 응답으로 되돌린다. */
    private static Mono<ClientResponse> withLoggedErrorBody(String integration, ClientResponse response,
                                                          UnaryOperator<String> sanitizer) {
        int status = response.statusCode().value();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {
                    logErrorBodySafely(integration, status, body, sanitizer);
                    return response.mutate().body(body).build();
                })
                // 관측 실패가 요청 결과를 바꾸면 안 된다 — 원래 응답을 그대로 넘긴다.
                .onErrorResume(ex -> Mono.just(response));
    }

    /**
     * 마스킹·치환이 어떤 이유로든 실패하면 <b>본문 로그만 생략</b>한다.
     *
     * <p>{@link StackOverflowError} 까지 여기서 잡는 이유: 리액터는 {@code map} 안에서 난 JVM 치명 오류를
     * {@code onErrorResume} 으로 넘기지 않고 그대로 다시 던진다 — 네티 스레드로 올라가 호출자가 응답 대신
     * 타임아웃을 받는다(관측이 요청 결과를 바꾼다). 메시지는 남기지 않는다(CWE-209).
     */
    private static void logErrorBodySafely(String integration, int status, String body,
                                           UnaryOperator<String> sanitizer) {
        try {
            logErrorBody(integration, status, body, sanitizer);
        } catch (RuntimeException | StackOverflowError e) {
            log.warn("[ExternalCall] error body skipped integration={} status={} cause={}",
                    integration, status, e.getClass().getSimpleName());
        }
    }

    private static void logErrorBody(String integration, int status, String body,
                                     UnaryOperator<String> sanitizer) {
        // 마스킹 전에 상한으로 먼저 자른다 — 출력은 앞 1000자뿐이다(MAX_MASK_CHARS 주석).
        String bounded = body.length() > MAX_MASK_CHARS ? body.substring(0, MAX_MASK_CHARS) : body;
        String safe = sanitizer.apply(bounded);
        // 판정·length 는 <원 본문> 기준이다.
        boolean truncated = body.length() > MAX_BODY_CHARS;
        if (safe.length() > MAX_BODY_CHARS) {
            safe = truncate(safe);
        }
        log.warn("[ExternalCall] error body integration={} status={} length={}{} body={}",
                integration, status, body.length(), truncated ? " truncated=true" : "", safe);
    }

    // ── reactor-netty HttpClient 직접 호출 경로 ─────────────────────────────

    /** 호출 1건의 관측 상태 — 구독마다 새로 만든다(경로·호출 간 공유 없음). */
    private static final class CallState {
        final long startedAt = System.nanoTime();
        final AtomicBoolean reported = new AtomicBoolean(false);
        volatile String method = "-";
        volatile String target = "-";
    }

    private static final String CALL_STATE_KEY = ExternalCallLoggingFilter.class.getName() + ".callState";

    /** 취소 감지용 — 연결이 끊길 때 그 연결로 나간 호출의 상태를 찾는다. */
    private static final AttributeKey<CallState> CHANNEL_CALL_STATE =
            AttributeKey.valueOf(ExternalCallLoggingFilter.class.getName() + ".channelCallState");

    /**
     * {@code WebClient} 가 아니라 reactor-netty {@link HttpClient} 를 직접 쓰는 경로(비식별 진행 조회 —
     * 본문 실은 GET)에 <b>같은 문구·같은 레벨 규칙</b>의 호출 로그를 훅으로 단다. [@design NFR-038]
     *
     * <ul>
     *   <li>호출별 상태는 연결 획득 Mono 에 {@code contextWrite} 로 싣는다 — 구독마다 새로 만들어지므로
     *       경로·호출 간에 공유되지 않는다. 요청·응답·오류 훅이 모두 그 컨텍스트를 본다.</li>
     *   <li>완료 = {@code doOnResponse}, 실패 = {@code doOnRequestError}/{@code doOnResponseError}.</li>
     *   <li>취소는 오류 훅으로 오지 않고 <b>연결 종료</b>로만 드러난다 — 요청 시점에 상태를 채널 속성에
     *       걸어 두고 {@code doOnDisconnected} 에서 아직 결과를 찍지 않은 호출만 {@code cancelled} 로 남긴다.
     *       ⚠ 상대가 응답 전에 연결을 끊은 경우 종료 훅이 오류 훅보다 먼저 돌면 {@code failed} 대신
     *       {@code cancelled} 로 남을 수 있다(결과가 남는다는 점은 같다).</li>
     *   <li>오류 <b>본문</b>은 기록하지 않는다 — 본문은 호출부가 소비한다.</li>
     *   <li>연결 실패는 요청 URL 이 아직 없어서 {@code uri} 와 기준 주소로 대상을 만든다.</li>
     * </ul>
     *
     * @param baseUrl 이 클라이언트의 기준 주소(연결 실패 시 대상 표기용). 비어 있어도 된다.
     */
    public static HttpClient withNettyCallLogging(HttpClient client, String integration, String baseUrl) {
        Objects.requireNonNull(integration, "integration");
        return client
                .mapConnect(connect -> connect.contextWrite(ctx -> ctx.put(CALL_STATE_KEY, new CallState())))
                .doOnRequest((req, conn) -> {
                    CallState state = stateOf(req.currentContextView());
                    if (state == null) {
                        return;
                    }
                    state.method = req.method().name();
                    state.target = targetOf(parse(req.resourceUrl()));
                    conn.channel().attr(CHANNEL_CALL_STATE).set(state);
                })
                .doOnResponse((res, conn) -> {
                    CallState state = stateOf(res.currentContextView());
                    if (state != null && state.reported.compareAndSet(false, true)) {
                        logCompleted(integration, res.method().name(), targetOf(parse(res.resourceUrl())),
                                HttpStatusCode.valueOf(res.status().code()), state.startedAt, false);
                    }
                })
                .doOnRequestError((req, ex) -> {
                    CallState state = stateOf(req.currentContextView());
                    String target = req.resourceUrl() != null
                            ? targetOf(parse(req.resourceUrl()))
                            : targetOf(parse(joinBase(baseUrl, req.uri())));
                    logNettyFailure(integration, state, req.method().name(), target, ex);
                })
                .doOnResponseError((res, ex) -> logNettyFailure(integration, stateOf(res.currentContextView()),
                        res.method().name(), targetOf(parse(res.resourceUrl())), ex))
                .doOnDisconnected(conn -> {
                    CallState state = conn.channel().attr(CHANNEL_CALL_STATE).getAndSet(null);
                    if (state != null && state.reported.compareAndSet(false, true)) {
                        logCancelled(integration, state.method, state.target, state.startedAt, false);
                    }
                });
    }

    private static void logNettyFailure(String integration, CallState state, String method, String target,
                                        Throwable ex) {
        if (state != null && !state.reported.compareAndSet(false, true)) {
            return;
        }
        log.warn("[ExternalCall] failed integration={} method={} target={} elapsedMs={} cause={}",
                integration, method, target, state == null ? -1 : elapsedMs(state.startedAt),
                ex.getClass().getSimpleName());
    }

    private static CallState stateOf(ContextView ctx) {
        return ctx.<CallState>getOrEmpty(CALL_STATE_KEY).orElse(null);
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String joinBase(String baseUrl, String uri) {
        if (uri == null) {
            return baseUrl;
        }
        if (uri.startsWith("http://") || uri.startsWith("https://") || baseUrl == null || baseUrl.isBlank()) {
            return uri;
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + (uri.startsWith("/") ? uri : "/" + uri);
    }

    /** 마스킹을 먼저 한다 — 자른 뒤에 가리면 경계에 걸린 값이 패턴을 빠져나간다. */
    static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String masked = maskSensitiveMembers(raw);
        masked = BEARER_VALUE.matcher(masked).replaceAll("Bearer " + MASK);
        return UNSAFE_CHARS.matcher(masked).replaceAll("_");
    }

    /**
     * 키 이름에 자격증명 낱말이 든 JSON 멤버의 <b>값 전체</b>를 {@code "***"} 로 바꾼다.
     *
     * <h3>왜 정규식이 아니라 한 번 훑는 스캐너인가</h3>
     * <p>값은 문자열·스칼라뿐 아니라 <b>객체·배열</b>일 수 있고, 임의 깊이의 괄호 짝은 재귀 없는 정규식으로
     * 맞출 수 없다(첫 닫는 괄호에서 멈추면 안쪽 값이 샌다). 문자마다 교대 반복하는 정규식은 긴 값에서
     * {@link StackOverflowError} 를 냈다. 이 스캐너는 <b>되돌아가지 않고 재귀도 없어</b> 입력 길이에
     * 선형이고 스택을 쓰지 않는다.
     *
     * <ul>
     *   <li>문자열 토큰 뒤에 (공백을 건너) {@code :} 가 오고 그 키에 낱말이 들어 있으면 값을 가린다.</li>
     *   <li>문자열 값: 역슬래시 뒤 한 글자(개행 포함)를 건너뛰며 닫는 따옴표까지 — <b>없으면 입력 끝까지</b>
     *       (64KB 절단·비정형 이스케이프로 닫힘이 사라져도 새지 않는다).</li>
     *   <li>객체·배열 값: 괄호를 <b>종류별로</b> 추적해 짝이 맞는 닫는 괄호까지(안쪽 문자열은 건너뛴다) —
     *       <b>종류가 어긋나거나 짝이 없거나 깊이 상한을 넘으면 입력 끝까지 과마스킹</b>한다. 뒤 멤버(사유)가
     *       함께 사라지지만 그것은 관측 손실이고 유출이 아니다.</li>
     *   <li>그 밖의 스칼라: {@code , } ] "} 또는 공백 전까지. 값 자리가 비어 있으면 입력 끝까지.</li>
     *   <li>값 뒤 첫 글자는 {@code ,}·입력 끝·<b>바깥 컨테이너의 짝 맞는 닫는 괄호</b>여야 한다 — 아니면
     *       (남는 닫는 괄호 등) 값 경계를 믿지 않고 입력 끝까지 가린다. 그래서 문서 전체의 괄호 스택도
     *       함께 유지한다.</li>
     * </ul>
     */
    static String maskSensitiveMembers(String in) {
        int n = in.length();
        StringBuilder out = new StringBuilder(n);
        BracketStack outer = new BracketStack();       // 가린 값 <바깥>의 구조 — 값 뒤 글자 검증용
        int copied = 0;
        int i = 0;
        while (i < n) {
            char c = in.charAt(i);
            if (c == '"') {
                int keyEnd = stringEnd(in, i);         // 닫는 따옴표 다음 위치
                int k = skipWhitespace(in, keyEnd);
                if (k < n && in.charAt(k) == ':'
                        && SENSITIVE_KEY.matcher(in).region(i + 1, Math.max(i + 1, keyEnd - 1)).find()) {
                    int v = skipWhitespace(in, k + 1);
                    int end = valueEnd(in, v);
                    if (!followsValueProperly(in, end, outer)) {
                        end = n;                       // 구조가 어긋나면 값 경계를 믿지 않는다(fail-closed)
                    }
                    out.append(in, copied, v).append('"').append(MASK).append('"');
                    copied = end;
                    i = end;
                } else {
                    i = keyEnd;
                }
                continue;
            }
            if (c == '{' || c == '[') {
                outer.push(c);
            } else if (c == '}' || c == ']') {
                outer.pop(c);
            }
            i++;
        }
        out.append(in, copied, n);
        return out.toString();
    }

    /**
     * 값이 끝난 뒤 첫 글자(공백 제외)가 입력 끝·{@code ,}·<b>바깥 컨테이너의 짝 맞는 닫는 괄호</b>인가.
     * 아니면(종류가 다른 괄호·남는 닫는 괄호·그 밖의 글자) 값 경계를 믿을 수 없다.
     */
    private static boolean followsValueProperly(String in, int end, BracketStack outer) {
        int after = skipWhitespace(in, end);
        if (after >= in.length()) {
            return true;
        }
        char c = in.charAt(after);
        return c == ',' || outer.closes(c);
    }

    /**
     * 괄호 종류 스택 — 깊이 상한 {@value #MAX_BRACKET_DEPTH}. 넘치거나 종류가 어긋나면 <b>깨짐</b>으로
     * 표시하고, 깨진 스택은 어떤 닫는 괄호도 짝으로 인정하지 않는다(fail-closed). 재귀 없이 배열만 쓴다.
     */
    private static final class BracketStack {
        private final char[] opens = new char[MAX_BRACKET_DEPTH];
        private int size;
        private boolean broken;

        /** @return 넣었으면 {@code true}, 상한 초과면 {@code false}(깨짐) */
        boolean push(char open) {
            if (broken || size == opens.length) {
                broken = true;
                return false;
            }
            opens[size++] = open;
            return true;
        }

        /** @return 짝이 맞아 꺼냈으면 {@code true}, 종류 불일치·빈 스택이면 {@code false}(깨짐) */
        boolean pop(char close) {
            if (!closes(close)) {
                broken = true;
                return false;
            }
            size--;
            return true;
        }

        boolean closes(char close) {
            return !broken && size > 0 && opens[size - 1] == (close == '}' ? '{' : close == ']' ? '[' : 0);
        }

        boolean isEmpty() {
            return size == 0;
        }
    }

    /** 괄호 깊이 상한 — 실제 오류 본문은 이보다 훨씬 얕다. 넘치면 입력 끝까지 가린다. */
    private static final int MAX_BRACKET_DEPTH = 256;

    /** {@code start} 의 따옴표로 시작한 문자열이 끝난 <b>다음</b> 위치(닫힘이 없으면 입력 길이). */
    private static int stringEnd(String in, int start) {
        int n = in.length();
        int j = start + 1;
        while (j < n) {
            char c = in.charAt(j);
            if (c == '\\') {
                j += 2;                                 // 이스케이프 뒤 한 글자(개행 포함) — 끝이면 넘어간다
            } else if (c == '"') {
                return j + 1;
            } else {
                j++;
            }
        }
        return n;
    }

    /**
     * 값이 끝난 다음 위치. 경계를 확신할 수 없으면 <b>입력 길이</b>(끝까지 가림)를 돌려준다 —
     * 괄호 종류 불일치·깊이 초과·짝 없음, 그리고 값 자리가 비어 있는 경우(첫 글자가 {@code , } ]}).
     */
    private static int valueEnd(String in, int v) {
        int n = in.length();
        if (v >= n) {
            return n;
        }
        char c = in.charAt(v);
        if (c == '"') {
            return stringEnd(in, v);
        }
        if (c == '{' || c == '[') {
            BracketStack inner = new BracketStack();
            int j = v;
            while (j < n) {
                char d = in.charAt(j);
                if (d == '"') {
                    j = stringEnd(in, j);
                    continue;
                }
                if (d == '{' || d == '[') {
                    if (!inner.push(d)) {
                        return n;                       // 깊이 초과
                    }
                } else if (d == '}' || d == ']') {
                    if (!inner.pop(d)) {
                        return n;                       // 종류 불일치
                    }
                    if (inner.isEmpty()) {
                        return j + 1;
                    }
                }
                j++;
            }
            return n;                                   // 짝이 안 맞음
        }
        if (c == ',' || c == '}' || c == ']') {
            return n;                                   // 값 자리가 비었다 — 뒤를 믿지 않는다
        }
        int j = v;
        while (j < n) {
            char d = in.charAt(j);
            if (d == ',' || d == '}' || d == ']' || d == '"' || Character.isWhitespace(d)) {
                break;
            }
            j++;
        }
        return j;
    }

    private static int skipWhitespace(String in, int from) {
        int j = Math.min(from, in.length());
        while (j < in.length() && Character.isWhitespace(in.charAt(j))) {
            j++;
        }
        return j;
    }

    /** 대리쌍 한가운데서 자르지 않는다. */
    private static String truncate(String s) {
        int end = MAX_BODY_CHARS;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** {@code host[:port]path} — 스킴·userinfo·쿼리·프래그먼트 제외. */
    static String targetOf(ClientRequest request) {
        return targetOf(request.url());
    }

    static String targetOf(URI url) {
        if (url == null) {
            return "-";
        }
        String host = url.getHost();
        StringBuilder sb = new StringBuilder(host == null || host.isBlank() ? "-" : host);
        if (url.getPort() >= 0) {
            sb.append(':').append(url.getPort());
        }
        String path = url.getRawPath();
        if (path != null) {
            sb.append(path);
        }
        return UNSAFE_CHARS.matcher(sb).replaceAll("_");
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
