package kr.co.cudo.authoring.common.security;

import kr.co.cudo.authoring.sysconfig.controller.SystemConfigController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ LOW-7 — <b>CORS allowlist 에 인증·세션 헤더가 있어야 그 기능이 교차 출처에서 동작한다</b>.
 *
 * <p>{@code allowedHeaders} 에 없는 요청 헤더는 preflight 에서 거절된다. 교차 출처 형상
 * (FE 와 BE 가 다른 origin)에서 그 헤더 없이 저장을 시도하면 브라우저가 요청 자체를 보내지 않아
 * <b>기능이 조용히 깨진다</b> — 서버 로그에는 아무것도 남지 않으므로 원인 추적이 어렵다.
 *
 * <p>헤더명을 문자열로 중복 선언하지 않고 컨트롤러 상수를 참조해, 이름이 바뀌면 여기서 함께 깨지게 한다.
 */
class CorsAllowedHeadersTest {

    /** CORS 빈만 확인하므로 나머지 협력자는 필요 없다(이 빈은 그것들을 쓰지 않는다). */
    private final SecurityConfig config =
            new SecurityConfig(null, null, null, null, null, null, null, null, null, null, null);

    private CorsConfiguration corsConfig() {
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) config.corsConfigurationSource("https://fe.example");
        CorsConfiguration cors = source.getCorsConfigurations().get("/**");
        assertThat(cors).isNotNull();
        return cors;
    }

    @Test
    @DisplayName("★관리자_세션_헤더가_CORS_허용_목록에_있다 — 없으면_주소_저장이_preflight에서_막힌다")
    void adminSessionHeaderIsAllowed() {
        assertThat(corsConfig().getAllowedHeaders())
                .contains(SystemConfigController.ADMIN_SESSION_HEADER);
    }

    @Test
    @DisplayName("기존_허용_헤더는_그대로다 — 회귀_방지")
    void existingHeadersUnchanged() {
        assertThat(corsConfig().getAllowedHeaders())
                .contains("Authorization", "X-Trace-Id", "Content-Type",
                        "X-Tus-Resumable", "Upload-Length", "Upload-Offset", "Upload-Metadata",
                        "Tus-Resumable");
    }

    @Test
    @DisplayName("★포털_전용_인증헤더가_CORS_허용_목록에_있다 — 없으면_포털_인증이_preflight에서_막힌다")
    void portalTokenHeaderIsAllowed() {
        // @design INT-013 — 포털 채널은 토큰을 Authorization 이 아니라 전용 헤더로 싣는다.
        //   CORS safelisted 헤더가 아니라 교차 출처에서 반드시 preflight 를 유발하며, 목록에 없으면
        //   <브라우저가 요청 자체를 막아> 서버 로그에 아무것도 남지 않는다.
        // ★ 이 가드는 「기존 허용 헤더는 그대로다」로 덮이지 않는다 — 그 시험은 <그때 있던> 헤더만
        //   열거하므로 새 헤더가 빠져도 계속 통과한다. 그래서 값 축 가드를 따로 둔다.
        // ★ 리터럴이 아니라 상수를 참조한다 — 이름이 바뀌면 여기서 함께 깨져야 한다.
        assertThat(corsConfig().getAllowedHeaders())
                .contains(JwtAuthenticationFilter.PORTAL_TOKEN_HEADER);
    }

    @Test
    @DisplayName("origin_미설정이면_외부_origin은_여전히_차단된다 — 기본_fail-closed_보존")
    void blocksAllOriginsWhenUnset() {
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) config.corsConfigurationSource("");
        assertThat(source.getCorsConfigurations().get("/**").getAllowedOrigins()).isEmpty();
    }
}
