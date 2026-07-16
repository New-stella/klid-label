package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.dataset.export.DatasetExportPendingSweeper;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 학습데이터 산출 stale PENDING 정리 Quartz Job.
 *
 * <p>파일 쓰기/상태 마감 전 크래시로 {@code LS_DATASET_EXPORT.EXPORT_STTS_CD='PENDING'} 에 고착된 잔재를
 * 주기적으로 FAILED 로 회수한다(파일 삭제 없음, 상태만 마감). 실제 로직은 {@link DatasetExportPendingSweeper}.
 *
 * <p>클러스터 안전: {@link DisallowConcurrentExecution} 으로 동일 JobKey 동시 tick 을 차단하고, 기존
 * Quartz PostgreSQL JobStore 클러스터링(2노드 Active-Active)이 매 tick 을 한 노드만 실행하도록 락으로
 * 보장하므로 중복 회수가 발생하지 않는다({@link ControlTrainingVideoScanJob} 과 동일 패턴).
 *
 * <p>보안: 사용자 입력을 받지 않으며 로그는 건수만 출력한다(CWE-359/117).
 */
@Slf4j
@DisallowConcurrentExecution
public class DatasetExportPendingSweepJob implements Job {

    public static final String JOB_NAME = "datasetExportPendingSweepJob";
    public static final String JOB_GROUP = "batch";
    public static final String TRIGGER_NAME = "datasetExportPendingSweepTrigger";

    @Autowired
    private DatasetExportPendingSweeper sweeper;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            sweeper.sweep();
        } catch (RuntimeException e) {
            // sweeper 내부에서 회수 실패를 격리하므로 통상 도달하지 않으나, 안전망(Quartz misfire 방지).
            log.error("[DatasetExportPendingSweepJob] unexpected failure causeType={}",
                    e.getClass().getSimpleName());
        }
    }
}
