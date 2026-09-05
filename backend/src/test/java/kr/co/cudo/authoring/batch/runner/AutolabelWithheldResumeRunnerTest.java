package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.pipeline.BatchBundleTogglePolicy;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.batch.policy.PresetResolution;
import kr.co.cudo.authoring.batch.policy.PresetResolutionStatus;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 프리셋이 채워진 뒤 <b>보류됐던 오토라벨 묶음의 재개</b>.
 * [@design ADR-054] [@design AC-115] [@design AC-119]
 *
 * <p>재개 조건 셋(보류 기록 존재 · 프리셋이 이제 실효 · 아직 완료 아님)이 전부 만족할 때만 파이프라인이
 * 돌고, 범위는 오토라벨 묶음(탐지→분할→보간)으로 한정된다(앞선 시계열 위탁이 이중으로 나가면 안 된다).
 */
class AutolabelWithheldResumeRunnerTest {

    private static final Long RAW_SN = 42L;
    /** 프리셋이 걸린 그룹 대표코드. */
    private static final String FILTER_KEY = "EV01000101";
    /** 그 그룹에 속한 영상의 상세 코드(비대표) — 그룹 축을 잊으면 후보에서 빠진다. */
    private static final String VIDEO_EV_CODE = "EV01000103";

    private BatchStatusService batchStatusService;
    private PresetLabelLookupService presetLabelLookup;
    private EventTypeService eventTypeService;
    private VideoRepository videoRepository;
    private BatchBundleTogglePolicy togglePolicy;
    private BatchOrchestrator orchestrator;
    private AutolabelWithheldResumeRunner runner;

    private final Map<String, Boolean> autolabelToggles =
            Map.of(BatchStage.YOLO.name(), true, BatchStage.VLM.name(), false);

    @BeforeEach
    void setUp() {
        batchStatusService = mock(BatchStatusService.class);
        presetLabelLookup = mock(PresetLabelLookupService.class);
        eventTypeService = mock(EventTypeService.class);
        videoRepository = mock(VideoRepository.class);
        togglePolicy = mock(BatchBundleTogglePolicy.class);
        orchestrator = mock(BatchOrchestrator.class);
        runner = new AutolabelWithheldResumeRunner(batchStatusService, presetLabelLookup,
                eventTypeService, videoRepository, togglePolicy, orchestrator);

        lenient().when(togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL)).thenReturn(autolabelToggles);
        lenient().when(eventTypeService.codesForFilterKey(FILTER_KEY))
                .thenReturn(Set.of(FILTER_KEY, VIDEO_EV_CODE));
        lenient().when(orchestrator.process(anyLong(), any())).thenReturn(BatchStage.COMPLETED);
    }

    private void withheldCandidate(Long rawSn) {
        when(batchStatusService.stageSkippedRawSns(
                BatchStage.YOLO, YoloAutolabelStep.RESUMABLE_SKIP_REASONS)).thenReturn(List.of(rawSn));
    }

    /**
     * 영상 mock 을 만들어 리포지토리 스텁까지 세운다.
     *
     * <p>★{@code when(repo.findById(..)).thenReturn(Optional.of(raw(..)))} 로 인라인 호출하면
     * 인자 평가가 진행 중인 스텁 안에서 다시 {@code when} 을 부르게 되어 Mockito 가
     * {@code UnfinishedStubbingException} 을 던진다. 그래서 mock 을 먼저 완성한 뒤 스텁을 세운다.
     */
    private LsDataRaw stubRaw(Long rawSn, String evntTypeCd, String dataSttsCd) {
        LsDataRaw raw = mock(LsDataRaw.class);
        lenient().when(raw.getEvntTypeCd()).thenReturn(evntTypeCd);
        lenient().when(raw.getDataSttsCd()).thenReturn(dataSttsCd);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        return raw;
    }

    @Test
    @DisplayName("보류된_영상은_프리셋이_실효해지면_오토라벨_묶음만_다시_돈다")
    void resumesWithheldVideoWithAutolabelBundleOnly() {
        withheldCandidate(RAW_SN);
        stubRaw(RAW_SN, VIDEO_EV_CODE, LsDataRaw.DATA_STTS_MARKING_READY);
        when(presetLabelLookup.resolve(VIDEO_EV_CODE)).thenReturn(PresetResolution.resolved(
                Map.of("person", new AnnotationToggle(true, true))));

        runner.resumeAsync(FILTER_KEY);

        // 범위는 오토라벨 묶음 — 앞선 단계(시계열 위탁·프레임 추출)를 다시 돌면 외부 위탁이 이중으로 나간다.
        verify(togglePolicy).togglesFor(BatchStageBundle.AUTOLABEL);
        verify(orchestrator).process(eq(RAW_SN), eq(autolabelToggles));
    }

    @Test
    @DisplayName("★프리셋이_아직_실효하지_않으면_돌리지_않는다")
    void doesNotResumeWhilePresetStillIneffective() {
        withheldCandidate(RAW_SN);
        stubRaw(RAW_SN, VIDEO_EV_CODE, LsDataRaw.DATA_STTS_MARKING_READY);
        when(presetLabelLookup.resolve(VIDEO_EV_CODE))
                .thenReturn(PresetResolution.of(PresetResolutionStatus.PRESET_UNMAPPED));

        runner.resumeAsync(FILTER_KEY);

        verify(orchestrator, never()).process(anyLong(), any());
    }

    @Test
    @DisplayName("★이미_완료된_영상은_다시_촉발해도_돌지_않는다_재촉발_멱등")
    void alreadyCompletedVideoIsNotReRun() {
        // 보류 기록은 append-only 라 지워지지 않는다 — 완료 여부가 재촉발의 멱등성을 담당한다.
        withheldCandidate(RAW_SN);
        stubRaw(RAW_SN, VIDEO_EV_CODE, LsDataRaw.DATA_STTS_COMPLETED);

        runner.resumeAsync(FILTER_KEY);

        verify(orchestrator, never()).process(anyLong(), any());
        verify(presetLabelLookup, never()).resolve(any());
    }

    @Test
    @DisplayName("★그룹_비대표코드_영상도_대표코드_프리셋_변경으로_재개된다")
    void groupMemberVideoIsResumedByRepresentativePreset() {
        withheldCandidate(RAW_SN);
        stubRaw(RAW_SN, VIDEO_EV_CODE, LsDataRaw.DATA_STTS_MARKING_READY);
        when(presetLabelLookup.resolve(VIDEO_EV_CODE)).thenReturn(PresetResolution.resolved(
                Map.of("person", new AnnotationToggle(true, true))));

        runner.resumeAsync(FILTER_KEY);

        verify(orchestrator).process(eq(RAW_SN), any());
    }

    @Test
    @DisplayName("다른_이벤트유형의_보류분은_이번_프리셋_변경으로_돌지_않는다")
    void otherEventTypeCandidateIsSkipped() {
        withheldCandidate(RAW_SN);
        stubRaw(RAW_SN, "EV09000101", LsDataRaw.DATA_STTS_MARKING_READY);

        runner.resumeAsync(FILTER_KEY);

        verify(orchestrator, never()).process(anyLong(), any());
    }

    @Test
    @DisplayName("보류_기록이_없으면_아무것도_조회하지_않는다")
    void noCandidateNoWork() {
        when(batchStatusService.stageSkippedRawSns(any(), any())).thenReturn(List.of());

        runner.resumeAsync(FILTER_KEY);

        verify(videoRepository, never()).findById(anyLong());
        verify(orchestrator, never()).process(anyLong(), any());
    }

    @Test
    @DisplayName("★재개_대상_사유는_탐지단계의_상수목록을_그대로_쓴다_재정의금지")
    void usesStepOwnedResumableReasons() {
        when(batchStatusService.stageSkippedRawSns(any(), any())).thenReturn(List.of());

        runner.resumeAsync(FILTER_KEY);

        verify(batchStatusService).stageSkippedRawSns(
                BatchStage.YOLO, YoloAutolabelStep.RESUMABLE_SKIP_REASONS);
    }

    @Test
    @DisplayName("★재개_실패는_삼킨다_프리셋_저장_트랜잭션에_영향을_주면_안_된다")
    void failureIsSwallowed() {
        withheldCandidate(RAW_SN);
        stubRaw(RAW_SN, VIDEO_EV_CODE, LsDataRaw.DATA_STTS_MARKING_READY);
        when(presetLabelLookup.resolve(VIDEO_EV_CODE)).thenReturn(PresetResolution.resolved(
                Map.of("person", new AnnotationToggle(true, true))));
        doThrow(new IllegalStateException("boom")).when(orchestrator).process(anyLong(), any());

        // 예외가 밖으로 새지 않는다.
        runner.resumeAsync(FILTER_KEY);

        assertThat(true).isTrue();
    }

    @Test
    @DisplayName("이벤트유형을_알_수_없으면_유형으로_좁히지_않고_후보마다_다시_판정한다")
    void blankEventTypeDoesNotNarrowCandidates() {
        withheldCandidate(RAW_SN);
        stubRaw(RAW_SN, VIDEO_EV_CODE, LsDataRaw.DATA_STTS_MARKING_READY);
        when(presetLabelLookup.resolve(VIDEO_EV_CODE)).thenReturn(PresetResolution.resolved(
                Map.of("person", new AnnotationToggle(true, true))));

        runner.resumeAsync(null);

        verify(eventTypeService, never()).codesForFilterKey(any());
        verify(orchestrator).process(eq(RAW_SN), any());
    }
}
