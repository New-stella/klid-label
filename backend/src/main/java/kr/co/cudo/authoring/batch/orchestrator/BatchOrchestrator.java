package kr.co.cudo.authoring.batch.orchestrator;

import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 배치 파이프라인 오케스트레이터 (V2.1 — 비식별 분리, post-marking 시퀀스 재정렬).
 *
 * <p><b>선언적 파이프라인 리팩토링</b>: 단계 순서를 하드코딩 순차 호출에서
 * {@link BatchPipeline} (순서를 가진 {@link BatchStep} 목록) 으로 분리했다. 본 오케스트레이터는
 * 더 이상 개별 step 빈을 알지 못하며, 주입받은 파이프라인을 순서대로 실행할 뿐이다.
 * 단계 순서 변경은 {@link kr.co.cudo.authoring.batch.pipeline.BatchPipelineConfig} 한 곳만 수정한다.
 *
 * <p>post-marking 시퀀스 단계 순서 (파이프라인 정의):
 *   1. MARKING        (MarkingLoadStep — 마킹 로드 + marks 파싱. 마킹 없으면 marks 빈 리스트.)
 *   2. VLM            (VlmTimeseriesStep — runWithMarking/run 분기. enabled=false 면 NO-OP.)
 *   3. FRAME_EXTRACT  (FfmpegFrameExtractor — marks 비면 INVALID_INPUT, extractByMarks,
 *                      결과 0건이면 INTERNAL_ERROR.)
 *   4. YOLO           (YoloAutolabelStep — 힌트 적재)
 *   5. SAM2           (Sam2SegmentStep — 힌트 소비)
 *   6. INTERPOLATE    (TrackInterpolationStep)
 *   7. COMPLETED      (statusService.markCompleted)
 *
 * <p>Phase 1 (파이프라인 재정렬): post-marking 시퀀스에서 DEIDENTIFY 단계 제거 (비식별은 적재 직후
 * 선두 단계로 분리, Phase 2). FfmpegFrameExtractor 가 이미 완료된 비식별 결과 경로를 스스로 조회.
 *
 * <p>실패 처리:
 *  - 어느 단계에서든 예외 발생 시 statusService.markFailed + retryQueue.enqueueIfRetryable.
 *  - retryQueue 가 maxAttempts 초과면 false 반환 → FAILED 상태 고정.
 *
 * <p>트랜잭션 분리:
 *  - 본 process() 자체는 NOT_SUPPORTED — 각 Step 이 REQUIRES_NEW 로 자체 트랜잭션 보유.
 *  - 단계 실패가 다른 단계 결과(예: VLM_META INSERT) 에 영향 없도록 격리.
 *
 * <p>영상 단위 직렬 호출 보장:
 *  - {@link #process(Long)} 는 단일 영상(rawSn) 에 대해 단일 스레드에서 호출된다.
 */
@Slf4j
@Service
public class BatchOrchestrator {

    private final BatchPipeline pipeline;
    private final BatchStatusService statusService;
    private final BatchTransitionService transitionService;
    private final BatchRetryQueue retryQueue;
    private final VideoRepository videoRepository;

    /**
     * post-marking 파이프라인을 명시 선택해 주입한다. 빈이 2개({@code preMarkingPipeline},
     * {@code postMarkingPipeline}) 이므로 {@code @Qualifier} 로 모호성을 해소한다 (Phase 2).
     */
    public BatchOrchestrator(
            @Qualifier("postMarkingPipeline") BatchPipeline pipeline,
            BatchStatusService statusService,
            BatchTransitionService transitionService,
            BatchRetryQueue retryQueue,
            VideoRepository videoRepository) {
        this.pipeline = pipeline;
        this.statusService = statusService;
        this.transitionService = transitionService;
        this.retryQueue = retryQueue;
        this.videoRepository = videoRepository;
    }

    /**
     * 단일 영상 1건 처리 (V2 순서).
     * - rawSn null/존재하지 않음 → INVALID_INPUT.
     * - process 자체는 트랜잭션을 시작하지 않으나(NOT_SUPPORTED), 영상 메타 조회를 위한
     *   짧은 readOnly 트랜잭션은 필요 → 별도 메서드로 격리.
     */
    public BatchStage process(Long rawSn) {
        return process(rawSn, null);
    }

    /**
     * 배치 단계 클레임을 <b>이미 보유한 호출자</b> 전용 진입 (B-ISSUE-01).
     *
     * <p>{@link kr.co.cudo.authoring.batch.service.BatchReprocessService} 는
     * {@link BatchTransitionService#tryClaimReprocessFromFailed} 로 {@code LS_DATA_RAW} 를
     * FAILED→PROCESSING 선점한 뒤 본 메서드를 호출한다. 일반 진입({@link #process(Long)})을 쓰면 진입
     * 가드가 <b>자기가 찍은 PROCESSING</b> 때문에 클레임에 실패해 수동 재처리가 전부 SKIPPED→409 가 된다.
     */
    public BatchStage processWithHeldStageClaim(Long rawSn) {
        return process(rawSn, null, true);
    }

    /**
     * 단일 영상 1건 처리 — stage 토글을 받는 오버로드 (Phase 3 — 조건부 step).
     *
     * <p>{@code stageToggles} 가 null/빈 맵이면 전 stage enabled — {@link #process(Long)} 와 동일
     * (프로덕션 경로 100% 보존). 토글 대상 단계(FRAME_EXTRACT/YOLO/SAM2)가 off 면 해당 단계의
     * stage 마킹과 execute 를 모두 건너뛴다. dev 단일 파이프라인 수렴 경로에서 사용한다.
     *
     * @param rawSn        영상 식별자
     * @param stageToggles {@link BatchStage#name()} → enabled. null/빈 맵 = 전부 enabled.
     */
    public BatchStage process(Long rawSn, Map<String, Boolean> stageToggles) {
        return process(rawSn, stageToggles, false);
    }

    /**
     * 공통 실행 본체.
     *
     * @param stageClaimHeld 호출자가 배치 단계({@code LS_DATA_RAW.DATA_STTS_CD}) PROCESSING 클레임을 이미
     *                       보유하는가 — {@code true} 면 진입 가드가 재클레임을 생략한다
     *                       ({@link #processWithHeldStageClaim} 경로).
     */
    private BatchStage process(Long rawSn, Map<String, Boolean> stageToggles, boolean stageClaimHeld) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }
        LsDataRaw raw = loadRaw(rawSn);

        // 배치 시작: 작업 상태 PROCESSING 전이 (REQUIRES_NEW 별도 트랜잭션으로 명시 영속).
        // ★ 진입 가드(DEV_FIX H8) — 작업 상태가 검수 소유(PENDING/IN_REVIEW/APPROVED/REJECTED)면
        //   전이가 차단되며, 이때는 step 을 한 건도 실행하지 않고 즉시 종료한다. 상태만 지키고 파이프라인을
        //   계속 돌리면 APPROVED 영상에 AUTO 라벨이 새로 적재되는데 상태는 APPROVED 로 남아
        //   export 폴더 JSON·V_COMPLETED_* 와 LS_DATA_LBL 이 재검수 없이 어긋나는 무증상 오염이 된다.
        //   본 가드는 마킹 브리지·dev 트리거·Quartz 큐·재시도 잡·수동 재처리 등 모든 진입점의 공통 관문이다.
        // ★ 동시 진입 상호배제(B-ISSUE-01) — 같은 가드가 LS_DATA_RAW 배치 단계를 단일 조건부 UPDATE 로
        //   원자 클레임한다. 동시 요청이 몇 건이든 1건만 클레임에 성공하고 나머지는 여기서 SKIPPED 로
        //   빠진다(구 구현은 전부 통과해 파이프라인 N벌 병렬 실행 + 외부 VLM N중 위탁).
        boolean blocked = stageClaimHeld
                ? transitionService.markRawDataProcessingBlockedWithHeldClaim(rawSn)
                : transitionService.markRawDataProcessingBlocked(rawSn);
        if (blocked) {
            log.warn("[BatchOrchestrator] skipped — entry guard blocked "
                    + "(review-owned work status or already processing) rawSn={}", rawSn);
            return BatchStage.SKIPPED;
        }

        try {
            BatchContext ctx = new BatchContext(rawSn, raw, stageToggles);
            for (BatchStep step : pipeline.steps()) {
                if (!step.isEnabled(ctx)) {
                    log.info("[BatchOrchestrator] skip disabled stage rawSn={} stage={}",
                            rawSn, step.stage());
                    continue;
                }
                statusService.markStage(rawSn, step.stage());
                step.execute(ctx);
            }

            // 작업 상태 COMPLETED 전이 + LS_DATA_RAW.DATA_STTS_CD=COMPLETED
            // (REQUIRES_NEW 별도 트랜잭션으로 명시 영속 — self-invocation/비트랜잭션 회피).
            transitionService.markRawDataCompleted(rawSn);
            statusService.markCompleted(rawSn);
            retryQueue.clear(rawSn);
            log.info("[BatchOrchestrator] completed rawSn={}", rawSn);
            return BatchStage.COMPLETED;
        } catch (RuntimeException e) {
            // ★ 클레임 해제를 먼저 한다 (B-ISSUE-01) — 진입 가드가 배치 단계 PROCESSING 을 원자 클레임하므로
            //   이 전이(→FAILED)가 실행되지 않으면 stage 가 PROCESSING 으로 고착돼 이후 모든 진입(자동 재시도
            //   잡·수동 재처리 포함)이 클레임에 막힌다. 부기(LS_BATCH_PROC_LOG) 기록이 실패해도 해제는 남는다.
            transitionService.markRawDataFailed(rawSn);
            statusService.markFailed(rawSn, e);
            boolean willRetry = retryQueue.enqueueIfRetryable(rawSn);
            log.warn("[BatchOrchestrator] failed rawSn={} willRetry={} cause={}",
                    rawSn, willRetry, e.getClass().getSimpleName());
            return BatchStage.FAILED;
        } catch (Error e) {
            // Error(OOM/StackOverflow 등)는 삼키지 않고 되던진다. 다만 그대로 빠져나가면 클레임이 영구
            // 고착되므로 해제만 시도하고 원인 예외를 보존한다(해제 실패는 로깅만 — 원인 예외를 덮지 않는다).
            releaseStageClaimQuietly(rawSn);
            throw e;
        }
    }

    /** 클레임 해제(→FAILED) 시도 — 실패해도 원인 예외를 덮지 않도록 삼키고 로깅만 한다. */
    private void releaseStageClaimQuietly(Long rawSn) {
        try {
            transitionService.markRawDataFailed(rawSn);
        } catch (Throwable t) {
            log.error("[BatchOrchestrator] stage claim release failed rawSn={} reason={}",
                    rawSn, t.getClass().getSimpleName());
        }
    }

    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    protected LsDataRaw loadRaw(Long rawSn) {
        return videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "영상을 찾을 수 없습니다 rawSn=" + rawSn));
    }
}
