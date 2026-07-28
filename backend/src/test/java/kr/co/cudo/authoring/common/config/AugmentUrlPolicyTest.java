package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AugmentUrlPolicy} — 증강 위탁 base-url 검증의 <b>VLM 과 동일 강도</b> 회귀 가드 (DEV_FIX HIGH-1).
 *
 * <p>과거 증강 클라이언트만 "스키마만 보는" 자체 검증을 갖고 있어, 같은 값에서 VLM 은 기동이 막히고
 * 증강은 통과하는 정책 비대칭이 있었다. 이 커넥션으로 NAS 비식별 프레임 절대경로 100건이 나가므로
 * 약한 정책은 그대로 유출 표면이다.
 */
class AugmentUrlPolicyTest {

    private static AugmentUrlPolicy policy(String activeProfile, boolean allowInsecure) {
        MockEnvironment env = new MockEnvironment();
        if (activeProfile != null) {
            env.setActiveProfiles(activeProfile.split(","));
        }
        return new AugmentUrlPolicy(env, allowInsecure);
    }

    @Test
    @DisplayName("prd_프로파일에서_증강_base_url_이_평문_HTTP_면_기동이_실패한다")
    void prdRejectsPlaintextHttp() {
        assertThatThrownBy(() -> policy("prd", false).validate("http://genai.vendor.io:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("prd_프로파일에서_증강_base_url_이_사설IP_면_기동이_실패한다")
    void prdRejectsPrivateNetwork() {
        AugmentUrlPolicy p = policy("prd", false);
        assertThatThrownBy(() -> p.validate("http://10.0.0.5:9400"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.validate("https://10.0.0.5:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("내부");
        // 클라우드 메타데이터 대역(SSRF 고전 표적)
        assertThatThrownBy(() -> p.validate("https://169.254.169.254"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.validate("https://127.0.0.1:9400"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("prd_프로파일에서_증강_완화플래그가_켜져있으면_기동이_실패한다")
    void prdRejectsRelaxationFlag() {
        assertThatThrownBy(() -> policy("prd", true).verifyRelaxationScope())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-insecure-url");
        // 플래그를 켜도 검증 자체는 엄격 유지(이중 방어).
        assertThatThrownBy(() -> policy("prd", true).validate("http://10.0.0.5:9400"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("증강_base_url_placeholder_호스트는_모든_프로파일에서_차단된다")
    void placeholderBlockedEverywhere() {
        assertThatThrownBy(() -> policy("prd", false).validate("http://your-service.example.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy("local", true).validate("http://changeme:9400"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("증강_base_url_이_비어있으면_기동이_실패한다")
    void blankUrlRejected() {
        assertThatThrownBy(() -> policy("prd", false).validate(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy("local", true).validate(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("완화는_local_dev_외_프로파일에서는_인정되지_않는다_allowlist_fail_closed")
    void relaxationOnlyForAllowlistedProfiles() {
        assertThatThrownBy(() -> policy("stg", true).verifyRelaxationScope())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy(null, true).verifyRelaxationScope())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy("local,prd", true).verifyRelaxationScope())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("local_dev_완화플래그에서만_목업_평문_사설_URL_이_허용된다")
    void localAllowsMockUrlOnlyWithFlag() {
        assertThatCode(() -> policy("local", true).validate("http://localhost:9400"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy("dev", true).validate("http://klid-mock-server:9400"))
                .doesNotThrowAnyException();
        // 플래그가 꺼져 있으면 local 에서도 엄격.
        assertThatThrownBy(() -> policy("local", false).validate("http://localhost:9400"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("증강도_배포표식_ENV_가_stg_prd_면_local_dev_프로파일에서_완화가_인정되지_않는다")
    void deployedEnvMarkerOverridesProfileRelaxation() {
        // 공용 골격(ProfileGatedUrlPolicy)에 흡수한 ENV 축이 VLM 뿐 아니라 증강에도 적용되는지 고정한다
        //   — 정책 비대칭 재발(DEV_FIX HIGH-1) 차단이 이 테스트의 목적이다.
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("ENV", "prd");
        AugmentUrlPolicy p = new AugmentUrlPolicy(env, true);
        assertThatThrownBy(p::verifyRelaxationScope)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-insecure-url");
        assertThatThrownBy(() -> p.validate("http://klid-mock-server:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("증강도_링크로컬_메타데이터_대역은_완화_프로파일에서_차단된다")
    void metadataRangeRejectedEvenWhenRelaxed() {
        AugmentUrlPolicy relaxed = policy("local", true);
        assertThatThrownBy(() -> relaxed.validate("http://169.254.169.254"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("메타데이터");
        assertThatThrownBy(() -> relaxed.validate("http://[fe80::1]:9400"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("링크로컬");
        // 해석 불가한 컨테이너명은 계속 통과(도커 밖 기동 보장)
        assertThatCode(() -> relaxed.validate(
                "http://genai-mock-does-not-resolve-" + System.nanoTime() + ":9400"))
                .doesNotThrowAnyException();
    }
}
