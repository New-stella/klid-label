package kr.co.cudo.authoring.common.security.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 웹훅 IP/CIDR 값 파서 — IPv6 텍스트 표기(RFC 4291 §2.2) 판정 회귀 가드.
 *
 * <p>구 판정은 16진 그룹이 0개인 {@code ::} 를 리터럴이 아니라고 거부해 {@code ::/0} 이 기동을 막았고,
 * 반대로 {@code 1::2::3}·{@code :1}·{@code 1:2:3} 같은 틀린 표기를 리터럴로 통과시켰다.
 * 허용 목록 값 형식의 진실원은 설계 INT-006·INT-003 의 인증 상세다.
 */
class WebhookCidrParserTest {

    private static final String KEY = "webhook.test.allowed-ip-cidrs";
    private static final String ENV = "WEBHOOK_TEST_ALLOWED_IP_CIDRS";

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "::", "::/0", "::1", "::1/128", "fe80::/10", "2001:db8::/32",
            "1:2:3:4:5:6:7:8", "::ffff:192.0.2.1", "1::"
    })
    @DisplayName("표준_IPv6_표기와_압축_표기는_통과한다")
    void 표준_IPv6_표기와_압축_표기는_통과한다(String value) {
        assertThat(WebhookCidrParser.parseStrict(value, KEY, ENV)).hasSize(1);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            ":::", "1::2::3", ":1", "1:", "1:2:3", "1:2:3:4:5:6:7:8:9",
            "1::2:3:4:5:6:7:8", "12345::", "::ffff:192.0.2.1:1", "::g",
            "fe80::1%eth0", "::/129"
    })
    @DisplayName("틀린_IPv6_표기는_기동_차단으로_거부된다")
    void 틀린_IPv6_표기는_기동_차단으로_거부된다(String value) {
        assertThatThrownBy(() -> WebhookCidrParser.parseStrict(value, KEY, ENV))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining(KEY)
                .hasMessageContaining(ENV);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "::1.2.3.4:5", "1.2.3.4::", "1:2:3:4:5:6:7:1.2.3.4", "::1:", ":1::", "1:2:3:4:5:6:7::8:9"
    })
    @DisplayName("IPv4_꼬리_위치_오류와_콜론_경계_오류는_리터럴이_아니다")
    void IPv4_꼬리_위치_오류와_콜론_경계_오류는_리터럴이_아니다(String value) {
        assertThat(WebhookCidrParser.isIpLiteral(value)).isFalse();
    }

    /**
     * 요청 경로({@link ClientIpResolver} 의 XFF·remoteAddr 판정)는 매처를 만들지 않고 {@code isIpLiteral} 만
     * 믿는다. 그래서 그룹 수 규칙(압축 없음 = 8 · 압축 ≤ 7)은 {@code parseStrict} 가 아니라 이 판정으로
     * 직접 지켜야 한다 — {@code parseStrict} 경유 시험은 매처 생성 예외가 규칙 파손을 가려 준다.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "1:2:3", "1::2::3", ":1", "1:", "1::2:3:4:5:6:7:8", "1:2:3:4:5:6:7",
            "::1:2:3:4:5:6:1.2.3.4", "1:2:3:4:5:6:7:8::"
    })
    @DisplayName("그룹_수_규칙을_어긴_IPv6_표기는_요청_경로_판정에서_리터럴이_아니다")
    void 그룹_수_규칙을_어긴_IPv6_표기는_요청_경로_판정에서_리터럴이_아니다(String value) {
        assertThat(WebhookCidrParser.isIpLiteral(value)).isFalse();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"0:0:0:0:0:0:0:0", "1:2:3:4:5:6:7::", "::1:2:3:4:5:6:7"})
    @DisplayName("그룹_수_경계값_IPv6_표기는_요청_경로_판정에서_리터럴이다")
    void 그룹_수_경계값_IPv6_표기는_요청_경로_판정에서_리터럴이다(String value) {
        assertThat(WebhookCidrParser.isIpLiteral(value)).isTrue();
    }

    /** 16진·10진 판정은 ASCII 만 받는다 — 전각·다른 문자 체계의 숫자가 리터럴로 통과하면 안 된다. */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"::１", "Ａ::", "::١", "１.2.3.4"})
    @DisplayName("비ASCII_숫자와_전각_16진_문자는_리터럴이_아니다")
    void 비ASCII_숫자와_전각_16진_문자는_리터럴이_아니다(String value) {
        assertThat(WebhookCidrParser.isIpLiteral(value)).isFalse();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"1:2:3", "::１"})
    @DisplayName("신뢰_프록시_뒤_XFF가_리터럴이_아니면_원격_주소로_폴백한다")
    void 신뢰_프록시_뒤_XFF가_리터럴이_아니면_원격_주소로_폴백한다(String forwardedFor) {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8", new MockEnvironment());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", forwardedFor);

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.5");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"1:2:3:4:5:6:1.2.3.4", "::1.2.3.4", "1::1.2.3.4", "0:0:0:0:0:0:0:0", "ABCD:ef01::"})
    @DisplayName("IPv4_꼬리가_맨_끝이면_16비트_그룹_2개로_세어_통과한다")
    void IPv4_꼬리가_맨_끝이면_16비트_그룹_2개로_세어_통과한다(String value) {
        assertThat(WebhookCidrParser.isIpLiteral(value)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"0.0.0.0/0", "10.0.0.0/8", "203.0.113.5", "255.255.255.255/32"})
    @DisplayName("IPv4_판정은_그대로_통과한다")
    void IPv4_판정은_그대로_통과한다(String value) {
        assertThat(WebhookCidrParser.parseStrict(value, KEY, ENV)).hasSize(1);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"10.0.0.0/33", "256.0.0.1", "example.com", "00a"})
    @DisplayName("IPv4_프리픽스_초과와_호스트명은_그대로_거부된다")
    void IPv4_프리픽스_초과와_호스트명은_그대로_거부된다(String value) {
        assertThatThrownBy(() -> WebhookCidrParser.parseStrict(value, KEY, ENV))
                .isInstanceOf(BeanInitializationException.class);
    }

    @Test
    @DisplayName("IPv4_IPv6_전체_대역_표기가_두_매처로_컴파일된다")
    void IPv4_IPv6_전체_대역_표기가_두_매처로_컴파일된다() {
        List<IpAddressMatcher> matchers = WebhookCidrParser.parseStrict("0.0.0.0/0, ::/0", KEY, ENV);

        assertThat(matchers).hasSize(2);
        assertThat(matchers.get(1).matches("2001:db8::5")).isTrue();
        assertThat(matchers.get(1).matches("::1")).isTrue();
        assertThat(matchers.get(0).matches("203.0.113.9")).isTrue();
    }

    @Test
    @DisplayName("VLM_허용_목록도_IPv4_IPv6_전체_대역_표기로_기동이_막히지_않는다")
    void VLM_허용_목록도_IPv4_IPv6_전체_대역_표기로_기동이_막히지_않는다() {
        assertThatCode(() -> new WebhookIpAllowlist("0.0.0.0/0,::/0", new MockEnvironment()))
                .doesNotThrowAnyException();

        WebhookIpAllowlist allowlist = new WebhookIpAllowlist("0.0.0.0/0,::/0", new MockEnvironment());
        assertThat(allowlist.isEnabled()).isTrue();
        assertThat(allowlist.isAllowed("203.0.113.9")).isTrue();
        assertThat(allowlist.isAllowed("2001:db8::5")).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"::1", "::"})
    @DisplayName("원격_주소가_IPv6_압축_리터럴이면_클라이언트_IP로_인정된다")
    void 원격_주소가_IPv6_압축_리터럴이면_클라이언트_IP로_인정된다(String remoteAddr) {
        ClientIpResolver resolver = new ClientIpResolver("", new MockEnvironment());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);

        assertThat(resolver.resolve(request)).isEqualTo(remoteAddr);
    }
}
