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
 * <p>파일 쓰기/상태 마감 전 크래시로 {@code LS_DATASET_EXPORT.OUTPUT_STTS_CD='PENDING'} 에 고착된 잔재를
 * 주기적으로 FAILED 로 회수한다(파일 삭제 없음, 상태만 마감). 실제 로직은 {@link DatasetExportPendingSweeper}.
 *
 * <p><b>클러스터 안전 — 정정(Phase 9-B)</b>: {@link DisallowConcurrentExecution} 은 <b>같은 노드</b>
 * 안에서만 동시 tick 을 막는다. 구 주석은 "Quartz 클러스터링이 매 tick 을 한 노드만 실행하도록
 * 보장한다"고 썼으나 {@code org.quartz.jobStore.isClustered} 는 <b>기본값이 false</b>
 * ({@code application.yml} 의 {@code QUARTZ_CLUSTERED:false})이므로 2노드 Active-Active 배포에서는
 * <b>같은 tick 이 양 노드에서 발화</b>한다 — 그 전제는 사실이 아니었고 이중 회수가 실제로 재현된다.
 * 따라서 중복 회수 방어는 잡이 아니라 <b>DB 조건부 UPDATE 클레임</b>
 * ({@code LsDatasetExportRepository#claimStalePending})이 담당한다. 클러스터링을 켜더라도 이 방어는
 * 유지한다(설정에 의존하지 않는 보장).
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
