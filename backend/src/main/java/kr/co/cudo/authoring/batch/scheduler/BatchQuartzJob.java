package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.queue.entity.LsClipScheduleQue;
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
        Optional<LsClipScheduleQue> picked = queueService.dequeueOne();
        if (picked.isEmpty()) {
            log.debug("[BatchQuartzJob] no pending work — skipping tick");
            return;
        }
        Long rawSn = picked.get().getRawSn();
        log.info("[BatchQuartzJob] processing rawSn={} queSn={}", rawSn, picked.get().getQueSn());
        try {
            BatchStage stage = orchestrator.process(rawSn);
            // DEV_FIX H1 — SKIPPED(검수 소유 작업 상태로 진입 차단)는 "처리 성공"이 아니다. 큐 항목은 이미
            //   dequeue 되어 사라졌으므로 재적재 없이 조용히 넘어가면 운영자는 작업이 증발한 사실을 알 수
            //   없다. 재적재하지 않는 것은 의도된 선택이다 — 검수 소유 영상은 배치를 돌리면 안 되므로 다시
            //   큐에 넣으면 무한 재시도가 된다. 대신 WARN 으로 소실 사실과 사유를 명시 노출한다.
            if (stage == BatchStage.SKIPPED) {
                log.warn("[BatchQuartzJob] skipped — review-owned work status; queue entry consumed "
                        + "without processing rawSn={} queSn={}", rawSn, picked.get().getQueSn());
            }
        } catch (RuntimeException e) {
            // orchestrator 내부에서 예외를 처리하므로 통상 도달하지 않으나, 안전망.
            log.error("[BatchQuartzJob] unexpected failure rawSn={} err={}", rawSn, e.getMessage());
        }
    }
}
