package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.video.service.TrainingVideoIngestService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 관제 인입 픽업 적재 Quartz Job.
 *
 * <p>관제서버가 {@code LS_DATA_INGEST} 에 직접 INSERT 한 미처리({@code PROC_STTS_CD='PENDING'})
 * 행을 주기적으로 픽업해 {@code LS_DATA_RAW} 로 적재한다(구 소스 {@code MNG_CLIP_MASTER.JOB_DMND_YN='Y'}
 * 스캔은 관제 2차 적재 주체 반전으로 폐지). {@link DisallowConcurrentExecution} 으로 동일 JobKey 의
 * 동시 tick 을 차단해 중복 적재(멱등성)를 1차 방어한다 ({@code BatchQuartzJob} 과 동일 패턴).
 *
 * <p><b>이 어노테이션은 노드 <i>내부</i> 동시 tick 만 막는다</b> — 2노드 Active-Active 배포에서 서로
 * 다른 노드의 동시 실행은 Quartz 클러스터링이, 같은 인입 행을 두 실행이 잡는 잡 내부 레이스는
 * {@code TrainingVideoIngestTx} 의 원자 클레임이 각각 막는다(서로 대체하지 않는다).
 *
 * <p>보안: 본 Job 은 사용자 입력을 받지 않으며 인입 테이블 READ 후 LS_DATA_RAW 적재만 수행한다.
 * 멱등성/부분 실패 격리/경로 검증/로그 정제는 {@link TrainingVideoIngestService} 와
 * {@code TrainingVideoIngestTx} 가 책임진다.
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
