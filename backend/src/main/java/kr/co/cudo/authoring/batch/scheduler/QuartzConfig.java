package kr.co.cudo.authoring.batch.scheduler;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

import javax.sql.DataSource;

/**
 * Quartz 부트스트랩 잡 등록 + DataSource 연결.
 *  - DataSourceAutoConfiguration 이 exclude 되어 있어, Spring Boot 의 Quartz auto-config 가
 *    DataSource 를 자동 주입하지 못한다. 따라서 @Primary controlDataSource 를
 *    SchedulerFactoryBeanCustomizer 로 명시 주입.
 *  - 실제 라벨링 잡은 Phase 5 에서 별도 Configuration 으로 추가.
 */
@Configuration
public class QuartzConfig {

    /**
     * Quartz JobStore 가 사용할 DataSource 를 controlDataSource 로 명시 지정.
     * (controlDataSource 는 @Primary, 기존 QRTZ_* 테이블이 위치한 klid_system 과 동일 DB.)
     */
    @Bean
    public SchedulerFactoryBeanCustomizer quartzDataSourceCustomizer(
            @Qualifier("controlDataSource") DataSource controlDataSource) {
        return (SchedulerFactoryBean factoryBean) -> {
            factoryBean.setDataSource(controlDataSource);
            factoryBean.setOverwriteExistingJobs(true);
        };
    }

    @Bean
    public JobDetail bootstrapJobDetail() {
        return JobBuilder.newJob(BootstrapSchedulerJob.class)
                .withIdentity(BootstrapSchedulerJob.JOB_NAME, BootstrapSchedulerJob.JOB_GROUP)
                .storeDurably()
                .withDescription("Quartz 스케줄러 부트스트랩 헬스 잡")
                .build();
    }

    @Bean
    public Trigger bootstrapJobTrigger(JobDetail bootstrapJobDetail) {
        // 시작 후 30초 뒤 1회 발화 (테스트 시 빠른 부트를 위해 짧은 지연).
        return TriggerBuilder.newTrigger()
                .forJob(bootstrapJobDetail)
                .withIdentity(BootstrapSchedulerJob.TRIGGER_NAME, BootstrapSchedulerJob.JOB_GROUP)
                .startAt(new java.util.Date(System.currentTimeMillis() + 30_000L))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withRepeatCount(0))
                .build();
    }
}
