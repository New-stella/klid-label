package kr.co.cudo.authoring.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ForwardedHeadersConfigGuard} 회귀 테스트 — <b>재도입 3축</b>을 각각 실측한다.
 *
 * <p>기존 회귀 고정 테스트({@code WebhookPathBypassSecurityIT})는 {@code @ActiveProfiles("local")} 이라
 * {@code application.yml} 축만 덮었다. 이 테스트는 프로파일별 yml·<b>환경변수(relaxed binding)</b>·
 * 시스템 프로퍼티 축까지 판정이 도달하는지 확인한다.
 *
 * <p>되돌림 검증: 가드를 삭제하거나 {@code FORBIDDEN_KEYS} 에서 키를 빼면 아래 테스트가 실패한다.
 */
class ForwardedHeadersConfigGuardTest {

    @Test
    @DisplayName("forward_headers_strategy_가_설정되면_기동_거부_local_포함_전프로파일")
    void forwardHeadersStrategyConfigured_failsBoot() {
        // given: 어떤 프로파일이든(local 포함) 동일하게 금지 — "로컬에서 되니까 운영에도" 경로 차단
        for (String profile : new String[]{"local", "dev", "stg", "prd"}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profile);
            env.setProperty(ForwardedHeadersConfigGuard.FORWARD_HEADERS_STRATEGY, "framework");

            // when/then
            assertThatThrownBy(() -> new ForwardedHeadersConfigGuard(env).verify())
                    .as("%s 프로파일에서도 기동이 거부돼야 한다", profile)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(ForwardedHeadersConfigGuard.FORWARD_HEADERS_STRATEGY)
                    .hasMessageContaining("ClientIpResolver");
        }
    }

    @Test
    @DisplayName("native_나_none_같은_다른_값이어도_키가_존재하면_기동_거부")
    void anyNonBlankValue_failsBoot() {
        for (String value : new String[]{"native", "NATIVE", "none"}) {
            MockEnvironment env = new MockEnvironment();
            env.setProperty(ForwardedHeadersConfigGuard.FORWARD_HEADERS_STRATEGY, value);

            // 값 파싱으로 예외를 두면 표기 변형(대소문자·오타)이 우회 경로가 된다. 키 존재 자체를 막는다.
            // none 은 이미 Spring 기본값이므로 키를 지우면 동작 변화가 없다.
            assertThatThrownBy(() -> new ForwardedHeadersConfigGuard(env).verify())
                    .as("value=%s", value)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("환경변수_SERVER_FORWARD_HEADERS_STRATEGY_도_relaxed_binding_으로_감지된다")
    void environmentVariableAxis_isDetected() {
        // given: 실제 OS 환경변수와 동일한 SystemEnvironmentPropertySource 로 relaxed binding 경로를 모사
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("SERVER_FORWARD_HEADERS_STRATEGY", "framework")));

        // 소스에는 점·하이픈 표기의 키가 아예 없다 — relaxed binding 이 load-bearing 임을 고정한다.
        assertThat(env.getPropertySources()
                .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                .containsProperty("SERVER_FORWARD_HEADERS_STRATEGY")).isTrue();

        // when/then: 점·하이픈 표기 차이를 넘어 감지돼야 한다(환경변수 축이 온프렘 실제 주입 경로)
        assertThat(ForwardedHeadersConfigGuard.configuredForbiddenKeys(env))
                .containsExactly(ForwardedHeadersConfigGuard.FORWARD_HEADERS_STRATEGY);
        assertThatThrownBy(() -> new ForwardedHeadersConfigGuard(env).verify())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("프로파일별_yml_축_설정도_감지된다")
    void profileSpecificYamlAxis_isDetected() {
        // given: application-prd.yml 에 추가된 상황 모사(프로파일별 소스도 결국 Environment 로 합류)
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prd");
        env.getPropertySources().addFirst(new MapPropertySource(
                "applicationConfig: [classpath:/application-prd.yml]",
                Map.of(ForwardedHeadersConfigGuard.FORWARD_HEADERS_STRATEGY, "framework")));

        assertThatThrownBy(() -> new ForwardedHeadersConfigGuard(env).verify())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("RemoteIpValve_를_세우는_tomcat_remoteip_키도_동일하게_거부된다")
    void tomcatRemoteIpValveKeys_failBoot() {
        // RemoteIpValve 는 필터보다 더 앞(컨테이너 레벨)에서 remoteAddr 을 XFF 로 치환한다 — 동일 위험.
        MockEnvironment remoteIpHeader = new MockEnvironment();
        remoteIpHeader.setProperty(ForwardedHeadersConfigGuard.TOMCAT_REMOTE_IP_HEADER, "X-Forwarded-For");
        assertThatThrownBy(() -> new ForwardedHeadersConfigGuard(remoteIpHeader).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ForwardedHeadersConfigGuard.TOMCAT_REMOTE_IP_HEADER);

        MockEnvironment protocolHeader = new MockEnvironment();
        protocolHeader.setProperty(ForwardedHeadersConfigGuard.TOMCAT_PROTOCOL_HEADER, "X-Forwarded-Proto");
        assertThatThrownBy(() -> new ForwardedHeadersConfigGuard(protocolHeader).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ForwardedHeadersConfigGuard.TOMCAT_PROTOCOL_HEADER);
    }

    @Test
    @DisplayName("미설정_이거나_빈값_이면_통과한다")
    void unsetOrBlank_passes() {
        assertThatCode(() -> new ForwardedHeadersConfigGuard(new MockEnvironment()).verify())
                .doesNotThrowAnyException();

        MockEnvironment blank = new MockEnvironment();
        blank.setProperty(ForwardedHeadersConfigGuard.FORWARD_HEADERS_STRATEGY, "  ");
        // 빈 값은 Spring 에서 아무 필터도 세우지 않으므로 미설정과 동일하게 본다.
        assertThatCode(() -> new ForwardedHeadersConfigGuard(blank).verify()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("실제_스프링_컨텍스트_기동에서도_설정이_있으면_부팅이_실패한다")
    void springContext_failsToStartWhenConfigured() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ForwardedHeadersConfigGuard.class);

        runner.run(ctx -> assertThat(ctx).hasNotFailed());

        runner.withPropertyValues("server.forward-headers-strategy=framework")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
