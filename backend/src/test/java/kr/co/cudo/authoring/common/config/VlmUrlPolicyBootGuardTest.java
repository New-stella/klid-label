package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VlmUrlPolicy} 기동 assert 의 <b>실제 배선</b> 검증 — 컨텍스트 러너로 확인한다.
 *
 * <p>{@link VlmUrlPolicyTest} 는 판정 로직을 직접 호출해 고정하지만, 그 assert 가 {@code @PostConstruct}
 * 로 <b>실제 기동 경로에 걸려 있는지</b>는 증명하지 못한다(어노테이션이 빠지면 조용히 통과). 운영에 완화
 * 설정이 들어와도 배포가 죽지 않는 무증상 회귀를 막기 위해 컨텍스트 refresh 로 고정한다.
 */
class VlmUrlPolicyBootGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(VlmUrlPolicy.class);

    @Test
    @DisplayName("prd_프로파일에서_완화_플래그가_켜져있으면_컨텍스트_기동이_실패한다")
    void prdWithRelaxationFlagFailsContextStartup() {
        runner.withPropertyValues(
                        "spring.profiles.active=prd",
                        "vlm.client.allow-insecure-url=true")
                .run(ctx -> assertThat(ctx)
                        .hasFailed()
                        .getFailure()
                        // 빈 생성 실패로 감싸이므로 원인 체인에서 확인한다.
                        .hasStackTraceContaining("allow-insecure-url")
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class));
    }

    @Test
    @DisplayName("local_프로파일_완화_플래그는_기동이_정상이다")
    void localWithRelaxationFlagStartsUp() {
        runner.withPropertyValues(
                        "spring.profiles.active=local",
                        "vlm.client.allow-insecure-url=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(VlmUrlPolicy.class));
    }

    @Test
    @DisplayName("완화_플래그_미설정이면_어떤_프로파일에서도_기동이_정상이다")
    void defaultFlagStartsUpEverywhere() {
        runner.withPropertyValues("spring.profiles.active=prd")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(VlmUrlPolicy.class));
    }
}
