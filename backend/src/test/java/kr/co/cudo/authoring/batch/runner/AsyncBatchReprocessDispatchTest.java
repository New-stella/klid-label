package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.config.AsyncConfig;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 수동 재기동이 <b>정말로 비동기로 도는지</b>와 전용 풀 구성을 고정하는 배선 테스트. [@design API-167]
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * <p>{@code @Async} 는 AOP 프록시를 거쳐야 동작한다. 실행부를 호출자와 같은 빈으로 합치면(self-invocation)
 * 프록시를 우회해 <b>여전히 동기로</b> 돌고, 단위 테스트는 전부 통과하는데 실기동만 안 고쳐진다.
 * 그래서 "호출이 즉시 반환하는가"와 "어느 스레드에서 실행되는가"를 직접 관측한다.
 *
 * <p>파이프라인이 블로킹 중인 동안 호출이 반환하는지로 판정하되, 회귀 시 <b>테스트가 매달리지 않도록</b>
 * 블로킹에 상한을 둔다 — 동기로 되돌아가면 호출이 {@value #BLOCK_MILLIS}ms 붙잡혀 임계값을 넘겨 실패한다.
 */
@SpringJUnitConfig(classes = {
        AsyncConfig.class,
        AsyncBatchReprocessRunner.class,
        AsyncBatchReprocessDispatchTest.MockCollaborators.class})
class AsyncBatchReprocessDispatchTest {

    /** 파이프라인이 붙잡혀 있는 시간 — 동기 회귀 시 호출이 이만큼 지연된다. */
    private static final long BLOCK_MILLIS = 2_000L;
    /** 접수(디스패치)가 이 이상 걸리면 동기 실행으로 본다. 풀 기동 여유를 포함한 값이다. */
    private static final long DISPATCH_BUDGET_MILLIS = 1_000L;

    @Configuration
    static class MockCollaborators {
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
    @DisplayName("★재기동_호출은_파이프라인_완료를_기다리지_않고_즉시_반환한다")
    void dispatchReturnsWithoutWaitingForPipeline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();
        when(orchestrator.processWithHeldStageClaim(anyLong())).thenAnswer(inv -> {
            workerThread.set(Thread.currentThread().getName());
            entered.countDown();
            Thread.sleep(BLOCK_MILLIS); // 프레임 추출·ai 추론·외부 위탁을 대신하는 블로킹
            return BatchStage.COMPLETED;
        });

        long start = System.nanoTime();
        runner.runAsync(1L, LsDataRaw.DATA_STTS_FAILED);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        // ① 호출은 파이프라인이 끝나기 전에 돌아온다(동기면 BLOCK_MILLIS 만큼 붙잡힌다).
        assertThat(elapsedMillis).isLessThan(DISPATCH_BUDGET_MILLIS);
        // ② 파이프라인은 실제로 시작됐다(디스패치가 조용히 버려지지 않았다).
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        // ③ 실행 스레드는 재기동 전용 풀이다 — 요청 스레드(CallerRuns)도, 다른 배치 풀도 아니다.
        assertThat(workerThread.get()).startsWith("batch-reprocess-");
    }

    @Test
    @DisplayName("재기동_전용_풀은_경계가_있고_포화시_거부한다_호출스레드로_떠넘기지_않는다")
    void dedicatedPoolIsBoundedAndAborts(
            @Autowired @Qualifier("batchReprocessExecutor") Executor executor) {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        ThreadPoolExecutor delegate = pool.getThreadPoolExecutor();

        // 무제한 풀 금지(CWE-770) — 스레드·큐 모두 경계가 있다.
        assertThat(pool.getCorePoolSize()).isEqualTo(2);
        assertThat(pool.getMaxPoolSize()).isEqualTo(4);
        // ★ 큐 = 실행 능력(maxPoolSize)의 1배. 이 값이 곧 "선점(PROCESSING 커밋)해 놓고 대기시키는
        //   최대 건수" 이며, 그 구간에 노드가 재기동되면 그만큼이 PROCESSING 으로 고착돼 애플리케이션
        //   안에 복구 수단이 남지 않는다. 키우려면 회수 수단이 먼저 있어야 한다.
        assertThat(delegate.getQueue().remainingCapacity())
                .as("재기동 큐는 실행 능력에 맞춰 좁게 유지한다 — 큐 깊이 = 고착 위험 폭")
                .isEqualTo(pool.getMaxPoolSize());
        // ★ CallerRunsPolicy 면 포화 시 <b>요청 스레드</b>에서 파이프라인이 돌아 이번 결함이 되살아난다.
        assertThat(delegate.getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }

    /**
     * ★일괄 상한(100)만큼을 <b>한 번에 접수하지 않는다</b> — 접수는 상태 선점을 동반하므로,
     * 접수 총량이 곧 재배포 시 {@code PROCESSING} 고착 위험 폭이다.
     *
     * <p>주입된 싱글턴 풀을 포화시키면 같은 컨텍스트를 공유하는 다른 테스트가 오염되므로,
     * <b>같은 설정으로 새 풀을 만들어</b> 검증한다(검증 대상은 인스턴스가 아니라 설정값이다).
     *
     * <p>수치는 {@link ThreadPoolExecutor} 규약에서 결정적으로 나온다 — core 2 가 먼저 스레드로,
     * 다음 queue 4 가 큐로, 큐가 찬 뒤 max 까지 2 가 스레드로, 그다음부터 거부.
     */
    @Test
    @DisplayName("★재기동_접수_총량은_일괄상한_100이_아니라_풀_용량으로_묶인다")
    void acceptanceIsBoundedByPoolNotByBulkLimit() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) new AsyncConfig().batchReprocessExecutor();
        CountDownLatch release = new CountDownLatch(1);
        int accepted = 0;
        try {
            for (int i = 0; i < 100; i++) {
                pool.execute(() -> {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                accepted++;
            }
            // 100건이 전부 들어갔다면 큐가 다시 넓어진 것이다(회귀).
            assertThat(accepted).as("포화에도 거부가 나지 않았다 — 접수 폭이 다시 무제한에 가까워졌다").isZero();
        } catch (org.springframework.core.task.TaskRejectedException expected) {
            // core(2) + queue(4) + 증설(max-core=2) = 8 건까지만 접수된다.
            assertThat(accepted)
                    .as("선점된 채 남을 수 있는 최대 건수 — 이 값이 커질수록 재배포 시 고착 폭이 커진다")
                    .isEqualTo(8);
        } finally {
            release.countDown();
            pool.shutdown();
        }
    }
}
