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
    @DisplayName("IPv4_IPv6_전체_대역_명시면_IPv4와_IPv6_원격_주소를_모두_허용한다")
    void IPv4_IPv6_전체_대역_명시면_IPv4와_IPv6_원격_주소를_모두_허용한다() {
        GenAiWebhookIpAllowlist allowlist = new GenAiWebhookIpAllowlist("0.0.0.0/0,::/0");

        assertThat(allowlist.isAllowed("203.0.113.9")).isTrue();
        assertThat(allowlist.isAllowed("2001:db8::5")).isTrue();
        assertThat(allowlist.isAllowed("::1")).isTrue();
    }

    /**
     * IPv4-매핑 IPv6 원격 주소의 <b>현행 동작 실측 고정</b> — 바꾸지 않는다.
     * {@code InetAddress} 가 {@code ::ffff:a.b.c.d} 를 IPv4 주소로 해석하므로 IPv4 대역에 매칭되고
     * IPv6 대역에는 매칭되지 않는다(매처는 주소 종류가 다르면 불일치로 본다).
     */
    @Test
    @DisplayName("IPv4_매핑_IPv6_원격_주소는_IPv4_대역에만_매칭된다_현행_실측")
    void IPv4_매핑_IPv6_원격_주소는_IPv4_대역에만_매칭된다_현행_실측() {
        GenAiWebhookIpAllowlist ipv4Only = new GenAiWebhookIpAllowlist("0.0.0.0/0");
        GenAiWebhookIpAllowlist ipv6Only = new GenAiWebhookIpAllowlist("::/0");

        assertThat(ipv4Only.isAllowed("::ffff:10.0.0.1")).isTrue();
        assertThat(ipv6Only.isAllowed("::ffff:10.0.0.1")).isFalse();
    }

    @Test
    @DisplayName("CIDR_오타는_조용히_무시되지_않고_기동이_차단된다")
    void malformedCidrFailsStartup() {
        assertThatThrownBy(() -> new GenAiWebhookIpAllowlist("203.0.113.0/24;10.0.0.0/8"))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining(GenAiWebhookIpAllowlist.SETTING_KEY);
    }
}
