package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.dto.BatchStageProgress;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BatchStatusServiceTest {

    @Test
    @DisplayName("markStage_후_currentStage_조회_가능")
    void markAndQuery() {
        BatchStatusService svc = new BatchStatusService();
        svc.markStage(1L, BatchStage.YOLO);
        assertThat(svc.currentStage(1L)).isEqualTo(BatchStage.YOLO);
    }

    @Test
    @DisplayName("markFailed_시_retryCount_증가_+_errorMessage_저장")
    void markFailedIncrementsRetry() {
        BatchStatusService svc = new BatchStatusService();
        svc.markFailed(2L, new IllegalStateException("boom"));
        svc.markFailed(2L, new RuntimeException("again"));

        assertThat(svc.retryCount(2L)).isEqualTo(2);
        assertThat(svc.currentStage(2L)).isEqualTo(BatchStage.FAILED);

        List<BatchStageProgress> recent = svc.recent(10);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).errorMessage()).isEqualTo("RuntimeException");
    }

    @Test
    @DisplayName("recent는_lastUpdatedAt_DESC_정렬_+_limit_적용")
    void recentSortedAndLimited() throws InterruptedException {
        BatchStatusService svc = new BatchStatusService();
        svc.markStage(10L, BatchStage.YOLO);
        Thread.sleep(5);
        svc.markStage(11L, BatchStage.SAM2);
        Thread.sleep(5);
        svc.markStage(12L, BatchStage.VLM_VERIFY);

        List<BatchStageProgress> top2 = svc.recent(2);
        assertThat(top2).hasSize(2);
        assertThat(top2.get(0).rawSn()).isEqualTo(12L);
        assertThat(top2.get(1).rawSn()).isEqualTo(11L);
    }

    @Test
    @DisplayName("동시_markStage_호출_안전성_검증_ConcurrentHashMap")
    void concurrentMarksAreSafe() throws InterruptedException {
        BatchStatusService svc = new BatchStatusService();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        int n = 200;
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final long id = i;
            pool.submit(() -> {
                try {
                    start.await();
                    svc.markStage(id, BatchStage.YOLO);
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

        // recent(MAX_ENTRIES) 이내 모든 항목 반환되어야.
        assertThat(svc.recent(1000)).hasSize(n);
    }

    @Test
    @DisplayName("limit_0_이하는_1로_보정_+_상한_초과는_MAX_ENTRIES로_보정")
    void limitClamped() {
        BatchStatusService svc = new BatchStatusService();
        svc.markStage(1L, BatchStage.YOLO);
        assertThat(svc.recent(0)).hasSize(1); // 0 → 1 보정
        assertThat(svc.recent(-5)).hasSize(1);
        // upper limit 초과는 가능한 범위까지만
        assertThat(svc.recent(1_000_000)).hasSize(1);
    }
}
