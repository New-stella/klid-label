package kr.co.cudo.authoring.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Webhook HMAC 시그니처 검증 필터 — Phase 2 신설, Phase 2 보강 (DEV_FIX H-4/M-1) 강화.
 *
 * <p>외부 시스템(Deidentify SW / 외부 VLM 서비스 / 외부 증강 시스템) 의 결과 인계
 * 콜백을 인증한다. JWT 와 분리된 별도 인증 경로로, /v1/[deidentify|vlm|augments]/result 경로에만 적용된다.
 *
 * <h3>인증 헤더</h3>
 * <ul>
 *   <li>{@code X-Signature: hmac-sha256=<hex>} — HMAC-SHA256(secret, "{timestamp}.{body}") 의 hex</li>
 *   <li>{@code X-Timestamp: <unix-ms>} — replay 방지용. 현재 시각 ±5분 윈도우 (설정 가능)</li>
 * </ul>
 *
 * <h3>경로별 시크릿</h3>
 * <ul>
 *   <li>{@code /v1/deidentify/result} -- {@code webhook.hmac.secret.deidentify}</li>
 *   <li>{@code /v1/vlm/result} -- {@code webhook.hmac.secret.vlm}</li>
 *   <li>{@code /v1/augments/result} -- {@code webhook.hmac.secret.augment}</li>
 * </ul>
 *
 * <h3>보안 가드</h3>
 * <ul>
 *   <li><b>fail-closed</b>: 시크릿이 미설정(빈 문자열)이면 해당 경로 401 -- 빈 시크릿으로 검증
 *       통과되는 회피 차단</li>
 *   <li><b>시크릿 강도(M-1)</b>: 설정된 시크릿은 256bit(32B) 이상이어야 함. 부족 시 부팅 차단.</li>
 *   <li><b>상수시간 비교</b>: MessageDigest.isEqual 로 timing attack 차단 (CWE-208)</li>
 *   <li><b>replay 방지</b>: timestamp 윈도우 + signature 입력에 timestamp 포함</li>
 *   <li><b>Log Injection</b>: 헤더값/경로 sanitize 후 로깅 (CWE-117)</li>
 *   <li><b>본문 크기 제한(H-4)</b>: Content-Length 누락 401, 1MB 초과 413</li>
 *   <li><b>RateLimit(H-4)</b>: HMAC 검증 실패 IP 분당 5회 초과 시 60초 backoff (CWE-307 brute force 차단)</li>
 * </ul>
 */
@Slf4j
@Component
public class HmacWebhookFilter extends OncePerRequestFilter {

    public static final String SIGNATURE_HEADER = "X-Signature";
    public static final String TIMESTAMP_HEADER = "X-Timestamp";
    public static final String SIGNATURE_PREFIX = "hmac-sha256=";
    public static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 최대 webhook 본문 크기 — DEV_FIX H-4. 1MB. */
    public static final long MAX_WEBHOOK_BODY_BYTES = 1024L * 1024L;

    /** 시크릿 최소 길이 — DEV_FIX M-1. HS256 권장 256bit = 32B. */
    public static final int MIN_SECRET_BYTES = 32;

    /** Rate limit: 분당 실패 횟수 임계. */
    static final int RATE_LIMIT_FAILURES_PER_MINUTE = 5;
    /** Rate limit: backoff 윈도우 (ms). */
    static final long RATE_LIMIT_BACKOFF_MS = 60_000L;
    /** Rate limit: failureTrackers 메모리 hard cap — DEV_FIX 2차 (CWE-770 resource exhaustion). */
    public static final int MAX_FAILURE_TRACKERS = 4096;

    static final String PATH_DEIDENTIFY = "/v1/deidentify/result";
    static final String PATH_VLM = "/v1/vlm/result";
    static final String PATH_AUGMENT = "/v1/augments/result";

    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

    private final ObjectMapper objectMapper;
    private final Map<String, String> pathToSecret;
    private final long windowSeconds;

    /**
     * 실패 추적기 — IP 단위 분당 카운터. CWE-307 brute force 차단.
     * 작은 메모리 상한을 위해 ConcurrentHashMap + 윈도우 단위 reset.
     */
    private final Map<String, FailureTracker> failureTrackers = new ConcurrentHashMap<>();

    public HmacWebhookFilter(ObjectMapper objectMapper,
                             @Value("${webhook.hmac.secret.deidentify:}") String secretDeidentify,
                             @Value("${webhook.hmac.secret.vlm:}") String secretVlm,
                             @Value("${webhook.hmac.secret.augment:}") String secretAugment,
                             @Value("${webhook.hmac.timestamp-window-seconds:300}") long windowSeconds) {
        this.objectMapper = objectMapper;
        // DEV_FIX M-1: 시크릿이 설정되었는데 32B 미만이면 부팅 차단 (빈 시크릿은 fail-closed 그대로)
        ensureMinSecretStrength("webhook.hmac.secret.deidentify", secretDeidentify);
        ensureMinSecretStrength("webhook.hmac.secret.vlm", secretVlm);
        ensureMinSecretStrength("webhook.hmac.secret.augment", secretAugment);
        this.pathToSecret = Map.of(
                PATH_DEIDENTIFY, secretDeidentify == null ? "" : secretDeidentify,
                PATH_VLM, secretVlm == null ? "" : secretVlm,
                PATH_AUGMENT, secretAugment == null ? "" : secretAugment
        );
        this.windowSeconds = Math.max(60L, windowSeconds);
    }

    private static void ensureMinSecretStrength(String key, String secret) {
        if (secret == null || secret.isEmpty()) return; // 빈 시크릿은 fail-closed 정책으로 별도 처리
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new BeanInitializationException(
                    key + " 의 시크릿 길이가 " + bytes + "B 입니다. 최소 " + MIN_SECRET_BYTES
                            + "B(256bit) 이상이어야 합니다. (DEV_FIX M-1)");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = stripContext(request);
        return !pathToSecret.containsKey(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = stripContext(request);
        String secret = pathToSecret.get(path);
        if (secret == null || secret.isBlank()) {
            // fail-closed: 시크릿 미설정 시 webhook 자체 차단
            log.warn("[Webhook] secret missing path={}", safe(path));
            writeUnauthorized(response, "Webhook 시크릿이 설정되지 않았습니다.");
            return;
        }

        // DEV_FIX H-4: rate limit — 실패 누적 IP 는 backoff
        String clientIp = clientIp(request);
        if (isRateLimited(clientIp)) {
            log.warn("[Webhook] rate-limited ip={} path={}", safe(clientIp), safe(path));
            writeTooManyRequests(response);
            return;
        }

        // DEV_FIX H-4: 본문 크기 가드 (Content-Length 누락 401 / 초과 413)
        long contentLength = request.getContentLengthLong();
        if (contentLength < 0) {
            log.warn("[Webhook] missing Content-Length path={}", safe(path));
            writeUnauthorized(response, "Content-Length 헤더가 필요합니다.");
            return;
        }
        if (contentLength > MAX_WEBHOOK_BODY_BYTES) {
            log.warn("[Webhook] body too large path={} length={}", safe(path), contentLength);
            writePayloadTooLarge(response);
            return;
        }

        String signature = request.getHeader(SIGNATURE_HEADER);
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        if (signature == null || signature.isBlank()
                || timestamp == null || timestamp.isBlank()) {
            log.warn("[Webhook] missing signature/timestamp path={}", safe(path));
            recordFailure(clientIp);
            writeUnauthorized(response, "HMAC 시그니처 헤더가 필요합니다.");
            return;
        }
        if (!signature.startsWith(SIGNATURE_PREFIX)) {
            log.warn("[Webhook] invalid signature format path={}", safe(path));
            recordFailure(clientIp);
            writeUnauthorized(response, "지원하지 않는 시그니처 알고리즘입니다.");
            return;
        }

        long tsMs;
        try {
            tsMs = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            log.warn("[Webhook] timestamp not numeric path={}", safe(path));
            recordFailure(clientIp);
            writeUnauthorized(response, "X-Timestamp 헤더가 유효하지 않습니다.");
            return;
        }
        long nowMs = System.currentTimeMillis();
        long diffMs = Math.abs(nowMs - tsMs);
        if (diffMs > windowSeconds * 1000L) {
            log.warn("[Webhook] timestamp window exceeded path={} diffMs={}", safe(path), diffMs);
            recordFailure(clientIp);
            writeUnauthorized(response, "시그니처 timestamp 가 허용 시간 윈도우를 벗어났습니다.");
            return;
        }

        // 본문 캐싱 — 컨트롤러에서 다시 읽을 수 있도록 wrapper 사용. 본문도 크기 캡 적용.
        CachedBodyHttpServletRequest cached;
        try {
            cached = new CachedBodyHttpServletRequest(request);
        } catch (PayloadTooLargeException e) {
            log.warn("[Webhook] body too large (stream) path={}", safe(path));
            writePayloadTooLarge(response);
            return;
        }
        byte[] body = cached.getCachedBody();

        String providedHex = signature.substring(SIGNATURE_PREFIX.length()).trim();
        String canonical = timestamp + "." + new String(body, StandardCharsets.UTF_8);
        String computedHex;
        try {
            computedHex = hmacSha256Hex(secret, canonical);
        } catch (Exception e) {
            log.error("[Webhook] hmac compute failed path={}", safe(path), e);
            writeUnauthorized(response, "시그니처 검증 중 오류가 발생했습니다.");
            return;
        }

        // 상수시간 비교 (CWE-208 Observable Timing Discrepancy)
        byte[] providedBytes = providedHex.toLowerCase().getBytes(StandardCharsets.UTF_8);
        byte[] computedBytes = computedHex.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(providedBytes, computedBytes)) {
            log.warn("[Webhook] signature mismatch path={}", safe(path));
            recordFailure(clientIp);
            writeUnauthorized(response, "시그니처가 일치하지 않습니다.");
            return;
        }

        // 성공 시 누적 실패 카운터 리셋 (옵션)
        failureTrackers.remove(clientIp);
        chain.doFilter(cached, response);
    }

    /** HMAC-SHA256 → lowercase hex. */
    static String hmacSha256Hex(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(raw);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "HMAC");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(ApiResponse.error(ErrorCode.UNAUTHORIZED, message)));
    }

    private void writePayloadTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE); // 413
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(ApiResponse.error(
                        ErrorCode.PAYLOAD_TOO_LARGE,
                        "Webhook 본문은 " + (MAX_WEBHOOK_BODY_BYTES / 1024) + "KB 를 초과할 수 없습니다.")));
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(RATE_LIMIT_BACKOFF_MS / 1000));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(ApiResponse.error(
                        ErrorCode.TOO_MANY_REQUESTS,
                        "Webhook 인증 실패가 누적되어 일시 차단되었습니다.")));
    }

    private String stripContext(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (uri == null) return "";
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }

    private String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }

    private String clientIp(HttpServletRequest request) {
        // 운영에서는 LB X-Forwarded-For 사용을 권장하나, 본 가드는 위변조 방지 목적이 아니므로 remoteAddr 만 사용.
        String addr = request.getRemoteAddr();
        return addr == null ? "unknown" : addr;
    }

    private boolean isRateLimited(String ip) {
        FailureTracker tracker = failureTrackers.get(ip);
        if (tracker == null) return false;
        long now = System.currentTimeMillis();
        if (now - tracker.windowStartMs > RATE_LIMIT_BACKOFF_MS) {
            // 윈도우 만료 — 카운터 리셋
            failureTrackers.remove(ip);
            return false;
        }
        return tracker.failures.get() >= RATE_LIMIT_FAILURES_PER_MINUTE;
    }

    private void recordFailure(String ip) {
        long now = System.currentTimeMillis();
        failureTrackers.compute(ip, (k, existing) -> {
            if (existing == null || now - existing.windowStartMs > RATE_LIMIT_BACKOFF_MS) {
                FailureTracker t = new FailureTracker(now);
                t.failures.incrementAndGet();
                return t;
            }
            existing.failures.incrementAndGet();
            return existing;
        });
        // 메모리 hard cap — DEV_FIX 2차 (CWE-770): 윈도우 내 다수 IP 로 실패 폭주 시 무한 증가 차단
        if (failureTrackers.size() > MAX_FAILURE_TRACKERS) {
            // 1차: 윈도우 만료 항목 우선 제거
            failureTrackers.entrySet().removeIf(e -> now - e.getValue().windowStartMs > RATE_LIMIT_BACKOFF_MS);
            // 2차: 그래도 cap 초과 시 가장 오래된 항목부터 강제 evict
            if (failureTrackers.size() > MAX_FAILURE_TRACKERS) {
                int over = failureTrackers.size() - MAX_FAILURE_TRACKERS;
                failureTrackers.entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByValue(
                                java.util.Comparator.comparingLong(t -> t.windowStartMs)))
                        .limit(over)
                        .map(java.util.Map.Entry::getKey)
                        .toList()
                        .forEach(failureTrackers::remove);
            }
        }
    }

    /** IP 단위 실패 추적기. */
    private static final class FailureTracker {
        final long windowStartMs;
        final AtomicInteger failures = new AtomicInteger(0);
        FailureTracker(long windowStartMs) { this.windowStartMs = windowStartMs; }
    }

    /** 본문 캐싱 중 크기 초과 시그널. */
    static class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(String msg) { super(msg); }
    }

    /**
     * 본문 캐싱 wrapper — HMAC 계산 후 컨트롤러에서 본문을 다시 읽을 수 있도록 한다.
     * 본문은 메모리에 보관하므로 큰 페이로드 차단(S-3, H-4)을 위해 {@link #MAX_WEBHOOK_BODY_BYTES} 상한을 두고
     * 초과 시 PayloadTooLargeException 을 던진다.
     */
    static class CachedBodyHttpServletRequest extends jakarta.servlet.http.HttpServletRequestWrapper {
        private final byte[] cachedBody;

        CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            byte[] read = StreamUtils.copyToByteArray(request.getInputStream());
            if (read.length > MAX_WEBHOOK_BODY_BYTES) {
                throw new PayloadTooLargeException("Webhook 본문이 " + read.length + " 바이트로 한도를 초과합니다.");
            }
            this.cachedBody = read;
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
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    static class CachedBodyServletInputStream extends jakarta.servlet.ServletInputStream {
        private final java.io.ByteArrayInputStream buffer;

        CachedBodyServletInputStream(byte[] data) {
            this.buffer = new java.io.ByteArrayInputStream(data);
        }

        @Override
        public boolean isFinished() { return buffer.available() == 0; }

        @Override
        public boolean isReady() { return true; }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read() { return buffer.read(); }
    }
}
