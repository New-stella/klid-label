package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.queue.entity.MngClipScheduleQue;
import kr.co.cudo.authoring.batch.queue.service.LabelingBatchQueueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchQuartzJobTest {

    @Test
    @DisplayName("BatchQuartzJob은_DisallowConcurrentExecution_애너테이션_보유")
    void hasDisallowConcurrentExecutionAnnotation() {
        // 동시 실행 방지가 어노테이션 레벨에 보장되어 있음을 검증.
        // (Quartz 가 JobKey 단위로 BLOCKED 상태를 강제하므로 어노테이션 존재만 확인하면 충분.)
        assertThat(BatchQuartzJob.class.isAnnotationPresent(DisallowConcurrentExecution.class)).isTrue();
    }

    @Test
    @DisplayName("dequeueOne이_empty면_orchestrator_미호출_no_op")
    void dequeueEmptyIsNoOp() throws Exception {
        BatchQuartzJob job = new BatchQuartzJob();
        LabelingBatchQueueService queueService = mock(LabelingBatchQueueService.class);
        BatchOrchestrator orchestrator = mock(BatchOrchestrator.class);
        injectField(job, "queueService", queueService);
        injectField(job, "orchestrator", orchestrator);

        when(queueService.dequeueOne()).thenReturn(Optional.empty());

        job.execute(mock(JobExecutionContext.class));

        verify(orchestrator, never()).process(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("dequeueOne_성공시_orchestrator_process_호출")
    void dequeueProcessesOrchestrator() throws Exception {
        BatchQuartzJob job = new BatchQuartzJob();
        LabelingBatchQueueService queueService = mock(LabelingBatchQueueService.class);
        BatchOrchestrator orchestrator = mock(BatchOrchestrator.class);
        injectField(job, "queueService", queueService);
        injectField(job, "orchestrator", orchestrator);

        MngClipScheduleQue queue = MngClipScheduleQue.enqueueLabelingBatch(7777L);
        when(queueService.dequeueOne()).thenReturn(Optional.of(queue));
        when(orchestrator.process(7777L)).thenReturn(BatchStage.COMPLETED);

        job.execute(mock(JobExecutionContext.class));

        verify(orchestrator).process(eq(7777L));
    }

    @Test
    @DisplayName("orchestrator_예외시_Job은_안전하게_종료")
    void orchestratorFailureIsAbsorbed() throws Exception {
        BatchQuartzJob job = new BatchQuartzJob();
        LabelingBatchQueueService queueService = mock(LabelingBatchQueueService.class);
        BatchOrchestrator orchestrator = mock(BatchOrchestrator.class);
        injectField(job, "queueService", queueService);
        injectField(job, "orchestrator", orchestrator);

        MngClipScheduleQue queue = MngClipScheduleQue.enqueueLabelingBatch(8888L);
        when(queueService.dequeueOne()).thenReturn(Optional.of(queue));
        when(orchestrator.process(8888L)).thenThrow(new RuntimeException("unexpected"));

        // execute 자체는 예외를 던지지 않아야 한다 (Quartz misfire 방지).
        job.execute(mock(JobExecutionContext.class));
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
