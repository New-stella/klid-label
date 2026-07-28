package kr.co.cudo.authoring.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import kr.co.cudo.authoring.common.security.HmacWebhookFilter;
import kr.co.cudo.authoring.common.security.webhook.ClientIpResolver;
import kr.co.cudo.authoring.common.security.webhook.GenAiWebhookIpAllowlist;
import kr.co.cudo.authoring.common.security.webhook.WebhookGuardUnavailableException;
import kr.co.cudo.authoring.common.security.webhook.WebhookGuardedRequest;
import kr.co.cudo.authoring.common.security.webhook.WebhookIpAllowlist;
import kr.co.cudo.authoring.common.security.webhook.WebhookNonceStore;
import kr.co.cudo.authoring.common.security.webhook.WebhookProtectedPaths;
import kr.co.cudo.authoring.common.security.webhook.WebhookRateLimitStore;
import kr.co.cudo.authoring.common.security.webhook.WebhookRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * HmacWebhookFilter 단위 테스트 — 서명 검증 게이트 + 무서명 경로의 부수 방어.
 *
 * <p><b>현재 구조(Phase 7 기준)</b>: 서명 <b>필수</b> 경로 집합은 비어 있다 — 생성형 AI 콜백
 * ({@code /v1/genai/callback})과 VLM 콜백({@code /v1/vlm/callback}) 모두 벤더 계약이 무서명이며
 * (VLM v2.0.1 은 2026-07-07 승인), 생성형 AI 는 {@code request_id} 발급 게이트
 * ({@code LS_DATA_AUG_JOB.IDMP_KEY})가 최종 방어선이다. 따라서 본 클래스의 서명 검증 경로 단정은
 * 필수 경로 대신 <b>fail-closed 분기</b>(경로 판정 실패·서명 헤더가 붙은 요청 등)로 검증한다.
 * 무서명 경로에도 size cap · rate limit · (설정 시) IP allowlist 는 그대로 적용돼야 한다.
 *
 * <p><b>위양성 주의 (S-21)</b>: 본 클래스는 {@code MockHttpServletRequest} 기반이라 "필터가 통째로
 * 스킵돼도 GREEN" 이 되기 쉽다. 그래서 (a) 보호 대상 경로에서 <b>반드시 401 이어야 한다</b>는 negative
 * 단정과 (b) {@code shouldNotFilter} 직접 단정을 함께 둔다. 경로 우회의 <b>최종 판정</b>은 실 Security
 * 체인을 쓰는 {@code WebhookPathBypassSecurityIT} 가 담당한다(단위테스트로 갈음 금지).
 */
class HmacWebhookFilterTest {

    private static final String SECRET_AUGMENT = "augment-secret-32bytes-min-len-bbb!";

    private ObjectMapper objectMapper;
    private HmacWebhookFilter filter;
    private RecordingNonceStore nonceStore;
    private WebhookRateLimiter rateLimiter;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        nonceStore = new RecordingNonceStore();
        rateLimiter = new WebhookRateLimiter(null);
        meterRegistry = new SimpleMeterRegistry();
        filter = newFilter(SECRET_AUGMENT, "", "");
    }

    /** 프로덕션 배선과 동일한 협력자 구성으로 필터를 만든다(테스트 전용 우회 생성자 없음). */
    private HmacWebhookFilter newFilter(String secret, String trustedProxyCidrs, String vlmAllowlist) {
        return newFilter(secret, trustedProxyCidrs, vlmAllowlist, new MockEnvironment());
    }

    private HmacWebhookFilter newFilter(String secret, String trustedProxyCidrs, String vlmAllowlist,
                                        MockEnvironment environment) {
        return newFilter(secret, trustedProxyCidrs, vlmAllowlist, environment, rateLimiter);
    }

    /** 생성형 AI allowlist 를 좁혀 주입한다 — 무서명 genai 가드 테스트용. */
    private HmacWebhookFilter newFilterWithGenAiAllowlist(String genAiAllowlist) {
        MockEnvironment environment = new MockEnvironment();
        return new HmacWebhookFilter(
                objectMapper,
                nonceStore,
                rateLimiter,
                new ClientIpResolver("", environment),
                new WebhookIpAllowlist("", environment),
                new GenAiWebhookIpAllowlist(genAiAllowlist),
                meterRegistry,
                environment,
                SECRET_AUGMENT,
                300L);
    }

    /** 노드별 rate limiter 를 주입한다 — 2노드 공유 집계 회귀 테스트(N-1)용. */
    private HmacWebhookFilter newFilter(String secret, String trustedProxyCidrs, String vlmAllowlist,
                                        MockEnvironment environment, WebhookRateLimiter limiter) {
        return new HmacWebhookFilter(
                objectMapper,
                nonceStore,
                limiter,
                new ClientIpResolver(trustedProxyCidrs, environment),
                new WebhookIpAllowlist(vlmAllowlist, environment),
                new GenAiWebhookIpAllowlist("0.0.0.0/0"),
                meterRegistry,
                environment,
                secret,
                300L);
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        return e;
    }

    // ─── 경로 판정 (E-ISSUE-01 / A-ISSUE-13) ─────────────────────

    @Test
    @DisplayName("보호대상_경로에서_서명이_없으면_401_이며_필터가_스킵되지_않는다")
    void protectedPath_withoutSignature_returns401_andIsNotSkipped() throws Exception {
        MockHttpServletRequest req = hmacRequest("{\"x\":1}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // negative 단정 — 필터가 스킵되면(위양성) 여기서 실패한다.
        assertThat(shouldNotFilter(req))
                .as("보호 대상 경로는 필터가 반드시 적용돼야 한다")
                .isFalse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).isEqualTo("HMAC");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("경로_판정중_예외가_발생하면_필터가_적용됨_fail_closed")
    void pathResolutionFailure_appliesFilter() {
        // 경로 리터럴은 무의미하다 — getRequestURI 가 경로 매칭 <전에> 터지기 때문이다.
        // 현행 콜백 경로를 실어 오해를 없앤다(구 /v1/aug/callback 리터럴 잔존 정리).
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/genai/callback") {
            @Override
            public String getRequestURI() {
                throw new IllegalStateException("URI 파싱 실패 시뮬");
            }
        };

        assertThat(shouldNotFilter(req))
                .as("판정 불가 = 보호(필터 적용) 로 고정돼야 한다 (S-13)")
                .isFalse();
    }

    @Test
    @DisplayName("HmacWebhookFilter_관련_없는_경로는_통과(shouldNotFilter)")
    void unrelatedPath_skipsFilter() throws Exception {
        MockHttpServletRequest req = postRequest("/v1/manage/labels", "{}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        assertThat(shouldNotFilter(req)).isTrue();

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    // ─── 기본 HMAC 검증 ─────────────────────────────────────────

    @Test
    @DisplayName("POST_v1_aug_callback_HMAC_시그니처_틀리면_401")
    void augmentInvalidSignature_returns401() throws Exception {
        String body = "{\"idempotencyKey\":\"K1\"}";
        long ts = System.currentTimeMillis();
        MockHttpServletRequest req = hmacRequest(body);
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=deadbeefnotmatching");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("POST_v1_aug_callback_replay_시간초과_시_401")
    void augmentReplayTimestampExceeded_returns401() throws Exception {
        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis() - 10 * 60 * 1000L; // 10분 전
        String sig = hmacHex(SECRET_AUGMENT, ts + "." + body);
        MockHttpServletRequest req = hmacRequest(body);
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=" + sig);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("미래_timestamp_는_시계오차_범위를_넘으면_401")
    void futureTimestampBeyondSkew_returns401() throws Exception {
        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis() + 120_000L; // +2분 (허용 skew 30s 초과)
        String sig = hmacHex(SECRET_AUGMENT, ts + "." + body);
        MockHttpServletRequest req = hmacRequest(body);
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=" + sig);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("올바른_HMAC_서명은_FilterChain_doFilter_호출")
    void validSignature_chainsThrough() throws Exception {
        String body = "{\"idempotencyKey\":\"K2\"}";
        long ts = System.currentTimeMillis();
        String sig = hmacHex(SECRET_AUGMENT, ts + "." + body);
        MockHttpServletRequest req = hmacRequest(body);
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=" + sig);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isNotEqualTo(401);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("통과한_요청은_서명검증_증거_래퍼로_감싸진다_이중게이트")
    void passedRequest_isWrappedWithVerifiedMarker() throws Exception {
        String body = "{\"k\":9}";
        long ts = System.currentTimeMillis();
        MockHttpServletRequest req = hmacRequest(body);
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts + "." + body));
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        filter.doFilter(req, res, chain);

        assertThat(chain.captured)
                .as("컨트롤러 게이트가 판정할 증거 래퍼가 있어야 한다")
                .isInstanceOf(WebhookGuardedRequest.class);
        assertThat(((WebhookGuardedRequest) chain.captured).isSignatureVerified()).isTrue();
    }

    @Test
    @DisplayName("서명필수_경로가_없으면_HMAC_시크릿_없이도_기동한다")
    void emptySecret_bootsWhenNoSignatureRequiredPaths() {
        // DEV_FIX MEDIUM-3 — 서명 필수 경로가 0개인 현 형상에서 시크릿 강제는 보안 통제가 아니라
        // 배포 함정이었다(운영은 무의미한 값을 넣어야만 뜨고, 값을 지우면 기동이 죽었다).
        assertThat(WebhookProtectedPaths.hasSignatureRequiredPaths())
                .as("이 테스트의 전제 — 서명 필수 경로가 0개여야 한다")
                .isFalse();

        assertThatCode(() -> newFilter("", "", "")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("경로_판정_불가_요청은_여전히_401_이다")
    void unresolvablePath_stillReturns401_withoutSecret() throws Exception {
        // fail-closed 보존 — 시크릿 강제를 완화해도 판정 불가 요청은 통과시키지 않는다.
        filter = newFilter("", "", "");
        MockHttpServletRequest req = hmacRequest("{\"x\":1}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        assertThat(shouldNotFilter(req))
                .as("경로 판정 불가는 보호 대상으로 고정(fail-closed)")
                .isFalse();
        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.captured).as("컨트롤러로 흘러가면 안 된다").isNull();
    }

    @Test
    @DisplayName("HmacWebhookFilter_시크릿_32바이트_미만_시_부팅_실패")
    void shortSecret_failsBoot() {
        assertThatThrownBy(() -> newFilter("short", "", ""))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("M-1");
    }

    @Test
    @DisplayName("리포에_커밋된_공개_placeholder_시크릿은_local_이_아닌_프로파일에서_부팅_실패")
    void committedPlaceholderSecret_failsBootOutsideLocal() {
        // 이 값은 빈 값도 아니고 32B 를 넘겨 기존 검사 두 개를 모두 통과했다 → "공개된 키로 조용히 기동"(H-1).
        String placeholder = "local-augment-callback-hmac-change-me-32bytes-min";
        assertThat(placeholder.getBytes(StandardCharsets.UTF_8).length)
                .as("길이 검사만으로는 걸러지지 않는 값이어야 이 테스트가 의미가 있다")
                .isGreaterThanOrEqualTo(32);

        assertThatThrownBy(() -> newFilter(placeholder, "", "", env("dev")))
                .as("커밋된 공개 시크릿으로 기동하면 누구나 유효 서명을 위조할 수 있다")
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("공개 placeholder");

        // prd 는 프록시/allowlist 설정을 명시해야 하므로 'none' 을 주고 시크릿 사유만 남긴다.
        assertThatThrownBy(() -> newFilter(placeholder, "none", "none", env("prd")))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("공개 placeholder");
    }

    // ─── 운영 프로파일 명시 설정 강제 (M-3) ──────────────────────

    @Test
    @DisplayName("prd_에서_신뢰프록시_CIDR_미설정이면_기동_실패")
    void prdWithoutTrustedProxyCidrs_failsBoot() {
        assertThatThrownBy(() -> new ClientIpResolver("", env("prd")))
                .as("미설정이면 LB IP 단일 키 집계로 정상 벤더 콜백까지 429 가 된다")
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("WEBHOOK_TRUSTED_PROXY_CIDRS");
    }

    @Test
    @DisplayName("prd_에서_프록시_없음을_none_으로_명시하면_기동_가능하고_XFF_는_무시된다")
    void prdWithExplicitNoProxy_boots_andIgnoresXff() throws Exception {
        ClientIpResolver resolver = new ClientIpResolver(ClientIpResolver.NO_PROXY, env("prd"));
        MockHttpServletRequest req = hmacRequest("{}");
        req.setRemoteAddr("203.0.113.9");
        req.addHeader("X-Forwarded-For", "10.1.1.1");

        assertThat(resolver.isProxyAware()).isFalse();
        assertThat(resolver.resolve(req))
                .as("none 은 'XFF 를 의도적으로 무시' 를 뜻한다 — 위조 헤더를 신뢰하면 안 된다")
                .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("prd_에서_VLM_IP_allowlist_미설정이면_기동_실패")
    void prdWithoutVlmAllowlist_failsBoot() {
        assertThatThrownBy(() -> new WebhookIpAllowlist("", env("prd")))
                .as("무서명 콜백 3계층 방어의 첫 계층이 조용히 꺼지면 안 된다")
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("WEBHOOK_VLM_ALLOWED_IP_CIDRS");
    }

    /**
     * <b>R-3 회귀 고정</b> — 명시 강제가 {@code contains("prd")} 라 <b>stg 가 사각지대</b>였다.
     * 이 프로젝트의 stg 는 온프렘 개발서버로 <b>LB/Nginx 뒤</b>에 배포되는데,
     * {@code application-stg.yml} 에 webhook 키가 없어 {@code application.yml} 의 빈 기본값으로 조용히
     * 기동했고 모든 콜백 IP 가 LB IP 하나로 수렴했다.
     */
    @Test
    @DisplayName("stg_에서도_신뢰프록시_CIDR_과_VLM_allowlist_미설정이면_기동_실패")
    void stgWithoutWebhookGuardConfig_failsBoot() {
        assertThatThrownBy(() -> new ClientIpResolver("", env("stg")))
                .as("stg 는 LB/Nginx 뒤 온프렘 개발서버 — prd 와 동일하게 명시 강제여야 한다")
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("WEBHOOK_TRUSTED_PROXY_CIDRS");

        assertThatThrownBy(() -> new WebhookIpAllowlist("", env("stg")))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("WEBHOOK_VLM_ALLOWED_IP_CIDRS");

        // 명시하면(프록시 없음 = none 포함) 기동된다 — 강제의 의미는 "값을 의식적으로 정하라" 이다.
        assertThatCode(() -> new ClientIpResolver(ClientIpResolver.NO_PROXY, env("stg")))
                .doesNotThrowAnyException();
        assertThatCode(() -> new WebhookIpAllowlist("203.0.113.0/24", env("stg")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("local_dev_는_프록시_없는_직접기동이라_미설정으로도_기동된다")
    void localAndDevProfiles_bootWithoutExplicitConfig() {
        assertThatCode(() -> new ClientIpResolver("", env("local"))).doesNotThrowAnyException();
        assertThatCode(() -> new ClientIpResolver("", env("dev"))).doesNotThrowAnyException();
        assertThatCode(() -> new WebhookIpAllowlist("", env("dev"))).doesNotThrowAnyException();
    }

    /**
     * {@code remoteAddr} 이 IP 리터럴이 아닌 이상 상황(상위 래퍼가 헤더 유래 <b>호스트명</b>으로
     * 덮어쓴 경우)에서 fail-closed 로 접히는지 확인한다.
     *
     * <p><b>보증 범위</b>: 비-IP 문자열이 공유 카운터·allowlist 판정에 유입되지 않고,
     * {@code InetAddress.getByName()} 의 요청 스레드 DNS 조회도 발생하지 않는다.
     * <b>XFF 우회 차단은 이 가드의 몫이 아니다</b> — 공격자가 넣는 값은 대개 유효 IPv4 리터럴이라 이
     * 분기에 걸리지 않는다. 우회는 {@code server.forward-headers-strategy} 미사용
     * ({@code ForwardedHeadersConfigGuard} 기동 차단)으로 막는다.
     */
    @Test
    @DisplayName("remoteAddr_이_IP리터럴이_아니면_unknown_으로_접어_카운터오염과_DNS조회를_막는다")
    void nonLiteralRemoteAddr_isNotTrusted() {
        ClientIpResolver resolver = new ClientIpResolver("", new MockEnvironment());
        MockHttpServletRequest req = hmacRequest("{}");
        req.setRemoteAddr("evil.example.com"); // 상위 래퍼가 헤더 유래 호스트명으로 치환한 상태

        assertThat(resolver.resolve(req))
                .as("비-IP 문자열을 rate limit 키·allowlist 판정에 쓰면 안 된다(fail-closed)")
                .isEqualTo("unknown")
                .isNotEqualTo("evil.example.com");
    }

    @Test
    @DisplayName("정상_IPv4_IPv6_remoteAddr_은_그대로_사용된다")
    void literalRemoteAddr_isUsedAsIs() {
        ClientIpResolver resolver = new ClientIpResolver("", new MockEnvironment());
        MockHttpServletRequest v4 = hmacRequest("{}");
        v4.setRemoteAddr("203.0.113.9");
        MockHttpServletRequest v6 = hmacRequest("{}");
        v6.setRemoteAddr("2001:db8::1");

        assertThat(resolver.resolve(v4)).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(v6)).isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("호스트명_유사_XFF_값은_거부되어_요청스레드_DNS_조회를_유발하지_않는다")
    void hostnameLikeXffValue_isRejected() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8", new MockEnvironment());
        MockHttpServletRequest req = hmacRequest("{}");
        req.setRemoteAddr("10.0.0.1"); // 신뢰 프록시
        req.addHeader("X-Forwarded-For", "00a"); // 16진 문자만이라 구 검증을 통과했다(L-3)

        assertThat(resolver.resolve(req))
                .as("IP 리터럴이 아니면 remoteAddr 로 폴백해야 한다 (InetAddress.getByName DNS 조회 차단)")
                .isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("local_프로파일에서는_placeholder_시크릿으로_기동_가능_로컬스택_유지")
    void committedPlaceholderSecret_allowedOnLocalProfile() {
        assertThat(newFilter("local-augment-callback-hmac-change-me-32bytes-min", "", "", env("local")))
                .as("로컬 docker 스택은 계속 떠야 한다 (시크릿 비노출과 양립)")
                .isNotNull();
    }

    @Test
    @DisplayName("해석되지_않은_placeholder_기본값_물음표_시크릿은_부팅_실패")
    void unresolvedPlaceholderSecret_failsBoot() {
        // Spring 은 ${VAR:?msg} 를 fail-fast 로 처리하지 않고 "?msg" 를 기본값으로 주입한다.
        // 그 문자열은 32B 를 넘어 검증을 통과하므로 "커밋된 문자열을 키로 기동" 이 된다(H-1).
        assertThatThrownBy(() -> newFilter(
                "?WEBHOOK_HMAC_SECRET_AUGMENT 환경변수가 필수입니다 (prd)", "none", "none", env("prd")))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("해석되지 않은 placeholder");
    }

    // ─── replay / nonce (A-ISSUE-12) ────────────────────────────

    @Test
    @DisplayName("동일_서명_재전송시_윈도우_내라도_replay_로_흡수되고_401_이_아님")
    void replayWithinWindow_returns409_not401() throws Exception {
        String body = "{\"k\":\"replay\"}";
        long ts = System.currentTimeMillis();
        String sig = "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts + "." + body);

        MockHttpServletResponse first = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(signedRequest(body, ts, sig), first, chain);
        assertThat(first.getStatus()).isNotEqualTo(401);
        verify(chain, times(1)).doFilter(any(), any());

        MockHttpServletResponse second = new MockHttpServletResponse();
        FilterChain chain2 = mock(FilterChain.class);
        filter.doFilter(signedRequest(body, ts, sig), second, chain2);

        assertThat(second.getStatus())
                .as("정상 재시도를 인증 실패로 오인시키면 안 된다 — replay 는 409 (S-10)")
                .isEqualTo(409);
        verify(chain2, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("nonce_키는_원시URI가_아니라_정규화_경로로_계산됨")
    void nonceKey_usesCanonicalPath() throws Exception {
        String body = "{\"k\":\"canon\"}";
        long ts = System.currentTimeMillis();
        String sig = "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts + "." + body);

        filter.doFilter(signedRequest(body, ts, sig), new MockHttpServletResponse(),
                mock(FilterChain.class));

        // 원시 URI 를 키로 쓰면 인코딩 변형마다 키가 갈라져 replay 가 전부 신규로 통과한다(H-2).
        // 판정 불가 경로는 고정 상수 하나로 수렴해야 한다.
        assertThat(nonceStore.lastPath)
                .as("nonce 저장 경로는 정규화 경로 API 산출값이어야 한다 (H-2)")
                .isEqualTo(WebhookProtectedPaths.UNRESOLVED_PATH);
    }

    @Test
    @DisplayName("하류가_5xx_로_실패하면_nonce_예약이_해제되어_동일바이트_재전송이_다시_처리됨")
    void downstreamServerError_releasesNonce_soIdenticalRetryIsProcessed() throws Exception {
        String body = "{\"k\":\"boom5xx\"}";
        long ts = System.currentTimeMillis();
        String sig = "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts + "." + body);
        FilterChain failing = (rq, rs) ->
                ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(500);

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(signedRequest(body, ts, sig), first, failing);
        assertThat(first.getStatus()).isEqualTo(500);

        // 벤더는 통상 "바이트 동일" 재전송을 한다 — 409 로 흡수하면 적용되지 않은 결과가 영구 유실된다.
        MockHttpServletResponse retry = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(signedRequest(body, ts, sig), retry, chain);

        assertThat(retry.getStatus())
                .as("하류 5xx 후 동일 서명 재전송은 다시 처리돼야 한다 (M-1, CWE-754)")
                .isNotEqualTo(409);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("하류가_예외로_실패해도_nonce_예약이_해제됨")
    void downstreamException_releasesNonce() throws Exception {
        String body = "{\"k\":\"boom-ex\"}";
        long ts = System.currentTimeMillis();
        String sig = "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts + "." + body);
        FilterChain exploding = (rq, rs) -> {
            throw new IllegalStateException("downstream 5xx 시뮬");
        };

        assertThatThrownBy(() -> filter.doFilter(signedRequest(body, ts, sig),
                new MockHttpServletResponse(), exploding))
                .isInstanceOf(IllegalStateException.class);

        MockHttpServletResponse retry = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(signedRequest(body, ts, sig), retry, chain);

        assertThat(retry.getStatus()).isNotEqualTo(409);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("replay_응답_본문은_재전송_불필요_라고_단정하지_않음")
    void replayResponse_doesNotTellVendorToStopRetrying() throws Exception {
        String body = "{\"k\":\"msg\"}";
        long ts = System.currentTimeMillis();
        String sig = "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts + "." + body);
        filter.doFilter(signedRequest(body, ts, sig), new MockHttpServletResponse(),
                mock(FilterChain.class));

        MockHttpServletResponse replay = new MockHttpServletResponse();
        filter.doFilter(signedRequest(body, ts, sig), replay, mock(FilterChain.class));

        assertThat(replay.getStatus()).isEqualTo(409);
        assertThat(replay.getContentAsString())
                .as("벤더 재시도를 중단시키는 문구는 결과 영구 유실로 이어질 수 있다 (M-1)")
                .doesNotContain("재전송 불필요");
    }

    @Test
    @DisplayName("서명_검증_실패_요청은_nonce_테이블에_행을_쓰지_않음_pre_auth_write_DoS")
    void failedSignature_doesNotTouchNonceStore() throws Exception {
        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis();
        MockHttpServletRequest req = hmacRequest(body);
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=deadbeef");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(nonceStore.consumeCalls.get())
                .as("검증 실패 요청이 저장소를 쓰면 무인증 write DoS 가 된다 (S-08)")
                .isZero();
    }

    @Test
    @DisplayName("nonce_소비_후_처리가_5xx_로_실패해도_결과가_영구유실되지_않음")
    void nonceConsumedThenDownstreamFails_retryWithNewSignatureStillPasses() throws Exception {
        String body = "{\"k\":\"boom\"}";
        long ts = System.currentTimeMillis();
        String sig = "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts + "." + body);
        FilterChain exploding = (rq, rs) -> {
            throw new IllegalStateException("downstream 5xx 시뮬");
        };

        assertThatThrownBy(() -> filter.doFilter(signedRequest(body, ts, sig),
                new MockHttpServletResponse(), exploding))
                .isInstanceOf(IllegalStateException.class);
        assertThat(nonceStore.consumeCalls.get()).isEqualTo(1);

        // 벤더 재시도는 새 timestamp/서명으로 오므로 인증 계층이 막지 않는다.
        // (비즈니스 멱등은 otsd_job_id 계층 책임 — nonce 는 인증 계층 한정, S-11)
        long ts2 = ts + 1;
        String sig2 = "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts2 + "." + body);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(signedRequest(body, ts2, sig2), res, chain);

        assertThat(res.getStatus()).isNotIn(401, 409);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("nonce_저장소_장애시_fail_closed_503_이며_통과하지_않음")
    void nonceStoreUnavailable_failsClosed() throws Exception {
        nonceStore.failing = true;
        String body = "{\"k\":\"db-down\"}";
        long ts = System.currentTimeMillis();
        String sig = "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts + "." + body);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(signedRequest(body, ts, sig), res, chain);

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getHeader("Retry-After")).isNotNull();
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("rate_limit_공유저장소_장애시_fail_open_으로_정상_콜백이_통과")
    void sharedRateLimitStoreDown_failsOpen() throws Exception {
        rateLimiter = new WebhookRateLimiter(new WebhookRateLimitStore() {
            @Override
            public int recordFailure(String clientIp, LocalDateTime windowStart, Duration ttl) {
                return UNAVAILABLE;
            }

            @Override
            public int currentFailures(String clientIp, LocalDateTime windowStart) {
                return UNAVAILABLE;
            }

            @Override
            public void resetFailures(String clientIp, LocalDateTime windowStart) {
                // 저장소 장애 시뮬 — 해제도 무시된다(fail-open).
            }
        });
        filter = newFilter(SECRET_AUGMENT, "", "");

        String body = "{\"k\":\"open\"}";
        long ts = System.currentTimeMillis();
        String sig = "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts + "." + body);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(signedRequest(body, ts, sig), res, chain);

        assertThat(res.getStatus()).isNotEqualTo(429);
        verify(chain, times(1)).doFilter(any(), any());
    }

    // ─── rate limit / clientIp (A-ISSUE-14 / A-ISSUE-15) ────────

    @Test
    @DisplayName("HmacWebhookFilter_시그니처_실패_분당_5회_초과_시_60초_backoff")
    void rateLimit_after5Failures() throws Exception {
        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis();
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = hmacRequest(body);
            req.setRemoteAddr("10.0.0.99");
            req.addHeader("X-Timestamp", String.valueOf(ts));
            req.addHeader("X-Signature", "hmac-sha256=deadbeefwronghex" + i);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).isEqualTo(401);
        }
        MockHttpServletRequest req6 = hmacRequest(body);
        req6.setRemoteAddr("10.0.0.99");
        req6.addHeader("X-Timestamp", String.valueOf(ts));
        req6.addHeader("X-Signature", "hmac-sha256=anyhex");
        MockHttpServletResponse res6 = new MockHttpServletResponse();
        filter.doFilter(req6, res6, chain);

        assertThat(res6.getStatus()).isEqualTo(429);
        assertThat(res6.getHeader("Retry-After")).isNotNull();
    }

    @Test
    @DisplayName("신뢰프록시_밖의_X_Forwarded_For_는_폐기되고_rate_limit_우회가_불가")
    void untrustedXff_isDiscarded() throws Exception {
        // 신뢰 프록시 미설정 → XFF 전면 무시. 매 요청 다른 XFF 를 넣어도 동일 remoteAddr 로 집계된다.
        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis();
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = hmacRequest(body);
            req.setRemoteAddr("203.0.113.9");
            req.addHeader("X-Forwarded-For", "10.1.1." + i); // 위조 시도
            req.addHeader("X-Timestamp", String.valueOf(ts));
            req.addHeader("X-Signature", "hmac-sha256=wrong" + i);
            filter.doFilter(req, new MockHttpServletResponse(), chain);
        }
        MockHttpServletRequest req6 = hmacRequest(body);
        req6.setRemoteAddr("203.0.113.9");
        req6.addHeader("X-Forwarded-For", "10.1.1.99");
        req6.addHeader("X-Timestamp", String.valueOf(ts));
        req6.addHeader("X-Signature", "hmac-sha256=wrong-final");
        MockHttpServletResponse res6 = new MockHttpServletResponse();
        filter.doFilter(req6, res6, chain);

        assertThat(res6.getStatus())
                .as("XFF 위조로 rate limit 을 우회할 수 없어야 한다 (CWE-348)")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("신뢰프록시_뒤에서는_X_Forwarded_For_클라이언트별로_rate_limit_이_독립_적용")
    void trustedProxyXff_isolatesClients() throws Exception {
        filter = newFilter(SECRET_AUGMENT, "10.0.0.0/8", "");
        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = hmacRequest(body);
            req.setRemoteAddr("10.0.0.1"); // 신뢰 프록시
            req.addHeader("X-Forwarded-For", "198.51.100.7");
            req.addHeader("X-Timestamp", String.valueOf(ts));
            req.addHeader("X-Signature", "hmac-sha256=wrong" + i);
            filter.doFilter(req, new MockHttpServletResponse(), chain);
        }

        // 공격자(198.51.100.7)는 차단되지만 정상 벤더(198.51.100.8)는 영향 없어야 한다.
        MockHttpServletRequest attacker = hmacRequest(body);
        attacker.setRemoteAddr("10.0.0.1");
        attacker.addHeader("X-Forwarded-For", "198.51.100.7");
        attacker.addHeader("X-Timestamp", String.valueOf(ts));
        attacker.addHeader("X-Signature", "hmac-sha256=wrong-final");
        MockHttpServletResponse attackerRes = new MockHttpServletResponse();
        filter.doFilter(attacker, attackerRes, chain);
        assertThat(attackerRes.getStatus()).isEqualTo(429);

        String vendorBody = "{\"k\":\"vendor\"}";
        long ts2 = System.currentTimeMillis();
        MockHttpServletRequest vendor = hmacRequest(vendorBody);
        vendor.setRemoteAddr("10.0.0.1");
        vendor.addHeader("X-Forwarded-For", "198.51.100.8");
        vendor.addHeader("X-Timestamp", String.valueOf(ts2));
        vendor.addHeader("X-Signature", "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts2 + "." + vendorBody));
        MockHttpServletResponse vendorRes = new MockHttpServletResponse();
        FilterChain vendorChain = mock(FilterChain.class);
        filter.doFilter(vendor, vendorRes, vendorChain);

        assertThat(vendorRes.getStatus())
                .as("프록시 뒤 정상 벤더가 타 IP 실패에 연좌되면 안 된다 (A-ISSUE-15)")
                .isNotEqualTo(429);
        verify(vendorChain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("HmacWebhookFilter_FailureTracker_4096_초과_시_hard_cap_적용")
    void failureTrackers_hardCap() throws Exception {
        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis();
        FilterChain chain = mock(FilterChain.class);

        int totalIps = WebhookRateLimiter.MAX_FAILURE_TRACKERS + 1;
        for (int i = 0; i < totalIps; i++) {
            MockHttpServletRequest req = hmacRequest(body);
            req.setRemoteAddr("10.99." + (i / 256) + "." + (i % 256));
            req.addHeader("X-Timestamp", String.valueOf(ts));
            req.addHeader("X-Signature", "hmac-sha256=wronghex" + i);
            filter.doFilter(req, new MockHttpServletResponse(), chain);
        }

        assertThat(rateLimiter.localTrackerCount())
                .as("로컬 추적기는 MAX_FAILURE_TRACKERS(%d) 이하로 유지되어야 함",
                        WebhookRateLimiter.MAX_FAILURE_TRACKERS)
                .isLessThanOrEqualTo(WebhookRateLimiter.MAX_FAILURE_TRACKERS);
    }

    // ─── 본문 크기 가드 ──────────────────────────────────────────

    @Test
    @DisplayName("HmacWebhookFilter_본문_1MB_초과_시_413")
    void bodyOver1MB_returns413() throws Exception {
        byte[] big = new byte[(int) HmacWebhookFilter.MAX_WEBHOOK_BODY_BYTES + 1];
        MockHttpServletRequest req = unresolvablePathRequest();
        req.setContent(big);
        req.setContentType("application/json");
        long ts = System.currentTimeMillis();
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256="
                + hmacHex(SECRET_AUGMENT, ts + "." + new String(big, StandardCharsets.UTF_8)));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("HmacWebhookFilter_Content_Length_누락_시_401")
    void missingContentLength_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/unresolvable/callback") {
            @Override
            public String getRequestURI() {
                throw new IllegalStateException("URI 파싱 실패 시뮬 (fail-closed 분기 진입)");
            }
            @Override
            public long getContentLengthLong() { return -1L; }
            @Override
            public int getContentLength() { return -1; }
        };
        req.setContent("{}".getBytes(StandardCharsets.UTF_8));
        req.setContentType("application/json");
        long ts = System.currentTimeMillis();
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts + ".{}"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    // ─── VLM 무서명 경로 (B-ISSUE-25) ───────────────────────────

    @Test
    @DisplayName("VLM_콜백은_서명_없이도_정상_처리됨_벤더계약_회귀고정")
    void vlmPath_passesThroughWithoutHmac() throws Exception {
        MockHttpServletRequest req = postRequest("/v1/vlm/callback",
                "{\"request_id\":\"REQ-1\",\"status\":\"completed\",\"results\":[]}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isNotEqualTo(401);
        assertThat(res.getStatus()).isNotEqualTo(413);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("VLM_위조_request_id_로_인한_서비스단_401_도_rate_limit_에_집계됨")
    void vlmPath_downstreamUnauthorized_countsTowardRateLimit() throws Exception {
        // 실제 공격 경로: 정상 형식(Content-Length 정상) + 위조 request_id → 필터 통과 후
        // VlmResultService.lookupForProcessing(비관적 락 SELECT) 에서 401.
        // 필터 단계 실패(411 등)로 카운터를 쌓으면 이 경로가 한 번도 태워지지 않아 위양성이 된다(H-4).
        FilterChain unauthorized = (rq, rs) ->
                ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(401);

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = postRequest("/v1/vlm/callback",
                    "{\"request_id\":\"forged-rl-" + i + "\",\"status\":\"failed\"}");
            req.setRemoteAddr("198.18.0.5");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, unauthorized);
            assertThat(res.getStatus())
                    .as("%d 회차는 아직 서비스단 401 이어야 한다", i + 1)
                    .isEqualTo(401);
        }

        MockHttpServletRequest sixth = postRequest("/v1/vlm/callback",
                "{\"request_id\":\"forged-rl-6\",\"status\":\"failed\"}");
        sixth.setRemoteAddr("198.18.0.5");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(sixth, res, chain);

        assertThat(res.getStatus())
                .as("위조 request_id 반복이 rate limit 에 안 잡히면 비관적 락 SELECT 를 무제한 유발 (H-3)")
                .isEqualTo(429);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("VLM_필터단계_실패_Content_Length_누락_도_rate_limit_에_집계됨")
    void vlmPath_filterStageFailure_countsTowardRateLimit() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/vlm/callback") {
                @Override
                public long getContentLengthLong() { return -1L; }
                @Override
                public int getContentLength() { return -1; }
            };
            req.setContent("{}".getBytes(StandardCharsets.UTF_8));
            req.setContentType("application/json");
            req.setRemoteAddr("198.18.0.6");
            filter.doFilter(req, new MockHttpServletResponse(), chain);
        }
        MockHttpServletRequest normal = postRequest("/v1/vlm/callback", "{\"request_id\":\"R\"}");
        normal.setRemoteAddr("198.18.0.6");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(normal, res, chain);

        assertThat(res.getStatus())
                .as("무인증 공개 엔드포인트에 rate limit 이 없으면 pre-auth DoS 표면 (B-ISSUE-25)")
                .isEqualTo(429);
    }

    /**
     * <b>N-2 회귀 고정</b> — 구 테스트({@code vlmPath_successResetsFailureCounter})는 "하류 2xx → 실패
     * 카운터 전면 reset" 이라는 <b>취약 동작을 정상 사양으로 고정</b>하고 있었다.
     *
     * <p>이 경로는 무서명이라 200 이 인증 성공을 뜻하지 않는다. 이미 처리된 {@code request_id}
     * 하나만 알면 {@code VlmResultService} 가 멱등 스킵 후 200 을 돌려주므로, "위조 4회 + 알려진 id 1회"
     * 를 반복하면 카운터가 영원히 0 이 되어 rate limit 이 전면 무력화된다. 정상 벤더의 연좌 방지는
     * <b>분 단위 창 만료</b>가 담당한다(명시적 reset 은 서명 검증 성공 경로에만 남긴다).
     */
    @Test
    @DisplayName("VLM_알려진_request_id_의_200_을_끼워넣어도_실패카운터가_초기화되지_않아_차단이_유지된다")
    void vlmPath_downstream2xx_doesNotResetFailureCounter() throws Exception {
        FilterChain unauthorized = (rq, rs) ->
                ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(401);
        FilterChain ok = (rq, rs) ->
                ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(200);
        String attackerIp = "198.18.0.7";

        // 위조 request_id 4회 (아직 임계 미만)
        for (int i = 0; i < 4; i++) {
            MockHttpServletRequest req = postRequest("/v1/vlm/callback", "{\"request_id\":\"f" + i + "\"}");
            req.setRemoteAddr(attackerIp);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, unauthorized);
            assertThat(res.getStatus()).isEqualTo(401);
        }

        // 알려진(이미 PROCESSED) request_id 1회 → 멱등 스킵이지만 HTTP 200
        MockHttpServletRequest known = postRequest("/v1/vlm/callback", "{\"request_id\":\"known\"}");
        known.setRemoteAddr(attackerIp);
        MockHttpServletResponse knownRes = new MockHttpServletResponse();
        filter.doFilter(known, knownRes, ok);
        assertThat(knownRes.getStatus()).isEqualTo(200);

        // 위조 1회 더 → 누적 5회. 200 이 카운터를 지웠다면 여기서 누적이 1 이라 아래 단정이 깨진다.
        MockHttpServletRequest fifth = postRequest("/v1/vlm/callback", "{\"request_id\":\"f4\"}");
        fifth.setRemoteAddr(attackerIp);
        filter.doFilter(fifth, new MockHttpServletResponse(), unauthorized);

        MockHttpServletRequest blocked = postRequest("/v1/vlm/callback", "{\"request_id\":\"f5\"}");
        blocked.setRemoteAddr(attackerIp);
        MockHttpServletResponse blockedRes = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(blocked, blockedRes, chain);

        assertThat(blockedRes.getStatus())
                .as("2xx 를 reset 신호로 쓰면 '위조 4회 + 알려진 id 200 1회' 반복으로 rate limit 이 "
                        + "전면 무력화된다 (N-2) — pre-auth 비관적 락 SELECT 무제한 유발")
                .isEqualTo(429);
        verify(chain, never()).doFilter(any(), any());

        // 차단 상태에서 다시 '알려진 id 200' 을 시도해도 해제되지 않는다(체인 진입 자체가 차단).
        MockHttpServletRequest retryKnown = postRequest("/v1/vlm/callback", "{\"request_id\":\"known\"}");
        retryKnown.setRemoteAddr(attackerIp);
        MockHttpServletResponse retryRes = new MockHttpServletResponse();
        filter.doFilter(retryKnown, retryRes, ok);
        assertThat(retryRes.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("VLM_하류_2xx_는_공유_실패카운터도_삭제하지_않는다_무인증_요청이_유발하는_DB_write_제거")
    void vlmPath_downstream2xx_doesNotDeleteSharedCounter() throws Exception {
        RecordingRateLimitStore shared = new RecordingRateLimitStore();
        rateLimiter = new WebhookRateLimiter(shared);
        filter = newFilter(SECRET_AUGMENT, "", "");
        FilterChain ok = (rq, rs) ->
                ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(200);

        MockHttpServletRequest req = postRequest("/v1/vlm/callback", "{\"request_id\":\"known\"}");
        req.setRemoteAddr("198.18.0.8");
        filter.doFilter(req, new MockHttpServletResponse(), ok);

        assertThat(shared.resetIps)
                .as("무서명·무인증 요청 한 건이 공유 DELETE 를 유발하면 그 자체가 pre-auth write 표면 (N-5)")
                .isEmpty();
    }

    // ─── 공격자 제어 헤더로 rate limit 이 꺼지지 않는다 (REDESIGN R-1) ──

    /**
     * <b>R-1 회귀 고정</b> — 폐기된 테스트({@code vlmPath_unattributableClientIp_skipsDownstreamFailureCount})
     * 는 "XFF 가 관측되면 하류 401 집계를 끈다"(N-3)는 <b>취약 동작을 정상 사양으로 고정</b>하고 있었다.
     *
     * <p>{@code X-Forwarded-For} 는 <b>공격자가 임의로 붙일 수 있는 헤더</b>다. 그것을 조건으로 보안
     * 통제를 끄면 <b>헤더 한 줄로 rate limit 이 무력화</b>된다(실측: XFF 없으면 6회째 429, XFF 를 붙이면
     * 15회 전부 401). 그러면 {@code B-ISSUE-25} 의 원 시나리오 — 위조 {@code request_id} 로
     * {@code lookupForProcessing} 의 {@code SELECT ... FOR UPDATE} 를 무제한 유발 — 이 그대로 재개통된다.
     *
     * <p>여기서는 공유 저장소를 붙인 상태로(로컬 카운터만 타는 위양성 방지) <b>XFF 를 붙여도 6회째부터
     * 429</b> 임을 단정한다.
     */
    @Test
    @DisplayName("X_Forwarded_For를_임의로_붙여도_위조_request_id_6회째부터_429_로_차단된다")
    void vlmPath_forgedXffHeader_doesNotDisableRateLimit() throws Exception {
        RecordingRateLimitStore shared = new RecordingRateLimitStore();
        rateLimiter = new WebhookRateLimiter(shared);
        filter = newFilter(SECRET_AUGMENT, "", ""); // trusted-proxy-cidrs 미설정 = XFF 전면 폐기
        FilterChain unauthorized = (rq, rs) ->
                ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(401);

        // 1~5 회차: 아직 임계 미만이라 하류까지 도달(401).
        for (int i = 0; i < WebhookRateLimiter.RATE_LIMIT_FAILURES_PER_MINUTE; i++) {
            MockHttpServletRequest req = postRequest("/v1/vlm/callback", "{\"request_id\":\"x" + i + "\"}");
            req.setRemoteAddr("198.18.9.1");
            req.addHeader("X-Forwarded-For", "203.0.113." + i); // 매 요청 다른 위조 헤더
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, unauthorized);
            assertThat(res.getStatus()).as("%d 회차는 아직 401", i + 1).isEqualTo(401);
        }

        // 6 회차: 차단. 헤더 한 줄로 통제가 꺼지면 여기서 401 이 되어 실패한다.
        MockHttpServletRequest blocked = postRequest("/v1/vlm/callback", "{\"request_id\":\"x9\"}");
        blocked.setRemoteAddr("198.18.9.1");
        blocked.addHeader("X-Forwarded-For", "203.0.113.99");
        MockHttpServletResponse blockedRes = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(blocked, blockedRes, chain);

        assertThat(blockedRes.getStatus())
                .as("공격자 제어 입력(XFF)으로 rate limit 이 꺼지면 위조 request_id 가 비관적 락 SELECT 를 "
                        + "무제한 유발한다 (R-1 / B-ISSUE-25 재개통)")
                .isEqualTo(429);
        verify(chain, never()).doFilter(any(), any());
        assertThat(shared.recordCalls.get())
                .as("공유 집계도 함께 동작해야 2노드에서 임계가 2배가 되지 않는다")
                .isEqualTo(WebhookRateLimiter.RATE_LIMIT_FAILURES_PER_MINUTE);
    }

    @Test
    @DisplayName("XFF_유무와_무관하게_동일_발신자는_동일_키로_집계된다_헤더로_카운터를_갈라칠_수_없다")
    void vlmPath_xffDoesNotSplitRateLimitKey() throws Exception {
        rateLimiter = new WebhookRateLimiter(new RecordingRateLimitStore());
        filter = newFilter(SECRET_AUGMENT, "", "");
        FilterChain unauthorized = (rq, rs) ->
                ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(401);

        // 3회는 XFF 없이, 2회는 XFF 를 붙여서 — 총 5회가 한 키로 모여야 한다.
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = postRequest("/v1/vlm/callback", "{\"request_id\":\"m" + i + "\"}");
            req.setRemoteAddr("198.18.9.2");
            if (i >= 3) {
                req.addHeader("X-Forwarded-For", "203.0.113.5");
            }
            filter.doFilter(req, new MockHttpServletResponse(), unauthorized);
        }

        MockHttpServletRequest blocked = postRequest("/v1/vlm/callback", "{\"request_id\":\"m9\"}");
        blocked.setRemoteAddr("198.18.9.2");
        blocked.addHeader("X-Forwarded-For", "203.0.113.77");
        MockHttpServletResponse blockedRes = new MockHttpServletResponse();
        filter.doFilter(blocked, blockedRes, unauthorized);

        assertThat(blockedRes.getStatus())
                .as("XFF 값을 바꿔가며 카운터를 갈라칠 수 있으면 rate limit 은 무의미하다 (CWE-348)")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("신뢰프록시가_설정되면_XFF_기반_클라이언트별로_VLM_하류401_집계가_정상_동작")
    void vlmPath_proxyAware_stillCountsDownstreamFailuresPerClient() throws Exception {
        filter = newFilter(SECRET_AUGMENT, "10.0.0.0/8", "");
        FilterChain unauthorized = (rq, rs) ->
                ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(401);

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = postRequest("/v1/vlm/callback", "{\"request_id\":\"a" + i + "\"}");
            req.setRemoteAddr("10.20.30.40");
            req.addHeader("X-Forwarded-For", "203.0.113.9");
            filter.doFilter(req, new MockHttpServletResponse(), unauthorized);
        }

        MockHttpServletRequest attacker = postRequest("/v1/vlm/callback", "{\"request_id\":\"a9\"}");
        attacker.setRemoteAddr("10.20.30.40");
        attacker.addHeader("X-Forwarded-For", "203.0.113.9");
        MockHttpServletResponse attackerRes = new MockHttpServletResponse();
        filter.doFilter(attacker, attackerRes, unauthorized);
        assertThat(attackerRes.getStatus())
                .as("신뢰 프록시가 설정돼 발신자 귀속이 가능하면 H-3 집계는 유지돼야 한다")
                .isEqualTo(429);

        MockHttpServletRequest other = postRequest("/v1/vlm/callback", "{\"request_id\":\"b0\"}");
        other.setRemoteAddr("10.20.30.40");
        other.addHeader("X-Forwarded-For", "203.0.113.77");
        MockHttpServletResponse otherRes = new MockHttpServletResponse();
        filter.doFilter(other, otherRes, unauthorized);
        assertThat(otherRes.getStatus())
                .as("다른 클라이언트는 연좌되지 않는다")
                .isEqualTo(401);
    }

    // ─── CIDR 설정 오타 fail-closed (DEV_FIX N-4) ────────────────

    @Test
    @DisplayName("신뢰프록시_CIDR_오타는_조용히_무시되지_않고_기동이_차단되며_사유가_메시지에_드러난다")
    void invalidTrustedProxyCidr_failsBoot() {
        // 예외 "타입" 만 단정하면 다른 사유(설정 키 오타 등)로 던져진 예외도 통과한다 — 사유까지 단정한다(R-7).
        // /33 (주소 폭 초과) — 구 구현은 log.warn 후 버려 matcher 가 비고 XFF 전면 무시로 떨어졌다.
        assertThatThrownBy(() -> new ClientIpResolver("203.0.113.0/33", new MockEnvironment()))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("webhook.trusted-proxy-cidrs")
                .hasMessageContaining("203.0.113.0/33")
                .hasMessageContaining("주소 폭");
        // 구분자 오타 (세미콜론)
        assertThatThrownBy(() -> new ClientIpResolver("10.0.0.0/8;192.168.0.0/16", new MockEnvironment()))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("WEBHOOK_TRUSTED_PROXY_CIDRS")
                .hasMessageContaining("프리픽스 길이가 숫자가 아닙니다");
        // 호스트명 — IpAddressMatcher 가 기동 시 DNS 1회 해석 후 고정해 버린다
        assertThatThrownBy(() -> new ClientIpResolver("proxy.internal", new MockEnvironment()))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("proxy.internal")
                .hasMessageContaining("IP/CIDR 리터럴이 아닙니다");
        // 정상 값은 기동
        assertThatCode(() -> new ClientIpResolver("10.0.0.0/8, 2001:db8::/32", new MockEnvironment()))
                .doesNotThrowAnyException();
        assertThatCode(() -> new ClientIpResolver("none", new MockEnvironment()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("VLM_allowlist_CIDR_오타는_전면허용으로_떨어지지_않고_기동이_차단되며_사유가_메시지에_드러난다")
    void invalidVlmAllowlistCidr_failsBoot() {
        assertThatThrownBy(() -> new WebhookIpAllowlist("203.0.113.0/33", new MockEnvironment()))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("webhook.vlm.allowed-ip-cidrs")
                .hasMessageContaining("주소 폭");
        assertThatThrownBy(() -> new WebhookIpAllowlist("203.0.113.0/24;198.51.100.0/24",
                new MockEnvironment()))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("WEBHOOK_VLM_ALLOWED_IP_CIDRS")
                .hasMessageContaining("프리픽스 길이가 숫자가 아닙니다");
        assertThatThrownBy(() -> new WebhookIpAllowlist("vlm.vendor.example.com", new MockEnvironment()))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("vlm.vendor.example.com")
                .hasMessageContaining("IP/CIDR 리터럴이 아닙니다");
        assertThatCode(() -> new WebhookIpAllowlist("203.0.113.0/24", new MockEnvironment()))
                .doesNotThrowAnyException();
        assertThatCode(() -> new WebhookIpAllowlist("none", new MockEnvironment()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("VLM_IP_allowlist_설정시_허용목록_밖_출처는_403")
    void vlmPath_ipAllowlistBlocks() throws Exception {
        filter = newFilter(SECRET_AUGMENT, "", "203.0.113.0/24");
        MockHttpServletRequest req = postRequest("/v1/vlm/callback", "{\"request_id\":\"R\"}");
        req.setRemoteAddr("198.51.100.1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());

        MockHttpServletRequest allowed = postRequest("/v1/vlm/callback", "{\"request_id\":\"R\"}");
        allowed.setRemoteAddr("203.0.113.5");
        MockHttpServletResponse allowedRes = new MockHttpServletResponse();
        FilterChain allowedChain = mock(FilterChain.class);
        filter.doFilter(allowed, allowedRes, allowedChain);

        assertThat(allowedRes.getStatus()).isNotEqualTo(403);
        verify(allowedChain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("vlm_콜백_본문_4MB_초과_시_413_역직렬화_전_조기거부")
    void vlmBodyOverCap_returns413() throws Exception {
        byte[] big = new byte[(int) HmacWebhookFilter.MAX_VLM_BODY_BYTES + 1];
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/vlm/callback");
        req.setContent(big);
        req.setContentType("application/json");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("chunked_무_Content_Length_대용량_본문은_역직렬화_전_거부")
    void vlmChunkedNoContentLength_rejectedBeforeDeserialize() throws Exception {
        byte[] big = new byte[(int) HmacWebhookFilter.MAX_VLM_BODY_BYTES + 1];
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/vlm/callback") {
            @Override
            public long getContentLengthLong() { return -1L; }
            @Override
            public int getContentLength() { return -1; }
        };
        req.setContent(big);
        req.setContentType("application/json");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(411);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("vlm_콜백_실제_스트림은_상한까지만_읽고_중단_bounded_read")
    void vlmBoundedRead_stopsAtCap() throws Exception {
        long cap = HmacWebhookFilter.MAX_VLM_BODY_BYTES;
        int streamLen = (int) (cap * 8);
        final int[] bytesRead = {0};
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/vlm/callback") {
            @Override
            public long getContentLengthLong() { return 10L; } // 위조: 작게 신고
            @Override
            public int getContentLength() { return 10; }
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() {
                return new jakarta.servlet.ServletInputStream() {
                    private int pos = 0;
                    @Override public boolean isFinished() { return pos >= streamLen; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(jakarta.servlet.ReadListener l) { }
                    @Override public int read() {
                        if (pos >= streamLen) return -1;
                        pos++;
                        bytesRead[0]++;
                        return 'a';
                    }
                    @Override public int read(byte[] b, int off, int len) {
                        if (pos >= streamLen) return -1;
                        int n = Math.min(len, streamLen - pos);
                        java.util.Arrays.fill(b, off, off + n, (byte) 'a');
                        pos += n;
                        bytesRead[0] += n;
                        return n;
                    }
                };
            }
        };
        req.setContentType("application/json");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(any(), any());
        assertThat((long) bytesRead[0])
                .as("bounded read 는 상한 초과 즉시 중단해야 한다 (전량 버퍼 금지)")
                .isLessThanOrEqualTo(cap + 64 * 1024);
    }

    @Test
    @DisplayName("vlm_콜백_Content_Length_위조해도_스트림_상한_초과시_413")
    void vlmBodySpoofedContentLength_returns413() throws Exception {
        byte[] big = new byte[(int) HmacWebhookFilter.MAX_VLM_BODY_BYTES + 1];
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/vlm/callback") {
            @Override
            public long getContentLengthLong() { return 10L; }
        };
        req.setContent(big);
        req.setContentType("application/json");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("vlm_콜백_정상_크기_본문은_서명없이_통과")
    void vlmNormalBody_passesThrough() throws Exception {
        MockHttpServletRequest req = postRequest("/v1/vlm/callback",
                "{\"request_id\":\"REQ-1\",\"status\":\"completed\",\"results\":[]}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isNotEqualTo(413);
        assertThat(res.getStatus()).isNotEqualTo(401);
        verify(chain, times(1)).doFilter(any(), any());
    }

    // ─── 메트릭 카디널리티 (M-2) ─────────────────────────────────

    @Test
    @DisplayName("인증실패_메트릭_path_태그는_경로별로_증식하지_않고_저카디널리티_상수를_쓴다")
    void authFailureMetric_usesLowCardinalityPathTag() throws Exception {
        // 하류(서비스)가 미발급 request_id 를 401 로 거부하는 상황 — 이 실패가 메트릭에 집계된다.
        FilterChain chain = (rq, rs) ->
                ((MockHttpServletResponse) rs).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // 공격자는 하위 경로를 자유롭게 바꿀 수 있다 — 원시 URI 를 태그로 쓰면 Meter 가 무한 증식한다.
        for (int i = 0; i < 50; i++) {
            MockHttpServletRequest req = postRequest("/v1/genai/AAAA" + String.format("%04d", i), "{}");
            req.setRemoteAddr("198.18.1." + (i % 200));
            filter.doFilter(req, new MockHttpServletResponse(), chain);
        }

        long distinctPathTags = meterRegistry.find(HmacWebhookFilter.METRIC_AUTH_FAILED).counters()
                .stream()
                .map(c -> c.getId().getTag("path"))
                .distinct()
                .count();

        assertThat(distinctPathTags)
                .as("경로마다 Meter 가 생기면 힙 증가 + /actuator/prometheus 폭증 (CWE-770)")
                .isEqualTo(1);
        assertThat(meterRegistry.find(HmacWebhookFilter.METRIC_AUTH_FAILED).counters()
                .iterator().next().getId().getTag("path"))
                .isEqualTo(WebhookProtectedPaths.TAG_GENAI);
    }

    // ─── 공유 카운터 해제 (M-4) ──────────────────────────────────

    @Test
    @DisplayName("인증_성공시_공유_실패카운터도_해제된다")
    void successResetsSharedFailureCounter() throws Exception {
        RecordingRateLimitStore shared = new RecordingRateLimitStore();
        rateLimiter = new WebhookRateLimiter(shared);
        filter = newFilter(SECRET_AUGMENT, "", "");

        String body = "{\"k\":\"reset\"}";
        long ts = System.currentTimeMillis();
        String sig = "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts + "." + body);
        MockHttpServletRequest req = signedRequest(body, ts, sig);
        req.setRemoteAddr("198.18.2.1");

        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(shared.resetIps)
                .as("공유 카운터가 남으면 유효 서명 콜백이 와도 그 분 동안 2노드 전체 429 (M-4)")
                .contains("198.18.2.1");
    }

    // ─── 2노드 공유 집계 (A-ISSUE-14 / DEV_FIX N-1) ──────────────

    /**
     * <b>N-1 회귀 고정</b> — 구 테스트({@code sharedFailureCounter_writtenOnlyAfterLocalThreshold})는
     * "공유 쓰기가 발생했다"({@code recordCalls.isPositive()})만 단정해, 정작 기대효과인 <b>"다른 노드도
     * 차단된다"</b>를 검증하지 않았다. 그래서 공유 카운터가 한 창에 +1 까지만 올라가
     * {@code shared >= 5} 판정이 구조적으로 도달 불가한 상태를 GREEN 으로 통과시켰다(위양성).
     *
     * <p>여기서는 {@link WebhookRateLimiter} <b>인스턴스 2개가 같은 store 를 공유</b>하게 두고,
     * 노드 A 에서 차단된 IP 가 노드 B 에서도 즉시 차단되는지를 단정한다.
     */
    @Test
    @DisplayName("노드A에서_차단된_IP는_공유집계를_통해_노드B에서도_즉시_차단된다")
    void sharedFailureCounter_blockPropagatesToOtherNode() throws Exception {
        RecordingRateLimitStore shared = new RecordingRateLimitStore();
        WebhookRateLimiter nodeA = new WebhookRateLimiter(shared);
        WebhookRateLimiter nodeB = new WebhookRateLimiter(shared);
        HmacWebhookFilter filterA = newFilter(SECRET_AUGMENT, "", "", new MockEnvironment(), nodeA);
        HmacWebhookFilter filterB = newFilter(SECRET_AUGMENT, "", "", new MockEnvironment(), nodeB);

        String attackerIp = "198.18.4.1";
        long ts = System.currentTimeMillis();
        for (int i = 0; i < WebhookRateLimiter.RATE_LIMIT_FAILURES_PER_MINUTE; i++) {
            MockHttpServletRequest req = hmacRequest("{\"k\":1}");
            req.setRemoteAddr(attackerIp);
            req.addHeader("X-Timestamp", String.valueOf(ts));
            req.addHeader("X-Signature", "hmac-sha256=wrong" + i);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filterA.doFilter(req, res, mock(FilterChain.class));
            assertThat(res.getStatus()).as("%d 회차는 아직 401", i + 1).isEqualTo(401);
        }

        // 노드 A 는 로컬 카운터로 차단
        MockHttpServletRequest onA = hmacRequest("{\"k\":1}");
        onA.setRemoteAddr(attackerIp);
        onA.addHeader("X-Timestamp", String.valueOf(ts));
        onA.addHeader("X-Signature", "hmac-sha256=wrong-a");
        MockHttpServletResponse resA = new MockHttpServletResponse();
        filterA.doFilter(onA, resA, mock(FilterChain.class));
        assertThat(resA.getStatus()).isEqualTo(429);

        // 노드 B 는 이 IP 의 실패를 한 번도 본 적이 없다 — 공유 집계로만 차단을 승계해야 한다.
        assertThat(nodeB.localTrackerCount())
                .as("사전조건: 노드 B 로컬 카운터는 비어 있어야 한다")
                .isZero();
        MockHttpServletRequest onB = hmacRequest("{\"k\":1}");
        onB.setRemoteAddr(attackerIp);
        onB.addHeader("X-Timestamp", String.valueOf(ts));
        onB.addHeader("X-Signature", "hmac-sha256=wrong-b");
        MockHttpServletResponse resB = new MockHttpServletResponse();
        FilterChain chainB = mock(FilterChain.class);
        filterB.doFilter(onB, resB, chainB);

        assertThat(resB.getStatus())
                .as("노드 B 가 자기 로컬 임계를 새로 허용하면 2노드 합산 허용치가 임계의 2배가 된다"
                        + " (A-ISSUE-14 원 재현: 10회 통과)")
                .isEqualTo(429);
        verify(chainB, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("공유_카운터는_차단_전까지_매_실패마다_기록되고_차단_이후에는_더_쓰지_않는다")
    void sharedFailureCounter_writtenUntilBlockedThenStops() throws Exception {
        RecordingRateLimitStore shared = new RecordingRateLimitStore();
        rateLimiter = new WebhookRateLimiter(shared);
        filter = newFilter(SECRET_AUGMENT, "", "");

        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis();
        for (int i = 0; i < 12; i++) {
            MockHttpServletRequest req = hmacRequest(body);
            req.setRemoteAddr("198.18.3.1");
            req.addHeader("X-Timestamp", String.valueOf(ts));
            req.addHeader("X-Signature", "hmac-sha256=wrong" + i);
            filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        }

        assertThat(shared.recordCalls.get())
                .as("차단에 이르기까지의 실패는 전부 공유에 기록돼야 전역 판정이 성립한다 (N-1)")
                .isEqualTo(WebhookRateLimiter.RATE_LIMIT_FAILURES_PER_MINUTE);
        assertThat(shared.currentFailures("198.18.3.1", java.time.LocalDateTime.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES)))
                .as("차단 이후 요청은 공유 쓰기를 유발하지 않는다 — pre-auth write 상한 (H-5)")
                .isEqualTo(WebhookRateLimiter.RATE_LIMIT_FAILURES_PER_MINUTE);
    }


    // ─── 생성형 AI 무서명 가드 (Phase 7-A2) ─────────────────────

    @Test
    @DisplayName("allowlist_밖_IP_의_웹훅은_403")
    void genAiCallbackFromDisallowedIp_returns403() throws Exception {
        filter = newFilterWithGenAiAllowlist("203.0.113.0/24");
        MockHttpServletRequest req = postRequest("/v1/genai/callback", "{}");
        req.setRemoteAddr("198.51.100.7");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        assertThat(shouldNotFilter(req))
                .as("genai 콜백은 무서명이라도 반드시 가드 필터를 거쳐야 한다")
                .isFalse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("allowlist_밖_IP_의_403_이_rate_limit_에_집계된다")
    void genAiCallbackFromDisallowedIp_isCountedInRateLimit() throws Exception {
        // DEV_FIX MEDIUM-2 — 이 경로만 recordFailure 를 호출하지 않아, 비허용 IP 가 초당 수천 회를
        // 던져도 backoff 가 걸리지 않았다(411/413/downstream 401 은 전부 집계 → 비대칭).
        filter = newFilterWithGenAiAllowlist("203.0.113.0/24");
        String attackerIp = "198.51.100.7";

        for (int i = 0; i < WebhookRateLimiter.RATE_LIMIT_FAILURES_PER_MINUTE; i++) {
            MockHttpServletRequest req = postRequest("/v1/genai/callback", "{}");
            req.setRemoteAddr(attackerIp);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, mock(FilterChain.class));
            assertThat(res.getStatus()).isEqualTo(403);
        }

        // 상한 초과 시점부터는 backoff(429)로 전환된다 — 403 무한 반복이 아니다.
        MockHttpServletRequest over = postRequest("/v1/genai/callback", "{}");
        over.setRemoteAddr(attackerIp);
        MockHttpServletResponse overRes = new MockHttpServletResponse();
        filter.doFilter(over, overRes, mock(FilterChain.class));

        assertThat(rateLimiter.isLimited(attackerIp))
                .as("allowlist 밖 403 은 인증 실패로 집계돼야 한다")
                .isTrue();
        assertThat(overRes.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("genai_allowlist_미설정이면_전면_차단되어_403")
    void genAiCallbackWithUnsetAllowlist_returns403() throws Exception {
        filter = newFilterWithGenAiAllowlist("");
        MockHttpServletRequest req = postRequest("/v1/genai/callback", "{}");
        req.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus())
                .as("VLM 과 달리 미설정은 전면 허용이 아니라 전면 차단이어야 한다")
                .isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("본문_크기_상한_초과시_거부된다_genai_1MB")
    void genAiBodyOverLimit_returns413() throws Exception {
        filter = newFilterWithGenAiAllowlist("0.0.0.0/0");
        byte[] big = new byte[(int) HmacWebhookFilter.MAX_GENAI_BODY_BYTES + 1];
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/genai/callback");
        req.setContentType("application/json");
        req.setContent(big);
        req.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("genai_상한은_VLM_4MB_보다_좁다_무인증_버퍼링_최소화")
    void genAiCapIsTighterThanVlm() {
        assertThat(HmacWebhookFilter.MAX_GENAI_BODY_BYTES)
                .isLessThan(HmacWebhookFilter.MAX_VLM_BODY_BYTES);
    }

    @Test
    @DisplayName("genai_정상_요청은_서명없이_통과한다_무서명_계약_회귀고정")
    void genAiCallbackPassesWithoutSignature() throws Exception {
        filter = newFilterWithGenAiAllowlist("0.0.0.0/0");
        MockHttpServletRequest req = postRequest("/v1/genai/callback", "{\"request_id\":\"x\"}");
        req.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    // ===== helpers =====

    /** {@code shouldNotFilter} 는 protected — 리플렉션으로 직접 단정한다(위양성 방지용 negative 단정). */
    private boolean shouldNotFilter(HttpServletRequest request) {
        try {
            java.lang.reflect.Method m = org.springframework.web.filter.OncePerRequestFilter.class
                    .getDeclaredMethod("shouldNotFilter", HttpServletRequest.class);
            m.setAccessible(true);
            return (boolean) m.invoke(filter, request);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private MockHttpServletRequest signedRequest(String body, long ts, String signature) {
        MockHttpServletRequest req = hmacRequest(body);
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", signature);
        return req;
    }

    /**
     * <b>서명 필수</b> 경로 요청 — 경로 판정이 불가능한 요청을 만든다.
     *
     * <p>Phase 7-A2 에서 유일한 HMAC 경로({@code /v1/aug/**})가 제거되어 서명 필수 <b>등록</b>
     * 경로는 없다. 그러나 {@code WebhookProtectedPaths} 는 경로 판정 실패를 <b>서명 요구</b>로
     * 고정하므로(S-13 fail-closed), URI 파싱이 깨지는 요청은 여전히 HMAC 분기를 탄다. 이 분기가
     * 살아 있어야 "정체불명 요청이 무인증으로 통과" 하는 창이 열리지 않으므로 계속 검증한다.
     */
    private MockHttpServletRequest hmacRequest(String body) {
        MockHttpServletRequest req = unresolvablePathRequest();
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        req.setContentType("application/json");
        return req;
    }

    /** 경로 판정이 불가능한 요청 — 서명 필수(fail-closed) 분기로 들어간다. */
    private MockHttpServletRequest unresolvablePathRequest() {
        return new MockHttpServletRequest("POST", "/v1/unresolvable/callback") {
            @Override
            public String getRequestURI() {
                throw new IllegalStateException("URI 파싱 실패 시뮬 (fail-closed 분기 진입)");
            }
        };
    }

    private MockHttpServletRequest postRequest(String uri, String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        req.setContentType("application/json");
        return req;
    }

    private static String hmacHex(String secret, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(raw);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }

    /** 인메모리 nonce 저장소 — 호출 횟수·저장 경로를 기록해 pre-auth write / 키 산출을 단정한다. */
    private static final class RecordingNonceStore implements WebhookNonceStore {
        private final Set<String> consumed = ConcurrentHashMap.newKeySet();
        private final AtomicInteger consumeCalls = new AtomicInteger();
        private volatile String lastPath;
        private boolean failing = false;

        @Override
        public boolean consume(String signatureHash, String path, Duration ttl) {
            consumeCalls.incrementAndGet();
            lastPath = path;
            if (failing) {
                throw new WebhookGuardUnavailableException("시뮬 장애", new IllegalStateException());
            }
            return consumed.add(signatureHash);
        }

        @Override
        public void release(String signatureHash) {
            consumed.remove(signatureHash);
        }
    }

    /**
     * 공유 rate-limit 저장소 스텁 — 공유 쓰기/해제 호출을 기록해 pre-auth write·연좌 해제를 단정한다.
     *
     * <p><b>버킷 키를 반영한다 (REDESIGN R-7)</b>: 구 스텁은 {@code windowStart} 를 <b>무시</b>하고
     * IP 만으로 집계해, 프로덕션의 "현재/직전 버킷" 로직이 스텁 위에서 <b>구조적으로 검증 불가</b>였다.
     * 실제 저장소({@code LS_WHK_FAIL_NMTM}) 의 PK 가 (IP, 버킷) 이므로 스텁도 동일하게 키를 잡는다.
     */
    static final class RecordingRateLimitStore implements WebhookRateLimitStore {
        private final Map<String, Integer> counters = new ConcurrentHashMap<>();
        private final AtomicInteger recordCalls = new AtomicInteger();
        private final Set<String> resetIps = ConcurrentHashMap.newKeySet();

        private static String key(String clientIp, LocalDateTime windowStart) {
            return clientIp + "@" + windowStart;
        }

        /** 특정 버킷에 실패 횟수를 미리 심는다(다른 노드가 기록한 상태 시뮬). */
        void seed(String clientIp, LocalDateTime windowStart, int failures) {
            counters.put(key(clientIp, windowStart), failures);
        }

        /** 저장된 모든 버킷 제거(버킷 만료·purge 시뮬). */
        void clearAll() {
            counters.clear();
        }

        @Override
        public int recordFailure(String clientIp, LocalDateTime windowStart, Duration ttl) {
            recordCalls.incrementAndGet();
            return counters.merge(key(clientIp, windowStart), 1, Integer::sum);
        }

        @Override
        public int currentFailures(String clientIp, LocalDateTime windowStart) {
            return counters.getOrDefault(key(clientIp, windowStart), 0);
        }

        @Override
        public void resetFailures(String clientIp, LocalDateTime windowStart) {
            resetIps.add(clientIp);
            counters.remove(key(clientIp, windowStart));
        }
    }

    /** 체인에 전달된 요청 객체를 캡처한다(증거 래퍼 단정용). */
    private static final class CapturingChain implements FilterChain {
        private jakarta.servlet.ServletRequest captured;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) {
            this.captured = request;
        }
    }
}
