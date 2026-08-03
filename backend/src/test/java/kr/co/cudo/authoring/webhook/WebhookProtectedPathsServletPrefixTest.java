package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.common.security.webhook.WebhookProtectedPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3차 QA (CWE-436 잔여, fail-open) — 웹훅 경로 판정이 <b>서블릿 경로 접두</b>까지 MVC 와 정합한지 검증.
 *
 * <p>{@code spring.mvc.servlet.path=/api2} 형상에서 DispatcherServlet 은 {@code /api2} 하위에 매핑되고
 * MVC 는 그 접두를 제외한 경로({@code /v1/genai/callback})로 라우팅한다. 판정이 {@code contextPath}
 * 만 반영하는 자체 파싱을 쓰면 필터가 보는 경로는 {@code /api2/v1/genai/callback} 이라 보호 대상
 * allowlist 에 걸리지 않고 <b>MVC 만 라우팅</b>하는 fail-open 이 된다 — E-ISSUE-01(인증 우회)과
 * 동일 클래스의 재발이다.
 *
 * <p>현재 이 프로젝트는 {@code spring.mvc.servlet.path} 를 설정하지 않아 실위험이 없지만, 설정이
 * 추가되는 순간 조용히 열리는 구조였다. 본 테스트가 그 구조적 정합을 고정한다.
 */
class WebhookProtectedPathsServletPrefixTest {

    /** {@code spring.mvc.servlet.path=/api2} 를 흉내낸 요청 (requestURI 가 접두를 포함한다). */
    private static MockHttpServletRequest withServletPrefix(String appPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api2" + appPath);
        request.setServletPath("/api2");
        return request;
    }

    @Test
    @DisplayName("servlet_path_prefix_환경에서도_genai_웹훅은_보호대상으로_판정된다")
    void genAiProtectedUnderServletPrefix() {
        MockHttpServletRequest request = withServletPrefix(WebhookProtectedPaths.PATH_GENAI_CALLBACK);

        assertThat(WebhookProtectedPaths.isProtected(request)).isTrue();
        assertThat(WebhookProtectedPaths.requiresGuardOnly(request)).isTrue();
        assertThat(WebhookProtectedPaths.isGenAi(request)).isTrue();
        assertThat(WebhookProtectedPaths.metricTag(request)).isEqualTo(WebhookProtectedPaths.TAG_GENAI);
    }

    @Test
    @DisplayName("servlet_path_prefix_환경에서도_vlm_웹훅은_보호대상으로_판정된다")
    void vlmProtectedUnderServletPrefix() {
        MockHttpServletRequest request = withServletPrefix(WebhookProtectedPaths.PATH_VLM);

        assertThat(WebhookProtectedPaths.isProtected(request)).isTrue();
        assertThat(WebhookProtectedPaths.metricTag(request)).isEqualTo(WebhookProtectedPaths.TAG_VLM);
    }

    @Test
    @DisplayName("servlet_path_prefix_환경의_정규화_경로는_접두를_제외한_MVC_경로다")
    void canonicalPathExcludesServletPrefix() {
        MockHttpServletRequest request = withServletPrefix(WebhookProtectedPaths.PATH_GENAI_CALLBACK);

        // nonce 키로 쓰이는 값 — 접두가 섞이면 같은 엔드포인트가 서로 다른 키가 되어 replay 판정이 갈린다.
        assertThat(WebhookProtectedPaths.canonicalPath(request))
                .isEqualTo(WebhookProtectedPaths.PATH_GENAI_CALLBACK);
    }

    @Test
    @DisplayName("접두_설정이_없는_기본_형상의_판정은_기존과_동일하다")
    void unchangedWithoutServletPrefix() {
        MockHttpServletRequest plain =
                new MockHttpServletRequest("POST", WebhookProtectedPaths.PATH_GENAI_CALLBACK);

        assertThat(WebhookProtectedPaths.isProtected(plain)).isTrue();
        assertThat(WebhookProtectedPaths.canonicalPath(plain))
                .isEqualTo(WebhookProtectedPaths.PATH_GENAI_CALLBACK);

        MockHttpServletRequest unrelated = new MockHttpServletRequest("POST", "/v1/manage/labels");
        assertThat(WebhookProtectedPaths.isProtected(unrelated)).isFalse();
        assertThat(WebhookProtectedPaths.metricTag(unrelated)).isEqualTo(WebhookProtectedPaths.TAG_OTHER);
    }
}
