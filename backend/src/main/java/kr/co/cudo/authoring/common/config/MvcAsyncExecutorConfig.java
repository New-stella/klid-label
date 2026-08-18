package kr.co.cudo.authoring.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring MVC 비동기 응답({@code StreamingResponseBody}) 실행기 — <b>경계 있는 풀</b> 명시 지정.
 *
 * <h3>왜 필요한가 (실측으로 확인한 back-off)</h3>
 * <p>Spring Boot 의 {@code TaskExecutionAutoConfiguration#applicationTaskExecutor} 는
 * {@code @ConditionalOnMissingBean(Executor.class)} 라, 애플리케이션이 {@link java.util.concurrent.Executor}
 * 빈을 하나라도 직접 등록하면 <b>물러난다</b>. 이 프로젝트는 {@link AsyncConfig} 가 배치·위탁용 풀을
 * 여러 개 등록하므로 그 조건이 이미 성립한다(격리 재현: {@code AutoConfigurations.of(TaskExecutionAutoConfiguration)}
 * 단독이면 빈 있음 → {@code AsyncConfig} 를 얹으면 빈 없음).
 *
 * <p>그리고 {@code WebMvcAutoConfiguration} 은 <b>그 이름의 빈이 있을 때만</b> MVC 비동기 실행기로
 * 지정한다. 빠지면 {@code RequestMappingHandlerAdapter} 의 기본값인
 * {@link org.springframework.core.task.SimpleAsyncTaskExecutor} 로 떨어지는데, 그것은 <b>요청마다 새
 * 스레드를 만들고 상한이 없다</b>(CWE-400).
 *
 * <h3>왜 지금 문제가 되는가</h3>
 * <p>비동기 응답의 절대 제한시간({@code spring.mvc.async.request-timeout})이 30초에서 <b>30분</b>으로
 * 늘었다. 경계 없는 실행기 위에서는 그 변경의 대가가 60배가 된다 — 느린 회선·조용히 멈춘 연결마다
 * 스레드와 커넥션을 30분씩 붙잡는다. 제한시간을 되돌리면 대용량 내려받기가 다시 전부 잘리므로,
 * 되돌릴 것은 제한시간이 아니라 <b>경계 없는 실행기</b> 쪽이다.
 *
 * <h3>포화 정책 = CallerRuns (Abort 아님)</h3>
 * <p>여기서 거부하면 사용자에게는 <b>내려받기 실패</b>로 보인다. CallerRuns 면 컨테이너 요청 스레드가
 * 그대로 스트리밍을 수행해 실패 없이 되밀리고, 그 순간 동시성의 실질 상한은 <b>컨테이너 스레드 수</b>가
 * 된다 — 즉 경계는 유지된다. 이 축에서 우리가 없애려는 것은 「무제한 스레드 생성」이지 「요청 수용」이
 * 아니다.
 *
 * <p>⚠ <b>이 설정은 MVC 비동기 축만 건드린다.</b> {@code @Async} 러너들의 풀({@link AsyncConfig})은
 * 각자 {@code @Async("...")} 로 자기 풀을 명시 지정하므로 영향을 받지 않는다.
 */
@Slf4j
@Configuration
public class MvcAsyncExecutorConfig implements WebMvcConfigurer {

    /** 동시 스트리밍 응답 상한 — 이 수를 넘으면 컨테이너 요청 스레드가 직접 수행(CallerRuns). */
    public static final int MAX_POOL_SIZE = 16;
    /** 대기 큐 — 큐에 눕는 동안 클라이언트는 첫 바이트를 못 받으므로 얕게 둔다. */
    public static final int QUEUE_CAPACITY = 8;

    /**
     * 종료 시 진행 중 응답을 기다리는 <b>상한</b>(초).
     *
     * <p>커넥터 유예({@code spring.lifecycle.timeout-per-shutdown-phase})와 <b>합쳐진 값</b>이 프로세스의
     * 종료 예산이고, systemd {@code TimeoutStopSec} 은 그보다 커야 한다 — 작으면 SIGKILL 이 먼저 와서
     * 유예 설정이 통째로 무의미해진다. 그 관계는 {@code GracefulShutdownConfigGuardTest} 가 고정하므로
     * 이 값은 상수로 노출한다(리터럴로 두면 배포 예산과의 관계를 기계로 볼 수 없다).
     *
     * <p>실제로는 이 상한이 소진되지 않는다 — 커넥터가 닫힌 뒤 남은 작업은 소켓 쓰기가 즉시 실패해
     * 스스로 끝난다.
     */
    public static final long AWAIT_TERMINATION_SECONDS = 60L;

    /**
     * MVC 비동기 응답 전용 풀.
     *
     * <p>작업 1건 = 대용량 ZIP 스트리밍(디스크 읽기 + 소켓 쓰기)이라 CPU 가 아니라 I/O 대기가
     * 지배적이다. 그래서 코어 수보다 넉넉히 잡되 상한은 둔다.
     */
    @Bean(name = "mvcAsyncTaskExecutor")
    public ThreadPoolTaskExecutor mvcAsyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("mvc-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 스트리밍은 중간에 끊으면 파일이 잘린 채 내려간다 — 종료 시 진행 중 응답을 마치게 둔다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) AWAIT_TERMINATION_SECONDS);
        executor.initialize();
        return executor;
    }

    /**
     * {@code AsyncSupportConfigurer} 에 실행기만 지정한다.
     *
     * <p>제한시간({@code setDefaultTimeout})은 건드리지 않는다 — 그것은
     * {@code WebMvcAutoConfiguration} 이 {@code spring.mvc.async.request-timeout} 에서 읽어 적용하며,
     * 여기서 함께 지정하면 yml 이 진실원이라는 계약이 깨진다(회귀 고정:
     * {@code AsyncRequestTimeoutConfigGuardTest}).
     *
     * <p>{@code @Configuration}(proxyBeanMethods=true) 이므로 {@link #mvcAsyncTaskExecutor()} 직접 호출은
     * 싱글턴 빈을 돌려준다. 같은 클래스가 정의하는 빈을 생성자로 주입하면 순환 참조가 되므로 이 형태를 쓴다.
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        AsyncTaskExecutor executor = mvcAsyncTaskExecutor();
        configurer.setTaskExecutor(executor);
    }
}
