package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 풀이 포화됐을 때 <b>거부가 호출자에게 도달하는지</b>를 고정한다. [@design API-167]
 *
 * <p>{@code BatchReprocessService} 는 디스패치 거부를 <b>접수 실패</b>로 처리한다(클레임 보상 롤백 + 503).
 * 그 처리가 성립하려면 {@code AbortPolicy} 의 거부가 {@code @Async} 프록시를 통해
 * {@link TaskRejectedException} 으로 <b>호출 스레드까지 전파</b>돼야 한다 — 거부가 조용히 삼켜지면
 * 사용자는 접수됐다고 믿는데 아무것도 돌지 않고, 선점한 PROCESSING 도 되돌아가지 않는다.
 *
 * <p>실제 운영 풀(core 2/max 4/queue 4)을 포화시키는 것은 비싸므로, 같은 <b>빈 이름</b>으로 최소 크기
 * 풀을 두고 같은 포화 정책만 재현한다(검증 대상은 크기가 아니라 전파 경로다 — 운영 풀의 크기·정책은
 * {@code AsyncBatchReprocessDispatchTest} 가 따로 고정한다).
 */
@SpringJUnitConfig(classes = AsyncBatchReprocessRejectionTest.SaturatedPoolConfig.class)
class AsyncBatchReprocessRejectionTest {

    @Configuration
    @EnableAsync
    static class SaturatedPoolConfig {

        /** 동시 1건 + 큐 1건만 받는 최소 풀 — 세 번째 접수부터 거부된다. */
        @Bean(name = "batchReprocessExecutor")
        Executor batchReprocessExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(1);
            executor.setThreadNamePrefix("batch-reprocess-test-");
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
            executor.initialize();
            return executor;
        }

        @Bean
        AsyncBatchReprocessRunner runner(BatchOrchestrator orchestrator, BatchTransitionService transitionService,
                                        BatchStatusService batchStatusService) {
            return new AsyncBatchReprocessRunner(orchestrator, transitionService, batchStatusService,
                    mock(AsyncDeidentifyRunner.class));
        }

        @Bean
        BatchStatusService batchStatusService() {
            return mock(BatchStatusService.class);
        }

        @Bean
        BatchOrchestrator batchOrchestrator() {
            return mock(BatchOrchestrator.class);
        }

        @Bean
        BatchTransitionService batchTransitionService() {
            return mock(BatchTransitionService.class);
        }
    }

    @Autowired private AsyncBatchReprocessRunner runner;
    @Autowired private BatchOrchestrator orchestrator;

    @Test
    @DisplayName("★풀이_포화되면_거부가_호출자에게_전파된다_조용히_삼켜지지_않는다")
    void rejectionPropagatesToCaller() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(orchestrator.processWithHeldStageClaim(anyLong())).thenAnswer(inv -> {
            running.countDown();
            release.await(10, TimeUnit.SECONDS);
            return BatchStage.COMPLETED;
        });

        try {
            runner.runAsync(1L, LsDataRaw.DATA_STTS_FAILED);                                  // 스레드 점유
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            runner.runAsync(2L, LsDataRaw.DATA_STTS_FAILED);                                  // 큐 1칸 점유

            // 세 번째는 받을 자리가 없다 — 호출자가 이 예외를 잡아 접수 실패로 처리한다.
            assertThatThrownBy(() -> runner.runAsync(3L, LsDataRaw.DATA_STTS_FAILED))
                    .isInstanceOf(TaskRejectedException.class);
        } finally {
            release.countDown();
        }
    }
}
