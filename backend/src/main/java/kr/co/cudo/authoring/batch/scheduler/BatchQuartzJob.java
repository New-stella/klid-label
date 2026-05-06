package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.queue.entity.MngClipScheduleQue;
import kr.co.cudo.authoring.batch.queue.service.LabelingBatchQueueService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

/**
 * 배치 파이프라인 Quartz Job (Phase 5).
 * <p>
 * - {@link DisallowConcurrentExecution}: 동일 JobKey 인스턴스가 동시 실행되지 않음.
 *   클러스터 환경에서도 노드 간 보장 (Quartz 가 BLOCKED 상태로 관리).
 * - 매 트리거 발화마다 큐에서 1건 dequeue → orchestrator.process(rawSn).
 *   비어있으면 no-op (다음 트리거 대기).
 * <p>
 * 보안:
 *  - SSRF/Path Manipulation/Privacy 등 모든 보안 책임은 각 Step 에서 처리.
 *  - 본 Job 자체는 사용자 입력을 받지 않으며, 큐에서 가져온 rawSn 만 사용.
 */
@Slf4j
@DisallowConcurrentExecution
public class BatchQuartzJob implements Job {

    public static final String JOB_NAME = "batchPipelineJob";
    public static final String JOB_GROUP = "batch";
    public static final String TRIGGER_NAME = "batchPipelineTrigger";

    @Autowired
    private LabelingBatchQueueService queueService;

    @Autowired
    private BatchOrchestrator orchestrator;

    @Override
    public void execute(JobExecutionContext context) {
        Optional<MngClipScheduleQue> picked = queueService.dequeueOne();
        if (picked.isEmpty()) {
            log.debug("[BatchQuartzJob] no pending work — skipping tick");
            return;
        }
        Long rawSn = picked.get().getRawSn();
        log.info("[BatchQuartzJob] processing rawSn={} queSn={}", rawSn, picked.get().getQueSn());
        try {
            orchestrator.process(rawSn);
        } catch (RuntimeException e) {
            // orchestrator 내부에서 예외를 처리하므로 통상 도달하지 않으나, 안전망.
            log.error("[BatchQuartzJob] unexpected failure rawSn={} err={}", rawSn, e.getMessage());
        }
    }
}
