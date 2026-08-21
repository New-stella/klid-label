package kr.co.cudo.authoring.batch.orchestrator;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import kr.co.cudo.authoring.batch.status.VlmDefaultSkipMarker;

/**
 * {@link BatchOrchestrator} <b>클레임 해제 경로</b> 회귀 가드 (B-ISSUE-01 보강).
 *
 * <p>진입 가드가 {@code LS_DATA_RAW.DATA_STTS_CD} 를 원자 클레임하게 되면서, <b>해제되지 않은 클레임은
 * 곧 영구 고착</b>이 됐다 — PROCESSING 이 남으면 이후 모든 진입(마킹 브리지·Quartz 큐·자동 재시도 잡·
 * 수동 재처리)이 클레임에 막힌다. 따라서 "어떤 종료 경로로 빠져나가든 해제된다"가 이 수정의 안전 조건이며,
 * 아래 두 경로는 정상 종료/RuntimeException 만 보던 기존 테스트가 <b>한 번도 실행하지 않던</b> 구간이다.
 *
 * <ul>
 *   <li><b>{@code Error} 전파 경로</b> — 해제만 시도하고 원인 {@code Error} 는 삼키지 않고 되던진다.
 *       해제 자체가 실패해도 원인 예외를 덮지 않는다.</li>
 *   <li><b>해제 → 부기 순서</b> — 실패 시 클레임 해제({@code markRawDataFailed})가 부기 기록
 *       ({@code statusService.markFailed})보다 <b>먼저</b> 실행된다. 부기가 실패해도 이미 수행된 해제는
 *       되돌아가지 않는다(순서를 뒤집으면 부기 장애가 클레임 고착으로 번진다).</li>
 * </ul>
 *
 * <p>실 DB 에서의 해제 결과(PROCESSING → FAILED, 후속 재진입 가능)는
 * {@code BatchOrchestratorConcurrentEntryIT} 가 별도로 고정한다.
 */
class BatchOrchestratorClaimReleaseTest {

    /**
     * 테스트 전용 {@code Error} 서브클래스.
     *
     * <p>{@code OutOfMemoryError}/{@code StackOverflowError} 를 실제로 유발하면 JVM 상태가 오염되고,
     * {@code AssertionError} 는 단언 프레임워크가 던지는 것과 구분되지 않는다. 안전하게 던질 수 있는
     * 전용 타입으로 "치명적 오류" 만 흉내 낸다.
     */
    private static final class SimulatedFatalError extends Error {
        SimulatedFatalError(String message) {
            super(message);
        }
    }

    private YoloAutolabelStep yoloStep;
    private BatchStatusService statusService;
    private BatchTransitionService transitionService;
    private BatchRetryQueue retryQueue;
    private VideoRepository videoRepository;
    private BatchOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        VlmTimeseriesStep vlmTimeseriesStep = mock(VlmTimeseriesStep.class);
        FfmpegFrameExtractor frameExtractor = mock(FfmpegFrameExtractor.class);
        yoloStep = mock(YoloAutolabelStep.class);
        Sam2SegmentStep sam2Step = mock(Sam2SegmentStep.class);
        TrackInterpolationStep trackInterpolationStep = mock(TrackInterpolationStep.class);
        statusService = mock(BatchStatusService.class);
        transitionService = mock(BatchTransitionService.class);
        retryQueue = mock(BatchRetryQueue.class);
        videoRepository = mock(VideoRepository.class);
        LsMarkingRepository markingRepository = mock(LsMarkingRepository.class);

        BatchPipeline pipeline = PipelineTestSupport.pipeline(
                markingRepository, vlmTimeseriesStep, frameExtractor,
                yoloStep, sam2Step, trackInterpolationStep);

        orchestrator = new BatchOrchestrator(
                pipeline, statusService, transitionService, retryQueue, videoRepository,
                mock(VlmDefaultSkipMarker.class));

        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(any()))
                .thenReturn(List.of(newMarking()));
        when(frameExtractor.extractByMarks(any(LsDataRaw.class), any()))
                .thenReturn(List.of(mock(LsDataSrc.class)));
    }

    private void newRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "raw/path.mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
    }

    private LsMarking newMarking() {
        return LsMarking.createAuto(1L, "fire", 5,
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

    // ── Error 전파 경로 (HIGH — 무증거였던 catch(Error) 블록) ──

    @Test
    @DisplayName("배치_스텝_실행중_Error_발생시에도_클레임이_해제되고_Error가_그대로_전파된다")
    void stepThrowsError_releasesClaim_andRethrows() {
        // given — 스텝이 Error(치명적 오류 계열)를 던진다
        newRaw(701L);
        doThrow(new SimulatedFatalError("fatal in step")).when(yoloStep).run(any());

        // when / then — ① 삼키지 않고 그대로 전파한다(FAILED 로 위장 종료하면 치명적 오류가 은폐된다)
        assertThatThrownBy(() -> orchestrator.process(701L))
                .isInstanceOf(SimulatedFatalError.class)
                .hasMessage("fatal in step");

        // ② 클레임은 해제된다 — 해제하지 않으면 stage 가 PROCESSING 으로 고착돼 이후 모든 진입이 막힌다
        verify(transitionService).markRawDataFailed(701L);
        verify(transitionService, never()).markRawDataCompleted(any());
        // ③ Error 는 "재시도로 넘길 실패"가 아니다 — 부기/재시도 큐 경로는 타지 않는다
        verify(statusService, never()).markFailed(any(), any());
        verify(retryQueue, never()).enqueueIfRetryable(any());
    }

    @Test
    @DisplayName("Error_경로에서_클레임_해제가_실패해도_원인_Error가_덮이지_않고_전파된다")
    void stepThrowsError_releaseFailure_doesNotMaskCause() {
        // given — 스텝이 Error 를 던지고, 해제 시도(DB)마저 실패한다
        newRaw(702L);
        doThrow(new SimulatedFatalError("fatal in step")).when(yoloStep).run(any());
        doThrow(new IllegalStateException("db unavailable"))
                .when(transitionService).markRawDataFailed(702L);

        // when / then — 해제 실패는 로깅만 하고, 호출자에게는 원인 Error 가 그대로 도달해야 한다
        assertThatThrownBy(() -> orchestrator.process(702L))
                .isInstanceOf(SimulatedFatalError.class)
                .hasMessage("fatal in step");
        verify(transitionService).markRawDataFailed(702L);
    }

    // ── 해제 → 부기 순서 (HIGH — 순서 자체가 무검증이었다) ──

    @Test
    @DisplayName("실패시_클레임_해제가_부기_기록보다_먼저_실행된다")
    void failure_releasesClaimBeforeBookkeeping() {
        // given
        newRaw(703L);
        doThrow(new RuntimeException("yolo failure")).when(yoloStep).run(any());

        // when
        BatchStage stage = orchestrator.process(703L);

        // then — 순서를 뒤집으면 부기(LS_BATCH_PROC_LOG) 장애가 클레임 고착으로 번진다.
        //        해제가 먼저여야 부기 실패와 무관하게 재진입 경로가 열려 있다.
        InOrder order = inOrder(transitionService, statusService);
        order.verify(transitionService).markRawDataFailed(703L);
        order.verify(statusService).markFailed(eq(703L), any(RuntimeException.class));
        assertThat(stage).isEqualTo(BatchStage.FAILED);
    }

    @Test
    @DisplayName("부기_기록이_실패해도_클레임_해제는_이미_수행되어_유지된다")
    void bookkeepingFailure_doesNotRevertRelease() {
        // given — 해제는 성공하지만 부기 기록이 실패한다(DB 장애/제약 위반 등)
        newRaw(704L);
        doThrow(new RuntimeException("yolo failure")).when(yoloStep).run(any());
        doThrow(new IllegalStateException("proc-log write failed"))
                .when(statusService).markFailed(eq(704L), any());

        // when / then — 부기 예외는 현재 구현상 호출자로 전파된다(삼키지 않는다).
        //   중요한 것은 그것이 <b>이미 커밋된 해제를 되돌리지 않는다</b>는 점이다 —
        //   markRawDataFailed 는 REQUIRES_NEW 로 독립 커밋되므로 부기 실패와 운명을 공유하지 않는다.
        assertThatThrownBy(() -> orchestrator.process(704L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("proc-log write failed");

        verify(transitionService, times(1)).markRawDataFailed(704L);
        // 되돌리기 시도가 없어야 한다 — 완료 전이도, 클레임 재취득(진입 전이 재호출)도 하지 않는다.
        // (진입 전이는 process 시작 시 1회만 호출된 그대로여야 한다.)
        verify(transitionService, never()).markRawDataCompleted(any());
        verify(transitionService, times(1)).markRawDataProcessingBlocked(704L);
    }
}
