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
 * 관제 학습용 영상 픽업 스캔 잡 등록.
 *
 * <p>{@code authoring.control.training-scan.interval-sec} (기본 60초) 간격으로
 * {@link ControlTrainingVideoScanJob} 을 발화한다. {@code authoring.control.training-scan.enabled=false}
 * 시 잡 자체가 등록되지 않는다 (test/local 격리용 — {@code BatchTriggerConfig} 와 동일 패턴).
 */
@Configuration
@ConditionalOnProperty(prefix = "authoring.control.training-scan", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ControlTrainingVideoScanTriggerConfig {

    @Value("${authoring.control.training-scan.interval-sec:60}")
    private int intervalSec;

    @Bean
    public JobDetail controlTrainingVideoScanJobDetail() {
        return JobBuilder.newJob(ControlTrainingVideoScanJob.class)
                .withIdentity(ControlTrainingVideoScanJob.JOB_NAME, ControlTrainingVideoScanJob.JOB_GROUP)
                .storeDurably()
                .withDescription("관제 학습용 영상(JOB_DMND_YN='Y') 픽업 적재")
                .build();
    }

    @Bean
    public Trigger controlTrainingVideoScanTrigger(JobDetail controlTrainingVideoScanJobDetail) {
        // 부트 후 30초 뒤 첫 발화. 이후 interval-sec 간격 반복.
        return TriggerBuilder.newTrigger()
                .forJob(controlTrainingVideoScanJobDetail)
                .withIdentity(ControlTrainingVideoScanJob.TRIGGER_NAME, ControlTrainingVideoScanJob.JOB_GROUP)
                .startAt(new java.util.Date(System.currentTimeMillis() + 30_000L))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(intervalSec)
                        .repeatForever())
                .build();
    }
}
