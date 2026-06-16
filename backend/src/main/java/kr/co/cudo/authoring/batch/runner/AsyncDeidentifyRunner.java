package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 선두(pre-marking) 비식별 비동기 실행기 (Phase 2 — 배치 파이프라인 재정렬).
 *
 * <p>{@code IngestDeidentifyBridge} 가 {@code VideoIngestedEvent} 수신(AFTER_COMMIT) 후
 * 호출한다. 적재된 모든 영상(ANONY 포함)을 무조건 비식별({@code preMarkingPipeline}) 한 뒤
 * 성공 시 LS_DATA_RAW 를 MARKING_READY 로 전이해 마킹 단계 진입을 허용한다.
 *
 * <h3>실패 정책 (확정된 설계 결정 3)</h3>
 * <ul>
 *   <li>자동 재비식별 큐를 신설하지 않는다. 실패 영상은 외부 비식별 프로그램에서 수동 재비식별 후
 *       기존 수동 resolve 경로로 복구한다.</li>
 *   <li>실패 시 deIdntfYn='F' 는 {@code DeidentifyStep} 이 <b>별도 커밋 트랜잭션</b>
 *       ({@code BatchTransitionService.recordDeidentFailure}, REQUIRES_NEW)으로 기록하므로 — 본 run()
 *       의 REQUIRES_NEW 롤백과 독립적으로 'F' 가 영속된다 — 여기서는 WARN 로깅만 하고 예외를
 *       삼킨다(@Async). MARKING_READY 로 전이하지 않는다.</li>
 * </ul>
 *
 * <p>{@code AsyncBatchRunner} 의 try/catch + 로깅 패턴을 따른다. 재시도 큐(BatchRetryQueue) 는
 * 의존성으로 주입하지 않아 구조적으로 큐 사용을 차단한다.
 */
@Slf4j
@Service
public class AsyncDeidentifyRunner {

    private final BatchPipeline preMarkingPipeline;
    private final BatchTransitionService batchTransitionService;
    private final VideoRepository videoRepository;

    public AsyncDeidentifyRunner(
            @Qualifier("preMarkingPipeline") BatchPipeline preMarkingPipeline,
            BatchTransitionService batchTransitionService,
            VideoRepository videoRepository) {
        this.preMarkingPipeline = preMarkingPipeline;
        this.batchTransitionService = batchTransitionService;
        this.videoRepository = videoRepository;
    }

    @Async("batchAsyncExecutor")
    public void runAsync(Long rawSn) {
        log.info("[AsyncDeidentifyRunner] starting deidentify rawSn={}", rawSn);
        LsDataRaw raw = loadRaw(rawSn).orElse(null);
        if (raw == null) {
            log.warn("[AsyncDeidentifyRunner] raw not found rawSn={} — skip", rawSn);
            return;
        }
        try {
            BatchContext ctx = new BatchContext(rawSn, raw);
            for (BatchStep step : preMarkingPipeline.steps()) {
                step.execute(ctx);
            }
            // 비식별 성공 → 마킹 단계 진입 가능 상태 전이 (REQUIRES_NEW).
            batchTransitionService.markRawDataMarkingReady(rawSn);
            log.info("[AsyncDeidentifyRunner] deidentify completed rawSn={}", rawSn);
        } catch (RuntimeException e) {
            // 실패 시 deIdntfYn='F' 는 DeidentifyStep 이 별도 커밋 트랜잭션(BatchTransitionService
            // .recordDeidentFailure, REQUIRES_NEW)으로 기록한다 — run() 롤백과 독립 영속.
            // 재시도 큐 enqueue 금지(설계 결정 3).
            // 수동 재비식별 후 기존 resolve 경로로 복구한다. @Async 이므로 예외는 삼킨다.
            log.warn("[AsyncDeidentifyRunner] deidentify failed rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /** 영상 메타 조회 — REQUIRES_NEW readOnly (BatchOrchestrator.loadRaw 패턴). */
    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    protected Optional<LsDataRaw> loadRaw(Long rawSn) {
        if (rawSn == null) {
            return Optional.empty();
        }
        return videoRepository.findById(rawSn);
    }
}
