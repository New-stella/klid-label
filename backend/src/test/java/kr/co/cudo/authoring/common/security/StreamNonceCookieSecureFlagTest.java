package kr.co.cudo.authoring.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * nonce 쿠키 {@code Secure} 판정 축 고정 — <b>배포 설정</b>이지 요청/헤더가 아니다.
 *
 * <h3>구 의도(DEV_FIX MEDIUM-2 ②)와 그것이 지금도 지켜지는 이유</h3>
 * <p>MEDIUM-2 ② 는 {@code .secure(request.isSecure())} 를 걷어낸 수정이었다. 운영은 TLS 종단 LB 뒤라
 * 앱이 받는 요청은 평문 HTTP 이고({@code request.isSecure() == false}) 그래서 운영 쿠키에 {@code Secure}
 * 가 영영 붙지 못했으며, 애초에 요청·헤더는 공격자 제어 축에 걸쳐 있어 판정 축으로 삼을 값이 아니다.
 * 같은 문제를 {@code server.forward-headers-strategy: framework} 로 풀려던 시도는 rate limit 을 헤더
 * 한 줄로 우회 가능하게 만드는 보안 회귀였고 되돌렸다(REDESIGN 2026-07-25).
 *
 * <p>그 수정의 <b>실질은 "요청에 의존하지 않는다"</b> 이며 이번 변경 후에도 그대로다. 바뀐 것은
 * 요청 대신 무엇을 보느냐다 — <b>프로파일 유추</b>({@code !localProfile})에서 <b>명시 설정 키</b>
 * ({@code authoring.stream.cookie-secure}, 기본 {@code false})로 옮겼다. 프로파일 유추는 "평문 HTTP
 * 개발 서버 = local 프로파일" 을 전제했는데 dev 프로파일로 도는 평문 HTTP 개발 서버가 실재해, 그
 * 서버에서는 브라우저가 {@code Secure} 쿠키를 저장하지 않아 nonce 가 동반되지 않았고 스트림 요청이
 * <b>전건 401</b>(재생 불가)이었다.
 *
 * <p>따라서 이 테스트가 고정하는 불변식은 둘이다.
 * <ol>
 *   <li>{@code Secure} 는 <b>설정값에만</b> 좌우된다 — 프로파일·{@code request.isSecure()}·
 *       {@code X-Forwarded-Proto} 어느 것도 결과를 바꾸지 못한다(MEDIUM-2 ②의 실질 유지).</li>
 *   <li>기본값은 <b>OFF</b> 이고, 켠 경우와 끈 경우 모두 나머지 속성
 *       ({@code HttpOnly}·{@code SameSite=Lax}·{@code Path}·{@code Max-Age})은 동일하다.</li>
 * </ol>
 *
 * @see StreamNonceCookie
 */
class StreamNonceCookieSecureFlagTest {

    private static final String SIGN_SECRET = "stream-sign-secret-for-nonce-seal-32b!!";

    /** {@code Secure} 외 나머지 쿠키 속성 — 토글과 무관하게 고정돼야 하는 부분. */
    private static void assertInvariantAttributes(String setCookie) {
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains(StreamNonceCookie.COOKIE_NAME + "=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).contains("Path=/api/v1/videos");
        assertThat(setCookie).contains("Max-Age=" + StreamNonceCookie.COOKIE_TTL.toSeconds());
    }

    private static String issueSetCookie(boolean cookieSecure, MockHttpServletRequest request) {
        StreamNonceCookie cookie = new StreamNonceCookie(SIGN_SECRET, cookieSecure);
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookie.resolveOrIssue(request, response, "1");
        return response.getHeader(HttpHeaders.SET_COOKIE);
    }

    /** TLS 종단 LB 뒤 = 앱이 보는 요청은 평문 HTTP, 프록시 헤더 반영도 비활성. */
    private static MockHttpServletRequest plainHttpRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(false);
        request.setContextPath("/api");
        return request;
    }

    @Test
    @DisplayName("cookie_secure_기본값_false_이면_nonce_쿠키에_Secure_가_붙지_않는다")
    void defaultOff_omitsSecure() {
        String setCookie = issueSetCookie(false, plainHttpRequest());

        assertThat(setCookie)
                .as("기본 OFF — 평문 HTTP 배포에서 쿠키가 버려져 스트림이 전건 401 이 되는 것을 막는다")
                .doesNotContain("Secure");
        assertInvariantAttributes(setCookie);
    }

    @Test
    @DisplayName("cookie_secure_true_이면_평문HTTP_요청이어도_nonce_쿠키에_Secure_가_붙는다")
    void toggledOn_setsSecureEvenOnPlainHttpRequest() {
        // given/when: request.isSecure()=false, X-Forwarded-Proto 없음 — 그래도 설정이 켜져 있으면 붙어야 한다
        String setCookie = issueSetCookie(true, plainHttpRequest());

        assertThat(setCookie)
                .as("HTTPS 배포에서 켜면 요청 상태와 무관하게 Secure 가 부여된다 (MEDIUM-2 ② 실질 유지)")
                .contains("Secure");
        assertInvariantAttributes(setCookie);
    }

    @Test
    @DisplayName("HTTPS_요청이어도_cookie_secure_가_false_면_Secure_가_붙지_않아_판정축이_설정임을_확인")
    void secureRequestDoesNotDrivePolicy() {
        // 요청(공격자 제어 가능 축)이 아니라 배포 설정이 판정 축임을 반대 방향으로도 고정한다.
        MockHttpServletRequest request = plainHttpRequest();
        request.setSecure(true);

        assertThat(issueSetCookie(false, request)).doesNotContain("Secure");
    }

    @Test
    @DisplayName("X_Forwarded_Proto_헤더는_Secure_판정에_영향을_주지_않는다")
    void forwardedProtoHeaderDoesNotDrivePolicy() {
        // forward-headers-strategy 는 되돌린 결정(REDESIGN 2026-07-25)이며, 헤더는 판정 입력이 아니다.
        MockHttpServletRequest offRequest = plainHttpRequest();
        offRequest.addHeader("X-Forwarded-Proto", "https");
        assertThat(issueSetCookie(false, offRequest))
                .as("헤더가 https 라고 주장해도 설정이 꺼져 있으면 Secure 는 붙지 않는다")
                .doesNotContain("Secure");

        MockHttpServletRequest onRequest = plainHttpRequest();
        onRequest.addHeader("X-Forwarded-Proto", "http");
        assertThat(issueSetCookie(true, onRequest))
                .as("헤더가 http 라고 주장해도 설정이 켜져 있으면 Secure 가 붙는다")
                .contains("Secure");
    }

    @Test
    @DisplayName("프로파일은_Secure_판정_입력이_아니다_생성자가_Environment_를_받지_않는다")
    void profileIsNotAnInputAtAll() {
        // 구 구현은 프로파일이 판정 축이라 local 이 아니면 무조건 Secure 였고, dev 로 도는 평문 HTTP
        // 개발 서버가 그 예외에서 빠져 스트림이 전건 401 이었다. 프로파일을 다시 판정 축으로 끌어오면
        // 그 결함이 재발하므로, 애초에 프로파일을 읽을 수단이 주입되지 않음을 구조로 고정한다.
        // (런타임 동작만 단언하면 "설정 && !local" 같은 혼합 판정이 슬쩍 들어와도 통과할 수 있다.)
        assertThat(StreamNonceCookie.class.getDeclaredConstructors())
                .as("StreamNonceCookie 는 Environment/Profiles 를 주입받지 않는다")
                .allSatisfy(constructor -> assertThat(constructor.getParameterTypes())
                        .noneMatch(type -> type.getName().startsWith("org.springframework.core.env.")));
    }
}
