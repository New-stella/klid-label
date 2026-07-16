package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.dataset.export.DatasetExportPendingSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetExportPendingSweepJobTest {

    @Test
    @DisplayName("DatasetExportPendingSweepJob은_DisallowConcurrentExecution_애너테이션_보유")
    void hasDisallowConcurrentExecutionAnnotation() {
        // 클러스터 2노드 중 동일 JobKey 동시 tick 차단이 어노테이션 레벨에 보장됨을 검증.
        assertThat(DatasetExportPendingSweepJob.class.isAnnotationPresent(DisallowConcurrentExecution.class)).isTrue();
    }

    @Test
    @DisplayName("execute시_Sweeper_sweep_호출")
    void executeInvokesSweep() throws Exception {
        DatasetExportPendingSweepJob job = new DatasetExportPendingSweepJob();
        DatasetExportPendingSweeper sweeper = mock(DatasetExportPendingSweeper.class);
        injectField(job, "sweeper", sweeper);
        when(sweeper.sweep()).thenReturn(2);

        job.execute(mock(JobExecutionContext.class));

        verify(sweeper).sweep();
    }

    @Test
    @DisplayName("sweep_예외시_Job은_안전하게_종료 — 예외를 삼키고 재던지지 않음")
    void sweepFailureIsAbsorbed() throws Exception {
        DatasetExportPendingSweepJob job = new DatasetExportPendingSweepJob();
        DatasetExportPendingSweeper sweeper = mock(DatasetExportPendingSweeper.class);
        injectField(job, "sweeper", sweeper);
        when(sweeper.sweep()).thenThrow(new RuntimeException("sweep failed"));

        // execute 자체는 예외를 던지지 않아야 한다 (Quartz misfire 방지).
        job.execute(mock(JobExecutionContext.class));
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
