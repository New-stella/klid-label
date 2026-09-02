package kr.co.cudo.authoring.batch.pipeline;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.step.BbHint;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.batch.policy.PresetResolution;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.config.DeployedEnvironmentDetector;
import kr.co.cudo.authoring.label.service.FrameBoundsResolver;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 각 step 의 {@code execute(BatchContext)} 경로 단위 테스트 — typed 메서드로의 위임 +
 * stage 식별자 + ctx 읽기/쓰기 + FRAME_EXTRACT 가드를 검증한다.
 *
 * <p>typed 메서드(run/extractByMarks)는 spy 로 스텁하여 DB/외부 호출 없이 위임만 검증한다.
 */
class BatchStepExecuteTest {

    private static LsDataRaw rawWith(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
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

    private static MarkItem mark(int idx) {
        return new MarkItem(idx, "00:00");
    }

    // --- VLM ---

    @Test
    @DisplayName("VLM_execute_마킹있으면_runWithMarking_호출_stage_VLM")
    void vlmExecuteWithMarking() {
        VlmTimeseriesStep real = mock(VlmTimeseriesStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(null).when(real).runWithMarking(any(), any());

        LsMarking marking = LsMarking.createAuto(1L, 5, "[]", "1");
        BatchContext ctx = new BatchContext(1L, rawWith(1L));
        ctx.setMarkings(List.of(marking));

        real.execute(ctx);

        assertThat(real.stage()).isEqualTo(BatchStage.VLM);
        verify(real).runWithMarking(eq(1L), eq(marking));
        verify(real, never()).run(any());
    }

    @Test
    @DisplayName("VLM_execute_마킹없으면_run_호출")
    void vlmExecuteWithoutMarking() {
        VlmTimeseriesStep real = mock(VlmTimeseriesStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.doReturn(null).when(real).run(any());

        BatchContext ctx = new BatchContext(2L, rawWith(2L));

        real.execute(ctx);

        verify(real).run(2L);
        verify(real, never()).runWithMarking(any(), any());
    }

    // --- FRAME_EXTRACT ---

    @Test
    @DisplayName("FRAME_execute_marks있으면_extractByMarks_pin전달_호출_stage_FRAME_EXTRACT")
    void frameExecuteWithMarks() {
        FfmpegFrameExtractor real = mock(FfmpegFrameExtractor.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        // execute 는 3-인자(pin) 오버로드로 위임한다.
        doReturn(List.of(mock(LsDataSrc.class))).when(real).extractByMarks(any(LsDataRaw.class), any(), any());

        LsDataRaw raw = rawWith(3L);
        BatchContext ctx = new BatchContext(3L, raw);
        ctx.setMarks(List.of(mark(0)));
        // 마킹이 pin 한 fps(25.0)를 execute 가 3-인자로 그대로 넘기는지 검증.
        LsMarking pinned = LsMarking.createAuto(3L, 5, "[]", "1", 25.0);
        ctx.setMarkings(List.of(pinned));

        real.execute(ctx);

        assertThat(real.stage()).isEqualTo(BatchStage.FRAME_EXTRACT);
        verify(real).extractByMarks(eq(raw), any(), eq(25.0));
    }

    @Test
    @DisplayName("FRAME_execute_마킹pin_null이면_3인자에_null_전달")
    void frameExecuteWithMarksNullPin() {
        FfmpegFrameExtractor real = mock(FfmpegFrameExtractor.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(List.of(mock(LsDataSrc.class))).when(real).extractByMarks(any(LsDataRaw.class), any(), any());

        LsDataRaw raw = rawWith(33L);
        BatchContext ctx = new BatchContext(33L, raw);
        ctx.setMarks(List.of(mark(0)));
        // markings 미설정(빈 리스트) → pin 없음 → null 전달(하위호환 폴백 경로).

        real.execute(ctx);

        verify(real).extractByMarks(eq(raw), any(), eq((Double) null));
    }

    @Test
    @DisplayName("FRAME_execute_marks비면_INVALID_INPUT_extractByMarks_미호출")
    void frameExecuteEmptyMarks() {
        FfmpegFrameExtractor real = mock(FfmpegFrameExtractor.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        BatchContext ctx = new BatchContext(4L, rawWith(4L));

        assertThatThrownBy(() -> real.execute(ctx))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode().name()).isEqualTo("INVALID_INPUT"));
        verify(real, never()).extractByMarks(any(), any(), any());
    }

    @Test
    @DisplayName("FRAME_execute_추출결과_0건이면_INTERNAL_ERROR")
    void frameExecuteEmptyFrames() {
        FfmpegFrameExtractor real = mock(FfmpegFrameExtractor.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(List.of()).when(real).extractByMarks(any(LsDataRaw.class), any(), any());

        BatchContext ctx = new BatchContext(5L, rawWith(5L));
        ctx.setMarks(List.of(mark(0)));

        assertThatThrownBy(() -> real.execute(ctx))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode().name()).isEqualTo("INTERNAL_ERROR"));
    }

    // --- YOLO ---

    /**
     * ★위임 형상이 바뀐 지점 — 탐지 단계의 {@code execute} 는 <b>프리셋 게이트를 먼저</b> 통과시킨 뒤
     * 본체를 돈다. [@design ADR-054]
     *
     * <p>그래서 구 형상({@code CALLS_REAL_METHODS} 로 {@code run(Long)} 만 스텁)이 성립하지 않는다 —
     * {@code execute} 가 본체보다 먼저 영상·프리셋을 조회하기 때문이다. 검증 대상(단계 식별자 ·
     * {@code ctx.hints} 적재)은 그대로 두고, 게이트를 통과시키기 위해 실물 스텝을 의존성 mock 으로 만든다.
     */
    @Test
    @DisplayName("YOLO_execute_는_프리셋_게이트_통과후_결과를_ctx_hints_에_적재_stage_YOLO")
    void yoloExecuteSetsHints() throws Exception {
        VideoRepository videoRepository = mock(VideoRepository.class);
        LsDataSrcRepository srcRepository = mock(LsDataSrcRepository.class);
        PresetLabelLookupService presetLabelLookup = mock(PresetLabelLookupService.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        FrameBoundsResolver frameBoundsResolver = mock(FrameBoundsResolver.class);
        LsDataRaw raw = rawWith(6L);
        org.mockito.Mockito.when(videoRepository.findById(6L)).thenReturn(java.util.Optional.of(raw));
        org.mockito.Mockito.when(presetLabelLookup.resolve(any())).thenReturn(
                PresetResolution.resolved(java.util.Map.of("person", new AnnotationToggle(true, true))));
        // 프레임 0건 — 본체는 즉시 빈 힌트를 돌려준다(외부 추론 호출 없음).
        org.mockito.Mockito.when(srcRepository.findByRawSnOrderByFrameNoAsc(6L)).thenReturn(List.of());
        org.mockito.Mockito.when(systemConfigService.getInt(any())).thenReturn(null);

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        YoloAutolabelStep real = new YoloAutolabelStep(
                mock(AiServerClient.class), srcRepository, mock(LsDataLblRepository.class),
                videoRepository, presetLabelLookup, mock(BatchStatusService.class),
                systemConfigService, mock(LabelMasterService.class), frameBoundsResolver,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                java.nio.file.Files.createTempDirectory("yolo-exec-").toString(),
                new DeployedEnvironmentDetector(env));

        BatchContext ctx = new BatchContext(6L, raw);
        real.execute(ctx);

        assertThat(real.stage()).isEqualTo(BatchStage.YOLO);
        assertThat(ctx.isWithheld()).isFalse();
        assertThat(ctx.getHints()).isEmpty();
        verify(srcRepository).findByRawSnOrderByFrameNoAsc(6L);
    }

    // --- SAM2 ---

    @Test
    @DisplayName("SAM2_execute_ctx_hints_를_run_에_전달_stage_SAM2")
    void sam2ExecutePassesHints() {
        Sam2SegmentStep real = mock(Sam2SegmentStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(0).when(real).run(any(), any());

        List<BbHint> hints = List.of(new BbHint(1L, "car", List.of(1.0, 2.0, 3.0, 4.0), 0.8, 3));
        BatchContext ctx = new BatchContext(7L, rawWith(7L));
        ctx.setHints(hints);

        real.execute(ctx);

        assertThat(real.stage()).isEqualTo(BatchStage.SAM2);
        verify(real).run(eq(7L), eq(hints));
    }

    // --- INTERPOLATE ---

    @Test
    @DisplayName("INTERPOLATE_execute_run_호출_stage_INTERPOLATE")
    void interpolateExecute() {
        TrackInterpolationStep real = mock(TrackInterpolationStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(0).when(real).run(any());

        BatchContext ctx = new BatchContext(8L, rawWith(8L));
        real.execute(ctx);

        assertThat(real.stage()).isEqualTo(BatchStage.INTERPOLATE);
        verify(real).run(8L);
    }

    @Test
    @DisplayName("INTERPOLATE_execute_run_실패_전파")
    void interpolateExecutePropagatesFailure() {
        TrackInterpolationStep real = mock(TrackInterpolationStep.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doThrow(new RuntimeException("boom")).when(real).run(any());

        BatchContext ctx = new BatchContext(9L, rawWith(9L));
        assertThatThrownBy(() -> real.execute(ctx)).isInstanceOf(RuntimeException.class);
    }
}
