package kr.co.cudo.authoring.batch.orchestrator;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.VlmObjectVerifyStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 BatchOrchestrator 단위 테스트.
 * - 모든 Step / Repository 는 Mockito mock.
 * - 비즈니스 규칙(needsDeidentify, autoLblYn, retry) 검증 위주.
 */
class BatchOrchestratorTest {

    private FfmpegFrameExtractor frameExtractor;
    private DeidentifyStep deidentifyStep;
    private YoloAutolabelStep yoloStep;
    private Sam2SegmentStep sam2Step;
    private VlmObjectVerifyStep vlmStep;
    private BatchStatusService statusService;
    private BatchRetryQueue retryQueue;
    private VideoRepository videoRepository;
    private BatchOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        frameExtractor = mock(FfmpegFrameExtractor.class);
        deidentifyStep = mock(DeidentifyStep.class);
        yoloStep = mock(YoloAutolabelStep.class);
        sam2Step = mock(Sam2SegmentStep.class);
        vlmStep = mock(VlmObjectVerifyStep.class);
        statusService = mock(BatchStatusService.class);
        retryQueue = new BatchRetryQueue(3, 60);
        videoRepository = mock(VideoRepository.class);

        orchestrator = new BatchOrchestrator(
                frameExtractor, deidentifyStep, yoloStep, sam2Step, vlmStep,
                statusService, retryQueue, videoRepository);

        // 기본 — 프레임 1개 추출, ai-server step 모두 정상.
        when(frameExtractor.extract(any(LsDataRaw.class)))
                .thenReturn(List.of(mock(LsDataSrc.class)));
    }

    private LsDataRaw newRaw(Long rawSn, String prvcTypeCd) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                prvcTypeCd, "raw/path.mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        return raw;
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

    @Test
    @DisplayName("ANONY_영상은_DeidentifyStep_skip")
    void anonySkipsDeidentifyStep() {
        newRaw(101L, LsDataRaw.PRVC_TYPE_ANONY);

        BatchStage result = orchestrator.process(101L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(deidentifyStep, never()).run(any());
        verify(yoloStep).run(101L);
        verify(sam2Step).run(101L);
        verify(vlmStep).run(101L);
    }

    @Test
    @DisplayName("PRVC_영상은_DeidentifyStep_호출")
    void prvcInvokesDeidentifyStep() {
        newRaw(102L, LsDataRaw.PRVC_TYPE_PRVC);

        BatchStage result = orchestrator.process(102L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(deidentifyStep, times(1)).run(any(LsDataRaw.class));
    }

    @Test
    @DisplayName("PSDO_영상도_DeidentifyStep_호출")
    void psdoInvokesDeidentifyStep() {
        newRaw(103L, LsDataRaw.PRVC_TYPE_PSDO);

        BatchStage result = orchestrator.process(103L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(deidentifyStep, times(1)).run(any(LsDataRaw.class));
    }

    @Test
    @DisplayName("비식별_API_500_응답시_FAILED_+_재시도큐_등록")
    void deidentifyFailureMovesToFailedAndRetry() {
        newRaw(104L, LsDataRaw.PRVC_TYPE_PRVC);
        doThrow(new RuntimeException("deid 5xx")).when(deidentifyStep).run(any(LsDataRaw.class));

        BatchStage result = orchestrator.process(104L);

        assertThat(result).isEqualTo(BatchStage.FAILED);
        assertThat(retryQueue.retryCount(104L)).isEqualTo(1);
        // 비식별 이후 단계는 호출되지 않아야 함 (단계 격리).
        verify(yoloStep, never()).run(any());
        verify(sam2Step, never()).run(any());
        verify(vlmStep, never()).run(any());
        // statusService 가 FAILED 로 마킹되었어야 함.
        verify(statusService).markFailed(eq(104L), any(RuntimeException.class));
    }

    @Test
    @DisplayName("YOLO_단계_정상_처리시_COMPLETED_상태_전이")
    void yoloSuccessReachesCompleted() {
        newRaw(105L, LsDataRaw.PRVC_TYPE_ANONY);
        when(yoloStep.run(eq(105L))).thenReturn(7);

        BatchStage result = orchestrator.process(105L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(statusService).markCompleted(105L);
    }

    @Test
    @DisplayName("VLM_단계_실패시_이전_단계는_커밋되고_FAILED_고정")
    void vlmFailureKeepsPriorStagesAndMarksFailed() {
        newRaw(106L, LsDataRaw.PRVC_TYPE_ANONY);
        doThrow(new RuntimeException("vlm down")).when(vlmStep).run(any());

        BatchStage result = orchestrator.process(106L);

        assertThat(result).isEqualTo(BatchStage.FAILED);
        // 이전 단계는 호출 완료
        verify(yoloStep).run(106L);
        verify(sam2Step).run(106L);
        verify(vlmStep).run(106L);
        // 상태는 FAILED 로 마킹
        verify(statusService).markFailed(eq(106L), any(RuntimeException.class));
    }

    @Test
    @DisplayName("최대_3회_재시도_후_FAILED_상태_고정_큐_재등록_거부")
    void maxRetryReachedRefusesEnqueue() {
        newRaw(107L, LsDataRaw.PRVC_TYPE_ANONY);
        doThrow(new RuntimeException("yolo down")).when(yoloStep).run(any());

        // 4회 실패 시도 — 1·2·3회는 재시도 등록 성공, 4회는 enqueueIfRetryable=false
        for (int i = 0; i < 4; i++) {
            orchestrator.process(107L);
        }

        // retryCount 는 1·2·3 까지 증가 후, 4회째는 maxAttempts(3) 초과로 false
        assertThat(retryQueue.retryCount(107L)).isEqualTo(4);
        // BatchStatusService.markFailed 가 4회 호출되었어야 함.
        verify(statusService, times(4)).markFailed(eq(107L), any(RuntimeException.class));
    }

    @Test
    @DisplayName("rawSn_null이면_INVALID_INPUT")
    void nullRawSnRejected() {
        try {
            orchestrator.process(null);
        } catch (CustomException e) {
            assertThat(e.getErrorCode().name()).isEqualTo("INVALID_INPUT");
            return;
        }
        org.assertj.core.api.Assertions.fail("CustomException 이 발생해야 합니다.");
    }

    @Test
    @DisplayName("프레임_추출_결과_0건이면_FAILED_+_이후_단계_미호출")
    void emptyFramesMovesToFailed() {
        newRaw(108L, LsDataRaw.PRVC_TYPE_ANONY);
        when(frameExtractor.extract(any(LsDataRaw.class))).thenReturn(List.of());

        BatchStage result = orchestrator.process(108L);

        assertThat(result).isEqualTo(BatchStage.FAILED);
        verify(deidentifyStep, never()).run(any());
        verify(yoloStep, never()).run(any());
    }

    @Test
    @DisplayName("성공_시_재시도큐_clear_호출")
    void successClearsRetryQueue() {
        newRaw(109L, LsDataRaw.PRVC_TYPE_ANONY);
        // 한번 실패해서 retry 등록 후 재시도 성공 시나리오.
        doThrow(new RuntimeException("transient")).when(yoloStep).run(any());
        orchestrator.process(109L);
        assertThat(retryQueue.retryCount(109L)).isEqualTo(1);

        // 이번엔 정상 동작
        org.mockito.Mockito.reset(yoloStep);
        when(yoloStep.run(any())).thenReturn(3);

        ArgumentCaptor<LsDataRaw> captor = ArgumentCaptor.forClass(LsDataRaw.class);
        BatchStage result = orchestrator.process(109L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        assertThat(retryQueue.retryCount(109L)).isEqualTo(0);
        verify(frameExtractor, times(2)).extract(captor.capture());
    }
}
