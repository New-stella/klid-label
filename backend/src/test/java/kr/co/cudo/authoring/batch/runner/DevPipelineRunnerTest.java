package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;

/**
 * DevPipelineRunner 단위 테스트 (Phase 3 — dev 경로를 프로덕션 파이프라인으로 수렴).
 *
 * <p>dev 업로드는 사람 마킹·VideoIngestedEvent 가 없으므로, 본 러너가 토글에 따라
 * 비식별(+MARKING_READY) 과 합성 마킹 생성을 순차 수행한 뒤 단일 프로덕션
 * {@link BatchOrchestrator#process(Long, Map)} 를 호출한다.
 */
class DevPipelineRunnerTest {

    private BatchOrchestrator orchestrator;
    private DeidentifyStep deidentifyStep;
    private BatchTransitionService transitionService;
    private VideoRepository videoRepository;
    private LsMarkingRepository markingRepository;

    private DevPipelineRunner runner;

    private static final Long RAW_SN = 700L;

    @BeforeEach
    void setUp() {
        orchestrator = mock(BatchOrchestrator.class);
        deidentifyStep = mock(DeidentifyStep.class);
        transitionService = mock(BatchTransitionService.class);
        videoRepository = mock(VideoRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        runner = new DevPipelineRunner(
                orchestrator, deidentifyStep, transitionService,
                videoRepository, markingRepository,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private LsDataRaw rawWith(Long rawSn) {
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
    @DisplayName("DEIDENTIFY_on이면_deid_실행후_MARKING_READY_전이")
    void deidentifyOnRunsDeidAndMarkingReady() {
        LsDataRaw raw = rawWith(RAW_SN);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));

        runner.runAsync(RAW_SN, toggles(false, true, false, false));

        verify(deidentifyStep).run(raw);
        verify(transitionService).markRawDataMarkingReady(RAW_SN);
        verify(orchestrator).process(eq(RAW_SN), anyMap());
    }

    @Test
    @DisplayName("DEIDENTIFY_off이면_deid_미실행_MARKING_READY_미전이")
    void deidentifyOffSkipsDeid() {
        LsDataRaw raw = rawWith(RAW_SN);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));

        runner.runAsync(RAW_SN, toggles(false, false, false, false));

        verify(deidentifyStep, never()).run(any());
        verify(transitionService, never()).markRawDataMarkingReady(any());
        verify(orchestrator).process(eq(RAW_SN), anyMap());
    }

    @Test
    @DisplayName("FRAME_EXTRACT_on이면_합성_마킹을_생성_저장")
    void frameExtractOnCreatesSyntheticMarking() {
        LsDataRaw raw = rawWith(RAW_SN);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        runner.runAsync(RAW_SN, toggles(true, true, true, true));

        org.mockito.ArgumentCaptor<LsMarking> captor = forClass(LsMarking.class);
        verify(markingRepository).save(captor.capture());
        LsMarking saved = captor.getValue();
        assertThat(saved.getRawSn()).isEqualTo(RAW_SN);
        // 합성 마킹은 최소 1개 MarkItem(frameIndex 0) JSON 을 담는다.
        assertThat(saved.getMarkCn()).contains("frameIndex");
        verify(orchestrator).process(eq(RAW_SN), anyMap());
    }

    @Test
    @DisplayName("FRAME_EXTRACT_off이면_합성_마킹_미생성")
    void frameExtractOffSkipsSyntheticMarking() {
        LsDataRaw raw = rawWith(RAW_SN);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));

        runner.runAsync(RAW_SN, toggles(false, false, true, true));

        verify(markingRepository, never()).save(any());
        verify(orchestrator).process(eq(RAW_SN), anyMap());
    }

    @Test
    @DisplayName("토글이_orchestrator_process에_그대로_전달")
    void togglesForwardedToProcess() {
        LsDataRaw raw = rawWith(RAW_SN);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Boolean> t = toggles(true, true, false, true);
        runner.runAsync(RAW_SN, t);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Boolean>> captor = forClass(Map.class);
        verify(orchestrator).process(eq(RAW_SN), captor.capture());
        assertThat(captor.getValue()).containsEntry("YOLO", false).containsEntry("SAM2", true);
    }

    @Test
    @DisplayName("raw_없으면_graceful_skip_deid_orchestrator_미호출")
    void rawNotFoundGracefulSkip() {
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.empty());

        runner.runAsync(RAW_SN, toggles(true, true, true, true));

        verify(deidentifyStep, never()).run(any());
        verify(orchestrator, never()).process(any(), anyMap());
        verify(markingRepository, never()).save(any());
    }

    @Test
    @DisplayName("deid_실패해도_예외_삼킴_orchestrator는_호출안됨_않으면_무한대기_방지")
    void deidFailureSwallowed() {
        LsDataRaw raw = rawWith(RAW_SN);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        org.mockito.Mockito.doThrow(new RuntimeException("deid 5xx")).when(deidentifyStep).run(raw);

        // @Async 예외 삼킴 — 호출이 예외를 전파하지 않아야 한다.
        runner.runAsync(RAW_SN, toggles(false, true, true, true));

        verify(deidentifyStep).run(raw);
        // deid 실패 시 이후 단계(MARKING_READY/process)는 진행하지 않는다.
        verify(transitionService, never()).markRawDataMarkingReady(any());
        verify(orchestrator, never()).process(any(), anyMap());
    }
}
