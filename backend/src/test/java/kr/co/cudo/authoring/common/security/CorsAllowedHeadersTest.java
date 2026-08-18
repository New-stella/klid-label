package kr.co.cudo.authoring.common.security;

import kr.co.cudo.authoring.sysconfig.controller.SystemConfigController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ LOW-7 — <b>CORS allowlist 에 관리자 세션 헤더가 있어야 연동 주소 저장이 동작한다</b>.
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
            new SecurityConfig(null, null, null, null, null, null, null, null);

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
    @DisplayName("origin_미설정이면_외부_origin은_여전히_차단된다 — 기본_fail-closed_보존")
    void blocksAllOriginsWhenUnset() {
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) config.corsConfigurationSource("");
        assertThat(source.getCorsConfigurations().get("/**").getAllowedOrigins()).isEmpty();
    }
}
