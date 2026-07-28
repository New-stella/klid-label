package kr.co.cudo.authoring.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.security.webhook.WebhookGateInterceptor;
import kr.co.cudo.authoring.common.security.webhook.WebhookGuardedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.HttpServletRequestWrapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이중 게이트 2단 검증 — 컨트롤러 진입 직전 fail-closed (S-18).
 *
 * <p>경로 정규화 규칙이 어긋나 필터를 우회한 요청이 라우팅되더라도, "필터를 통과했다는 증거"
 * ({@link WebhookGuardedRequest} 래퍼)가 없으면 컨트롤러에 닿기 전에 401 이어야 한다.
 */
class WebhookGateInterceptorTest {

    private final WebhookGateInterceptor interceptor = new WebhookGateInterceptor(new ObjectMapper());

    @Test
    @DisplayName("필터_증거_래퍼가_없는_요청은_401_컨트롤러_미도달")
    void withoutMarker_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/genai/callback");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("HMAC");
    }

    @Test
    @DisplayName("서명검증_통과_래퍼면_증강_콜백_허용")
    void withVerifiedMarker_proceeds() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/genai/callback");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(new GuardedWrapper(request, true), response, new Object());

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("ASYNC_재디스패치는_최초_디스패치에서_이미_게이트를_통과했으므로_401_이_아님")
    void asyncDispatch_isNotBlocked() throws Exception {
        // 컨트롤러가 startAsync() 를 쓰면 ASYNC 재디스패치에서 증거 래퍼가 소실될 수 있다.
        // 필터는 shouldNotFilterAsyncDispatch()=true 라 재실행되지 않으므로, 여기서 막으면
        // **정상 콜백이 401** 이 된다(DEV_FIX L-2). 디스패치 타입은 컨테이너가 정하며 위조 불가.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/genai/callback");
        request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("REQUEST_디스패치는_증거_래퍼가_없으면_여전히_401_ASYNC_예외가_우회로가_아님")
    void requestDispatchWithoutMarker_stillBlocked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/genai/callback");
        request.setDispatcherType(jakarta.servlet.DispatcherType.REQUEST);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("서명필수_판정_경로에_size_cap_전용_래퍼만_있으면_401")
    void signatureRequiredPathWithUnverifiedMarker_returns401() throws Exception {
        // Phase 7-A2 — 등록된 서명 필수 경로는 없지만, 경로 판정 불가는 여전히 "서명 요구"다
        // (fail-closed). 이때 서명 미검증 래퍼로는 통과할 수 없어야 한다.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/genai/callback") {
            @Override
            public String getRequestURI() {
                throw new IllegalStateException("URI 파싱 실패 시뮬");
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(new GuardedWrapper(request, false), response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("VLM_은_서명검증_없이_size_cap_래퍼만_있어도_허용_벤더계약")
    void vlmPathWithGuardOnlyMarker_proceeds() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/vlm/callback");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(new GuardedWrapper(request, false), response, new Object());

        assertThat(proceed).isTrue();
    }

    @Test
    @DisplayName("VLM_경로도_증거_래퍼가_없으면_401_size_cap_우회_차단")
    void vlmPathWithoutMarker_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/vlm/callback");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    /** 필터가 감싸는 증거 래퍼 대역. */
    private static final class GuardedWrapper extends HttpServletRequestWrapper
            implements WebhookGuardedRequest {
        private final boolean verified;

        GuardedWrapper(MockHttpServletRequest request, boolean verified) {
            super(request);
            this.verified = verified;
        }

        @Override
        public boolean isSignatureVerified() {
            return verified;
        }
    }
}
