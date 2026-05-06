package kr.co.cudo.authoring.export.quartz;

import kr.co.cudo.authoring.export.service.ExportRunner;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Phase 10 — 학습데이터셋 내보내기 Quartz Job.
 *
 * <p>{@link DisallowConcurrentExecution}: 동일 JobKey 인스턴스 동시 실행 차단.
 * JobDataMap 의 {@link #KEY_EXPORT_SN} 으로 ExportRunner 호출.
 *
 * <p>본 Job 은 사용자 입력을 받지 않으며 ExportService 가 enqueue 한 exportSn 만 사용.
 */
@Slf4j
@DisallowConcurrentExecution
public class ExportJob implements Job {

    public static final String JOB_GROUP = "export";
    public static final String KEY_EXPORT_SN = "exportSn";

    @Autowired
    private ExportRunner runner;

    @Override
    public void execute(JobExecutionContext context) {
        JobDataMap data = context.getMergedJobDataMap();
        Long exportSn = data.getLong(KEY_EXPORT_SN);
        log.info("[ExportJob] firing exportSn={} jobKey={}", exportSn, context.getJobDetail().getKey());
        try {
            runner.run(exportSn);
        } catch (RuntimeException e) {
            // ExportRunner 가 내부에서 status FAILED 마킹. 안전망 로깅만.
            log.error("[ExportJob] unexpected failure exportSn={} err={}", exportSn, e.getMessage());
        }
    }
}
