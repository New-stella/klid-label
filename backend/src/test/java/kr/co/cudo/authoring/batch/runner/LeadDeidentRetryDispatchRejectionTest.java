package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.dto.BatchReprocessResponse;
import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.service.BatchReprocessService;
import kr.co.cudo.authoring.batch.service.DeidentRetryLockFixture;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 선두 비식별 실패 영상의 배치 재시작이 <b>실제로 포화 시 거부되는 풀</b>로 디스패치되는지 — 목으로 거부를
 * 흉내 내지 않고, 실제 {@link ThreadPoolTaskExecutor}(거부 정책·작은 큐)를 채워서 본다.
 *
 * <p>검증: ①접수된 건은 요청 스레드가 아니라 풀 스레드에서 돈다 ②풀이 차면 세 번째 요청이 503 이고
 * 그 요청이 잡은 잠금은 풀린다 ③접수된 두 건의 잠금은 유지된다. 선두 비식별 풀(호출자 실행)로 보냈다면
 * ②가 요청 스레드 실행으로 바뀌어 이 시험이 깨진다.
 *
 * <p>{@code @SpringBootTest} 가 아니라 컨텍스트 다양성 상한과 무관하다.
 *
 * @design API-167
 * @design AC-1133
 * @design AC-1135
 */
@SpringJUnitConfig(classes = LeadDeidentRetryDispatchRejectionTest.Config.class)
class LeadDeidentRetryDispatchRejectionTest {

    static final CountDownLatch RELEASE = new CountDownLatch(1);
    static final CountDownLatch FIRST_STARTED = new CountDownLatch(1);
    static final AtomicReference<String> FIRST_THREAD = new AtomicReference<>();

    @Configuration
    @EnableAsync
    static class Config {

        /** 동시 1건 + 큐 1건 — 세 번째 접수부터 거부된다(운영 풀과 같은 이름·같은 거부 정책). */
        @Bean(name = "batchReprocessExecutor")
        Executor batchReprocessExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(1);
            executor.setThreadNamePrefix("reprocess-sat-");
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
            executor.initialize();
            return executor;
        }

        /**
         * 선두 비식별 풀 — 호출자 실행 정책. 재시작이 여기로 잘못 보내지면 포화 시 요청 스레드에서 돌게 된다.
         * 이 시험에서는 누구도 이 풀을 쓰지 않아야 한다.
         */
        @Bean(name = "batchAsyncExecutor")
        Executor batchAsyncExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(1);
            executor.setThreadNamePrefix("deid-async-");
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
            executor.initialize();
            return executor;
        }

        @Bean
        DeidentRetryLockFixture locks() {
            return new DeidentRetryLockFixture();
        }

        @Bean
        VideoRepository videoRepository() {
            VideoRepository repo = mock(VideoRepository.class);
            when(repo.existsById(anyLong())).thenReturn(true);
            when(repo.findById(anyLong())).thenAnswer(inv -> {
                LsDataRaw raw = LsDataRaw.createFromIngest("clip-s", "cctv-s", "EVT", "GOV",
                        LsDataRaw.PRVC_TYPE_PRVC, "/raw/s.mp4", null, 60);
                ReflectionTestUtils.setField(raw, "rawSn", inv.getArgument(0));
                raw.markDeidentified("F");
                return Optional.of(raw);
            });
            return repo;
        }

        @Bean
        AsyncBatchReprocessRunner reprocessRunner() {
            AsyncDeidentifyRunner deidentifyRunner = mock(AsyncDeidentifyRunner.class);
            doAnswer(inv -> {
                FIRST_THREAD.compareAndSet(null, Thread.currentThread().getName());
                FIRST_STARTED.countDown();
                RELEASE.await(30, TimeUnit.SECONDS);
                return null;
            }).when(deidentifyRunner).runNow(anyLong());
            return new AsyncBatchReprocessRunner(mock(BatchOrchestrator.class),
                    mock(BatchTransitionService.class), mock(BatchStatusService.class), deidentifyRunner);
        }

        @Bean
        BatchReprocessService batchReprocessService(VideoRepository videoRepository,
                                                    AsyncBatchReprocessRunner reprocessRunner,
                                                    DeidentRetryLockFixture locks) {
            return new BatchReprocessService(videoRepository, mock(BatchTransitionService.class),
                    mock(BatchStatusService.class), reprocessRunner, mock(BatchRetryQueue.class),
                    locks.claimService(videoRepository));
        }
    }

    @Autowired private BatchReprocessService service;
    @Autowired private DeidentRetryLockFixture locks;

    @Test
    @DisplayName("★수동재기동_풀이_차면_선두비식별_재시작은_503이고_그_요청의_잠금만_풀린다")
    void 포화시_503과_잠금해제() throws Exception {
        try {
            BatchReprocessResponse first = service.retry(1L);
            assertThat(FIRST_STARTED.await(10, TimeUnit.SECONDS)).isTrue();
            BatchReprocessResponse second = service.retry(2L); // 큐 1칸

            CustomException third = catchThrowableOfType(() -> service.retry(3L), CustomException.class);

            assertThat(first.stage()).isEqualTo(LsDataRaw.STATUS_PENDING);
            assertThat(second.stage()).isEqualTo(LsDataRaw.STATUS_PENDING);
            assertThat(third).as("포화 거부가 호출자까지 와야 한다(호출자 실행이면 여기서 막혀 버린다)").isNotNull();
            assertThat(third.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(locks.activeRetryLocks(3L)).as("거부된 요청의 잠금은 남지 않는다").isZero();
            assertThat(locks.activeRetryLocks(1L)).isEqualTo(1);
            assertThat(locks.activeRetryLocks(2L)).isEqualTo(1);
            assertThat(FIRST_THREAD.get()).as("요청 스레드가 아니라 수동 재기동 풀에서 돈다")
                    .startsWith("reprocess-sat-");
        } finally {
            RELEASE.countDown();
        }
    }
}
