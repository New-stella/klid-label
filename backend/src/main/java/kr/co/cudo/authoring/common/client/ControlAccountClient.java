package kr.co.cudo.authoring.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 관제지원 계정 서비스의 <b>세션 창구</b>(갱신·로그아웃)를 부르는 서버 간 클라이언트.
 *
 * <p>관제 채널 브라우저는 관제를 직접 부르지 않고 저작도구 API 를 부르며, 저작도구 서버가 이 클라이언트로
 * 관제 창구에 중계한다(2026-09-10 사용자 확정 — 「관제 호출은 우리 WAS 를 통해」).
 *
 * <h3>계약 (246 실측)</h3>
 * <ul>
 *   <li>갱신 — {@code POST {관제}/api/account/auth/refresh} · 헤더 {@code x-access-token} 에
 *       <b>refresh</b> 토큰 · 바디 없음 → {@code 200 {error:0, data:{session_token, refresh_token}}}</li>
 *   <li>로그아웃 — {@code POST {관제}/api/account/auth/logout} · 같은 헤더에 <b>access</b> 토큰 · 바디 없음</li>
 * </ul>
 * 창구마다 담는 토큰이 반대다 — 섞지 않는다.
 *
 * <h3>갱신 결과 분류 — 세 갈래뿐이다</h3>
 * <ul>
 *   <li><b>성공</b> — HTTP 200 이고 {@code error} 가 0 이고 {@code data.session_token} 이 문자열.</li>
 *   <li><b>거절</b> — 관제 HTTP 401, 또는 응답의 {@code error} 가 0 이 아니다(예: 900205 서버 세션 정리).</li>
 *   <li><b>일시 장애</b> — 연결 실패·타임아웃·5xx·응답 형식 불일치·서킷 오픈·주소 미설정.</li>
 * </ul>
 * 브라우저의 결말(즉시 로그아웃 / 현재 토큰 유지)이 이 구분에 달려 있으므로 두 실패를 섞지 않는다.
 * 「거절」은 <b>오류가 아니라 결과값</b>으로 흘려 서킷 집계에서 빠진다 — 관제가 멀쩡히 판정한 결과가
 * 서킷을 열면 정상 사용자의 연장까지 막힌다.
 *
 * <h3>주소 — 관제 계정 창구 설정만 (2026-09-14 확정)</h3>
 * <p>호출 대상은 {@code authoring.control-account.url} 하나다(빈 {@code controlAccountWebClient}).
 * 관제 통지 수신처 주소로 <b>폴백하지 않는다</b> — 관제는 계정 창구와 데이터셋 창구를 서로 다른 WAS 에 둔다.
 * 주소가 비었거나 형식이 틀리면 전송 가드가 관제를 부르지 않고 {@link NonRetryableExternalException} 을 내며,
 * 이 클라이언트는 그것을 <b>일시 장애</b>(갱신) / <b>미확인</b>(로그아웃)으로 끝낸다. 그 예외는 두 서킷의
 * {@code ignore-exceptions} 에 등록돼 <b>서킷 실패로 세지 않는다</b>(결정적 설정 상태이지 관제 장애가 아니다).
 *
 * <h3>서킷 — 갱신·로그아웃이 따로다 (2026-09-14 확정)</h3>
 * <p>갱신은 {@code controlAccount}, 로그아웃은 {@code controlAccountLogout} 인스턴스를 쓴다. 한 서킷을
 * 나눠 쓰면 갱신의 누적 일시 장애가 서킷을 열어 <b>로그아웃 중계까지 막혀 관제 서버 세션이 닫히지 않는다</b>
 * (현장 실사고). 두 서킷 모두 관제 통지 서킷과도 별개다.
 *
 * <h3>★ 자동 재시도 없음</h3>
 * <p>갱신은 refresh 토큰을 <b>교체</b>하는 비멱등 호출이다. 같은 요청을 다시 보내면 이중 교체가 되어 한쪽이
 * 무효 토큰을 쥐고 남는다. Resilience4j Retry 를 걸지 않고, 전송 계층 재전송도 빈에서 끈다
 * ({@code WebClientConfig#controlAccountWebClient}).
 *
 * <h3>보안</h3>
 * <p>토큰 원문·관제 응답 본문·주소를 <b>로그에 싣지 않는다</b>(CWE-532/209) — 상태코드·관제 오류 번호·
 * 실패 분류·예외 클래스명만 남긴다. 토큰은 저장하지 않는다.
 *
 * @design INT-015
 * @design API-247
 * @design API-246
 */
@Slf4j
@Component
public class ControlAccountClient {

    /** 갱신 창구 경로 (INT-015). */
    public static final String REFRESH_PATH = "/api/account/auth/refresh";

    /** 로그아웃 창구 경로 (INT-015). */
    public static final String LOGOUT_PATH = "/api/account/auth/logout";

    /** 관제 계정 창구 인증 헤더명 — 갱신은 refresh, 로그아웃은 access 토큰을 싣는다. */
    public static final String ACCESS_TOKEN_HEADER = "x-access-token";

    /** 0 이하·잘못된 설정값이면 쓰는 기본 타임아웃(ms). */
    static final long DEFAULT_TIMEOUT_MS = 3_000L;

    /** {@code block()} 여유 — 호출 타임아웃이 먼저 터지게 한다. */
    private static final Duration BLOCK_MARGIN = Duration.ofSeconds(2);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 분류 문자열 — 전송 가드가 주소 미설정·부적합으로 전송을 막았다(주소·설정키 원문은 싣지 않는다). */
    static final String CAUSE_ADDRESS_NOT_CONFIGURED = "address-not-configured";

    private final WebClient webClient;
    private final CircuitBreaker refreshCircuitBreaker;
    private final CircuitBreaker logoutCircuitBreaker;
    private final Duration refreshTimeout;
    private final Duration logoutTimeout;

    public ControlAccountClient(@Qualifier("controlAccountWebClient") WebClient webClient,
                                @Qualifier("controlAccountCircuitBreaker") CircuitBreaker refreshCircuitBreaker,
                                @Qualifier("controlAccountLogoutCircuitBreaker") CircuitBreaker logoutCircuitBreaker,
                                @Value("${authoring.control-account.refresh-timeout-ms:3000}") long refreshTimeoutMs,
                                @Value("${authoring.control-account.logout-timeout-ms:3000}") long logoutTimeoutMs) {
        this.webClient = webClient;
        this.refreshCircuitBreaker = refreshCircuitBreaker;
        this.logoutCircuitBreaker = logoutCircuitBreaker;
        this.refreshTimeout = Duration.ofMillis(positiveOrDefault(refreshTimeoutMs));
        this.logoutTimeout = Duration.ofMillis(positiveOrDefault(logoutTimeoutMs));
    }

    private static long positiveOrDefault(long ms) {
        // 0 이하 타임아웃은 「즉시 실패」가 되어 연장 기능 전체가 조용히 죽는다 — 기본값으로 되돌린다.
        return ms > 0 ? ms : DEFAULT_TIMEOUT_MS;
    }

    /**
     * 관제 세션을 갱신한다. <b>예외를 던지지 않는다</b> — 결과는 항상 세 갈래 중 하나다.
     *
     * @param refreshToken 관제 refresh 토큰 원문(호출자가 공백 아님을 보장한다)
     */
    public RefreshResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            // 호출자(요청 검증)가 막는 입력이다. 여기 닿으면 관제를 부르지 않는다 — 부를 자격증명이 없다.
            return RefreshResult.rejected();
        }
        try {
            RefreshResult result = webClient.post()
                    .uri(REFRESH_PATH)
                    .header(ACCESS_TOKEN_HEADER, refreshToken)
                    .exchangeToMono(response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> classifyRefresh(response.statusCode().value(), body)))
                    .timeout(refreshTimeout)
                    .transformDeferred(CircuitBreakerOperator.of(refreshCircuitBreaker))
                    .block(refreshTimeout.plus(BLOCK_MARGIN));
            if (result == null) {
                log.warn("[ControlAccount] refresh unavailable cause=empty-result");
                return RefreshResult.unavailable();
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("[ControlAccount] refresh unavailable cause={}", describe(e));
            return RefreshResult.unavailable();
        }
    }

    /**
     * 관제 세션을 닫는다. <b>예외를 던지지 않는다</b> — 브라우저 결말이 관제 응답과 무관하기 때문이다.
     *
     * @param accessToken 관제 access 토큰 원문
     * @return 관제가 로그아웃을 확인했는가(HTTP 2xx + {@code error} 0). 실패는 WARN 으로만 남는다
     */
    public boolean logout(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return false;
        }
        try {
            Boolean confirmed = webClient.post()
                    .uri(LOGOUT_PATH)
                    .header(ACCESS_TOKEN_HEADER, accessToken)
                    .exchangeToMono(response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> classifyLogout(response.statusCode().value(), body)))
                    .timeout(logoutTimeout)
                    // ★ 로그아웃 전용 서킷 — 갱신 서킷이 열려도 이 호출은 나간다.
                    .transformDeferred(CircuitBreakerOperator.of(logoutCircuitBreaker))
                    .block(logoutTimeout.plus(BLOCK_MARGIN));
            return Boolean.TRUE.equals(confirmed);
        } catch (RuntimeException e) {
            log.warn("[ControlAccount] logout relay failed cause={}", describe(e));
            return false;
        }
    }

    /**
     * 갱신 응답 분류 — 「거절」은 값으로, 「일시 장애」는 오류로 흘린다(오류만 서킷에 집계된다).
     */
    static Mono<RefreshResult> classifyRefresh(int status, String body) {
        if (status == 401) {
            log.info("[ControlAccount] refresh rejected status=401 error={}", errorCodeOf(parse(body)));
            return Mono.just(RefreshResult.rejected());
        }
        if (status >= 500) {
            return Mono.error(new UpstreamUnavailableException("upstream-" + status));
        }
        JsonNode root = parse(body);
        if (root == null || !root.isObject()) {
            return Mono.error(new UpstreamUnavailableException("malformed-body status=" + status));
        }
        JsonNode error = root.get("error");
        if (error == null || !error.isIntegralNumber()) {
            return Mono.error(new UpstreamUnavailableException("missing-error-code status=" + status));
        }
        if (error.asLong() != 0L) {
            log.info("[ControlAccount] refresh rejected status={} error={}", status, error.asLong());
            return Mono.just(RefreshResult.rejected());
        }
        if (status != 200) {
            return Mono.error(new UpstreamUnavailableException("unexpected-status " + status));
        }
        JsonNode data = root.get("data");
        JsonNode session = data == null ? null : data.get("session_token");
        if (session == null || !session.isTextual() || session.asText().isBlank()) {
            return Mono.error(new UpstreamUnavailableException("missing-session-token"));
        }
        JsonNode refresh = data.get("refresh_token");
        String newRefresh = refresh != null && refresh.isTextual() && !refresh.asText().isBlank()
                ? refresh.asText() : null;
        if (newRefresh == null) {
            // 계약상 성공 판정은 session_token 하나다 — 막지는 않되 드러낸다(다음 갱신이 불가능해진다).
            log.warn("[ControlAccount] refresh succeeded without a new refresh token");
        }
        return Mono.just(RefreshResult.success(session.asText(), newRefresh));
    }

    /** 로그아웃 응답 분류 — 5xx 만 오류(서킷 집계), 나머지는 확인 여부 값이다. */
    static Mono<Boolean> classifyLogout(int status, String body) {
        if (status >= 500) {
            return Mono.error(new UpstreamUnavailableException("upstream-" + status));
        }
        JsonNode root = parse(body);
        Long errorCode = errorCodeOf(root);
        boolean confirmed = status >= 200 && status < 300 && errorCode != null && errorCode == 0L;
        if (!confirmed) {
            log.warn("[ControlAccount] logout not confirmed status={} error={}", status, errorCode);
        }
        return Mono.just(confirmed);
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static Long errorCodeOf(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode error = root.get("error");
        return error != null && error.isIntegralNumber() ? error.asLong() : null;
    }

    /**
     * 실패 분류 문자열 — 예외 <b>메시지를 싣지 않는다</b>(연결 예외 메시지에는 주소가 실린다, CWE-209).
     * 우리가 만든 분류 예외만 그 사유를 쓴다.
     *
     * <p>{@link NonRetryableExternalException} 은 이 클라이언트 경로에서 <b>전송 가드만</b> 낸다(응답 분류는
     * {@link UpstreamUnavailableException} 만 쓴다) — 주소 미설정·부적합이라 관제를 부르지 않았다는 뜻이다.
     */
    private static String describe(Throwable e) {
        Throwable t = Exceptions.unwrap(e);
        if (t instanceof UpstreamUnavailableException u) {
            return u.getMessage();
        }
        if (t instanceof NonRetryableExternalException) {
            return CAUSE_ADDRESS_NOT_CONFIGURED;
        }
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        return t.getClass().getSimpleName()
                + (cause != t ? "/" + cause.getClass().getSimpleName() : "");
    }

    /** 관제가 「일시 장애」로 분류된 응답을 줬다 — 서킷 failure 로 집계되는 유일한 우리 쪽 예외. */
    static final class UpstreamUnavailableException extends RuntimeException {
        UpstreamUnavailableException(String reason) {
            super(reason, null, false, false);
        }
    }

    /**
     * 갱신 결과. {@code toString} 은 토큰을 싣지 않는다(로그·디버거 유출 방지, CWE-532).
     *
     * @param outcome      세 갈래 중 하나
     * @param sessionToken 성공 시 새 access 토큰 원문(그 외 {@code null})
     * @param refreshToken 성공 시 새 refresh 토큰 원문(없으면 {@code null})
     */
    public record RefreshResult(Outcome outcome, String sessionToken, String refreshToken) {

        public enum Outcome { SUCCESS, REJECTED, UNAVAILABLE }

        static RefreshResult success(String sessionToken, String refreshToken) {
            return new RefreshResult(Outcome.SUCCESS, sessionToken, refreshToken);
        }

        static RefreshResult rejected() {
            return new RefreshResult(Outcome.REJECTED, null, null);
        }

        static RefreshResult unavailable() {
            return new RefreshResult(Outcome.UNAVAILABLE, null, null);
        }

        @Override
        public String toString() {
            return "RefreshResult[outcome=" + outcome + "]";
        }
    }
}
