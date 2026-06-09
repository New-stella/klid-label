package kr.co.cudo.authoring.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * {@code @Async} 전용 스레드풀 구성 (DoS 방어 — CWE-400 Unrestricted Resource Consumption).
 *
 * <p>문제: {@code @EnableAsync} 만 두면 기본 executor 가 {@code SimpleAsyncTaskExecutor} 로,
 * 매 작업마다 새 스레드를 무제한 생성한다. 대량 영상 적재 시 비식별(70s 블로킹) 러너가
 * 동시 폭주하면 스레드 폭증으로 서버가 고갈된다.
 *
 * <p>해결: 경계가 있는 {@link ThreadPoolTaskExecutor}({@code batchAsyncExecutor}) 를 등록하고,
 * 큐가 가득 차면 {@link ThreadPoolExecutor.CallerRunsPolicy} 로 호출 스레드가 직접 실행(역압)해
 * 무제한 적재를 차단한다. 비식별은 외부 API 블로킹이라 풀 크기를 작게 유지한다.
 *
 * <p>각 러너는 {@code @Async("batchAsyncExecutor")} 로 이 풀을 명시 지정한다.
 * {@code @EnableAsync} 는 본 클래스로 일원화한다(AuthoringApplication 에서 제거).
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 배치/비식별 비동기 작업 전용 스레드풀 빈. */
    @Bean(name = "batchAsyncExecutor")
    public Executor batchAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 외부 비식별 API 가 70s 블로킹이므로 풀을 작게 유지 — 동시 비식별 4건 상한.
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // 큐 50 초과 시 CallerRunsPolicy 로 역압(호출 스레드가 직접 실행) → 무제한 스레드 생성 차단.
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("batch-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 종료 시 진행 중 작업 대기 (graceful shutdown)
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
