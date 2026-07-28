package kr.co.cudo.authoring.webhook;

import jakarta.servlet.http.HttpServletRequest;
import kr.co.cudo.authoring.common.security.webhook.WebhookProtectedPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 웹훅 경로 등록 회귀 가드 — Phase 7-A2 구 계약 제거 + 신규 무서명 경로 등록.
 *
 * <p>구 증강 콜백({@code /v1/aug/callback}) 은 컨트롤러·DTO·시뮬레이터와 함께 제거됐다. 보호
 * 목록에만 남겨 두면 수신처 없는 "죽은 보안 설정" 이 되어 실제 보호 범위를 오독하게 만든다.
 */
class GenAiWebhookRegistrationTest {

    private static HttpServletRequest post(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }

    @Test
    @DisplayName("구_경로가_HMAC_서명필수_목록에서도_제거됐다")
    void legacyAugPathIsNoLongerSignatureRequired() {
        HttpServletRequest legacy = post("/v1/aug/callback");

        assertThat(WebhookProtectedPaths.requiresSignature(legacy)).isFalse();
        assertThat(WebhookProtectedPaths.requiresGuardOnly(legacy)).isFalse();
        assertThat(WebhookProtectedPaths.isProtected(legacy)).isFalse();
    }

    @Test
    @DisplayName("genai_콜백은_무서명_가드_전용_경로로_등록된다")
    void genAiPathIsGuardOnly() {
        HttpServletRequest req = post(WebhookProtectedPaths.PATH_GENAI_CALLBACK);

        assertThat(WebhookProtectedPaths.isProtected(req)).isTrue();
        assertThat(WebhookProtectedPaths.requiresGuardOnly(req)).isTrue();
        assertThat(WebhookProtectedPaths.requiresSignature(req)).isFalse();
        assertThat(WebhookProtectedPaths.isGenAi(req)).isTrue();
        assertThat(WebhookProtectedPaths.metricTag(req)).isEqualTo(WebhookProtectedPaths.TAG_GENAI);
    }

    @Test
    @DisplayName("2단_게이트_인터셉터_패턴에_genai_가_포함된다")
    void gatePatternsCoverGenAi() {
        assertThat(WebhookProtectedPaths.GATE_PATTERNS)
                .containsExactlyInAnyOrder(WebhookProtectedPaths.PATTERN_VLM,
                        WebhookProtectedPaths.PATTERN_GENAI);
    }

    @Test
    @DisplayName("경로_판정_불가는_여전히_서명필수_fail_closed_다")
    void unresolvablePathStaysSignatureRequired() {
        HttpServletRequest broken = new MockHttpServletRequest("POST", "/v1/genai/callback") {
            @Override
            public String getRequestURI() {
                throw new IllegalStateException("URI 파싱 실패 시뮬");
            }
        };

        assertThat(WebhookProtectedPaths.requiresSignature(broken)).isTrue();
        assertThat(WebhookProtectedPaths.isProtected(broken)).isTrue();
    }
}
