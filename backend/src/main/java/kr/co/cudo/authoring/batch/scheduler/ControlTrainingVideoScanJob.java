package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.video.service.TrainingVideoIngestService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 관제 학습용 영상 픽업 적재 Quartz Job.
 *
 * <p>관제서버가 {@code MNG_CLIP_MASTER.JOB_DMND_YN='Y'} 로 학습용 지정한 클립을 주기적으로
 * 픽업해 {@code LS_DATA_RAW} 로 적재한다. {@link DisallowConcurrentExecution} 으로 동일 JobKey
 * 의 동시 tick 을 차단해 중복 적재(멱등성)를 1차 방어한다 ({@code BatchQuartzJob} 과 동일 패턴).
 *
 * <p>보안: 본 Job 은 사용자 입력을 받지 않으며 공유 DB(관제) READ 후 LS_DATA_RAW 적재만 수행한다.
 * 멱등성/부분 실패 격리/로그 마스킹은 {@link TrainingVideoIngestService} 가 책임진다.
 */
@Slf4j
@DisallowConcurrentExecution
public class ControlTrainingVideoScanJob implements Job {

    public static final String JOB_NAME = "controlTrainingVideoScanJob";
    public static final String JOB_GROUP = "batch";
    public static final String TRIGGER_NAME = "controlTrainingVideoScanTrigger";

    @Autowired
    private TrainingVideoIngestService ingestService;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            int ingested = ingestService.scanAndIngest();
            if (ingested > 0) {
                log.info("[ControlTrainingVideoScanJob] tick ingested={}", ingested);
            } else {
                log.debug("[ControlTrainingVideoScanJob] tick no new clips");
            }
        } catch (RuntimeException e) {
            // 서비스 내부에서 클립별 실패를 격리하므로 통상 도달하지 않으나, 안전망(Quartz misfire 방지).
            log.error("[ControlTrainingVideoScanJob] unexpected failure causeType={}",
                    e.getClass().getSimpleName());
        }
    }
}
