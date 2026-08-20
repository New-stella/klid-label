package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [design: ADR-049] 미연동(연동 주소 미주입) 상태에서 <b>기동이 성공</b>하는지 고정한다.
 *
 * <h3>무엇을 막는가</h3>
 * <p>{@code ADR-049} 는 시계열 위탁의 비활성 토글을 폐지하면서 미연동 판정을 <b>연동 주소 주입
 * 여부</b>로 옮겼다. 그때 같이 검토된 「미연동 시 기동 거부」안은 <b>기각</b>됐다 — 벤더 연동이
 * 확정되기 전에는 배포 자체가 불가능해지고, 시계열 외의 모든 기능까지 함께 막히기 때문이다.
 *
 * <p>그런데 토글이 사라진 자리에 검증 조건을 잘못 넣으면(예: {@code baseUrl} 을 무조건 검증)
 * 배포 기본값이 비어 있는 stg/prd 에서 <b>빈 생성 실패 → 기동 차단</b>이 되어 그 기각안과 같은
 * 결과가 된다. 이 테스트는 그 회귀를 차단한다.
 *
 * <h3>왜 컨텍스트를 띄우지 않나</h3>
 * <p>판정 지점이 {@code vlmWebClient} 빈 팩토리 메서드 <b>한 곳</b>이고, 그 메서드를 직접 호출하는
 * 것이 곧 기동 경로의 재현이다({@link WebClientConfigTest} 와 같은 방식). 다만 직접 호출만으로는
 * <b>배포 기본값이 실제로 비어 있는지</b>를 증명하지 못하므로, {@code @Value} 기본값을 리플렉션으로
 * 함께 고정한다 — 기본값이 비어 있지 않으면 "미주입 = 빈 값" 전제가 성립하지 않는다.
 */
class VlmBlankUrlBootTest {

    private final WebClientConfig cfg = new WebClientConfig();

    /** 운영 등가(엄격) 정책 — 완화 플래그 off + 배포 표식 프로파일. */
    private static VlmUrlPolicy strictPolicy() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prd");
        return new VlmUrlPolicy(env, false);
    }

    @Test
    @DisplayName("주소가_비어_있으면_검증을_생략하고_기동에_성공한다_ADR049_기각안_회귀차단")
    void blankUrlSkipsValidationAndBoots() {
        // given: 운영 등가(prd + 완화 플래그 off) — 가장 엄격한 조건에서도 미연동 기동이 막히면 안 된다.
        VlmUrlPolicy policy = strictPolicy();

        // when / then: 빈 값·공백·null 어느 형태든 빈이 생성된다.
        assertThat(cfg.vlmWebClient("", "", policy, null))
                .as("주소 미주입(빈 값)에서 기동이 막히면 ADR-049 가 기각한 「미연동 시 기동 거부」로 되돌아간다")
                .isNotNull();
        assertThat(cfg.vlmWebClient("   ", "", policy, null))
                .as("공백만 있는 값도 미주입과 같이 취급해야 한다")
                .isNotNull();
        assertThat(cfg.vlmWebClient(null, "", policy, null))
                .as("null 도 미주입으로 취급해야 한다(프로퍼티 소스 구성에 따라 null 이 올 수 있다)")
                .isNotNull();
    }

    @Test
    @DisplayName("주소가_채워져_있으면_종전대로_검증하고_위반이면_기동을_차단한다")
    void filledUrlIsStillValidated() {
        // given: 운영 등가 정책
        VlmUrlPolicy policy = strictPolicy();

        // when / then: 평문 http · 내부 대역 · placeholder 는 종전대로 거부된다(검증 약화 금지).
        assertThatThrownBy(() -> cfg.vlmWebClient("http://vlm.vendor.io", "", policy, null))
                .as("주소가 있으면 HTTPS 강제가 살아 있어야 한다")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://10.0.0.5", "", policy, null))
                .as("주소가 있으면 사설 대역 차단이 살아 있어야 한다")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cfg.vlmWebClient("https://example.com", "", policy, null))
                .as("주소가 있으면 placeholder fail-closed 가 살아 있어야 한다")
                .isInstanceOf(IllegalStateException.class);

        // and: 정상 주소는 그대로 통과한다.
        assertThat(cfg.vlmWebClient("https://8.8.8.8/", "", policy, null)).isNotNull();
    }

    /**
     * 배포 기본값 자체가 비어 있어야 위 "미주입 = 빈 값" 전제가 성립한다.
     *
     * <p>기본값이 {@code http://localhost:9400} 같은 실주소면 stg/prd 는 <b>미연동인데도 주소가 채워진
     * 것</b>으로 판정되어 엄격 정책에 걸려 기동이 차단된다(=기각안과 동일 결과). 배포 템플릿
     * ({@code deploy/onprem/config/backend/*}) 의 빈 값 배포도 같은 전제 위에 있다.
     */
    @Test
    @DisplayName("연동_주소의_배포_기본값이_빈_값이다_미주입이_기동을_막지_않는_전제")
    void deployDefaultOfVlmUrlIsBlank() throws Exception {
        // given: 빈 팩토리 메서드의 첫 파라미터가 연동 주소다.
        Method bean = WebClientConfig.class.getDeclaredMethod(
                "vlmWebClient", String.class, String.class, VlmUrlPolicy.class,
                kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver.class);
        Parameter urlParam = bean.getParameters()[0];

        // when
        Value value = urlParam.getAnnotation(Value.class);

        // then
        assertThat(value)
                .as("연동 주소는 @Value 로 주입돼야 한다 — 배선이 바뀌면 이 가드의 판정 근거가 사라진다")
                .isNotNull();
        assertThat(value.value())
                .as("배포 기본값이 비어 있어야 미주입이 곧 '연동 안 됨' 으로 판정된다")
                .isEqualTo("${vlm.client.url:}");
    }

    /**
     * ★★<b>기동은 되지만 「아무 데도 안 보낸다」까지 성립해야 한다</b> — 배선 회귀 차단.
     *
     * <h3>무엇이 어긋나 있었나 (실측)</h3>
     * <p>주소가 비면 {@code baseUrl("")} + 상대 URI 라 요청이 <b>loopback 의 80 포트로 실제로 나간다</b>
     * ({@code Connection refused: /[0:0:0:0:0:0:0:1]:80} 로 관측). 온프렘은 같은 호스트에 프론트
     * 웹서버를 두므로 80 이 열려 있으면 <b>연결이 수신되고 접근 로그에 요청 경로가 남는다</b> — 그 요청
     * 바디에는 비식별 영상의 절대 경로와 콜백 주소가 실린다.
     *
     * <p>가드 자체의 단위 검증은 {@code IntegrationEndpointBlankHostGuardTest} 가 한다. 여기서는
     * <b>그 가드가 이 빈에 실제로 배선돼 있는지</b>를 고정한다 — 가드만 있고 배선이 빠지면 "코드는 맞는데
     * 실동작 0건" 이 된다.
     *
     * <p>네트워크에 나가지 않으므로(나가면 그 자체가 실패다) 이 테스트는 외부 의존이 없다.
     */
    @Test
    @DisplayName("★★주소가_비면_요청이_전송되지_않고_즉시_실패한다_loopback80_유출차단_배선")
    void blankUrlNeverSendsARequest() {
        org.springframework.web.reactive.function.client.WebClient client =
                cfg.vlmWebClient("", "", strictPolicy(), null);

        assertThatThrownBy(() -> client.post()
                .uri("/v1/videovlm/verify")
                .bodyValue("{}")
                .retrieve()
                .bodyToMono(String.class)
                .block(java.time.Duration.ofSeconds(5)))
                .as("연결 거부(ConnectException)가 나면 이미 전송을 시도했다는 뜻이다")
                .isInstanceOf(kr.co.cudo.authoring.common.client.NonRetryableExternalException.class);
    }

}
