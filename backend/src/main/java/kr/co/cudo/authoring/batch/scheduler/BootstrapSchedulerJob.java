package kr.co.cudo.authoring.batch.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;

/**
 * Quartz 부트스트랩 Job. 시작 시 1회 실행되어 스케줄러 자체가 정상 동작하는지 로그로 확인.
 * (실제 라벨링 배치 잡은 Phase 5 에서 등록.)
 */
@Slf4j
public class BootstrapSchedulerJob implements Job {

    public static final String JOB_NAME = "bootstrapJob";
    public static final String JOB_GROUP = "bootstrap";
    public static final String TRIGGER_NAME = "bootstrapTrigger";

    @Override
    public void execute(JobExecutionContext context) {
        log.info("[Scheduler] bootstrap job fired schedulerName={} jobKey={}",
                context.getScheduler() == null ? "unknown"
                        : safeSchedName(context),
                context.getJobDetail().getKey());
    }

    private String safeSchedName(JobExecutionContext context) {
        try {
            return context.getScheduler().getSchedulerName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
