package kr.co.cudo.authoring.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
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
 * HmacWebhookFilter 단위 테스트 — 증강 콜백(/v1/aug/callback) HMAC 검증.
 *
 * <p>VLM 콜백(/v1/vlm/callback)은 벤더 v2.0.1 무서명 규격 → HMAC 대상에서 제거(2026-07-07 승인).
 * 따라서 VLM 경로는 서명 없이도 필터를 통과(shouldNotFilter)해야 한다.
 *
 * <p>시나리오 방어 검증(증강 경로):
 *  - 시그니처/timestamp 헤더 없으면 401
 *  - replay(timestamp 윈도우 밖) 401
 *  - fail-closed: 시크릿 미설정 시 401
 *  - 정상: 올바른 시그니처는 통과 (FilterChain.doFilter 호출)
 */
class HmacWebhookFilterTest {

    private static final String SECRET_AUGMENT = "augment-secret-32bytes-min-len-bbb!";

    private ObjectMapper objectMapper;
    private HmacWebhookFilter filter;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        filter = new HmacWebhookFilter(objectMapper, SECRET_AUGMENT, 300L);
    }

    @Test
    @DisplayName("HMAC_서명_없이도_vlm_콜백_수신됨")
    void vlmPath_passesThroughWithoutHmac() throws Exception {
        // VLM 경로는 HMAC 대상에서 제거 — 서명 헤더 없이도 다음 체인으로 통과해야 함
        MockHttpServletRequest req = postRequest("/v1/vlm/callback",
                "{\"request_id\":\"REQ-1\",\"status\":\"completed\",\"results\":[]}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isNotEqualTo(401);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("POST_v1_augments_result_HMAC_헤더_없으면_401")
    void augmentMissingHmacHeader_returns401() throws Exception {
        MockHttpServletRequest req = postRequest("/v1/aug/callback", "{\"x\":1}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).isEqualTo("HMAC");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("POST_v1_augments_result_HMAC_시그니처_틀리면_401")
    void augmentInvalidSignature_returns401() throws Exception {
        String body = "{\"idempotencyKey\":\"K1\"}";
        long ts = System.currentTimeMillis();
        MockHttpServletRequest req = postRequest("/v1/aug/callback", body);
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=deadbeefnotmatching");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("POST_v1_augments_result_replay_시간초과_시_401")
    void augmentReplayTimestampExceeded_returns401() throws Exception {
        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis() - 10 * 60 * 1000L; // 10분 전
        String sig = hmacHex(SECRET_AUGMENT, ts + "." + body);
        MockHttpServletRequest req = postRequest("/v1/aug/callback", body);
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
        MockHttpServletRequest req = postRequest("/v1/aug/callback", body);
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=" + sig);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isNotEqualTo(401);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    @DisplayName("시크릿_미설정_경로는_fail_closed_401")
    void missingSecret_failsClosed() throws Exception {
        HmacWebhookFilter noSecretFilter = new HmacWebhookFilter(objectMapper, "", 300L);
        String body = "{}";
        long ts = System.currentTimeMillis();
        MockHttpServletRequest req = postRequest("/v1/aug/callback", body);
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
        byte[] big = new byte[(int) HmacWebhookFilter.MAX_WEBHOOK_BODY_BYTES + 1];
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/aug/callback");
        req.setContent(big);
        req.setContentType("application/json");
        long ts = System.currentTimeMillis();
        req.addHeader("X-Timestamp", String.valueOf(ts));
        req.addHeader("X-Signature", "hmac-sha256=" + hmacHex(SECRET_AUGMENT, ts + "." + new String(big, StandardCharsets.UTF_8)));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(any(), any());
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
        // 재현: Transfer-Encoding chunked → Content-Length 없음(getContentLengthLong == -1).
        // 과거엔 -1 이 size-cap 을 우회해 전체 스트림을 힙에 버퍼링(OOM) 했다.
        // 이제는 역직렬화(본문 버퍼링) 이전에 411(Length Required)로 조기 거부해야 한다.
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
        // Content-Length 를 작게 위조하고 실제 스트림은 상한의 8배(32MB)를 흘려도,
        // bounded read 가 상한 초과 즉시 중단해야 한다(전량 버퍼 금지 → OOM 방지).
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
        // 상한 + 한 청크(여유) 이내에서 읽기를 멈춰야 함 — 전량(32MB) 소비 금지.
        assertThat((long) bytesRead[0])
                .as("bounded read 는 상한 초과 즉시 중단해야 한다 (전량 버퍼 금지)")
                .isLessThanOrEqualTo(cap + 64 * 1024);
    }

    @Test
    @DisplayName("vlm_콜백_Content_Length_위조해도_스트림_상한_초과시_413")
    void vlmBodySpoofedContentLength_returns413() throws Exception {
        byte[] big = new byte[(int) HmacWebhookFilter.MAX_VLM_BODY_BYTES + 1];
        // Content-Length 를 작게 위조해도 실제 스트림 길이로 캡되어야 함
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

    @Test
    @DisplayName("HmacWebhookFilter_Content_Length_누락_시_401")
    void missingContentLength_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/aug/callback") {
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

    @Test
    @DisplayName("HmacWebhookFilter_시그니처_실패_분당_5회_초과_시_60초_backoff")
    void rateLimit_after5Failures() throws Exception {
        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis();
        FilterChain chain = mock(FilterChain.class);
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = postRequest("/v1/aug/callback", body);
            req.setRemoteAddr("10.0.0.99");
            req.addHeader("X-Timestamp", String.valueOf(ts));
            req.addHeader("X-Signature", "hmac-sha256=deadbeefwronghex" + i);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertThat(res.getStatus()).isEqualTo(401);
        }
        MockHttpServletRequest req6 = postRequest("/v1/aug/callback", body);
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
        assertThatThrownBy(() -> new HmacWebhookFilter(objectMapper, "short", 300L))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("M-1");
    }

    @Test
    @DisplayName("HmacWebhookFilter_FailureTracker_4096_초과_시_hard_cap_적용")
    void failureTrackers_hardCap() throws Exception {
        String body = "{\"k\":1}";
        long ts = System.currentTimeMillis();
        FilterChain chain = mock(FilterChain.class);

        int totalIps = HmacWebhookFilter.MAX_FAILURE_TRACKERS + 1;
        for (int i = 0; i < totalIps; i++) {
            MockHttpServletRequest req = postRequest("/v1/aug/callback", body);
            req.setRemoteAddr("10.99." + (i / 256) + "." + (i % 256));
            req.addHeader("X-Timestamp", String.valueOf(ts));
            req.addHeader("X-Signature", "hmac-sha256=wronghex" + i);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
        }

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
