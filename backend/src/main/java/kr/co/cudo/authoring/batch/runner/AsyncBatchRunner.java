package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 배치 파이프라인 비동기 실행기.
 * MarkingBatchBridge 에서 호출되어 BatchOrchestrator.process() 를 별도 스레드에서 실행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncBatchRunner {

    private final BatchOrchestrator orchestrator;

    @Async("batchAsyncExecutor")
    public void runAsync(Long rawSn) {
        log.info("[AsyncBatchRunner] starting batch rawSn={}", rawSn);
        try {
            BatchStage stage = orchestrator.process(rawSn);
            // DEV_FIX H1 — SKIPPED(검수 소유 작업 상태로 진입 차단)를 성공처럼 흘리지 않는다. 호출부
            //   (MarkingBatchBridge)는 이미 BATCH_QUEUED 클레임을 커밋했으므로, 여기서 조용히 끝나면
            //   "큐잉은 됐는데 아무 단계도 안 도는" 상태가 로그 없이 남는다.
            if (stage == BatchStage.SKIPPED) {
                log.warn("[AsyncBatchRunner] skipped — review-owned work status rawSn={}", rawSn);
            }
        } catch (Exception e) {
            log.error("[AsyncBatchRunner] batch failed rawSn={}", rawSn, e);
        }
    }
}
