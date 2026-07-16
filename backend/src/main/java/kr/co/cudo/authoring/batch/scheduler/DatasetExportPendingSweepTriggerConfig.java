package kr.co.cudo.authoring.batch.scheduler;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 학습데이터 산출 stale PENDING 정리 잡 등록.
 *
 * <p>{@code authoring.dataset-export.pending-sweep.interval-sec} (기본 600초=10분) 간격으로
 * {@link DatasetExportPendingSweepJob} 을 발화한다. {@code authoring.dataset-export.pending-sweep.enabled=false}
 * 시 잡 자체가 등록되지 않는다(test/local 격리용 — {@link ControlTrainingVideoScanTriggerConfig} 와 동일 패턴).
 */
@Configuration
@ConditionalOnProperty(prefix = "authoring.dataset-export.pending-sweep", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DatasetExportPendingSweepTriggerConfig {

    @Value("${authoring.dataset-export.pending-sweep.interval-sec:600}")
    private int intervalSec;

    @Bean
    public JobDetail datasetExportPendingSweepJobDetail() {
        return JobBuilder.newJob(DatasetExportPendingSweepJob.class)
                .withIdentity(DatasetExportPendingSweepJob.JOB_NAME, DatasetExportPendingSweepJob.JOB_GROUP)
                .storeDurably()
                .withDescription("학습데이터 산출 stale PENDING 정리")
                .build();
    }

    @Bean
    public Trigger datasetExportPendingSweepTrigger(JobDetail datasetExportPendingSweepJobDetail) {
        // 부트 후 60초 뒤 첫 발화. 이후 interval-sec 간격 반복.
        return TriggerBuilder.newTrigger()
                .forJob(datasetExportPendingSweepJobDetail)
                .withIdentity(DatasetExportPendingSweepJob.TRIGGER_NAME, DatasetExportPendingSweepJob.JOB_GROUP)
                .startAt(new java.util.Date(System.currentTimeMillis() + 60_000L))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(intervalSec)
                        .repeatForever())
                .build();
    }
}
