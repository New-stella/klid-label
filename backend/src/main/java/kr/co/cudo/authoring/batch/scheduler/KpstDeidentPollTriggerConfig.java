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
 * KPST 비식별 폴링 잡 등록 (Phase 3 / UC018).
 *
 * <p>{@code kpst.deid.enabled=true} 일 때만 등록된다(콜백/동기 경로와 격리). 주기는
 * {@code kpst.deid.poll-interval-sec}(기본 30초). 부트 후 30초 뒤 첫 발화.
 */
@Configuration
@ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")
public class KpstDeidentPollTriggerConfig {

    @Value("${kpst.deid.poll-interval-sec:30}")
    private int pollIntervalSec;

    @Bean
    public JobDetail kpstDeidentPollJobDetail() {
        return JobBuilder.newJob(KpstDeidentPollJob.class)
                .withIdentity(KpstDeidentPollJob.JOB_NAME, KpstDeidentPollJob.JOB_GROUP)
                .storeDurably()
                .withDescription("UC018 KPST 비식별 완료감지 폴링")
                .build();
    }

    @Bean
    public Trigger kpstDeidentPollTrigger(JobDetail kpstDeidentPollJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(kpstDeidentPollJobDetail)
                .withIdentity(KpstDeidentPollJob.TRIGGER_NAME, KpstDeidentPollJob.JOB_GROUP)
                .startAt(new java.util.Date(System.currentTimeMillis() + 30_000L))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(pollIntervalSec)
                        .repeatForever())
                .build();
    }
}
