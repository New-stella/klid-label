package kr.co.cudo.authoring.batch.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BatchRetryQueueTest {

    @Test
    @DisplayName("최초_enqueue는_attempt_1_+_재시도_가능")
    void firstEnqueueAccepted() {
        BatchRetryQueue q = new BatchRetryQueue(3, 60);
        boolean accepted = q.enqueueIfRetryable(100L);
        assertThat(accepted).isTrue();
        assertThat(q.retryCount(100L)).isEqualTo(1);
    }

    @Test
    @DisplayName("max_attempts_3_초과시_4번째_enqueue는_거부")
    void overMaxAttemptsRejected() {
        BatchRetryQueue q = new BatchRetryQueue(3, 60);
        assertThat(q.enqueueIfRetryable(101L)).isTrue(); // 1
        assertThat(q.enqueueIfRetryable(101L)).isTrue(); // 2
        assertThat(q.enqueueIfRetryable(101L)).isTrue(); // 3
        assertThat(q.enqueueIfRetryable(101L)).isFalse(); // 4 → 거부

        // 그러나 retryCount 자체는 증가 (총 시도 횟수 추적용)
        assertThat(q.retryCount(101L)).isEqualTo(4);
    }

    @Test
    @DisplayName("clear_호출시_retry_메타_제거")
    void clearRemovesEntry() {
        BatchRetryQueue q = new BatchRetryQueue(3, 60);
        q.enqueueIfRetryable(102L);
        q.clear(102L);
        assertThat(q.retryCount(102L)).isEqualTo(0);
    }

    @Test
    @DisplayName("pollReady_즉시_호출시_빈_옵셔널_반환_(아직_지연시간_경과_X)")
    void pollReadyEmptyImmediately() {
        BatchRetryQueue q = new BatchRetryQueue(3, 60);
        q.enqueueIfRetryable(103L);
        Optional<Long> ready = q.pollReady();
        assertThat(ready).isEmpty();
    }

    @Test
    @DisplayName("초기지연_0초_설정시_즉시_pollReady_가능")
    void zeroDelayPollsReady() {
        BatchRetryQueue q = new BatchRetryQueue(3, 0);
        q.enqueueIfRetryable(104L);
        // initial-delay-sec=0 → 60*(2^0)=0 → 즉시 가능
        // 그러나 BatchRetryQueue 식: initialDelaySec * (1L << (attempt-1))
        // = 0 * 1 = 0 (delay 0)
        Optional<Long> ready = q.pollReady();
        assertThat(ready).contains(104L);
    }

    @Test
    @DisplayName("동시_enqueueIfRetryable_호출_안전성_AtomicInteger")
    void concurrentEnqueueAtomic() throws InterruptedException {
        BatchRetryQueue q = new BatchRetryQueue(1000, 60);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        int n = 100;
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger acceptedCount = new AtomicInteger(0);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (q.enqueueIfRetryable(999L)) acceptedCount.incrementAndGet();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // 모두 max(1000) 이하이므로 모두 accepted, retryCount 정확히 n
        assertThat(acceptedCount.get()).isEqualTo(n);
        assertThat(q.retryCount(999L)).isEqualTo(n);
    }
}
