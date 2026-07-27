package kr.co.cudo.authoring.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.webhook.ClientIpResolver;
import kr.co.cudo.authoring.common.security.webhook.WebhookGuardUnavailableException;
import kr.co.cudo.authoring.common.security.webhook.WebhookGuardedRequest;
import kr.co.cudo.authoring.common.security.webhook.WebhookIpAllowlist;
import kr.co.cudo.authoring.common.security.webhook.WebhookNonceStore;
import kr.co.cudo.authoring.common.security.webhook.WebhookProtectedPaths;
import kr.co.cudo.authoring.common.security.webhook.WebhookRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Webhook HMAC 시그니처 검증 필터 — 외부 시스템 결과 인계 콜백의 단독 인증 수단.
 *
 * <p>대상 경로는 {@link WebhookProtectedPaths} 의 <b>allowlist(보호 대상)</b> 로 판정한다. 판정은
 * Spring MVC 라우팅과 <b>동일한</b> {@code RequestPath} + {@code PathPattern} 기반이며, 판정 불가·예외는
 * <b>보호(필터 적용)</b> 로 고정한다(fail-closed).
 *
 * <p><b>이중 게이트</b>: 본 필터는 통과 요청을 {@link CachedBodyHttpServletRequest}(=
 * {@link WebhookGuardedRequest}) 로 감싸고, {@code WebhookGateInterceptor} 가 컨트롤러 진입 직전 그
 * 증거의 유무를 다시 확인한다. 어떤 경로 표현으로 라우팅되든 "필터를 통과했다는 증거" 없이는 401 이다.
 *
 * <p><b>VLM 콜백 제외</b>: 벤더 확정 계약(IntelliVIX Video VLM API v2.0.1) describe 콜백은
 * 서명 헤더가 없는 규격이라 {@code /v1/vlm/callback} 는 HMAC 대상이 아니다(2026-07-07 승인).
 * 대신 ①IP allowlist(설정 시) ②rate limit ③본문 size cap 을 적용하고, 무단 주입은
 * {@code VlmResultService} 의 request_id 발급 게이트가 차단한다.
 *
 * <h3>인증 헤더</h3>
 * <ul>
 *   <li>{@code X-Signature: hmac-sha256=<hex>} — HMAC-SHA256(secret, "{timestamp}.{body}") 의 hex</li>
 *   <li>{@code X-Timestamp: <unix-ms>} — 과거 방향 ±윈도우(기본 300s), 미래 방향은 시계 오차분(30s)만 허용</li>
 * </ul>
 *
 * <h3>검증 순서 (고정 — S-08)</h3>
 * <p>rate-limit → size cap → 헤더/timestamp 윈도우 → <b>서명 검증 성공</b> → nonce 소비.
 * nonce 를 서명 검증 <b>전에</b> 소비하면 무인증 공격자가 저장소를 무한 팽창시키는
 * pre-auth write DoS 가 된다. 검증 실패 요청은 DB 를 전혀 쓰지 않는다.
 *
 * <h3>보안 가드</h3>
 * <ul>
 *   <li><b>기동 fail-closed</b>: 시크릿이 빈 값이면 <b>애플리케이션 기동 실패</b>. 조용한 401 로 방치하면
 *       "정상 콜백은 죽고 우회 경로만 열린" 상태가 배포된 뒤에야 발견된다(E-ISSUE-04).</li>
 *   <li><b>시크릿 강도</b>: 256bit(32B) 이상 강제. 부족 시 부팅 차단.</li>
 *   <li><b>상수시간 비교</b>: MessageDigest.isEqual 로 timing attack 차단 (CWE-208)</li>
 *   <li><b>replay 방지</b>: timestamp 윈도우 + <b>서명 nonce 1회성 소비</b>(노드 공유, CWE-294)</li>
 *   <li><b>Log Injection</b>: 헤더값/경로 sanitize 후 로깅 (CWE-117)</li>
 *   <li><b>본문 크기 제한</b>: Content-Length 누락 401(증강)/411(VLM), 상한 초과 413. 실제 스트림도 bounded read</li>
 *   <li><b>RateLimit</b>: 인증 실패 IP 분당 5회 초과 시 60초 backoff (CWE-307). 로컬+공유 2단</li>
 * </ul>
 *
 * <h3>응답 코드 규약 (S-03 / S-10)</h3>
 * <ul>
 *   <li><b>401</b> — 인증 실패(서명·타임스탬프·헤더). 5xx 를 쓰지 않는다(벤더 재시도 폭주 방지).</li>
 *   <li><b>409</b> — replay(이미 소비된 서명). 인증 실패가 아니라 <b>중복 흡수</b>임을 구분해 벤더의
 *       정상 재시도를 인증 실패로 오인시키지 않는다.</li>
 *   <li><b>503</b> — 가드 저장소 장애. 일시적 장애이므로 벤더가 재시도할 수 있게 한다.</li>
 * </ul>
 */
@Slf4j
@Component
public class HmacWebhookFilter extends OncePerRequestFilter {

    public static final String SIGNATURE_HEADER = "X-Signature";
    public static final String TIMESTAMP_HEADER = "X-Timestamp";
    public static final String SIGNATURE_PREFIX = "hmac-sha256=";
    public static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 최대 webhook 본문 크기 — 1MB. */
    public static final long MAX_WEBHOOK_BODY_BYTES = 1024L * 1024L;

    /** 시크릿 최소 길이 — HS256 권장 256bit = 32B. */
    public static final int MIN_SECRET_BYTES = 32;

    /** 시크릿 설정 위치 안내 — 기동 실패 메시지에 포함한다(S-22). */
    private static final String SECRET_HINT =
            "환경변수 WEBHOOK_HMAC_SECRET_AUGMENT (또는 webhook.hmac.secret.augment) 를 "
                    + MIN_SECRET_BYTES + "B(256bit) 이상으로 설정하세요. "
                    + "설정 위치: .env / docker compose env / application-{profile}.yml";

    /**
     * 증강 결과 콜백 경로 — {@link WebhookProtectedPaths#PATH_AUGMENT} 별칭(기존 참조 호환).
     * 요청측·dev 시뮬·검증 필터가 모두 이 상수를 참조해 경로 드리프트 회귀를 차단한다.
     */
    public static final String PATH_AUGMENT = WebhookProtectedPaths.PATH_AUGMENT;

    /** VLM describe 콜백 경로 — 벤더 규격상 무서명(HMAC 대상 아님). */
    public static final String PATH_VLM = WebhookProtectedPaths.PATH_VLM;

    /**
     * VLM 콜백 본문 하드 상한 — 4MB. 정상 최대치(results 500개 × description 2000자, UTF-8 다바이트)를
     * 수용하면서 GB 규모 공격을 조기 차단한다. 역직렬화 전에 스트림 단계에서 캡한다.
     */
    public static final long MAX_VLM_BODY_BYTES = 4L * 1024L * 1024L;

    /** 미래 방향 timestamp 허용치 — 시계 오차분만(A-ISSUE-12 ②). 과거는 windowSeconds 를 따른다. */
    static final long FUTURE_SKEW_MS = 30_000L;

    /** 인증 실패 메트릭 이름 (S-03). 태그는 저카디널리티 상수만 사용한다(M-2). */
    public static final String METRIC_AUTH_FAILED = "webhook.auth.failed";

    private final ObjectMapper objectMapper;
    private final WebhookNonceStore nonceStore;
    private final WebhookRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final WebhookIpAllowlist vlmIpAllowlist;
    private final MeterRegistry meterRegistry;
    private final String secretAugment;
    private final long windowSeconds;

    /**
     * 리포지토리에 커밋돼 있어 <b>공개된</b> placeholder 시크릿 목록 (DEV_FIX H-1, CWE-1392/798).
     *
     * <p>이 값들은 빈 값도 아니고 32B 이상이라 기존 fail-closed 검사 두 개를 <b>모두 통과</b>했다.
     * 그 결과 "시크릿 미설정 시 기동 실패" 라는 방어가 실제로는 <b>"공개된 키로 조용히 기동"</b> 이 되어,
     * 누구나 유효 서명을 위조할 수 있는 상태로 배포됐다. local 프로파일(개발 편의)에서만 허용하고
     * 그 외 프로파일에서는 기동을 차단한다.
     */
    static final Set<String> KNOWN_PLACEHOLDER_SECRETS = Set.of(
            "local-augment-callback-hmac-change-me-32bytes-min",
            // 테스트 리소스(src/test/resources/application-local.yml)에 커밋된 값 — local 프로파일에서만
            // 허용되고 그 외 프로파일로 새 나가면 기동을 막는다.
            "test-augment-callback-hmac-secret-32bytes-min",
            "change-me",
            "changeme");

    /** 개발 편의상 placeholder 시크릿을 허용하는 프로파일. */
    private static final String LOCAL_PROFILE = "local";

    public HmacWebhookFilter(ObjectMapper objectMapper,
                             WebhookNonceStore nonceStore,
                             WebhookRateLimiter rateLimiter,
                             ClientIpResolver clientIpResolver,
                             WebhookIpAllowlist vlmIpAllowlist,
                             MeterRegistry meterRegistry,
                             Environment environment,
                             @Value("${webhook.hmac.secret.augment:}") String secretAugment,
                             @Value("${webhook.hmac.timestamp-window-seconds:300}") long windowSeconds) {
        this.objectMapper = objectMapper;
        this.nonceStore = nonceStore;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.vlmIpAllowlist = vlmIpAllowlist;
        this.meterRegistry = meterRegistry;
        // fail-closed: 빈 시크릿·약한 시크릿·공개된 placeholder 는 기동 자체를 막는다(조용한 401/위조 금지).
        boolean localProfile = environment != null
                && List.of(environment.getActiveProfiles()).contains(LOCAL_PROFILE);
        ensureSecretConfigured("webhook.hmac.secret.augment", secretAugment, localProfile);
        this.secretAugment = secretAugment;
        this.windowSeconds = Math.max(60L, windowSeconds);
    }

    /**
     * 시크릿 필수 + 최소 강도 + <b>공개 placeholder 금지</b> 검증 — 위반 시
     * {@link BeanInitializationException} 으로 기동 차단.
     *
     * <p>과거에는 빈 시크릿을 "요청이 올 때 401" 로 처리했는데, 실제 배포에서 {@code .env} 의 빈 값이
     * yml 기본값을 덮어써 <b>정상 콜백이 전건 401</b> 인 상태가 오래 발견되지 않았다(E-ISSUE-04).
     * 반대로 그 fail-closed 를 피하려고 넣은 <b>커밋된 기본값</b>은 "공개 키로 조용히 기동" 이라는 더 큰
     * 결함이 됐다(DEV_FIX H-1). 두 실패 모드를 모두 막으려면 <b>빈 값도 공개 기본값도</b> 거부해야 한다.
     *
     * @param localProfile local 프로파일이면 개발 편의상 placeholder 를 허용한다(로컬 스택 기동 유지)
     */
    private static void ensureSecretConfigured(String key, String secret, boolean localProfile) {
        if (secret == null || secret.isBlank()) {
            throw new BeanInitializationException(
                    key + " 이(가) 설정되지 않았습니다. webhook 콜백 인증을 수행할 수 없어 기동을 중단합니다. "
                            + SECRET_HINT);
        }
        // Spring 은 shell/docker 의 `${VAR:?err}` 문법을 지원하지 않는다 — 미설정 시 "?err" 이 그대로
        // 시크릿이 되어 "커밋된 문자열로 기동" 이 된다. 그 회귀를 구조적으로 차단한다.
        if (secret.startsWith("?")) {
            throw new BeanInitializationException(
                    key + " 값이 해석되지 않은 placeholder 기본값(\"?...\") 입니다. Spring 은 "
                            + "${VAR:?message} 를 fail-fast 로 처리하지 않고 \"?message\" 를 기본값으로 "
                            + "사용합니다. yml 은 ${VAR:} 로 두고 미설정 시 본 검증으로 기동을 차단하세요. "
                            + SECRET_HINT);
        }
        if (!localProfile && KNOWN_PLACEHOLDER_SECRETS.contains(secret)) {
            throw new BeanInitializationException(
                    key + " 에 리포지토리에 커밋된 공개 placeholder 값이 설정되어 있습니다. 공개된 키는 "
                            + "누구나 유효 서명을 위조할 수 있어 HMAC 검증이 무의미해집니다(DEV_FIX H-1). "
                            + "`openssl rand -hex 32` 로 생성한 난수를 주입하세요. " + SECRET_HINT);
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new BeanInitializationException(
                    key + " 의 시크릿 길이가 " + bytes + "B 입니다. 최소 " + MIN_SECRET_BYTES
                            + "B(256bit) 이상이어야 합니다. (DEV_FIX M-1) " + SECRET_HINT);
        }
    }

    /**
     * 필터 적용 여부 — allowlist(보호 대상) 기반. 판정 불가·예외는 <b>적용</b>으로 고정(S-13).
     *
     * <p>구 구현은 {@code getRequestURI()} 문자열 정확일치라 {@code %61ug} 같은 인코딩 변형이
     * "스킵 + 컨트롤러 도달" 로 관통했다(E-ISSUE-01 CRITICAL).
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        try {
            return !WebhookProtectedPaths.isProtected(request);
        } catch (Exception e) {
            log.warn("[Webhook] filter applicability check failed — applying filter (fail-closed) reason={}",
                    e.getClass().getSimpleName());
            return false; // 판정 실패 = 보호
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        PathView path = PathView.of(request);
        String clientIp = clientIpResolver.resolve(request);

        // 서명 필수 여부는 allowlist 로 판정. 판정 불가 시 requiresSignature=true (fail-closed).
        if (!WebhookProtectedPaths.requiresSignature(request)
                && WebhookProtectedPaths.requiresGuardOnly(request)) {
            handleGuardOnly(request, response, chain, path, clientIp);
            return;
        }
        handleSignatureRequired(request, response, chain, path, clientIp);
    }

    /**
     * 한 요청의 <b>세 가지 경로 표현</b>을 한데 묶는다 — 용도를 섞으면 결함이 된다.
     *
     * @param log       원시 URI(sanitize) — <b>로그 전용</b>
     * @param canonical MVC 라우팅과 동일한 정규화 경로 — <b>nonce 키 등 보안 판정용</b> (H-2)
     * @param tag       {@code aug}/{@code vlm}/{@code other} — <b>메트릭 태그 전용</b> 저카디널리티 (M-2)
     */
    private record PathView(String log, String canonical, String tag) {
        static PathView of(HttpServletRequest request) {
            return new PathView(
                    WebhookProtectedPaths.describePath(request),
                    WebhookProtectedPaths.canonicalPath(request),
                    WebhookProtectedPaths.metricTag(request));
        }
    }

    // ─── 서명 필수 경로(증강 콜백) ─────────────────────────────────────

    private void handleSignatureRequired(HttpServletRequest request,
                                         HttpServletResponse response,
                                         FilterChain chain,
                                         PathView path,
                                         String clientIp) throws IOException, ServletException {
        // 1) rate limit — 실패 누적 IP 는 backoff (DB 왕복 없이 로컬 1차 차단)
        if (rateLimiter.isLimited(clientIp)) {
            log.warn("[Webhook] rate-limited ip={} path={}", sanitize(clientIp), path.log());
            writeTooManyRequests(response);
            return;
        }

        // 2) 방어적 fail-closed — 정상 기동 경로에서는 도달하지 않는다(빈 시크릿이면 기동 실패).
        if (secretAugment == null || secretAugment.isBlank()) {
            log.error("[Webhook] secret missing path={}", path.log());
            fail(response, path, "secret_missing", "Webhook 시크릿이 설정되지 않았습니다.");
            return;
        }

        // 3) 본문 크기 가드 (Content-Length 누락 401 / 초과 413)
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0) {
            log.warn("[Webhook] missing Content-Length path={}", path.log());
            fail(response, path, "missing_content_length", "Content-Length 헤더가 필요합니다.");
            return;
        }
        if (contentLength > MAX_WEBHOOK_BODY_BYTES) {
            log.warn("[Webhook] body too large path={} length={}", path.log(), contentLength);
            writePayloadTooLarge(response, MAX_WEBHOOK_BODY_BYTES);
            return;
        }

        String signature = request.getHeader(SIGNATURE_HEADER);
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        if (signature == null || signature.isBlank() || timestamp == null || timestamp.isBlank()) {
            log.warn("[Webhook] missing signature/timestamp path={}", path.log());
            rateLimiter.recordFailure(clientIp);
            fail(response, path, "missing_signature", "HMAC 시그니처 헤더가 필요합니다.");
            return;
        }
        if (!signature.startsWith(SIGNATURE_PREFIX)) {
            log.warn("[Webhook] invalid signature format path={}", path.log());
            rateLimiter.recordFailure(clientIp);
            fail(response, path, "unsupported_algorithm", "지원하지 않는 시그니처 알고리즘입니다.");
            return;
        }

        // 4) timestamp 윈도우 — 미래 방향은 시계 오차분만 허용(유효창이 2배가 되던 문제 교정)
        long tsMs;
        try {
            tsMs = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            log.warn("[Webhook] timestamp not numeric path={}", path.log());
            rateLimiter.recordFailure(clientIp);
            fail(response, path, "timestamp_malformed", "X-Timestamp 헤더가 유효하지 않습니다.");
            return;
        }
        long nowMs = System.currentTimeMillis();
        long ageMs = nowMs - tsMs;
        if (ageMs > windowSeconds * 1000L || ageMs < -FUTURE_SKEW_MS) {
            log.warn("[Webhook] timestamp window exceeded path={} ageMs={}", path.log(), ageMs);
            rateLimiter.recordFailure(clientIp);
            fail(response, path, "timestamp_window", "시그니처 timestamp 가 허용 시간 윈도우를 벗어났습니다.");
            return;
        }

        // 5) 본문 캐싱 — 컨트롤러에서 다시 읽을 수 있도록 wrapper 사용. 본문도 크기 캡 적용.
        CachedBodyHttpServletRequest cached;
        try {
            cached = new CachedBodyHttpServletRequest(request, MAX_WEBHOOK_BODY_BYTES, true);
        } catch (PayloadTooLargeException e) {
            log.warn("[Webhook] body too large (stream) path={}", path.log());
            writePayloadTooLarge(response, MAX_WEBHOOK_BODY_BYTES);
            return;
        }
        byte[] body = cached.getCachedBody();

        // 6) 서명 검증 (상수시간 비교 — CWE-208)
        String providedHex = signature.substring(SIGNATURE_PREFIX.length()).trim();
        String canonical = timestamp + "." + new String(body, StandardCharsets.UTF_8);
        String computedHex;
        try {
            computedHex = hmacSha256Hex(secretAugment, canonical);
        } catch (Exception e) {
            log.error("[Webhook] hmac compute failed path={}", path.log(), e);
            fail(response, path, "hmac_error", "시그니처 검증 중 오류가 발생했습니다.");
            return;
        }
        byte[] providedBytes = providedHex.toLowerCase().getBytes(StandardCharsets.UTF_8);
        byte[] computedBytes = computedHex.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(providedBytes, computedBytes)) {
            log.warn("[Webhook] signature mismatch path={}", path.log());
            rateLimiter.recordFailure(clientIp);
            fail(response, path, "signature_mismatch", "시그니처가 일치하지 않습니다.");
            return;
        }

        // 7) nonce 1회성 소비 — 반드시 서명 검증 성공 이후 (pre-auth write DoS 차단, S-08).
        //    키는 <b>정규화 경로</b> 기준이다. 원시 URI 를 쓰면 %61ug 류 인코딩 변형마다 키가 갈라져
        //    같은 서명의 replay 가 전부 신규로 통과한다(H-2).
        String nonceKey = nonceHash(path.canonical(), timestamp, providedHex);
        boolean firstUse;
        try {
            firstUse = nonceStore.consume(nonceKey, path.canonical(), nonceTtl());
        } catch (WebhookGuardUnavailableException e) {
            log.error("[Webhook] nonce store unavailable — rejecting (fail-closed) path={}", path.log());
            writeServiceUnavailable(response);
            return;
        }
        if (!firstUse) {
            // replay 는 인증 실패가 아니라 중복 흡수 — 401 이 아닌 409 로 구분한다(S-10).
            log.warn("[Webhook] replay detected (signature already consumed) path={}", path.log());
            writeReplayConflict(response);
            return;
        }

        rateLimiter.reset(clientIp);
        // 8) nonce 는 "예약" 상태다 — 하류가 5xx/예외로 실패하면 해제해 벤더의 동일-바이트 재전송이
        //    다시 처리되게 한다. 해제하지 않으면 적용되지 않은 결과가 409 로 흡수돼 영구 유실된다(M-1).
        boolean committed = false;
        try {
            chain.doFilter(cached, response);
            // 전제: 증강 콜백 컨트롤러는 동기 반환이라 여기서 최종 상태가 확정돼 있다.
            //   비동기 반환(DeferredResult/CompletableFuture)으로 바뀌면 확정 전 상태(200)를 읽어
            //   하류 5xx 에서도 nonce 를 해제하지 않게 되므로(M-1 무력화) 판정 지점을 옮겨야 한다.
            committed = response.getStatus() < 500;
        } finally {
            if (!committed) {
                releaseNonce(nonceKey, path);
            }
        }
    }

    /** nonce 예약 해제 — 실패해도 요청 처리 결과를 바꾸지 않는다(이미 오류 응답 중). */
    private void releaseNonce(String nonceKey, PathView path) {
        try {
            nonceStore.release(nonceKey);
            log.warn("[Webhook] downstream failed — nonce released for retry path={}", path.log());
        } catch (RuntimeException e) {
            log.error("[Webhook] nonce release failed path={} reason={}",
                    path.log(), e.getClass().getSimpleName());
        }
    }

    // ─── 무서명 가드 전용 경로(VLM 콜백) ─────────────────────────────

    /**
     * 벤더 무서명 규격 경로 — HMAC 없이 ①IP allowlist ②rate limit ③본문 크기 상한을 적용한다(B-ISSUE-25).
     * Content-Length(위조 가능) + 실제 스트림 길이 두 단계로 캡하여 역직렬화 전에 조기 거부한다.
     *
     * <h3>하류 인증 실패도 집계한다 (DEV_FIX H-3)</h3>
     * <p>이 경로의 <b>실제 인증 관문</b>은 필터가 아니라 {@code VlmResultService.lookupForProcessing}
     * (미발급 {@code request_id} → 401)이다. 필터 단계 실패(Content-Length 누락/초과)만 카운트하면
     * "정상 형식 + 위조 request_id" 공격이 rate limit 에 <b>전혀 잡히지 않고</b> 매 요청 비관적 락
     * SELECT 를 유발한다. 그래서 체인 반환 후 응답 상태를 보고 인증 실패(401/403)를 집계한다.
     *
     * <h3>집계에는 예외를 두지 않는다 (REDESIGN R-1 — N-3 설계 폐기)</h3>
     * <p>과거에는 "신뢰 프록시 미설정 + {@code X-Forwarded-For} 관측" 이면 하류 집계를 <b>끄는</b>
     * 게이트가 있었다(N-3). {@code X-Forwarded-For} 는 <b>공격자가 임의로 붙일 수 있는 헤더</b>라,
     * 그 존재를 조건으로 보안 통제를 끄면 <b>헤더 한 줄로 rate limit 이 무력화</b>된다 —
     * 실측상 XFF 없이는 6회째 429, XFF 를 붙이면 15회 전부 401 로 {@code B-ISSUE-25} 의
     * 비관적 락 SELECT 무제한 유발이 그대로 재개통됐다. 게다가 그 게이트는 목적이던 가용성도 지키지
     * 못했다(필터 단계 411/413·서명 불일치는 게이트와 무관하게 수렴 IP 로 집계됨).
     *
     * <p>"프록시 뒤인데 설정 누락" 이라는 <b>가용성</b> 문제는 런타임 우회가 아니라
     * <b>기동 차단(fail-fast)</b> 으로 푼다 — prd/stg 는 {@code webhook.trusted-proxy-cidrs} 미설정 시
     * 기동되지 않는다({@code WebhookConfigProfiles}). 따라서 필터 단계 실패와 하류 인증 실패는
     * <b>동일 정책</b>으로 집계된다(R-4).
     */
    private void handleGuardOnly(HttpServletRequest request,
                                 HttpServletResponse response,
                                 FilterChain chain,
                                 PathView path,
                                 String clientIp) throws IOException, ServletException {
        if (!vlmIpAllowlist.isAllowed(clientIp)) {
            log.warn("[Webhook] vlm callback from disallowed ip={} path={}", sanitize(clientIp), path.log());
            writeForbidden(response);
            return;
        }
        if (rateLimiter.isLimited(clientIp)) {
            log.warn("[Webhook] vlm rate-limited ip={} path={}", sanitize(clientIp), path.log());
            writeTooManyRequests(response);
            return;
        }

        long contentLength = request.getContentLengthLong();
        // chunked/무 Content-Length(-1) 는 size-cap 을 우회하므로 역직렬화(본문 버퍼링) 전에 조기 거부한다.
        // 벤더(IntelliVIX VLM v2.0.1)는 JSON + Content-Length 로 송신하므로 chunked 거부는 규격상 허용된다.
        if (contentLength < 0) {
            log.warn("[Webhook] vlm missing/chunked Content-Length path={}", path.log());
            rateLimiter.recordFailure(clientIp);
            writeLengthRequired(response);
            return;
        }
        if (contentLength > MAX_VLM_BODY_BYTES) {
            log.warn("[Webhook] vlm body too large (declared) path={} length={}", path.log(), contentLength);
            rateLimiter.recordFailure(clientIp);
            writePayloadTooLarge(response, MAX_VLM_BODY_BYTES);
            return;
        }
        CachedBodyHttpServletRequest cached;
        try {
            cached = new CachedBodyHttpServletRequest(request, MAX_VLM_BODY_BYTES, false);
        } catch (PayloadTooLargeException e) {
            log.warn("[Webhook] vlm body too large (stream) path={}", path.log());
            rateLimiter.recordFailure(clientIp);
            writePayloadTooLarge(response, MAX_VLM_BODY_BYTES);
            return;
        }
        chain.doFilter(cached, response);
        // 전제: 이 경로의 컨트롤러는 동기 반환이라 chain 복귀 시점에 최종 상태가 확정돼 있다.
        //   DeferredResult/CompletableFuture 등 비동기 반환으로 바뀌면 여기서 읽는 상태가
        //   확정 전 값(200)이라 집계가 무력화된다 — 그때는 집계 지점을 컨트롤러/서비스로 옮겨야 한다.
        countDownstreamAuthOutcome(response.getStatus(), path, clientIp);
    }

    /**
     * 무서명 경로의 <b>하류 인증 결과</b>를 rate limit 에 반영한다 (H-3).
     *
     * <ul>
     *   <li>401/403 — 서비스 계층 인증 실패(미발급 request_id 등) → 실패 카운트</li>
     *   <li>그 외(2xx·4xx 검증 오류·5xx) — 인증 실패 판정이 아니므로 카운트하지 않는다</li>
     * </ul>
     *
     * <h3>2xx 로 카운터를 해제하지 않는다 (DEV_FIX N-2)</h3>
     * <p>이 경로는 <b>무서명</b>이라 200 이 "인증 성공" 을 뜻하지 않는다. 이미 처리된
     * {@code request_id} 하나만 알면 {@code VlmResultService} 가 멱등 스킵 후 <b>200</b> 을 돌려주므로,
     * "위조 4회 → 알려진 id 1회(200)" 를 반복하면 카운터가 영원히 0 이 되어 rate limit 이 전면
     * 무력화된다(B-ISSUE-25 의 방어 목표였던 pre-auth 비관적 락 SELECT 무제한 유발이 그대로 열린다).
     * 게다가 reset 키가 IP 단위라 VLM 200 한 건이 {@code /v1/aug/**} 의 HMAC 실패 카운터까지 지웠다.
     * 카운터는 분 단위 창 만료로 자연 소멸시키고, 명시적 reset 은 <b>서명 검증 성공</b>(HMAC 경로)
     * 에서만 수행한다. 이 결정으로 "무인증 요청이 공유 DELETE 를 유발" 하던 문제(N-5)도 함께 사라진다.
     *
     * <h3>집계 비활성 분기를 두지 않는다 (REDESIGN R-1)</h3>
     * <p>공격자가 제어할 수 있는 입력(요청 헤더)을 조건으로 이 집계를 끄면 rate limit 이 헤더 한 줄로
     * 무력화된다. 어떤 런타임 조건에서도 401/403 은 집계한다.
     */
    private void countDownstreamAuthOutcome(int status, PathView path, String clientIp) {
        if (status != HttpServletResponse.SC_UNAUTHORIZED && status != HttpServletResponse.SC_FORBIDDEN) {
            return;
        }
        log.warn("[Webhook] downstream auth rejected ip={} path={} status={}",
                sanitize(clientIp), path.log(), status);
        rateLimiter.recordFailure(clientIp);
        countAuthFailure(path.tag(), "downstream_unauthorized");
    }

    // ─── 헬퍼 ─────────────────────────────────────────────────────

    /**
     * HMAC-SHA256 → lowercase hex.
     *
     * <p>서명 규칙은 {@link HmacSigner} 로 추출되어 서명 측(콜백 시뮬레이터)과 공유된다.
     * 본 필터(검증 측)와 서명 측이 동일 코드를 사용해 서명 불일치 회귀를 구조적으로 차단한다.
     */
    static String hmacSha256Hex(String secret, String message) {
        return HmacSigner.hex(secret, message);
    }

    /**
     * nonce 키 — (<b>정규화</b> 경로, timestamp, 서명) 를 묶은 SHA-256 hex.
     *
     * <p>{@code path} 는 반드시 {@link WebhookProtectedPaths#canonicalPath}(디코딩·정규화 완료) 여야
     * 한다. 원시 URI 를 넣으면 인코딩 변형마다 키가 갈라져 replay 방어가 무력화된다(H-2).
     */
    static String nonceHash(String path, String timestamp, String signatureHex) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(timestamp.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(signatureHex.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    /** nonce 보존 기간 — 타임스탬프 유효창(과거+미래) 전체를 덮는다. */
    private Duration nonceTtl() {
        return Duration.ofSeconds(windowSeconds).plusMillis(FUTURE_SKEW_MS).plusSeconds(60);
    }

    /** 인증 실패 공통 처리 — 401 고정 + 메트릭 카운트 (S-03). */
    private void fail(HttpServletResponse response, PathView path, String reason, String message)
            throws IOException {
        countAuthFailure(path.tag(), reason);
        writeUnauthorized(response, message);
    }

    /**
     * 인증 실패 메트릭 — 태그는 <b>저카디널리티 상수</b>({@code aug}/{@code vlm}/{@code other})만 쓴다.
     *
     * <p>과거에는 원시 URI(최대 200자 자유 문자열)를 {@code path} 태그로 넣어, 공격자가
     * {@code /v1/aug/AAAA0001..9999} 로 <b>영구 보존되는 Meter</b> 를 무한 생성할 수 있었다
     * (MeterRegistry 는 evict 하지 않음 → 힙 증가 + {@code /actuator/prometheus} 응답 폭증, M-2).
     */
    private void countAuthFailure(String pathTag, String reason) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(METRIC_AUTH_FAILED, "path", pathTag, "reason", reason).increment();
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "HMAC");
        writeBody(response, ErrorCode.UNAUTHORIZED, message);
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        writeBody(response, ErrorCode.FORBIDDEN, "허용되지 않은 출처입니다.");
    }

    /**
     * replay(이미 소비된 서명) 응답.
     *
     * <p>메시지에 "재전송 불필요" 를 넣지 않는다 — 하류 실패 시 nonce 를 해제하긴 하지만(M-1), 해제까지
     * 실패한 경계 상황에서 이 문구가 벤더의 정당한 재시도를 <b>중단</b>시키면 결과가 영구 유실된다.
     * 이 응답은 "이 서명은 이미 접수됐다" 는 사실만 알린다.
     */
    private void writeReplayConflict(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        writeBody(response, ErrorCode.CONFLICT, "이미 접수된 시그니처입니다. (중복 콜백 흡수)");
    }

    private void writeServiceUnavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader(HttpHeaders.RETRY_AFTER, "30");
        writeBody(response, ErrorCode.SERVICE_UNAVAILABLE,
                "webhook 가드 저장소를 사용할 수 없습니다. 잠시 후 재시도해 주세요.");
    }

    private void writeLengthRequired(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_LENGTH_REQUIRED); // 411
        writeBody(response, ErrorCode.LENGTH_REQUIRED,
                "Content-Length 헤더가 필요합니다. (chunked 전송은 허용되지 않습니다)");
    }

    private void writePayloadTooLarge(HttpServletResponse response, long limitBytes) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE); // 413
        writeBody(response, ErrorCode.PAYLOAD_TOO_LARGE,
                "Webhook 본문은 " + (limitBytes / 1024) + "KB 를 초과할 수 없습니다.");
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER,
                String.valueOf(WebhookRateLimiter.RATE_LIMIT_BACKOFF_MS / 1000));
        writeBody(response, ErrorCode.TOO_MANY_REQUESTS,
                "Webhook 인증 실패가 누적되어 일시 차단되었습니다.");
    }

    private void writeBody(HttpServletResponse response, ErrorCode errorCode, String message)
            throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(errorCode, message)));
    }

    /** 로그 안전 문자열 (CWE-117). */
    private String sanitize(String value) {
        return WebhookProtectedPaths.sanitize(value);
    }

    /** 본문 캐싱 중 크기 초과 시그널. */
    static class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(String msg) {
            super(msg);
        }
    }

    /**
     * 본문 캐싱 wrapper — HMAC 계산 후 컨트롤러에서 본문을 다시 읽을 수 있도록 한다.
     *
     * <p>동시에 <b>"필터를 통과했다"는 증거</b>({@link WebhookGuardedRequest}) 역할을 한다. 이 래퍼가
     * 없는 요청은 {@code WebhookGateInterceptor} 가 컨트롤러 진입 직전 401 로 거부한다(S-18).
     *
     * <p>본문은 메모리에 보관하므로 큰 페이로드 차단을 위해 상한을 두고 초과 시
     * {@link PayloadTooLargeException} 을 던진다.
     */
    static class CachedBodyHttpServletRequest extends jakarta.servlet.http.HttpServletRequestWrapper
            implements WebhookGuardedRequest {

        private final byte[] cachedBody;
        private final boolean signatureVerified;

        CachedBodyHttpServletRequest(HttpServletRequest request, long maxBytes, boolean signatureVerified)
                throws IOException {
            super(request);
            // bounded read: 선언된 Content-Length 와 무관하게 실제 스트림도 maxBytes 에서 끊는다.
            // 전량 버퍼링 후 사후검사는 GB 규모 스트림에서 OOM 위험이 있어 상한 초과 즉시 읽기를 중단한다.
            this.cachedBody = readCapped(request.getInputStream(), maxBytes);
            this.signatureVerified = signatureVerified;
        }

        @Override
        public boolean isSignatureVerified() {
            return signatureVerified;
        }

        /**
         * 스트림을 최대 {@code maxBytes} 까지만 읽고, 초과 즉시 중단해 {@link PayloadTooLargeException} 을 던진다.
         * 전량 버퍼링을 하지 않으므로 위조된 Content-Length 나 chunked 대용량 본문에서도 힙 사용이 상한에 묶인다.
         */
        private static byte[] readCapped(java.io.InputStream in, long maxBytes) throws IOException {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new PayloadTooLargeException(
                            "Webhook 본문이 한도(" + maxBytes + " 바이트)를 초과합니다.");
                }
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        }

        byte[] getCachedBody() {
            return cachedBody;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(cachedBody);
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    static class CachedBodyServletInputStream extends jakarta.servlet.ServletInputStream {
        private final java.io.ByteArrayInputStream buffer;

        CachedBodyServletInputStream(byte[] data) {
            this.buffer = new java.io.ByteArrayInputStream(data);
        }

        @Override
        public boolean isFinished() {
            return buffer.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read() {
            return buffer.read();
        }
    }
}
