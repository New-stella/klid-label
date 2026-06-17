package kr.co.cudo.authoring.augment.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 콜백 충실 플로우 Phase 2 — dev 시뮬레이터 빈 활성 조건(@ConditionalOnProperty) 검증.
 *
 * <p>운영 비활성 가드(HIGH):
 *  - mode=dev 일 때만 빈 로드
 *  - mode=noop / 기본(미설정) 에서는 미로드 (운영 NoopExternalAugmentClient 사용)
 *
 * <p>@Profile("!prd") 의 prd 비활성은 컨텍스트 러너의 프로파일 격리 한계로
 * {@link DevAugmentCallbackSimulator} 클래스 메타애너테이션(@Profile) 존재 단언으로 보강한다.
 */
class DevAugmentCallbackSimulatorBeanConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestBeans.class)
            .withConfiguration(AutoConfigurations.of());

    @Test
    @DisplayName("mode_dev에서는_DevAugmentCallbackSimulator_빈이_로드된다")
    void loadsWhenModeDev() {
        runner.withPropertyValues("authoring.augment.external.mode=dev")
                .run(ctx -> assertThat(ctx).hasSingleBean(DevAugmentCallbackSimulator.class));
    }

    @Test
    @DisplayName("mode_noop에서는_DevAugmentCallbackSimulator_빈이_로드되지_않는다")
    void doesNotLoadWhenModeNoop() {
        runner.withPropertyValues("authoring.augment.external.mode=noop")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DevAugmentCallbackSimulator.class));
    }

    @Test
    @DisplayName("mode_미설정_기본에서는_DevAugmentCallbackSimulator_빈이_로드되지_않는다")
    void doesNotLoadWhenModeMissing() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(DevAugmentCallbackSimulator.class));
    }

    @Test
    @DisplayName("prd에서는_DevAugmentCallbackSimulator가_Profile_제외로_미로드된다")
    void notLoadedInPrdByProfile() {
        // ApplicationContextRunner 는 @Profile 평가 시점 제약이 있어, 클래스에 @Profile("!prd") 가
        // 선언되어 있음을 메타애너테이션으로 단언한다(운영 prd 비활성 가드).
        org.springframework.context.annotation.Profile profile =
                DevAugmentCallbackSimulator.class.getAnnotation(
                        org.springframework.context.annotation.Profile.class);
        assertThat(profile).as("@Profile 선언 존재").isNotNull();
        assertThat(profile.value()).containsExactly("!prd");

        ConditionalOnProperty cond =
                DevAugmentCallbackSimulator.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(cond).as("@ConditionalOnProperty 선언 존재").isNotNull();
        assertThat(cond.havingValue()).isEqualTo("dev");
    }

    @Configuration
    @Import(DevAugmentCallbackSimulator.class)
    static class TestBeans {
        @Bean(name = "augmentCallbackWebClient")
        WebClient augmentCallbackWebClient() {
            return mock(WebClient.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
