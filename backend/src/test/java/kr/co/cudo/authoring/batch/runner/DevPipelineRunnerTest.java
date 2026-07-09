package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.step.DeidentResult;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DevPipelineRunner 단위 테스트 (dev 업로드 단순화 — 운영 시나리오 1:1).
 *
 * <p>dev 업로드는 고정 플로우다: <b>비식별(무조건) → MARKING_READY 전이 → 정지</b>.
 * 합성 마킹 생성·{@code orchestrator.process} 직접 호출은 제거되었으며, 잔여 배치는
 * 사용자가 마킹 화면에서 마킹→완료할 때 {@code MarkingCompletedEvent → MarkingBatchBridge}
 * 경로로만 트리거된다.
 */
class DevPipelineRunnerTest {

    private DeidentifyStep deidentifyStep;
    private BatchTransitionService transitionService;
    private VideoRepository videoRepository;

    private DevPipelineRunner runner;

    private static final Long RAW_SN = 700L;

    @BeforeEach
    void setUp() {
        deidentifyStep = mock(DeidentifyStep.class);
        transitionService = mock(BatchTransitionService.class);
        videoRepository = mock(VideoRepository.class);
        runner = new DevPipelineRunner(deidentifyStep, transitionService, videoRepository);
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

    @Test
    @DisplayName("mock_모드_비식별_동기완료시_run직후_MARKING_READY로_전이")
    void mockSyncCompletedTransitionsMarkingReady() {
        LsDataRaw raw = rawWith(RAW_SN);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        // mock 동기 완료 — completed=true 반환.
        given(deidentifyStep.run(raw)).willReturn(DeidentResult.completed("/deid/videos/700/deidentified.mp4"));

        runner.runAsync(RAW_SN);

        verify(deidentifyStep).run(raw);
        verify(transitionService).markRawDataMarkingReady(RAW_SN);
    }

    @Test
    @DisplayName("KPST_모드_비식별_제출직후_MARKING_READY로_전이되지_않고_PENDING_유지")
    void kpstDeferredDoesNotTransitionMarkingReady() {
        LsDataRaw raw = rawWith(RAW_SN);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        // KPST 위탁(지연) — completed=false 반환. 제출만 했을 뿐 비식별 미완료(DE_IDNTF_YN='N').
        given(deidentifyStep.run(raw)).willReturn(DeidentResult.deferred());

        runner.runAsync(RAW_SN);

        verify(deidentifyStep).run(raw);
        // 조기 전이 차단: 제출 직후에는 MARKING_READY 로 전이하지 않는다(폴링 완료가 단일 지점에서 전이).
        verify(transitionService, never()).markRawDataMarkingReady(any());
    }

    @Test
    @DisplayName("raw_없으면_graceful_skip_비식별_미실행_MARKING_READY_미전이")
    void rawNotFoundGracefulSkip() {
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.empty());

        runner.runAsync(RAW_SN);

        verify(deidentifyStep, never()).run(any());
        verify(transitionService, never()).markRawDataMarkingReady(any());
    }

    @Test
    @DisplayName("deid_실패시_예외_삼킴_MARKING_READY_미전이_예외_미전파")
    void deidFailureSwallowed() {
        LsDataRaw raw = rawWith(RAW_SN);
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(raw));
        org.mockito.Mockito.doThrow(new RuntimeException("deid 5xx")).when(deidentifyStep).run(raw);

        // @Async 예외 삼킴 — 호출이 예외를 전파하지 않아야 한다.
        assertThatCode(() -> runner.runAsync(RAW_SN)).doesNotThrowAnyException();

        verify(deidentifyStep).run(raw);
        // deid 실패 시 MARKING_READY 전이는 진행하지 않는다 (외부 수동 재비식별 정책).
        verify(transitionService, never()).markRawDataMarkingReady(any());
    }
}
