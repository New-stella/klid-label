package kr.co.cudo.authoring.batch.queue;

import kr.co.cudo.authoring.batch.queue.entity.MngClipScheduleQue;
import kr.co.cudo.authoring.batch.queue.repository.MngClipScheduleQueRepository;
import kr.co.cudo.authoring.batch.queue.service.LabelingBatchQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
class LabelingBatchQueueServiceTest {

    @Autowired private LabelingBatchQueueService queueService;
    @Autowired private MngClipScheduleQueRepository queueRepository;

    @BeforeEach
    void setup() {
        queueRepository.deleteAll();
    }

    @Test
    @DisplayName("enqueue_후_dequeueOne_동일_레코드_반환")
    void enqueueThenDequeueReturnsSameRecord() {
        MngClipScheduleQue enqueued = queueService.enqueue(9001L);

        Optional<MngClipScheduleQue> dequeued = queueService.dequeueOne();

        assertThat(dequeued).isPresent();
        assertThat(dequeued.get().getQueSn()).isEqualTo(enqueued.getQueSn());
        assertThat(dequeued.get().getRawSn()).isEqualTo(9001L);
        assertThat(dequeued.get().getJobType()).isEqualTo(MngClipScheduleQue.JOB_LABELING_BATCH);
    }

    @Test
    @DisplayName("dequeueOne_은_PENDING_상태만_반환")
    void dequeueOneOnlyReturnsPending() {
        // 첫 번째 enqueue → 즉시 dequeue 하면 IN_PROGRESS 로 전이
        queueService.enqueue(9100L);
        Optional<MngClipScheduleQue> first = queueService.dequeueOne();
        assertThat(first).isPresent();

        // 같은 레코드 다시 dequeue 해도 PENDING 이 없으므로 empty
        Optional<MngClipScheduleQue> second = queueService.dequeueOne();
        assertThat(second).isEmpty();

        // 새 레코드를 enqueue 하면 다시 dequeue 가능
        queueService.enqueue(9101L);
        Optional<MngClipScheduleQue> third = queueService.dequeueOne();
        assertThat(third).isPresent();
        assertThat(third.get().getRawSn()).isEqualTo(9101L);
    }

    /**
     * Phase 5 사전 대비: dequeueOne 동시성 갭 검증.
     * - 단일 PENDING 레코드를 두 스레드가 동시에 dequeue 시도.
     * - PESSIMISTIC_WRITE + lock.timeout=0 정책으로 한쪽만 IN_PROGRESS 전이에 성공해야 한다.
     * - 다른 한쪽은 락 충돌로 empty 반환 또는 첫 번째 커밋 후 PENDING 이 사라져 empty.
     * - 두 스레드가 동일 레코드를 중복 처리하는 일이 없는지 확인.
     */
    @Test
    @DisplayName("dequeueOne_동시_호출시_한쪽만_레코드_반환")
    void dequeueOneConcurrentReturnsExclusiveRecord() throws Exception {
        MngClipScheduleQue enqueued = queueService.enqueue(9200L);
        Long expectedQueSn = enqueued.getQueSn();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Optional<MngClipScheduleQue>> r1 = new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<MngClipScheduleQue>> r2 = new AtomicReference<>(Optional.empty());
        AtomicReference<Throwable> err = new AtomicReference<>();

        Runnable task1 = () -> {
            try {
                ready.countDown();
                start.await();
                r1.set(queueService.dequeueOne());
            } catch (Throwable t) {
                err.set(t);
            }
        };
        Runnable task2 = () -> {
            try {
                ready.countDown();
                start.await();
                r2.set(queueService.dequeueOne());
            } catch (Throwable t) {
                err.set(t);
            }
        };

        executor.submit(task1);
        executor.submit(task2);
        ready.await(2, TimeUnit.SECONDS);
        start.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(finished).isTrue();
        assertThat(err.get()).isNull();

        // 정확히 한 스레드만 레코드를 받아야 한다 (XOR).
        boolean p1 = r1.get().isPresent();
        boolean p2 = r2.get().isPresent();
        assertThat(p1 ^ p2).as("정확히 한쪽 스레드만 레코드를 받아야 한다 (p1=%s, p2=%s)", p1, p2).isTrue();

        Optional<MngClipScheduleQue> winner = p1 ? r1.get() : r2.get();
        assertThat(winner.get().getQueSn()).isEqualTo(expectedQueSn);

        // DB 상태도 IN_PROGRESS 1건만 있어야 한다.
        List<MngClipScheduleQue> all = queueRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getStatus()).isEqualTo(MngClipScheduleQue.STATUS_IN_PROGRESS);
    }
}
