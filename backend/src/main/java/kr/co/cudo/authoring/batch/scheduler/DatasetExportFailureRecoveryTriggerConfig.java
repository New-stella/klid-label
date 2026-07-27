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
 * D-ISSUE-04(b) — 실패 export 재시도 잡 등록.
 *
 * <p>{@code authoring.dataset-export.failure-recovery.interval-sec}(기본 900초=15분) 간격으로
 * {@link DatasetExportFailureRecoveryJob} 을 발화한다.
 * {@code authoring.dataset-export.failure-recovery.enabled=false} 시 잡 자체가 등록되지 않는다
 * (test/local 격리용 — {@link DatasetExportPendingSweepTriggerConfig} 와 동일 패턴).
 */
@Configuration
@ConditionalOnProperty(prefix = "authoring.dataset-export.failure-recovery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DatasetExportFailureRecoveryTriggerConfig {

    @Value("${authoring.dataset-export.failure-recovery.interval-sec:900}")
    private int intervalSec;

    @Bean
    public JobDetail datasetExportFailureRecoveryJobDetail() {
        return JobBuilder.newJob(DatasetExportFailureRecoveryJob.class)
                .withIdentity(DatasetExportFailureRecoveryJob.JOB_NAME, DatasetExportFailureRecoveryJob.JOB_GROUP)
                .storeDurably()
                .withDescription("실패한 학습데이터 산출(export) 재시도")
                .build();
    }

    @Bean
    public Trigger datasetExportFailureRecoveryTrigger(JobDetail datasetExportFailureRecoveryJobDetail) {
        // 부트 후 120초 뒤 첫 발화(기동 직후 승인 폭주와 겹치지 않게 PENDING sweep 보다 늦춘다). 이후 반복.
        return TriggerBuilder.newTrigger()
                .forJob(datasetExportFailureRecoveryJobDetail)
                .withIdentity(DatasetExportFailureRecoveryJob.TRIGGER_NAME,
                        DatasetExportFailureRecoveryJob.JOB_GROUP)
                .startAt(new java.util.Date(System.currentTimeMillis() + 120_000L))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(intervalSec)
                        .repeatForever())
                .build();
    }
}
