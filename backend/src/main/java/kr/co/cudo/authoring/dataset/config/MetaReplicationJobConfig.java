package kr.co.cudo.authoring.dataset.config;

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
 * 포털 메타 복제 워커 Quartz 등록.
 *
 * <p>{@code authoring.meta-replication.interval-sec}(기본 60초) 간격으로 {@link MetaReplicationQuartzJob}
 * 을 발화한다. {@code authoring.meta-replication.enabled=false} 시 잡 자체가 등록되지 않는다
 * (test/local 격리용 — {@code ControlTrainingVideoScanTriggerConfig} 와 동일 패턴). 운영(dev/stg/prd)은
 * 기본값(matchIfMissing=true)으로 활성화된다.
 */
@Configuration
@ConditionalOnProperty(prefix = "authoring.meta-replication", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MetaReplicationJobConfig {

    @Value("${authoring.meta-replication.interval-sec:60}")
    private int intervalSec;

    @Bean
    public JobDetail metaReplicationJobDetail() {
        return JobBuilder.newJob(MetaReplicationQuartzJob.class)
                .withIdentity(MetaReplicationQuartzJob.JOB_NAME, MetaReplicationQuartzJob.JOB_GROUP)
                .storeDurably()
                .withDescription("포털향 통합 메타 outbox → 포털 DB 단방향 복제")
                .build();
    }

    @Bean
    public Trigger metaReplicationTrigger(JobDetail metaReplicationJobDetail) {
        // 부트 후 30초 뒤 첫 발화. 이후 interval-sec 간격 반복.
        return TriggerBuilder.newTrigger()
                .forJob(metaReplicationJobDetail)
                .withIdentity(MetaReplicationQuartzJob.TRIGGER_NAME, MetaReplicationQuartzJob.JOB_GROUP)
                .startAt(new java.util.Date(System.currentTimeMillis() + 30_000L))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(intervalSec)
                        .repeatForever())
                .build();
    }
}
