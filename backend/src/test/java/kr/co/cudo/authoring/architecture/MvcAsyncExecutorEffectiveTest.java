package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.common.config.AsyncConfig;
import kr.co.cudo.authoring.common.config.MvcAsyncExecutorConfig;
import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MVC 비동기 응답의 <b>최종 실효 실행기·실효 제한시간</b>을 기동된 컨텍스트에서 단언한다.
 *
 * <h3>{@link MvcAsyncExecutorBoundedTest} 와 무엇이 다른가 (이 테스트가 닫는 구멍)</h3>
 * <p>그 테스트는 설정 객체를 <b>직접 만들어</b> {@code configureAsyncSupport} 를 호출하고 그 안에 무엇이
 * 꽂혔는지만 본다. 즉 "우리 설정이 자기 할 일을 한다" 는 확인이지 <b>"기동이 끝났을 때 실제로 무엇이
 * 쓰이는가"</b> 의 확인이 아니다. 그래서 아래 회귀는 그 테스트가 전부 통과한 채로 뚫린다.
 * <ul>
 *   <li>{@link WebMvcAutoConfiguration} 과의 <b>적용 순서</b>가 뒤바뀌어 우리 실행기가 자동설정 값으로
 *       덮이는 경우(우리 설정은 무순위라 <b>나중에</b> 적용되는 데 의존한다).</li>
 *   <li>{@code applicationTaskExecutor} 빈이 <b>다시 등장</b>해 자동설정이 물러나지 않게 되는 경우.</li>
 *   <li>{@code WebMvcConfigurer} 배선이 끊겨(예: {@code implements} 제거) 설정이 아예 수집되지 않는 경우.</li>
 * </ul>
 *
 * <p>판정은 {@link RequestMappingHandlerAdapter} 가 실제로 들고 있는 값을 읽어서 한다 — 그 어댑터가
 * {@code StreamingResponseBody} 를 제출하는 주체이므로, 여기 담긴 값이 곧 <b>실효값</b>이다.
 * 게터가 없어 리플렉션을 쓰되 필드명이 아니라 <b>타입</b>으로 찾아 프레임워크 리네임에 견디게 한다.
 */
class MvcAsyncExecutorEffectiveTest {

    private static final String COMMON_YML = "application.yml";
    private static final String ASYNC_TIMEOUT_KEY = "spring.mvc.async.request-timeout";

    /** 실제 배포 형상 = 우리 풀들(AsyncConfig) + MVC 비동기 실행기 명시 지정(MvcAsyncExecutorConfig). */
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class,
                    TaskExecutionAutoConfiguration.class))
            .withInitializer(ctx -> {
                // 테스트가 지어낸 값이 아니라 <배포되는 yml> 을 그대로 얹는다.
                for (PropertySource<?> source : MainResourceYaml.load(COMMON_YML)) {
                    ctx.getEnvironment().getPropertySources().addLast(source);
                }
            });

    @Test
    @DisplayName("기동_후_실효_비동기_실행기가_경계_있는_우리_풀이다")
    void effectiveExecutorIsOurBoundedPool() {
        runner.withUserConfiguration(AsyncConfig.class, MvcAsyncExecutorConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    AsyncTaskExecutor effective = effectiveExecutor(ctx);

                    assertThat(effective)
                            .as("SimpleAsyncTaskExecutor 는 요청마다 새 스레드를 만들고 상한이 없다(CWE-400)")
                            .isNotInstanceOf(SimpleAsyncTaskExecutor.class)
                            // 같은 <인스턴스> 여야 한다 — 타입만 보면 다른 풀로 바뀌어도 통과한다.
                            .isSameAs(ctx.getBean("mvcAsyncTaskExecutor", ThreadPoolTaskExecutor.class));
                    assertThat(((ThreadPoolTaskExecutor) effective).getMaxPoolSize())
                            .isEqualTo(MvcAsyncExecutorConfig.MAX_POOL_SIZE);
                });
    }

    @Test
    @DisplayName("우리_설정이_빠지면_실효_실행기가_무경계로_떨어진다_대조군")
    void withoutOurConfigItFallsBackToUnbounded() {
        // 대조군 — 이 관측이 없으면 위 단언이 "원래부터 그랬던 것" 인지 "우리가 고쳐서 그런 것" 인지
        // 구분되지 않는다. AsyncConfig 만 얹으면 applicationTaskExecutor 가 물러나 무경계로 떨어진다.
        runner.withUserConfiguration(AsyncConfig.class)
                .run(ctx -> assertThat(effectiveExecutor(ctx))
                        .as("이 대조군이 깨지면 부트 동작이 바뀐 것이라 위 단언의 근거가 사라진다")
                        .isInstanceOf(SimpleAsyncTaskExecutor.class));
    }

    @Test
    @DisplayName("기본_실행기_빈이_다시_등장해도_우리_풀이_이긴다")
    void ourExecutorWinsEvenIfApplicationTaskExecutorComesBack() {
        // 자동설정이 물러나는 것에만 기대면, 누군가 applicationTaskExecutor 를 되살리는 순간
        // WebMvcAutoConfiguration 이 다시 실행기를 지정하게 되고 <적용 순서>가 유일한 방어선이 된다.
        runner.withUserConfiguration(
                        AsyncConfig.class, MvcAsyncExecutorConfig.class, ResurrectedDefaultExecutor.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(effectiveExecutor(ctx))
                            .as("우리 설정이 자동설정보다 나중에 적용돼야 실효 실행기가 경계 있는 풀로 남는다")
                            .isSameAs(ctx.getBean("mvcAsyncTaskExecutor", ThreadPoolTaskExecutor.class));
                });
    }

    @Test
    @DisplayName("yml의_비동기_제한시간이_실제_어댑터까지_도달한다")
    void ymlTimeoutReachesTheAdapter() {
        // 실효 제한시간의 진실원은 yml 이다. 우리 설정이 실행기를 꽂으면서 제한시간을 함께 덮으면
        // 그 계약이 조용히 깨지는데, 설정 객체만 보는 테스트로는 "덮지 않았다" 까지만 알 수 있다.
        long declared = Long.parseLong(
                String.valueOf(MainResourceYaml.rawValue(COMMON_YML, ASYNC_TIMEOUT_KEY)));

        runner.withUserConfiguration(AsyncConfig.class, MvcAsyncExecutorConfig.class)
                .run(ctx -> assertThat(readField(adapter(ctx), Long.class))
                        .as("어댑터에 도달한 제한시간(ms)이 yml 선언값과 달라졌다")
                        .isEqualTo(declared));
    }

    /** {@code applicationTaskExecutor} 가 되살아난 상황 재현용. */
    @Configuration(proxyBeanMethods = false)
    static class ResurrectedDefaultExecutor {
        @Bean(name = TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
        AsyncTaskExecutor applicationTaskExecutor() {
            return new SimpleAsyncTaskExecutor("resurrected-");
        }
    }

    private static RequestMappingHandlerAdapter adapter(AssertableWebApplicationContext ctx) {
        return ctx.getBean(RequestMappingHandlerAdapter.class);
    }

    private static AsyncTaskExecutor effectiveExecutor(AssertableWebApplicationContext ctx) {
        return readField(adapter(ctx), AsyncTaskExecutor.class);
    }

    /**
     * 어댑터가 들고 있는 값을 <b>타입으로</b> 찾아 읽는다(게터 없음). 필드명 하드코딩을 피해 프레임워크
     * 리네임에 견디게 한다 — 이름이 바뀌어 조용히 null 을 읽고 통과하는 것이 가장 나쁜 실패다.
     */
    private static <T> T readField(RequestMappingHandlerAdapter adapter, Class<T> type) {
        for (Field field : RequestMappingHandlerAdapter.class.getDeclaredFields()) {
            if (!field.getType().equals(type)) {
                continue;
            }
            field.setAccessible(true);
            try {
                T value = type.cast(field.get(adapter));
                assertThat(value).as("RequestMappingHandlerAdapter.%s 가 비어 있다", field.getName()).isNotNull();
                return value;
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("어댑터 필드 접근 실패: " + field.getName(), e);
            }
        }
        throw new IllegalStateException(
                "RequestMappingHandlerAdapter 에 " + type.getSimpleName() + " 필드가 없다"
                        + " — 프레임워크 구조가 바뀌었으므로 이 테스트의 판정 방식을 다시 정해야 한다");
    }
}
