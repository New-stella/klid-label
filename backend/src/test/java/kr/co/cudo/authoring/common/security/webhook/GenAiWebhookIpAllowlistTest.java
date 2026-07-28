package kr.co.cudo.authoring.common.security.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanInitializationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 생성형 AI 웹훅 IP allowlist — <b>fail-closed 기본값</b> 회귀 가드 (Phase 7-A2).
 *
 * <p>{@link WebhookIpAllowlist}(VLM)는 "미설정 = 미적용(전면 허용)" 이라 설정 누락이 조용한 전면
 * 허용으로 떨어졌다. 신규 연동인 생성형 AI 웹훅은 그 기본값을 물려받지 않는다 — 미설정이면
 * 모든 출처를 차단하고, 열려면 대역을 명시해야 한다.
 */
class GenAiWebhookIpAllowlistTest {

    @Test
    @DisplayName("allowed_ip_cidrs_미설정이면_전면_차단된다")
    void unsetBlocksEverything() {
        GenAiWebhookIpAllowlist allowlist = new GenAiWebhookIpAllowlist("");

        assertThat(allowlist.isAllowed("127.0.0.1")).isFalse();
        assertThat(allowlist.isAllowed("10.0.0.5")).isFalse();
        assertThat(allowlist.isAllowed("203.0.113.9")).isFalse();
    }

    @Test
    @DisplayName("none_명시도_허용_IP_없음이라_전면_차단된다")
    void noneBlocksEverything() {
        GenAiWebhookIpAllowlist allowlist = new GenAiWebhookIpAllowlist("none");

        assertThat(allowlist.isAllowed("203.0.113.9")).isFalse();
    }

    @Test
    @DisplayName("명시된_대역만_허용되고_그_밖은_차단된다")
    void onlyConfiguredCidrsAllowed() {
        GenAiWebhookIpAllowlist allowlist = new GenAiWebhookIpAllowlist("203.0.113.0/24");

        assertThat(allowlist.isAllowed("203.0.113.7")).isTrue();
        assertThat(allowlist.isAllowed("203.0.114.7")).isFalse();
        assertThat(allowlist.isAllowed(null)).isFalse();
        assertThat(allowlist.isAllowed(" ")).isFalse();
    }

    @Test
    @DisplayName("CIDR_오타는_조용히_무시되지_않고_기동이_차단된다")
    void malformedCidrFailsStartup() {
        assertThatThrownBy(() -> new GenAiWebhookIpAllowlist("203.0.113.0/24;10.0.0.0/8"))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining(GenAiWebhookIpAllowlist.SETTING_KEY);
    }
}
