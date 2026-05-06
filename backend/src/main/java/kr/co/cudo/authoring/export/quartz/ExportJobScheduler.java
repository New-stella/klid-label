package kr.co.cudo.authoring.export.quartz;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * Phase 10 — Export Quartz Job 트리거 등록.
 *
 * <p>exportSn 별로 1회성 Job 을 enqueue (즉시 실행 트리거). 동일 exportSn 이 중복 enqueue 되더라도
 * Job 자체에 {@code @DisallowConcurrentExecution} + ExportRunner 의 status 검증으로 보호됨.
 *
 * <p>본 빈은 단위 테스트에서 mock 으로 대체 가능 (ExportService 만 Scheduler 의존 격리).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportJobScheduler {

    private final Scheduler scheduler;

    public void schedule(Long exportSn) {
        if (exportSn == null) {
            throw new IllegalArgumentException("exportSn 은 필수입니다.");
        }
        String name = "export-" + exportSn;
        JobKey jobKey = JobKey.jobKey(name, ExportJob.JOB_GROUP);
        JobDetail jobDetail = JobBuilder.newJob(ExportJob.class)
                .withIdentity(jobKey)
                .usingJobData(ExportJob.KEY_EXPORT_SN, exportSn)
                .storeDurably(false)
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("trigger-" + name, ExportJob.JOB_GROUP)
                .forJob(jobKey)
                .startAt(new Date(System.currentTimeMillis() + 1_000L))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withRepeatCount(0))
                .build();
        try {
            // 같은 jobKey 가 이미 있으면 (중복 enqueue) 새 trigger 만 추가
            if (scheduler.checkExists(jobKey)) {
                scheduler.scheduleJob(trigger);
            } else {
                scheduler.scheduleJob(jobDetail, trigger);
            }
            log.info("[ExportJobScheduler] scheduled exportSn={} jobKey={}", exportSn, jobKey);
        } catch (SchedulerException e) {
            log.error("[ExportJobScheduler] schedule failed exportSn={} err={}", exportSn, e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "Quartz 스케줄링 실패", e);
        }
    }
}
