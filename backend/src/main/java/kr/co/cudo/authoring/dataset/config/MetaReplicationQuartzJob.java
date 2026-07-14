package kr.co.cudo.authoring.dataset.config;

import kr.co.cudo.authoring.dataset.worker.MetaReplicationWorker;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 포털 메타 복제 워커 Quartz Job.
 *
 * <p>{@link DisallowConcurrentExecution} 으로 동일 JobKey 의 동시 tick 을 차단해 중복 복제 실행을
 * 방지한다({@code ControlTrainingVideoScanJob} 과 동일 패턴). 실제 복제/재시도/dead-letter 는
 * {@link MetaReplicationWorker} 가 책임진다.
 */
@Slf4j
@DisallowConcurrentExecution
public class MetaReplicationQuartzJob implements Job {

    public static final String JOB_NAME = "metaReplicationJob";
    public static final String JOB_GROUP = "dataset";
    public static final String TRIGGER_NAME = "metaReplicationTrigger";

    @Autowired
    private MetaReplicationWorker worker;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            worker.replicatePending();
        } catch (RuntimeException e) {
            // 워커 내부에서 outbox 별 실패를 격리하므로 통상 도달하지 않으나 안전망(Quartz misfire 방지).
            log.error("[MetaReplication] unexpected tick failure reason={}", e.getClass().getSimpleName());
        }
    }
}
