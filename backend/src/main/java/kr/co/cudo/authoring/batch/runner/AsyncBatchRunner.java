package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
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

    @Async
    public void runAsync(Long rawSn) {
        log.info("[AsyncBatchRunner] starting batch rawSn={}", rawSn);
        try {
            orchestrator.process(rawSn);
        } catch (Exception e) {
            log.error("[AsyncBatchRunner] batch failed rawSn={}", rawSn, e);
        }
    }
}
