package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.dataset.export.DatasetExportFailureRecoverer;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D-ISSUE-04(b) — 실패한 학습데이터 산출(export) 재시도 Quartz Job.
 *
 * <p>승인 후 export 는 AFTER_COMMIT {@code @Async} 라 실패해도 승인이 롤백되지 않는데 재시도 경로가
 * 없었다. 이 잡이 주기적으로 <b>최신 export 가 FAILED 인 영상</b>을 찾아 재산출을 트리거한다. 실제 로직은
 * {@link DatasetExportFailureRecoverer}(Job 은 얇은 어댑터 — {@link DatasetExportPendingSweepJob} 과 동일 패턴).
 *
 * <p><b>클러스터 안전 (DEV_FIX H7③ — 정정)</b>: {@link DisallowConcurrentExecution} 은 <b>같은 노드</b>
 * 안에서 동일 JobKey 의 tick 겹침만 막는다. Quartz JobStore 클러스터링은 현재 설정상 꺼져 있으므로
 * ({@code org.quartz.jobStore.isClustered} 기본 false — 활성화는 Phase 9 소관) 2노드 Active-Active 에서
 * <b>두 노드가 같은 tick 을 각자 실행한다</b>. 중복 재산출은 스케줄러가 아니라
 * {@link DatasetExportFailureRecoverer} 의 DB 레벨 클레임(조건부 UPDATE)이 막는다.
 *
 * <p>보안: 사용자 입력을 받지 않으며 로그는 건수·rawSn 만 출력한다(CWE-359/117).
 */
@Slf4j
@DisallowConcurrentExecution
public class DatasetExportFailureRecoveryJob implements Job {

    public static final String JOB_NAME = "datasetExportFailureRecoveryJob";
    public static final String JOB_GROUP = "batch";
    public static final String TRIGGER_NAME = "datasetExportFailureRecoveryTrigger";

    @Autowired
    private DatasetExportFailureRecoverer recoverer;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            recoverer.recover();
        } catch (RuntimeException e) {
            // 회수 실패가 스케줄러를 멈추지 않도록 격리(Quartz misfire 방지). 원인은 타입만 남긴다.
            log.error("[DatasetExportFailureRecoveryJob] unexpected failure causeType={}",
                    e.getClass().getSimpleName());
        }
    }
}
