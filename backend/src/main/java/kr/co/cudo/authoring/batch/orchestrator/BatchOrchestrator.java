package kr.co.cudo.authoring.batch.orchestrator;

import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.status.VlmDefaultSkipMarker;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
     * 전체 설정 건너뛰기 표식기 — 시계열 단계 <b>진입 직전</b> 한 번 태운다. [@design ADR-050]
     *
     * <p>여기는 «표식을 세우는» 자리일 뿐이고 건너뛸지 말지는 아래 기존 게이트가 정한다. 게이트를
     * 두 벌로 만들면 "설정으로 껐는데 어떤 경로에서는 도는" 상태가 생긴다.
     */
    private final VlmDefaultSkipMarker vlmDefaultSkipMarker;

    /**
     * post-marking 파이프라인을 명시 선택해 주입한다. 빈이 2개({@code preMarkingPipeline},
     * {@code postMarkingPipeline}) 이므로 {@code @Qualifier} 로 모호성을 해소한다 (Phase 2).
     */
    public BatchOrchestrator(
            @Qualifier("postMarkingPipeline") BatchPipeline pipeline,
            BatchStatusService statusService,
            BatchTransitionService transitionService,
            BatchRetryQueue retryQueue,
            VideoRepository videoRepository,
            VlmDefaultSkipMarker vlmDefaultSkipMarker) {
        this.pipeline = pipeline;
        this.statusService = statusService;
        this.transitionService = transitionService;
        this.retryQueue = retryQueue;
        this.videoRepository = videoRepository;
        this.vlmDefaultSkipMarker = vlmDefaultSkipMarker;
    }

    /**
     * 단일 영상 1건 처리 (V2 순서).
     * - rawSn null/존재하지 않음 → INVALID_INPUT.
     * - process 자체는 트랜잭션을 시작하지 않으나(NOT_SUPPORTED), 영상 메타 조회를 위한
     *   짧은 readOnly 트랜잭션은 필요 → 별도 메서드로 격리.
     */
    public BatchStage process(Long rawSn) {
        return process(rawSn, (Map<String, Boolean>) null);
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
        return process(rawSn, null, true, null, false);
    }

    /**
     * 배치 단계 클레임을 보유한 호출자 전용 진입 — <b>작업 묶음 지목 재수행</b> 전용. [@design API-201]
     *
     * <p>재수행({@code POST /v1/videos/{rawSn}/batch/stages/{stage}/rerun})이 건너뛰기를 해제한 묶음의 구성 단계를
     * stage 토글로 환산해({@code BatchBundleTogglePolicy}) 이 인자로 전달한다. 각 단계의
     * {@link BatchStep#isEnabled} 가 그것을 해석한다.
     *
     * <p>⚠ <b>여기까지 내려온 토글은 이미 검증된 값이다</b> — "그 영상에서 실제로 건너뛰기를 해제한 묶음인가"는
     * 입구({@code BatchStageRerunService})가 판정한다. 오케스트레이터는 임의 조합을 다시 의심하지 않는
     * 대신, 이 진입점을 그 서비스 밖에서 쓰지 않는다(쓰면 앞 작업을 건너뛴 산출물이 만들어진다).
     *
     * <h3>★실패해도 영상 상태를 훼손하지 않는다 (전체 재기동과 갈리는 유일한 지점)</h3>
     * <p>일반 경로는 실패 시 {@code markRawDataFailed} + 자동 재시도 큐 등록으로 마감한다. 그 처리를 이
     * 경로에 그대로 적용하면 <b>완주한 영상이 FAILED 로 강등</b>되고(작업 상태까지 FAILED 가 되어 작업자가
     * 검수 제출을 못 한다) <b>자동 재시도 큐가 범위를 모른 채 전 단계를 돌려</b> 사람이 손댄 보간 라벨을
     * 지운다 — 이 기능이 막으려던 바로 그 파괴가 실패 경로로 되살아난다.
     *
     * <p>더구나 스킵의 존재 이유가 "기다려도 성공하지 않는 작업"이라 <b>해제해 재수행하면 실패가 예외가
     * 아니라 기대값</b>이다. 이 경로의 실패 처리는 흔하게 탄다.
     *
     * <p>그래서 여기서는 ①선점 직전 상태로 <b>원상 복구</b>하고 ②자동 재시도 큐에 <b>넣지 않는다</b>.
     * 다만 <b>실패 사실 자체는 기록한다</b>({@code statusService.markFailed}) — 실행이 비동기라 요청이
     * 결과를 받지 못하므로, 이 기록이 없으면 재수행 실패가 아무 흔적 없이 사라져 운영자가 "눌렀는데 왜
     * 그대로인지" 알 수 없다(영상 상세의 배치 실패 사유가 이 행을 읽는다).
     *
     * <p>이 복구 계약은 {@code RuntimeException} 뿐 아니라 <b>{@code Error} 에도 똑같이 적용</b>된다 —
     * 오토라벨 재수행은 프레임 이미지 인코딩을 수반해 {@code OutOfMemoryError}·{@code NoClassDefFoundError}
     * 가 현실적인 실패 유형이고, 그 갈래만 강등으로 남겨 두면 위 파괴가 그 경로로 되살아난다. 다만
     * {@code Error} 는 <b>삼키지 않고 되던진다</b>(복구만 하고 전파는 유지 — 치명적 오류를 정상 흐름으로
     * 만들지 않는다).
     *
     * <p>전체 재기동(API-167) 경로의 강등·재시도 동작은 <b>그대로다</b> — 그쪽은 대상이 실패 영상이라
     * 강등이 맞고, 실패 영상에는 사람이 만든 라벨이 없어 전 단계 재수행이 파괴가 되지 않는다.
     *
     * @param stageToggles      {@link BatchStage#name()} → enabled. {@code null}/빈 맵이면 전 단계 실행
     * @param claimOriginStatus 선점 직전의 배치 단계 상태(실패 시 복구 목표값)
     */
    public BatchStage processBundleRerun(
            Long rawSn, Map<String, Boolean> stageToggles, String claimOriginStatus) {
        return processBundleRerun(rawSn, stageToggles, claimOriginStatus, false);
    }

    /**
     * 묶음 재수행 — <b>검수 소유 작업 상태 보존</b> 모드를 고를 수 있는 진입. [@design API-201]
     *
     * <p>{@code preserveReviewOwnedStatus=true} 면 진입 가드가 검수 소유 상태를 차단 사유로 보지 않고,
     * 마감도 작업 상태를 보존하는 경로로 간다. 「메타만 더하는 묶음」(라벨을 다시 만들지 않는 묶음)의
     * 재수행 전용이며, 그 판정은 {@link MetadataOnlyRerunPolicy} 단일 지점이 소유한다.
     *
     * <p>⚠ <b>기본값은 {@code false}</b> 다(위 오버로드). 면제는 호출자가 명시적으로 고를 때만 켜지고,
     * 켜졌더라도 아래 실행 범위 검사가 한 번 더 확인한다(fail-closed 2겹).
     */
    public BatchStage processBundleRerun(
            Long rawSn, Map<String, Boolean> stageToggles, String claimOriginStatus,
            boolean preserveReviewOwnedStatus) {
        return process(rawSn, stageToggles, true, claimOriginStatus, preserveReviewOwnedStatus);
    }

    /**
     * 단일 영상 1건 처리 — stage 토글을 받는 오버로드 (Phase 3 — 조건부 step).
     *
     * <p>{@code stageToggles} 가 null/빈 맵이면 전 stage enabled — {@link #process(Long)} 와 동일
     * (프로덕션 경로 100% 보존). off 로 지정된 단계는 stage 마킹과 execute 를 모두 건너뛴다
     * ({@link BatchStep#isEnabled} 규약 — 특정 3종이 아니라 <b>전 단계</b>가 토글을 따른다).
     *
     * @param rawSn        영상 식별자
     * @param stageToggles {@link BatchStage#name()} → enabled. null/빈 맵 = 전부 enabled.
     */
    public BatchStage process(Long rawSn, Map<String, Boolean> stageToggles) {
        return process(rawSn, stageToggles, false, null, false);
    }

    /**
     * 공통 실행 본체.
     *
     * @param stageClaimHeld    호출자가 배치 단계({@code LS_DATA_RAW.DATA_STTS_CD}) PROCESSING 클레임을 이미
     *                          보유하는가 — {@code true} 면 진입 가드가 재클레임을 생략한다
     *                          ({@link #processWithHeldStageClaim} 경로).
     * @param rerunRestoreStatus {@code null} 이 아니면 <b>묶음 재수행</b> 경로다 — 실패 시 FAILED 강등·자동
     *                          재시도 대신 이 상태로 원상 복구한다([@design API-201], 위 Javadoc).
     */
    private BatchStage process(Long rawSn, Map<String, Boolean> stageToggles,
                               boolean stageClaimHeld, String rerunRestoreStatus,
                               boolean preserveReviewOwnedStatus) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }
        LsDataRaw raw = loadRaw(rawSn);

        // ★ 면제 모드의 런타임 fail-closed [@design API-201] — 진입 전이<보다 먼저> 검사한다.
        //   면제는 "라벨을 만들지 않는다" 를 전제로 승인 영상의 진입을 여는 것이라, 그 전제가 배선
        //   실수로 깨지면 승인 영상에 오토라벨이 도는 최악이 된다. 검사는 실제 파이프라인 × 컨텍스트로
        //   "정말 돌 단계" 를 산출해서 한다 — 토글 맵만 보면 <키가 없어 도는> 단계를 놓친다
        //   (BatchContext.isStageEnabled 는 키 부재를 enabled 로 읽는다).
        //   SKIPPED 로 빠지면 호출자(AsyncBatchReprocessRunner)가 선점 클레임을 보상 롤백한다.
        if (preserveReviewOwnedStatus
                && !MetadataOnlyRerunPolicy.runScopeIsMetadataOnly(
                        stagesThatWillRun(stageToggles))) {
            log.error("[BatchOrchestrator] refused — review-status-preserving rerun with a scope that is not "
                    + "metadata-only rawSn={}", rawSn);
            return BatchStage.SKIPPED;
        }

        // 배치 시작: 작업 상태 PROCESSING 전이 (REQUIRES_NEW 별도 트랜잭션으로 명시 영속).
        // ★ 진입 가드(DEV_FIX H8) — 작업 상태가 검수 소유(PENDING/IN_REVIEW/APPROVED/REJECTED)면
        //   전이가 차단되며, 이때는 step 을 한 건도 실행하지 않고 즉시 종료한다. 상태만 지키고 파이프라인을
        //   계속 돌리면 APPROVED 영상에 AUTO 라벨이 새로 적재되는데 상태는 APPROVED 로 남아
        //   export 폴더 JSON·V_COMPLETED_* 와 LS_DATA_LBL 이 재검수 없이 어긋나는 무증상 오염이 된다.
        //   본 가드는 마킹 브리지·dev 트리거·Quartz 큐·재시도 잡·수동 재처리 등 모든 진입점의 공통 관문이다.
        // ★ 동시 진입 상호배제(B-ISSUE-01) — 같은 가드가 LS_DATA_RAW 배치 단계를 단일 조건부 UPDATE 로
        //   원자 클레임한다. 동시 요청이 몇 건이든 1건만 클레임에 성공하고 나머지는 여기서 SKIPPED 로
        //   빠진다(구 구현은 전부 통과해 파이프라인 N벌 병렬 실행 + 외부 VLM N중 위탁).
        boolean blocked;
        if (preserveReviewOwnedStatus) {
            // 검수 소유 상태를 차단 사유로 보지 않는다 — 작업 상태 자체는 전이되지 않아 APPROVED 가 보존된다.
            blocked = transitionService.markRawDataProcessingWithHeldClaimPreservingReviewStatus(rawSn);
        } else if (stageClaimHeld) {
            blocked = transitionService.markRawDataProcessingBlockedWithHeldClaim(rawSn);
        } else {
            blocked = transitionService.markRawDataProcessingBlocked(rawSn);
        }
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
                // [design: ADR-050] [design: SEQ-001] 전체 설정 건너뛰기 — 시계열 위탁 단계에
                //   <b>진입하기 직전</b> 자동으로 건너뜀 표식을 세운다. 여기서 세운 표식은 바로 아래
                //   기존 게이트가 읽어 그 묶음을 건너뛰므로 외부 벤더 호출이 한 번도 일어나지 않는다
                //   (위탁했다가 실패시키는 것이 아니다). 대상 묶음 판정·사람 표식 보호·멱등은 모두
                //   VlmDefaultSkipMarker 단일 지점이 소유하며 여기서 재유도하지 않는다.
                vlmDefaultSkipMarker.applyBeforeStage(rawSn, step.stage());
                // [@design API-198] REVIEWER 수동 스킵 — 그 단계가 속한 <b>작업 묶음</b>에 표식이 서 있으면
                //   실행하지 않고 통과한다. 판정은 BatchStatusService.isStageManuallySkipped 단일 지점이며
                //   (단계 → 묶음 해석은 BatchStageBundle.containing), 여기서 규칙을 재유도하지 않는다.
                //   markStage 도 건너뛰므로 표식 행이 진행 축을 오염시키지 않는다(표식 행 자체도
                //   PROC_STTS_CD='SKIPPED' 라 진행 조회에서 이미 제외된다).
                if (statusService.isStageManuallySkipped(rawSn, step.stage())) {
                    log.info("[BatchOrchestrator] skip manually skipped stage rawSn={} stage={}",
                            rawSn, step.stage());
                    continue;
                }
                statusService.markStage(rawSn, step.stage());
                step.execute(ctx);
            }

            // 작업 상태 COMPLETED 전이 + LS_DATA_RAW.DATA_STTS_CD=COMPLETED
            // (REQUIRES_NEW 별도 트랜잭션으로 명시 영속 — self-invocation/비트랜잭션 회피).
            // ★ 면제 모드는 전용 마감을 쓴다 — 일반 마감은 작업 상태 전이가 차단되면 LS_DATA_RAW 도
            //   건드리지 않고 반환해, 완주해도 배치 단계가 PROCESSING 으로 <영구 고착>된다(이후 전
            //   배치 진입이 409). 게다가 아래 markCompleted 가 진행 행을 종결로 갱신해 회수 스윕까지
            //   포기하므로 스스로 놓아야 한다.
            if (preserveReviewOwnedStatus) {
                transitionService.markRawDataCompletedPreservingReviewStatus(rawSn);
            } else {
                transitionService.markRawDataCompleted(rawSn);
            }
            statusService.markCompleted(rawSn);
            retryQueue.clear(rawSn);
            log.info("[BatchOrchestrator] completed rawSn={}", rawSn);
            return BatchStage.COMPLETED;
        } catch (RuntimeException e) {
            // ★ 묶음 재수행 경로는 영상 상태를 훼손하지 않는다 [@design API-201] — 선점 직전 상태로 원상
            //   복구하고 자동 재시도 큐에 넣지 않는다. FAILED 로 강등하면 완주 영상이 실패로 뒤집히고,
            //   범위를 모르는 자동 재시도가 전 단계를 돌려 사람이 손댄 보간 라벨을 지운다(위 Javadoc).
            if (rerunRestoreStatus != null) {
                // 클레임 복구를 먼저 — 이 전이가 실행되지 않으면 stage 가 PROCESSING 으로 고착된다.
                transitionService.restoreAfterBundleRerunFailure(rawSn, rerunRestoreStatus);
                // 실패 사실은 남긴다(비동기라 요청이 결과를 받지 못한다 — 이 기록이 유일한 흔적이다).
                statusService.markFailed(rawSn, e);
                log.warn("[BatchOrchestrator] bundle rerun failed — state restored rawSn={} restored={} cause={}",
                        rawSn, rerunRestoreStatus, e.getClass().getSimpleName());
                return BatchStage.FAILED;
            }
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
            // 고착되므로 복구만 시도하고 원인 예외를 보존한다(복구 실패는 로깅만 — 원인 예외를 덮지 않는다).
            //
            // ★ 복구 계약은 RuntimeException 갈래와 <b>같다</b> [@design API-201] — 갈림의 기준도 동일하게
            //   {@code rerunRestoreStatus} 다. 이 분기가 없으면 묶음 재수행 중 Error(오토라벨 재수행은 프레임
            //   이미지 인코딩을 수반한다)에서 <b>완주 영상이 FAILED 로 강등</b>되고, 그러면 전체 재기동
            //   (API-167) 경로가 열려 토글 없는 파이프라인이 사람이 손댄 보간 라벨을 전량 삭제·재생성한다
            //   (작업 상태까지 FAILED 라 작업자가 검수 제출도 못 한다). 두 갈래를 한 헬퍼로 합치지 않는
            //   이유는 그 차이가 인자 하나에 숨으면 조용히 뒤바뀌기 때문이다(진입점을 나눈 것과 같은 원칙).
            //
            // 부기(statusService.markFailed)·자동 재시도 큐는 두 갈래 모두 타지 않는다 — Error 는 "재시도로
            //   넘길 실패"가 아니며, 치명적 오류 상황에서 쓰기 시도를 늘리면 원인 예외를 가릴 위험만 커진다.
            if (rerunRestoreStatus != null) {
                restoreBundleRerunStateQuietly(rawSn, rerunRestoreStatus);
            } else {
                releaseStageClaimQuietly(rawSn);
            }
            throw e;
        }
    }

    /**
     * 이번 실행에서 <b>실제로 돌 단계</b>의 보수적 상위집합 — 면제 모드 fail-closed 검사의 입력.
     *
     * <h3>왜 토글 맵을 그대로 넘기지 않는가 (Critical)</h3>
     * <p>{@link BatchContext#isStageEnabled} 는 <b>토글 맵에 키가 없으면 켜진 것으로</b> 읽는다. 즉
     * 맵에서 {@code true} 인 항목만 세면 「맵에 없어서 도는」 단계를 통째로 놓친다. 그래서 해석 규칙을
     * 복제하지 않고 <b>{@link BatchContext} 에게 그대로 묻는다</b>(판정 규칙 단일 지점).
     *
     * <p>각 {@code BatchStep} 이 런타임에 스스로를 더 끄거나 수동 스킵 표식이 단계를 건너뛸 수 있으나,
     * 그것들은 집합을 <b>줄이기만</b> 한다. 따라서 이 상위집합으로 판정하면 fail-closed 다 —
     * 여기서 DB 를 읽지 않으므로 검사 자체가 부수효과를 만들지도 않는다.
     */
    private List<BatchStage> stagesThatWillRun(Map<String, Boolean> stageToggles) {
        BatchContext probe = new BatchContext(null, null, stageToggles);
        List<BatchStage> stages = new java.util.ArrayList<>();
        for (BatchStep step : pipeline.steps()) {
            if (probe.isStageEnabled(step.stage())) {
                stages.add(step.stage());
            }
        }
        return stages;
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

    /**
     * 묶음 재수행의 원상 복구(선점 직전 배치 단계 + 작업 상태) 시도 — 실패해도 원인 예외를 덮지 않도록
     * 삼키고 로깅만 한다. [@design API-201]
     *
     * <p>{@link #releaseStageClaimQuietly} 와 목적지가 다르다(FAILED 강등 vs 원상 복구). 두 헬퍼를
     * 합치지 않는다 — 목적지가 인자로 숨으면 어느 갈래가 어디로 가는지가 호출부에서 보이지 않는다.
     */
    private void restoreBundleRerunStateQuietly(Long rawSn, String rerunRestoreStatus) {
        try {
            transitionService.restoreAfterBundleRerunFailure(rawSn, rerunRestoreStatus);
        } catch (Throwable t) {
            log.error("[BatchOrchestrator] bundle rerun state restore failed rawSn={} reason={}",
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
