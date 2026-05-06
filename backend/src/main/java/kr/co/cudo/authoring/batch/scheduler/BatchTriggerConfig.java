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
 * 배치 파이프라인 잡 등록 (Phase 5).
 *  - BATCH_INTERVAL_SEC (기본 60초) 간격으로 BatchQuartzJob 발화.
 *  - BATCH_ENABLED=false 시 잡 자체가 등록되지 않음 (test/local 격리용).
 */
@Configuration
@ConditionalOnProperty(prefix = "authoring.batch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BatchTriggerConfig {

    @Value("${authoring.batch.interval-sec:60}")
    private int intervalSec;

    @Bean
    public JobDetail batchPipelineJobDetail() {
        return JobBuilder.newJob(BatchQuartzJob.class)
                .withIdentity(BatchQuartzJob.JOB_NAME, BatchQuartzJob.JOB_GROUP)
                .storeDurably()
                .withDescription("Phase 5 배치 파이프라인 (1건/분 처리)")
                .build();
    }

    @Bean
    public Trigger batchPipelineTrigger(JobDetail batchPipelineJobDetail) {
        // 부트 후 30초 뒤 첫 발화. 이후 BATCH_INTERVAL_SEC 간격 반복.
        return TriggerBuilder.newTrigger()
                .forJob(batchPipelineJobDetail)
                .withIdentity(BatchQuartzJob.TRIGGER_NAME, BatchQuartzJob.JOB_GROUP)
                .startAt(new java.util.Date(System.currentTimeMillis() + 30_000L))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(intervalSec)
                        .repeatForever())
                .build();
    }
}
