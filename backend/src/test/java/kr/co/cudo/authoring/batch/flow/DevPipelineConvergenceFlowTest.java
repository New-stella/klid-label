package kr.co.cudo.authoring.batch.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.pipeline.MarkingLoadStep;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.runner.DevPipelineRunner;
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
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 — dev 단일 파이프라인 수렴 흐름(협력 빈) 테스트.
 *
 * <p>{@link DevPipelineRunner} 는 dev 업로드(사람 마킹·VideoIngestedEvent 없음)를 단일 프로덕션
 * 파이프라인으로 수렴시킨다. 본 테스트는 러너 + 실제 {@link BatchTransitionService} +
 * 실제 {@link BatchOrchestrator}(post-marking 단계 순서)를 묶어, dev 업로드가
 * <b>비식별선두 → 합성 마킹 → 잔여 파이프라인</b>을 신 순서로 순차 실행하는지 검증한다.
 *
 * <p>외부 step·BatchStatusService·repo 만 mock 으로 격리하고, repo mock 은 실제 엔티티를 반환해
 * 전이({@code markMarkingReady}/{@code changeStatus}) 가 같은 인스턴스에서 관찰되게 한다.
 */
class DevPipelineConvergenceFlowTest {

    private DevPipelineRunner runner;
    private BatchOrchestrator orchestrator;
    private BatchTransitionService transitionService;

    private VideoRepository videoRepository;
    private LsRawDataStatusRepository statusRepository;
    private LsMarkingRepository markingRepository;
    private BatchStatusService batchStatusService;
    private DeidentifyStep deidentifyStep;
    private VlmTimeseriesStep vlmStep;
    private FfmpegFrameExtractor frameExtractor;
    private YoloAutolabelStep yoloStep;
    private Sam2SegmentStep sam2Step;
    private TrackInterpolationStep interpStep;

    private static final Long RAW_SN = 900L;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        statusRepository = mock(LsRawDataStatusRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        deidentifyStep = mock(DeidentifyStep.class);
        vlmStep = mock(VlmTimeseriesStep.class);
        frameExtractor = mock(FfmpegFrameExtractor.class);
        yoloStep = mock(YoloAutolabelStep.class);
        sam2Step = mock(Sam2SegmentStep.class);
        interpStep = mock(TrackInterpolationStep.class);

        transitionService = new BatchTransitionService(statusRepository, videoRepository);

        BatchRetryQueue retryQueue = new BatchRetryQueue(3, 60);
        orchestrator = new BatchOrchestrator(
                postPipeline(), batchStatusService, transitionService, retryQueue, videoRepository);

        runner = new DevPipelineRunner(
                orchestrator, deidentifyStep, transitionService,
                videoRepository, markingRepository, new ObjectMapper());

        when(frameExtractor.extractByMarks(any(LsDataRaw.class), any()))
                .thenReturn(List.of(mock(LsDataSrc.class)));
    }

    private BatchPipeline postPipeline() {
        MarkingLoadStep markingLoad = new MarkingLoadStep(markingRepository, new ObjectMapper());

        BatchStep vlmAdapter = simpleAdapter(BatchStage.VLM, ctx -> {
            if (!ctx.getMarkings().isEmpty()) {
                vlmStep.runWithMarking(ctx.getRawSn(), ctx.getMarkings().get(0));
            } else {
                vlmStep.run(ctx.getRawSn());
            }
        });
        BatchStep frameAdapter = toggleableAdapter(BatchStage.FRAME_EXTRACT, ctx -> {
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
        BatchStep yoloAdapter = toggleableAdapter(BatchStage.YOLO, ctx -> ctx.setHints(yoloStep.run(ctx.getRawSn())));
        BatchStep sam2Adapter = toggleableAdapter(BatchStage.SAM2, ctx -> sam2Step.run(ctx.getRawSn(), ctx.getHints()));
        BatchStep interpAdapter = simpleAdapter(BatchStage.INTERPOLATE, ctx -> interpStep.run(ctx.getRawSn()));

        return new BatchPipeline(List.of(
                markingLoad, vlmAdapter, frameAdapter, yoloAdapter, sam2Adapter, interpAdapter));
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

    private static BatchStep toggleableAdapter(BatchStage stage, Body body) {
        return new BatchStep() {
            @Override
            public BatchStage stage() {
                return stage;
            }

            @Override
            public boolean isEnabled(BatchContext ctx) {
                return ctx.isStageEnabled(stage);
            }

            @Override
            public void execute(BatchContext ctx) {
                body.run(ctx);
            }
        };
    }

    private LsDataRaw newRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT_FALL", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "raw/path.mp4", null, 60);
        try {
            Field f = raw.getClass().getDeclaredField("rawSn");
            f.setAccessible(true);
            f.set(raw, rawSn);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return raw;
    }

    private static Map<String, Boolean> toggles(boolean frame, boolean deident, boolean yolo, boolean sam2) {
        Map<String, Boolean> m = new HashMap<>();
        m.put("FRAME_EXTRACT", frame);
        m.put("DEIDENTIFY", deident);
        m.put("YOLO", yolo);
        m.put("SAM2", sam2);
        return m;
    }

    @Test
    @DisplayName("dev_업로드가_비식별선두_합성마킹_파이프라인을_순차_실행")
    void devUpload_runsDeidentFirst_syntheticMarking_pipeline_inOrder() {
        // given — dev 업로드 영상(PENDING). 전 토글 on.
        LsDataRaw raw = newRaw(RAW_SN);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        // DeidentifyStep.run 은 dev 경로에서 raw 를 deIdntfYn='Y' 로 마킹.
        doAnswer(inv -> {
            ((LsDataRaw) inv.getArgument(0)).markDeidentified("Y");
            return "deid/path.mp4";
        }).when(deidentifyStep).run(raw);
        // 합성 마킹 저장 → 이후 MarkingLoadStep 이 조회.
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(markingRepository.findByRawSnOrderByRegDtDesc(RAW_SN))
                .thenAnswer(inv -> List.of(LsMarking.createAuto(RAW_SN, "EVT_FALL", 1,
                        "raw/path.mp4", "[{\"frameIndex\":0,\"timestamp\":null}]", null)));

        LsRawDataStatus stts = LsRawDataStatus.initial(RAW_SN);
        stts.markAssigned();
        when(statusRepository.findById(RAW_SN)).thenReturn(Optional.of(stts));

        // when — dev 단일 파이프라인 실행 (러너 → orchestrator 수렴을 동기 재현).
        runner.runAsync(RAW_SN, toggles(true, true, true, true));

        // then (1) — 선두 비식별 실행 + MARKING_READY 전이 후 합성 마킹 저장.
        verify(deidentifyStep).run(raw);
        verify(markingRepository).save(any(LsMarking.class));

        // then (2) — 단일 프로덕션 파이프라인이 신 순서(비식별 단계 없음)로 진행.
        InOrder order = inOrder(deidentifyStep, markingRepository, batchStatusService,
                vlmStep, frameExtractor, yoloStep, sam2Step, interpStep);
        order.verify(deidentifyStep).run(raw);
        order.verify(markingRepository).save(any(LsMarking.class));
        order.verify(batchStatusService).markStage(RAW_SN, BatchStage.MARKING);
        order.verify(batchStatusService).markStage(RAW_SN, BatchStage.VLM);
        order.verify(batchStatusService).markStage(RAW_SN, BatchStage.FRAME_EXTRACT);
        order.verify(frameExtractor).extractByMarks(any(LsDataRaw.class), any());
        order.verify(batchStatusService).markStage(RAW_SN, BatchStage.YOLO);
        order.verify(batchStatusService).markStage(RAW_SN, BatchStage.SAM2);
        order.verify(batchStatusService).markStage(RAW_SN, BatchStage.INTERPOLATE);

        // then (3) — 완료 시 책임 분리: 작업 상태 ASSIGNED 복귀, 배치 단계 COMPLETED.
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_ASSIGNED);
        assertThat(raw.getDataSttsCd()).isEqualTo("COMPLETED");
        verify(batchStatusService).markCompleted(RAW_SN);
    }

    @Test
    @DisplayName("dev_FRAME_EXTRACT_off면_합성마킹_미생성_FRAME단계_skip하고_완료")
    void devUpload_frameOff_skipsSyntheticMarking_andFrameStage() {
        // given — FRAME_EXTRACT off, DEIDENTIFY off (off 시 deIdntfYn 미설정이므로 FRAME 가드 회피 위해 frame 도 off).
        LsDataRaw raw = newRaw(RAW_SN);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        when(statusRepository.findById(RAW_SN)).thenReturn(Optional.of(initialAssigned()));
        // FRAME off → 합성 마킹 미생성 → MarkingLoadStep 은 빈 리스트(VLM 은 run() 분기).
        when(markingRepository.findByRawSnOrderByRegDtDesc(RAW_SN)).thenReturn(List.of());

        // when
        runner.runAsync(RAW_SN, toggles(false, false, false, false));

        // then — 합성 마킹 미생성, FRAME 단계 skip(extractByMarks 미호출), 정상 완료.
        verify(markingRepository, org.mockito.Mockito.never()).save(any());
        verify(frameExtractor, org.mockito.Mockito.never()).extractByMarks(any(), any());
        verify(batchStatusService, org.mockito.Mockito.never()).markStage(RAW_SN, BatchStage.FRAME_EXTRACT);
        // 마킹 없으므로 VLM 은 run() 분기.
        verify(vlmStep).run(RAW_SN);
        verify(batchStatusService).markCompleted(RAW_SN);
        assertThat(raw.getDataSttsCd()).isEqualTo("COMPLETED");
    }

    private LsRawDataStatus initialAssigned() {
        LsRawDataStatus s = LsRawDataStatus.initial(RAW_SN);
        s.markAssigned();
        return s;
    }
}
