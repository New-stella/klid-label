package kr.co.cudo.authoring.batch.retry;

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
 * 재시도 큐 Quartz Job 등록 (Phase 5).
 *  - BATCH_RETRY_INTERVAL_SEC (기본 300초) 간격 발화.
 *  - BATCH_ENABLED=false 시 등록되지 않음.
 */
@Configuration
@ConditionalOnProperty(prefix = "authoring.batch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BatchRetryTriggerConfig {

    @Value("${authoring.batch.retry.interval-sec:300}")
    private int retryIntervalSec;

    @Bean
    public JobDetail batchRetryJobDetail() {
        return JobBuilder.newJob(BatchRetryQuartzJob.class)
                .withIdentity(BatchRetryQuartzJob.JOB_NAME, BatchRetryQuartzJob.JOB_GROUP)
                .storeDurably()
                .withDescription("Phase 5 배치 재시도 큐 처리")
                .build();
    }

    @Bean
    public Trigger batchRetryTrigger(JobDetail batchRetryJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(batchRetryJobDetail)
                .withIdentity(BatchRetryQuartzJob.TRIGGER_NAME, BatchRetryQuartzJob.JOB_GROUP)
                .startAt(new java.util.Date(System.currentTimeMillis() + 60_000L))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(retryIntervalSec)
                        .repeatForever())
                .build();
    }
}
