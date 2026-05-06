package kr.co.cudo.authoring.version;

import kr.co.cudo.authoring.version.async.GiteaCommitFallbackQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GiteaCommitFallbackQueueTest {

    @Test
    @DisplayName("재시도_5회_초과시_큐에서_제거")
    void exceedingMaxAttemptsRemovesFromQueue() {
        GiteaCommitFallbackQueue queue = new GiteaCommitFallbackQueue();
        queue.enqueue(100L, "{\"items\":[]}", "1");

        // 5회 재시도까지는 다시 큐로 돌아옴 — 6번째 시도에서 폐기
        for (int i = 0; i < GiteaCommitFallbackQueue.MAX_RETRY_ATTEMPTS; i++) {
            Optional<GiteaCommitFallbackQueue.RetryItem> opt = queue.poll();
            assertThat(opt).isPresent();
            queue.requeueIfRetryable(opt.get());
            assertThat(queue.size()).isEqualTo(1);
        }

        // 6번째: requeueIfRetryable 시 한도 초과 → 폐기 → 큐 비어있음
        Optional<GiteaCommitFallbackQueue.RetryItem> last = queue.poll();
        assertThat(last).isPresent();
        queue.requeueIfRetryable(last.get());
        assertThat(queue.size()).isZero();
        assertThat(last.get().retryCount()).isGreaterThan(GiteaCommitFallbackQueue.MAX_RETRY_ATTEMPTS);
    }

    @Test
    @DisplayName("enqueue_와_poll_FIFO_순서_보장")
    void enqueuePollIsFifo() {
        GiteaCommitFallbackQueue queue = new GiteaCommitFallbackQueue();
        queue.enqueue(1L, "a", "u1");
        queue.enqueue(2L, "b", "u2");
        queue.enqueue(3L, "c", "u3");

        assertThat(queue.poll().orElseThrow().getSrcSn()).isEqualTo(1L);
        assertThat(queue.poll().orElseThrow().getSrcSn()).isEqualTo(2L);
        assertThat(queue.poll().orElseThrow().getSrcSn()).isEqualTo(3L);
        assertThat(queue.poll()).isEmpty();
    }
}
