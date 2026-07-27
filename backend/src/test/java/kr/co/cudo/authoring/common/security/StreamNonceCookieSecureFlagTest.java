package kr.co.cudo.authoring.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * nonce 쿠키 {@code Secure} 판정 축 고정 — <b>프로파일</b>이지 요청/헤더가 아니다 (DEV_FIX MEDIUM-2 ②).
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * <p>운영은 TLS 종단 LB 뒤라 앱이 받는 요청은 평문 HTTP 다({@code request.isSecure() == false}).
 * 구 구현({@code .secure(request.isSecure())})은 그래서 운영 쿠키에 {@code Secure} 를 영영 붙이지
 * 못했다. 이를 프로파일 기반({@code .secure(!localProfile)})으로 바꾼 것이 MEDIUM-2 ② 다.
 *
 * <p>같은 문제를 {@code server.forward-headers-strategy: framework} 로 풀려던 시도는
 * <b>rate limit 을 헤더 한 줄로 우회 가능하게 만드는 보안 회귀</b>였고 되돌렸다(REDESIGN 2026-07-25,
 * {@code application.yml} 주석 참조). 이 테스트는 그 되돌림 이후에도 MEDIUM-2 ② 가 <b>요청 헤더에
 * 의존하지 않고</b> 그대로 동작함을 고정한다 — 즉 {@code isSecure()=false} + {@code X-Forwarded-Proto}
 * 부재 상태에서도 non-local 프로파일이면 {@code Secure} 가 붙어야 한다.
 */
class StreamNonceCookieSecureFlagTest {

    private static final String SIGN_SECRET = "stream-sign-secret-for-nonce-seal-32b!!";

    private static String issueSetCookie(String activeProfile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfile);
        StreamNonceCookie cookie = new StreamNonceCookie(environment, SIGN_SECRET);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(false); // TLS 종단 LB 뒤 = 앱이 보는 요청은 평문 HTTP
        request.setContextPath("/api");
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookie.resolveOrIssue(request, response, "1");
        return response.getHeader(HttpHeaders.SET_COOKIE);
    }

    @Test
    @DisplayName("평문HTTP_요청이어도_prd_프로파일이면_nonce_쿠키에_Secure_가_붙는다")
    void nonLocalProfile_setsSecureEvenOnPlainHttpRequest() {
        // given/when: request.isSecure()=false, X-Forwarded-Proto 없음 (프록시 헤더 반영 비활성 상태)
        String setCookie = issueSetCookie("prd");

        // then: 판정 축이 프로파일이므로 헤더/요청 상태와 무관하게 Secure 가 부여된다
        assertThat(setCookie).isNotNull();
        assertThat(setCookie)
                .as("운영 프로파일은 평문 HTTP 요청에서도 Secure 를 붙여야 한다 (MEDIUM-2 ②)")
                .contains("Secure");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).contains(StreamNonceCookie.COOKIE_NAME + "=");
    }

    @Test
    @DisplayName("local_프로파일에서는_평문_개발서버_호환을_위해_Secure_가_붙지_않는다")
    void localProfile_omitsSecure() {
        String setCookie = issueSetCookie("local");

        assertThat(setCookie).isNotNull();
        assertThat(setCookie)
                .as("local 만 예외 — 평문 HTTP 개발 서버에서 쿠키가 버려지지 않게 한다")
                .doesNotContain("Secure");
        assertThat(setCookie).contains("HttpOnly");
    }

    @Test
    @DisplayName("HTTPS_요청이어도_local_프로파일이면_Secure_가_붙지_않아_판정축이_프로파일임을_확인")
    void secureRequestDoesNotDrivePolicy() {
        // 요청(공격자 제어 가능 축)이 아니라 배포 설정(프로파일)이 판정 축임을 반대 방향으로도 고정한다.
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        StreamNonceCookie cookie = new StreamNonceCookie(environment, SIGN_SECRET);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookie.resolveOrIssue(request, response, "1");

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).doesNotContain("Secure");
    }
}
