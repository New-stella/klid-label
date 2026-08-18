package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.common.config.AsyncConfig;
import kr.co.cudo.authoring.common.config.MvcAsyncExecutorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MVC 비동기 응답({@code StreamingResponseBody})이 <b>경계 있는 실행기</b> 위에서 돈다는 회귀 가드.
 *
 * <h3>무엇이 조용히 어긋나 있었나</h3>
 * <p>Spring Boot 의 {@code applicationTaskExecutor} 는 {@code @ConditionalOnMissingBean(Executor.class)}
 * 라, 애플리케이션이 {@link java.util.concurrent.Executor} 빈을 하나라도 등록하면 <b>물러난다</b>.
 * 이 프로젝트는 배치·위탁용 풀을 여러 개 등록하므로 그 조건이 이미 성립하고, 그러면
 * {@code WebMvcAutoConfiguration} 이 MVC 비동기 실행기를 지정하지 못해
 * {@link SimpleAsyncTaskExecutor}(<b>요청마다 새 스레드, 상한 없음</b>)로 떨어진다.
 *
 * <p>그 상태에서 비동기 응답 제한시간을 30초 → 30분으로 늘리면 대가가 60배가 된다 — 조용히 멈춘
 * 연결마다 스레드와 커넥션을 30분씩 붙잡는다. 되돌릴 것은 제한시간이 아니라 실행기 쪽이다.
 */
class MvcAsyncExecutorBoundedTest {

    @Test
    @DisplayName("우리_실행기_빈이_있으면_부트_기본_실행기는_물러난다_이_사실을_고정한다")
    void applicationTaskExecutorBacksOffWhenWeRegisterExecutorBeans() {
        // 대조군 — 자동설정 단독이면 기본 실행기가 있다.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .run(ctx -> assertThat(ctx)
                        .as("이 전제가 깨졌다면 부트 버전이 바뀐 것이라 아래 판정의 근거가 사라진다")
                        .hasBean("applicationTaskExecutor"));

        // 실험군 — 우리 풀들을 얹으면 물러난다. 이것이 MVC 비동기가 무경계로 떨어지는 «원인»이다.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withUserConfiguration(AsyncConfig.class)
                .run(ctx -> assertThat(ctx)
                        .as("물러나므로 MVC 비동기 실행기를 우리가 «명시 지정»해야 한다")
                        .doesNotHaveBean("applicationTaskExecutor"));
    }

    @Test
    @DisplayName("MVC_비동기_실행기는_경계_있는_풀로_명시_지정된다")
    void mvcAsyncExecutorIsBoundedAndExplicitlyWired() throws Exception {
        MvcAsyncExecutorConfig config = new MvcAsyncExecutorConfig();

        ThreadPoolTaskExecutor executor = config.mvcAsyncTaskExecutor();
        assertThat(executor.getMaxPoolSize())
                .as("상한이 없으면 요청마다 스레드가 늘어난다(CWE-400)")
                .isEqualTo(MvcAsyncExecutorConfig.MAX_POOL_SIZE);

        // configureAsyncSupport 가 실제로 그 실행기를 꽂는지 — 빈만 만들고 안 꽂으면 아무 효과가 없다.
        AsyncSupportConfigurer configurer = newAsyncSupportConfigurer();
        config.configureAsyncSupport(configurer);

        Object wired = readField(configurer, "taskExecutor");
        assertThat(wired)
                .as("MVC 비동기 실행기가 안 꽂히면 SimpleAsyncTaskExecutor(무경계)로 떨어진다")
                .isNotNull()
                .isNotInstanceOf(SimpleAsyncTaskExecutor.class)
                .isInstanceOf(ThreadPoolTaskExecutor.class);
    }

    @Test
    @DisplayName("MVC_비동기_실행기_설정이_제한시간은_건드리지_않는다")
    void doesNotOverrideAsyncTimeout() throws Exception {
        // 제한시간의 진실원은 yml(spring.mvc.async.request-timeout)이다. 여기서 함께 지정하면
        // 그 계약이 깨지고 AsyncRequestTimeoutConfigGuardTest 가 지키는 것이 무의미해진다.
        AsyncSupportConfigurer configurer = newAsyncSupportConfigurer();
        new MvcAsyncExecutorConfig().configureAsyncSupport(configurer);

        assertThat(readField(configurer, "timeout")).isNull();
    }

    /** {@code AsyncSupportConfigurer} 는 프레임워크 내부 생성자라 리플렉션으로 만든다. */
    private static AsyncSupportConfigurer newAsyncSupportConfigurer() throws Exception {
        Constructor<AsyncSupportConfigurer> ctor = AsyncSupportConfigurer.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
