package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.video.service.TrainingVideoIngestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlTrainingVideoScanJobTest {

    @Test
    @DisplayName("ControlTrainingVideoScanJob은_DisallowConcurrentExecution_애너테이션_보유")
    void hasDisallowConcurrentExecutionAnnotation() {
        // 동시 tick 차단이 어노테이션 레벨에 보장됨을 검증 (멱등성 1차 방어).
        assertThat(ControlTrainingVideoScanJob.class.isAnnotationPresent(DisallowConcurrentExecution.class)).isTrue();
    }

    @Test
    @DisplayName("execute시_TrainingVideoIngestService_scanAndIngest_호출")
    void executeInvokesScanAndIngest() throws Exception {
        ControlTrainingVideoScanJob job = new ControlTrainingVideoScanJob();
        TrainingVideoIngestService ingestService = mock(TrainingVideoIngestService.class);
        injectField(job, "ingestService", ingestService);
        when(ingestService.scanAndIngest()).thenReturn(3);

        job.execute(mock(JobExecutionContext.class));

        verify(ingestService).scanAndIngest();
    }

    @Test
    @DisplayName("scanAndIngest_예외시_Job은_안전하게_종료")
    void scanFailureIsAbsorbed() throws Exception {
        ControlTrainingVideoScanJob job = new ControlTrainingVideoScanJob();
        TrainingVideoIngestService ingestService = mock(TrainingVideoIngestService.class);
        injectField(job, "ingestService", ingestService);
        when(ingestService.scanAndIngest()).thenThrow(new RuntimeException("scan failed"));

        // execute 자체는 예외를 던지지 않아야 한다 (Quartz misfire 방지).
        job.execute(mock(JobExecutionContext.class));
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
