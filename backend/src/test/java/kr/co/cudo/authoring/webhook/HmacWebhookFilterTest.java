package kr.co.cudo.authoring.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.cudo.authoring.common.security.HmacWebhookFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 2 — HmacWebhookFilter 단위 테스트.
 *
 * <p>시나리오 방어 검증:
 *  - S-1 (HMAC 없이 호출): 시그니처/timestamp 헤더 없으면 401
 *  - S-6 (replay): timestamp 윈도우 밖이면 401
 *  - fail-closed: 시크릿 미설정 시 401
 *  - 정상: 올바른 시그니처는 통과 (FilterChain.doFilter 호출)
 *  - 시그니처 불일치 401
 */
class HmacWebhookFilterTest {

    private static final String SECRET_VLM = "vlm-secret-32bytes-min-len-aaaaaaa!";
    private static final String SECRET_AUGMENT = "augment-secret-32bytes-min-len-bbb!";

    private ObjectMapper objectMapper;
    private HmacWebhookFilter filter;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        filter = new HmacWebhookFilter(objectMapper, SECRET_VLM, SECRET_AUGMENT, 300L);
    }

    @Test
    @DisplayName("POST_v1_vlm_result_HMAC_헤더_없으면_401")
    void vlmMissingHmacHeader_returns401() throws Exception {
        MockHttpServletRequest req = postRequest("/v1/vlm/result", "{\"x\":1}");
        // 헤더 미설정
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).isEqualTo("HMAC");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("POST_v1_vlm_result_HMAC_시그니처_틀리면_401")
    void vlmInvalidSignature_returns401() throws Exception {
        String body = "{\"idempotencyKey\":\"K1\"}";
        long ts = System.currentTimeMillis();
        MockHttpServletRequest req = postRequest("/v1/vlm/result", body);
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=deadbeefnotmatching");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("POST_v1_vlm_result_replay_시간초과_시_401")
    void vlmReplayTimestampExceeded_returns401() throws Exception {
        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis() - 10 * 60 * 1000L; // 10분 전
        String sig = hmacHex(SECRET_VLM, ts + "." + body);
        MockHttpServletRequest req = postRequest("/v1/vlm/result", body);
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
        MockHttpServletRequest req = postRequest("/v1/augments/result", body);
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=" + sig);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        // 401이 아니어야 함 (정상 통과)
        assertThat(res.getStatus()).isNotEqualTo(401);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("시크릿_미설정_경로는_fail_closed_401")
    void missingSecret_failsClosed() throws Exception {
        // 모든 시크릿 빈 문자열로 새 필터 생성
        HmacWebhookFilter noSecretFilter = new HmacWebhookFilter(objectMapper, "", "", 300L);
        String body = "{}";
        long ts = System.currentTimeMillis();
        MockHttpServletRequest req = postRequest("/v1/vlm/result", body);
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=anyvalue");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        noSecretFilter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("HmacWebhookFilter_본문_1MB_초과_시_413")
    void bodyOver1MB_returns413() throws Exception {
        // DEV_FIX H-4: 1MB 초과 본문은 413 Payload Too Large
        byte[] big = new byte[(int) HmacWebhookFilter.MAX_WEBHOOK_BODY_BYTES + 1];
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/vlm/result");
        req.setContent(big);
        req.setContentType("application/json");
        long ts = System.currentTimeMillis();
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=" + hmacHex(SECRET_VLM, ts + "." + new String(big, StandardCharsets.UTF_8)));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("HmacWebhookFilter_Content_Length_누락_시_401")
    void missingContentLength_returns401() throws Exception {
        // DEV_FIX H-4: Content-Length 누락(-1) 은 401
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/vlm/result") {
            @Override
            public long getContentLengthLong() { return -1L; }
            @Override
            public int getContentLength() { return -1; }
        };
        req.setContent("{}".getBytes(StandardCharsets.UTF_8));
        req.setContentType("application/json");
        long ts = System.currentTimeMillis();
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=" + hmacHex(SECRET_VLM, ts + ".{}"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("HmacWebhookFilter_시그니처_실패_분당_5회_초과_시_60초_backoff")
    void rateLimit_after5Failures() throws Exception {
        // DEV_FIX H-4: 동일 IP 에서 인증 실패 5회 누적 시 6번째 호출은 429
        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis();
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = postRequest("/v1/vlm/result", body);
            req.setRemoteAddr("10.0.0.99");
            req.addHeader("X-Timestamp", String.valueOf(ts));
            req.addHeader("X-Signature", "hmac-sha256=deadbeefwronghex" + i);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).isEqualTo(401);
        }
        // 6번째 호출 — rate limit 적용으로 429
        MockHttpServletRequest req6 = postRequest("/v1/vlm/result", body);
        req6.setRemoteAddr("10.0.0.99");
        req6.addHeader("X-Timestamp", String.valueOf(ts));
        req6.addHeader("X-Signature", "hmac-sha256=anyhex");
        MockHttpServletResponse res6 = new MockHttpServletResponse();
        filter.doFilter(req6, res6, chain);

        assertThat(res6.getStatus()).isEqualTo(429);
        assertThat(res6.getHeader("Retry-After")).isNotNull();
    }

    @Test
    @DisplayName("HmacWebhookFilter_시크릿_32바이트_미만_시_부팅_실패")
    void shortSecret_failsBoot() {
        // DEV_FIX M-1: 32B 미만 시크릿은 BeanInitializationException
        assertThatThrownBy(() -> new HmacWebhookFilter(objectMapper, "short", "augment-secret-32bytes-min-len-bbb!", 300L))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("M-1");
    }

    @Test
    @DisplayName("HmacWebhookFilter_FailureTracker_4096_초과_시_hard_cap_적용")
    void failureTrackers_hardCap() throws Exception {
        // DEV_FIX 2차 H-4 부분: 공격자가 매우 짧은 시간에 다수의 다른 IP 로 실패 요청을 보내면
        // 윈도우 만료 항목 정리만으로는 무한 증가 가능 → 4096 초과 시 가장 오래된 항목 강제 evict.
        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis();
        FilterChain chain = mock(FilterChain.class);

        // 4097 개의 서로 다른 IP 로 실패 요청 — 동일 윈도우 내라 만료 정리만으로는 제거 안 됨
        int totalIps = HmacWebhookFilter.MAX_FAILURE_TRACKERS + 1;
        for (int i = 0; i < totalIps; i++) {
            MockHttpServletRequest req = postRequest("/v1/vlm/result", body);
            req.setRemoteAddr("10.99." + (i / 256) + "." + (i % 256));
            req.addHeader("X-Timestamp", String.valueOf(ts));
            req.addHeader("X-Signature", "hmac-sha256=wronghex" + i);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
        }

        // failureTrackers 크기는 cap 이하여야 함
        Field f = HmacWebhookFilter.class.getDeclaredField("failureTrackers");
        f.setAccessible(true);
        java.util.Map<?, ?> trackers = (java.util.Map<?, ?>) f.get(filter);
        assertThat(trackers.size())
                .as("failureTrackers 는 MAX_FAILURE_TRACKERS(%d) 이하로 유지되어야 함",
                        HmacWebhookFilter.MAX_FAILURE_TRACKERS)
                .isLessThanOrEqualTo(HmacWebhookFilter.MAX_FAILURE_TRACKERS);
    }

    @Test
    @DisplayName("HmacWebhookFilter_관련_없는_경로는_통과(shouldNotFilter)")
    void unrelatedPath_skipsFilter() throws Exception {
        MockHttpServletRequest req = postRequest("/v1/manage/labels", "{}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        // HMAC 헤더 없어도 통과해야 함 — webhook 경로가 아니므로
        verify(chain, times(1)).doFilter(any(), any());
    }

    // ===== helpers =====

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
}
