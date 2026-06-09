package kr.co.cudo.authoring.batch.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.listener.IngestDeidentifyBridge;
import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.pipeline.MarkingLoadStep;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.runner.AsyncBatchRunner;
import kr.co.cudo.authoring.batch.runner.AsyncDeidentifyRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import kr.co.cudo.authoring.marking.listener.MarkingBatchBridge;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 — 신 시나리오 전체 흐름 + 상태전이 통합(협력 빈) 테스트.
 *
 * <p>단위 mock 테스트들이 각 조각(IngestDeidentifyBridge / AsyncDeidentifyRunner /
 * MarkingBatchBridge / BatchOrchestrator / BatchTransitionService)을 개별로 덮지만, 조각을
 * <b>잇는 흐름과 상태 전이</b>의 회귀 가드가 없다. 본 테스트는 프로젝트 관례(협력 빈을 손으로 묶는
 * 단위/흐름 테스트, {@code PipelineTestSupport} 패턴)에 맞춰 실제 브릿지/러너/오케스트레이터/전이
 * 서비스를 묶고, 외부 step·BatchStatusService·@Async 러너·repo 만 mock 으로 격리한다.
 *
 * <h3>왜 @SpringBootTest 가 아니라 협력 빈 묶음인가</h3>
 * <ul>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Async} 타이밍은 통합
 *       컨텍스트에서 비결정적이라 흐름 단언이 flaky 해진다. 본 테스트는 브릿지 → 러너 위임을
 *       동기로 직접 재현해 read-after-write 가시성 레이스를 제거한다(프로젝트의 기존 흐름 검증 관례).</li>
 *   <li>repo 는 mock 이되 <b>실제 엔티티 인스턴스</b>를 반환하므로, 전이 서비스가 엔티티에 호출하는
 *       {@code markMarkingReady()} / {@code changeStatus("COMPLETED")} / {@code transitionTo(...)} 의
 *       결과가 같은 인스턴스에서 관찰된다 → 실제 상태머신 코드 경로로 전이가 검증된다.</li>
 * </ul>
 */
class BatchPipelineReorderFlowTest {

    // --- 협력 빈 (실제 구현) ---
    private BatchTransitionService transitionService;
    private BatchOrchestrator orchestrator;
    private AsyncDeidentifyRunner deidentifyRunner;
    private IngestDeidentifyBridge ingestBridge;
    private MarkingBatchBridge markingBridge;

    // --- 외부/비동기 격리 mock ---
    private VideoRepository videoRepository;
    private LsRawDataStatusRepository statusRepository;
    private LsMarkingRepository markingRepository;
    private BatchStatusService batchStatusService;
    private AsyncBatchRunner asyncBatchRunner;
    private DeidentifyStep deidentifyStep;
    private VlmTimeseriesStep vlmStep;
    private FfmpegFrameExtractor frameExtractor;
    private YoloAutolabelStep yoloStep;
    private Sam2SegmentStep sam2Step;
    private TrackInterpolationStep interpStep;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        statusRepository = mock(LsRawDataStatusRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        asyncBatchRunner = mock(AsyncBatchRunner.class);
        deidentifyStep = mock(DeidentifyStep.class);
        vlmStep = mock(VlmTimeseriesStep.class);
        frameExtractor = mock(FfmpegFrameExtractor.class);
        yoloStep = mock(YoloAutolabelStep.class);
        sam2Step = mock(Sam2SegmentStep.class);
        interpStep = mock(TrackInterpolationStep.class);

        // 실제 전이 서비스 — repo mock 이 실제 엔티티를 반환하므로 전이가 엔티티에 반영된다.
        transitionService = new BatchTransitionService(statusRepository, videoRepository);

        // pre-marking 파이프라인: 실제 step 어댑터로 DeidentifyStep.run(raw) 호출 재현(execute → raw.markDeidentified).
        BatchPipeline preMarkingPipeline = new BatchPipeline(List.of(deidStepAdapter()));
        deidentifyRunner = new AsyncDeidentifyRunner(preMarkingPipeline, transitionService, videoRepository);

        // post-marking 파이프라인: 실제 단계 순서(MARKING→VLM→FRAME_EXTRACT→YOLO→SAM2→INTERPOLATE).
        BatchPipeline postMarkingPipeline = postPipeline();
        BatchRetryQueue retryQueue = new BatchRetryQueue(3, 60);
        orchestrator = new BatchOrchestrator(
                postMarkingPipeline, batchStatusService, transitionService, retryQueue, videoRepository);

        ingestBridge = new IngestDeidentifyBridge(deidentifyRunner);
        markingBridge = new MarkingBatchBridge(
                statusRepository, batchStatusService, asyncBatchRunner, videoRepository);

        // 기본: frame extractor 가 1 프레임 반환 (FRAME_EXTRACT 가드 통과).
        when(frameExtractor.extractByMarks(any(LsDataRaw.class), any()))
                .thenReturn(List.of(mock(LsDataSrc.class)));
    }

    // ----- 실제 step 순서를 묶는 post-marking 파이프라인 (PipelineTestSupport 와 동일 동작) -----
    private BatchPipeline postPipeline() {
        MarkingLoadStep markingLoad = new MarkingLoadStep(markingRepository, new ObjectMapper());

        BatchStep vlmAdapter = simpleAdapter(BatchStage.VLM, ctx -> {
            if (!ctx.getMarkings().isEmpty()) {
                vlmStep.runWithMarking(ctx.getRawSn(), ctx.getMarkings().get(0));
            } else {
                vlmStep.run(ctx.getRawSn());
            }
        });
        BatchStep frameAdapter = simpleAdapter(BatchStage.FRAME_EXTRACT, ctx -> {
            if (ctx.getMarks().isEmpty()) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "마킹 데이터가 없습니다. rawSn=" + ctx.getRawSn());
            }
            List<LsDataSrc> frames = frameExtractor.extractByMarks(ctx.getRaw(), ctx.getMarks());
            if (frames.isEmpty()) {
                throw new CustomException(ErrorCode.INTERNAL_ERROR,
                        "프레임 추출 결과가 0건입니다 rawSn=" + ctx.getRawSn());
            }
        });
        BatchStep yoloAdapter = simpleAdapter(BatchStage.YOLO, ctx -> ctx.setHints(yoloStep.run(ctx.getRawSn())));
        BatchStep sam2Adapter = simpleAdapter(BatchStage.SAM2, ctx -> sam2Step.run(ctx.getRawSn(), ctx.getHints()));
        BatchStep interpAdapter = simpleAdapter(BatchStage.INTERPOLATE, ctx -> interpStep.run(ctx.getRawSn()));

        return new BatchPipeline(List.of(
                markingLoad, vlmAdapter, frameAdapter, yoloAdapter, sam2Adapter, interpAdapter));
    }

    /** pre-marking 단계 어댑터 — DeidentifyStep.run(raw) 호출(=raw.markDeidentified('Y')) 재현. */
    private BatchStep deidStepAdapter() {
        return simpleAdapter(BatchStage.DEIDENTIFY, ctx -> deidentifyStep.run(ctx.getRaw()));
    }

    private interface Body {
        void run(BatchContext ctx);
    }

    private static BatchStep simpleAdapter(BatchStage stage, Body body) {
        return new BatchStep() {
            @Override
            public BatchStage stage() {
                return stage;
            }

            @Override
            public void execute(BatchContext ctx) {
                body.run(ctx);
            }
        };
    }

    // ----- 픽스처 -----

    private LsDataRaw newRaw(Long rawSn, String prvcTypeCd) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV", prvcTypeCd, "raw/path.mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        return raw;
    }

    private LsMarking newMarking(Long rawSn) {
        return LsMarking.createAuto(rawSn, "fire", 5,
                "raw/path.mp4", "[{\"frameIndex\":0,\"timestamp\":\"00:00\"}]", 1L);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // =====================================================================================
    // 1. 전체 파이프라인 정상 흐름
    // =====================================================================================

    @Test
    @DisplayName("전체_파이프라인_적재_비식별선두_MARKING_READY_마킹_잔여배치_정상흐름")
    void fullPipeline_ingest_deidentFirst_markingReady_marking_postBatch_happyPath() {
        // given — 적재된 PRVC 영상(PENDING). DeidentifyStep 은 성공 시 raw 를 deIdntfYn='Y' 로 마킹.
        Long rawSn = 800L;
        LsDataRaw raw = newRaw(rawSn, LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        doAnswer(inv -> {
            ((LsDataRaw) inv.getArgument(0)).markDeidentified("Y");
            return "deid/path.mp4";
        }).when(deidentifyStep).run(raw);

        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.markAssigned(); // 배정 시점 생성된 작업 상태 (마킹 진입 전제)
        when(statusRepository.findById(rawSn)).thenReturn(Optional.of(stts));
        when(markingRepository.findByRawSnOrderByRegDtDesc(rawSn))
                .thenReturn(List.of(newMarking(rawSn)));

        // 적재 직후 배치 단계 상태는 PENDING 이어야 한다.
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);

        // when (1) — 적재 이벤트 → 선두 비식별 (브릿지 → 러너 위임을 동기 재현).
        ingestBridge.onVideoIngested(new VideoIngestedEvent(rawSn));

        // then (1) — 비식별 성공 → deIdntfYn='Y', 배치 단계 상태 MARKING_READY 전이.
        verify(deidentifyStep).run(raw);
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);

        // when (2) — 마킹 완료 → 브릿지가 deIdntfYn='Y' 가드 통과 → 작업 상태 BATCH_QUEUED + asyncBatchRunner 위임.
        markingBridge.onMarkingCompleted(new MarkingCompletedEvent(rawSn, 100L));

        // then (2) — 배치 트리거됨.
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_BATCH_QUEUED);
        verify(batchStatusService).markStage(rawSn, BatchStage.PENDING);
        verify(asyncBatchRunner).runAsync(rawSn);

        // when (3) — 잔여 배치 실행 (asyncBatchRunner 가 위임하는 orchestrator.process 를 동기 재현).
        BatchStage result = orchestrator.process(rawSn);

        // then (3) — 비식별 단계 없이 VLM→FRAME_EXTRACT→YOLO→SAM2→INTERPOLATE 순서로 진행 후 완료.
        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        InOrder order = inOrder(batchStatusService, vlmStep, frameExtractor, yoloStep, sam2Step, interpStep);
        order.verify(batchStatusService).markStage(rawSn, BatchStage.MARKING);
        order.verify(batchStatusService).markStage(rawSn, BatchStage.VLM);
        order.verify(vlmStep).runWithMarking(eq(rawSn), any(LsMarking.class));
        order.verify(batchStatusService).markStage(rawSn, BatchStage.FRAME_EXTRACT);
        order.verify(frameExtractor).extractByMarks(any(LsDataRaw.class), any());
        order.verify(batchStatusService).markStage(rawSn, BatchStage.YOLO);
        order.verify(yoloStep).run(rawSn);
        order.verify(batchStatusService).markStage(rawSn, BatchStage.SAM2);
        order.verify(sam2Step).run(eq(rawSn), any());
        order.verify(batchStatusService).markStage(rawSn, BatchStage.INTERPOLATE);
        order.verify(interpStep).run(rawSn);
        // 비식별은 post-marking 시퀀스에서 단계 마킹되지 않는다.
        verify(batchStatusService, never()).markStage(rawSn, BatchStage.DEIDENTIFY);

        // then (4) — 완료 시 책임 분리: 작업 상태 ASSIGNED 복귀, 배치 단계 상태 COMPLETED.
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_ASSIGNED);
        assertThat(raw.getDataSttsCd()).isEqualTo("COMPLETED");
        verify(batchStatusService).markCompleted(rawSn);
    }

    // =====================================================================================
    // 2. 상태 전이 단계 검증 (PENDING → MARKING_READY → COMPLETED) + 책임 분리
    // =====================================================================================

    @Test
    @DisplayName("상태전이_PENDING_MARKING_READY_그리고_배치완료_COMPLETED")
    void stateTransition_pending_markingReady_completed_andResponsibilitySplit() {
        // given
        Long rawSn = 810L;
        LsDataRaw raw = newRaw(rawSn, LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        doAnswer(inv -> {
            ((LsDataRaw) inv.getArgument(0)).markDeidentified("Y");
            return "deid/path.mp4";
        }).when(deidentifyStep).run(raw);

        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn); // 작업 상태: PENDING 시작
        stts.markAssigned();
        when(statusRepository.findById(rawSn)).thenReturn(Optional.of(stts));
        when(markingRepository.findByRawSnOrderByRegDtDesc(rawSn))
                .thenReturn(List.of(newMarking(rawSn)));

        // 단계 1: 적재 직후 — 배치 단계 PENDING.
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);

        // 단계 2: 비식별 성공 → MARKING_READY.
        deidentifyRunner.runAsync(rawSn);
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);

        // 단계 3: 배치 완료 → 배치 단계 COMPLETED.
        orchestrator.process(rawSn);
        assertThat(raw.getDataSttsCd()).isEqualTo("COMPLETED");

        // 책임 분리: LsRawDataStatus(작업/검수 워크플로우) 는 COMPLETED 로 점프하지 않고 ASSIGNED 복귀.
        // (검수 승인 시점의 COMPLETED 전이는 ReviewService 책임 — 배치 완료가 점프시키면 검수 제출이 차단됨)
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_ASSIGNED);
        assertThat(stts.getDataSttsCd()).isNotEqualTo(LsRawDataStatus.STTS_COMPLETED);
    }

    // =====================================================================================
    // 3. 비식별 실패 → MARKING_READY 미전이 + 마킹완료해도 배치 미트리거
    // =====================================================================================

    @Test
    @DisplayName("비식별_실패시_MARKING_READY_미전이_그리고_마킹완료해도_배치_미트리거")
    void deidentFailure_noMarkingReady_andMarkingCompletedDoesNotTriggerBatch() {
        // given — 비식별 실패: DeidentifyStep 이 deIdntfYn='F' 마킹 + 예외 전파.
        Long rawSn = 820L;
        LsDataRaw raw = newRaw(rawSn, LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        doAnswer(inv -> {
            ((LsDataRaw) inv.getArgument(0)).markDeidentified("F");
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "deident 5xx");
        }).when(deidentifyStep).run(raw);

        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.markAssigned();
        when(statusRepository.findById(rawSn)).thenReturn(Optional.of(stts));

        // when (1) — 적재 이벤트 → 선두 비식별 (실패, @Async 예외 삼킴).
        ingestBridge.onVideoIngested(new VideoIngestedEvent(rawSn));

        // then (1) — 실패 시 deIdntfYn='F', MARKING_READY 미전이(배치 단계 PENDING 유지).
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);

        // when (2) — 마킹 완료 이벤트 도착.
        markingBridge.onMarkingCompleted(new MarkingCompletedEvent(rawSn, 200L));

        // then (2) — MarkingBatchBridge 가드(deIdntfYn != 'Y')로 배치 트리거 skip (외부 수동 재비식별 경로).
        verify(asyncBatchRunner, never()).runAsync(rawSn);
        verify(batchStatusService, never()).markStage(rawSn, BatchStage.PENDING);
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_ASSIGNED); // BATCH_QUEUED 로 전이 안 됨
    }

    // =====================================================================================
    // 4. 배치 단계 실패 시 책임 분리 — 작업 상태 FAILED, 배치 단계 COMPLETED 미전이
    // =====================================================================================

    @Test
    @DisplayName("잔여배치_단계실패시_작업상태_FAILED_그리고_COMPLETED_미전이")
    void postBatchStepFailure_marksFailed_noCompleted() {
        // given — 비식별까지 정상, 잔여 배치의 YOLO 단계에서 실패.
        Long rawSn = 830L;
        LsDataRaw raw = newRaw(rawSn, LsDataRaw.PRVC_TYPE_PRVC);
        raw.markDeidentified("Y");
        raw.markMarkingReady();
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.markBatchQueued();
        when(statusRepository.findById(rawSn)).thenReturn(Optional.of(stts));
        when(markingRepository.findByRawSnOrderByRegDtDesc(rawSn))
                .thenReturn(List.of(newMarking(rawSn)));
        doThrow(new RuntimeException("yolo 5xx")).when(yoloStep).run(rawSn);

        // when
        BatchStage result = orchestrator.process(rawSn);

        // then — 작업 상태 FAILED, 배치 단계 상태는 COMPLETED 로 전이되지 않음(MARKING_READY 유지).
        assertThat(result).isEqualTo(BatchStage.FAILED);
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_FAILED);
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        verify(batchStatusService).markFailed(eq(rawSn), any(RuntimeException.class));
        verify(batchStatusService, never()).markCompleted(rawSn);
    }
}
