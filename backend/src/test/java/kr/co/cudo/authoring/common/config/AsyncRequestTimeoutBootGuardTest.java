package kr.co.cudo.authoring.common.config;

import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AsyncRequestTimeoutGuard} 기동 assert 의 <b>실제 배선</b> 검증 — 컨텍스트 러너로 확인한다.
 *
 * <h3>이 테스트가 잡는 것 (파일 가드가 못 잡는 것)</h3>
 * <p>{@code AsyncRequestTimeoutConfigGuardTest} 는 yml <b>파일 텍스트</b>만 본다. 이 테스트는
 * <b>실효값</b>을 본다 — 즉 실제 yml 을 얹은 뒤 <b>환경변수 표기</b>
 * ({@code SPRING_MVC_ASYNC_REQUEST_TIMEOUT})로 덮었을 때 기동이 거부되는지를 고정한다. 온프렘 배포는
 * {@code systemd EnvironmentFile} 로 값을 주입하므로 이 축이 실제 우회 경로다.
 *
 * <p>또한 {@code @PostConstruct} 어노테이션이 빠져 판정이 기동 경로에서 떨어져 나가는 회귀도 여기서만
 * 드러난다(순수 판정 테스트는 그 경우 조용히 통과한다).
 */
class AsyncRequestTimeoutBootGuardTest {

    private static final String COMMON_YML = "application.yml";

    /** 환경변수 표기(relaxed binding) — 배포가 실제로 쓰는 우회 경로. */
    private static final String ENV_VAR_NAME = "SPRING_MVC_ASYNC_REQUEST_TIMEOUT";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AsyncRequestTimeoutGuard.class);

    /** 실제 {@code application.yml} 을 그대로 얹는다 — 테스트가 만든 값이 아니라 배포되는 값을 본다. */
    private ApplicationContextRunner withRealCommonYaml(ApplicationContextRunner base) {
        return base.withInitializer(ctx -> {
            for (PropertySource<?> source : MainResourceYaml.load(COMMON_YML)) {
                ctx.getEnvironment().getPropertySources().addLast(source);
            }
        });
    }

    /**
     * 환경변수 소스를 흉내 낸다 — 이름은 {@code SPRING_MVC_ASYNC_REQUEST_TIMEOUT} 그대로 두고
     * {@code SystemEnvironmentPropertySource} 로 감싸 relaxed binding 을 실제와 같게 재현한다.
     */
    private ApplicationContextRunner withEnvVar(ApplicationContextRunner base, String value) {
        return base.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.SystemEnvironmentPropertySource(
                        "fake-system-environment", Map.of(ENV_VAR_NAME, (Object) value))));
    }

    @Test
    @DisplayName("실제_공통_yml만_얹으면_정상_기동한다")
    void realCommonYamlStartsUp() {
        withRealCommonYaml(runner)
                .run(ctx -> assertThat(ctx)
                        .as("이 전제가 깨졌다면 배포되는 값 자체가 하한 미만이라는 뜻이다")
                        .hasNotFailed()
                        .hasSingleBean(AsyncRequestTimeoutGuard.class));
    }

    @Test
    @DisplayName("yml은_그대로인데_환경변수로_30초로_덮으면_기동이_거부된다")
    void environmentVariableOverrideIsRejected() {
        // given: yml 파일은 손대지 않았다(파일 가드는 이 상황에서 그대로 통과한다)
        // when: 배포가 실제로 쓰는 축(EnvironmentFile → 환경변수)으로 원래 결함값을 주입
        // then: 기동 거부
        withEnvVar(withRealCommonYaml(runner), "30000")
                .run(ctx -> assertThat(ctx)
                        .as("환경변수가 yml 을 이기므로 파일만 보는 가드는 여기서 무력하다")
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(AsyncRequestTimeoutGuard.KEY));
    }

    @Test
    @DisplayName("환경변수로_제한없음_0을_주입해도_기동이_거부된다")
    void environmentVariableUnlimitedIsRejected() {
        withEnvVar(withRealCommonYaml(runner), "0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("환경변수로_더_길게_덮는_것은_허용된다")
    void longerOverrideIsAllowed() {
        withEnvVar(withRealCommonYaml(runner), "45m")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("키가_아예_없으면_기동이_거부된다_yml_한_줄만_지워도_드러난다")
    void missingKeyIsRejected() {
        // given: 공통 yml 을 얹지 않은 = 그 한 줄이 지워진 상태
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addLast(
                        new MapPropertySource("empty", Map.of())))
                .run(ctx -> assertThat(ctx)
                        .as("미설정은 컨테이너 기본값 30초와 같은 결과다 — 통과시키면 원래 결함이 되돌아온다")
                        .hasFailed());
    }
}
