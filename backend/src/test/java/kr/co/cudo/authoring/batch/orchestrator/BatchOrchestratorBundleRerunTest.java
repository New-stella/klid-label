package kr.co.cudo.authoring.batch.orchestrator;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.pipeline.BatchBundleTogglePolicy;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 작업 묶음 지목 재수행이 오케스트레이터까지 실제로 전달되는가. [@design API-201]
 *
 * <h3>이 파일이 지키는 것 둘</h3>
 * <ol>
 *   <li><b>시계열 재수행은 트랙 보간을 절대 돌리지 않는다.</b> 보간은 건너뛰는 조건이 없어 파이프라인을
 *       한 번 순회할 때마다 {@code lblSrcCd='INTERPOLATE'} 라벨을 사람이 고쳤는지 보지 않고 전량 삭제한
 *       뒤 재생성하며, 삭제 이력도 승인 스냅샷도 없어 복구 지점이 0 이다.</li>
 *   <li><b>재수행 실패가 영상 상태를 훼손하지 않는다.</b> 완주 영상이 FAILED 로 강등되면 자동 재시도가
 *       범위를 모른 채 전 단계를 돌려 위 파괴가 실패 경로로 되살아난다.</li>
 * </ol>
 *
 * <p>토글 산출은 프로덕션과 <b>같은 정책 빈</b>({@link BatchBundleTogglePolicy})으로 만들고, 파이프라인도
 * 같은 순서로 구성한다 — 토글 맵을 손으로 적으면 정책이 바뀌어도 이 테스트는 계속 통과한다.
 */
class BatchOrchestratorBundleRerunTest {

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

    private VlmTimeseriesStep vlmTimeseriesStep;
    private FfmpegFrameExtractor frameExtractor;
    private YoloAutolabelStep yoloStep;
    private Sam2SegmentStep sam2Step;
    private TrackInterpolationStep trackInterpolationStep;
    private BatchStatusService statusService;
    private BatchTransitionService transitionService;
    private BatchRetryQueue retryQueue;
    private VideoRepository videoRepository;
    private LsMarkingRepository markingRepository;
    private BatchOrchestrator orchestrator;
    private BatchBundleTogglePolicy togglePolicy;

    @BeforeEach
    void setUp() {
        vlmTimeseriesStep = mock(VlmTimeseriesStep.class);
        frameExtractor = mock(FfmpegFrameExtractor.class);
        yoloStep = mock(YoloAutolabelStep.class);
        sam2Step = mock(Sam2SegmentStep.class);
        trackInterpolationStep = mock(TrackInterpolationStep.class);
        statusService = mock(BatchStatusService.class);
        retryQueue = mock(BatchRetryQueue.class);
        videoRepository = mock(VideoRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        transitionService = mock(BatchTransitionService.class);

        BatchPipeline pipeline = PipelineTestSupport.pipeline(
                markingRepository, vlmTimeseriesStep, frameExtractor,
                yoloStep, sam2Step, trackInterpolationStep);
        orchestrator = new BatchOrchestrator(
                pipeline, statusService, transitionService, retryQueue, videoRepository);
        // 프로덕션과 같은 정책·같은 파이프라인으로 토글을 산출한다(맵을 손으로 적지 않는다).
        togglePolicy = new BatchBundleTogglePolicy(pipeline);

        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(any()))
                .thenReturn(List.of(newMarking()));
        when(frameExtractor.extractByMarks(any(LsDataRaw.class), any()))
                .thenReturn(List.of(mock(LsDataSrc.class)));
        when(statusService.isStageManuallySkipped(anyLong(), any())).thenReturn(false);
    }

    private void newRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "raw/path.mp4", null, 60);
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

    // ────────────────────────────────────────────────────────────────────────
    // ★ 묶음이 곧 범위다
    // ────────────────────────────────────────────────────────────────────────

    // ────────────────────────────────────────────────────────────────────────
    // ★ 검수 소유 작업 상태 보존 모드 — 승인 완료 영상의 시계열 재수행. [@design API-201]
    // ────────────────────────────────────────────────────────────────────────

    /**
     * ★★보존 모드는 <b>전용 진입 전이</b>를 타야 한다 — 일반 진입을 타면 검수 소유 상태에서 차단돼
     * step 을 한 건도 돌리지 않고 SKIPPED 로 끝난다(게이트 3겹째).
     */
    @Test
    @DisplayName("★★보존_모드는_검수소유_상태를_차단하지_않는_전용_진입을_탄다")
    void preservingModeUsesDedicatedEntry() {
        newRaw(340L);
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.VLM);
        when(transitionService.markRawDataProcessingWithHeldClaimPreservingReviewStatus(340L))
                .thenReturn(false);

        BatchStage result = orchestrator.processBundleRerun(
                340L, toggles, LsDataRaw.DATA_STTS_COMPLETED, true);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(transitionService).markRawDataProcessingWithHeldClaimPreservingReviewStatus(340L);
        verify(transitionService, never()).markRawDataProcessingBlockedWithHeldClaim(anyLong());
        // 시계열만 돌아야 한다 — 승인 영상에 라벨을 다시 만들면 안 된다.
        verify(vlmTimeseriesStep).runWithMarking(eq(340L), any());
        verify(yoloStep, never()).run(anyLong());
        verify(sam2Step, never()).run(anyLong(), any());
        verify(trackInterpolationStep, never()).run(anyLong());
    }

    /**
     * ★★마감도 전용 경로를 타야 한다 — 일반 마감은 검수 소유 상태에서 {@code LS_DATA_RAW} 를 건드리지
     * 않고 반환하므로, 완주해도 배치 단계가 {@code PROCESSING} 으로 <b>영구 고착</b>된다(이후 전 배치
     * 진입이 409). 즉 진입만 열면 결과가 거부보다 나쁘다.
     */
    @Test
    @DisplayName("★★보존_모드의_마감은_작업상태를_보존하는_전용_마감을_탄다_영구고착_차단")
    void preservingModeUsesDedicatedCompletion() {
        newRaw(341L);
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.VLM);
        when(transitionService.markRawDataProcessingWithHeldClaimPreservingReviewStatus(341L))
                .thenReturn(false);

        orchestrator.processBundleRerun(341L, toggles, LsDataRaw.DATA_STTS_COMPLETED, true);

        verify(transitionService).markRawDataCompletedPreservingReviewStatus(341L);
        verify(transitionService, never()).markRawDataCompleted(anyLong());
    }

    /**
     * ★★<b>런타임 fail-closed</b> — 보존 모드인데 실행 범위가 오토라벨을 포함하면 <b>돌리지 않는다</b>.
     *
     * <p>이 검사가 없으면 배선 실수 하나로 승인 완료 영상의 라벨이 재검수 없이 재생성된다. 검사는 진입
     * 전이 <b>전에</b> 이뤄져야 하며(클레임을 걸고 나서 거부하면 고착), SKIPPED 로 빠지면 호출자가 선점
     * 클레임을 보상 롤백한다.
     */
    @Test
    @DisplayName("★★보존_모드인데_오토라벨_범위면_아무_단계도_돌리지_않고_SKIPPED다")
    void preservingModeRefusesAutolabelScope() {
        newRaw(342L);
        Map<String, Boolean> autolabelToggles = togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL);

        BatchStage result = orchestrator.processBundleRerun(
                342L, autolabelToggles, LsDataRaw.DATA_STTS_COMPLETED, true);

        assertThat(result).isEqualTo(BatchStage.SKIPPED);
        // 진입 전이조차 하지 않는다 — 클레임을 걸고 나서 거부하면 배치 단계가 고착된다.
        verify(transitionService, never())
                .markRawDataProcessingWithHeldClaimPreservingReviewStatus(anyLong());
        verify(transitionService, never()).markRawDataProcessingBlockedWithHeldClaim(anyLong());
        verify(yoloStep, never()).run(anyLong());
        verify(sam2Step, never()).run(anyLong(), any());
        verify(trackInterpolationStep, never()).run(anyLong());
        verify(vlmTimeseriesStep, never()).runWithMarking(anyLong(), any());
    }

    /**
     * ★ 토글이 비어 있으면(=전 단계 실행) 보존 모드는 거부한다 — "아무 제한 없이 도는 실행" 에 승인
     * 게이트를 열어 주는 것이 가장 위험한 조합이다.
     */
    @Test
    @DisplayName("★보존_모드인데_토글이_비면_전단계_실행이므로_거부한다")
    void preservingModeRefusesEmptyToggles() {
        newRaw(343L);
        newRaw(344L);

        assertThat(orchestrator.processBundleRerun(343L, Map.of(), LsDataRaw.DATA_STTS_COMPLETED, true))
                .isEqualTo(BatchStage.SKIPPED);
        assertThat(orchestrator.processBundleRerun(344L, null, LsDataRaw.DATA_STTS_COMPLETED, true))
                .isEqualTo(BatchStage.SKIPPED);
    }

    /**
     * 대조군 — 보존 모드가 <b>아니면</b> 종전 진입·마감을 그대로 탄다(기본 동작 무변경).
     */
    @Test
    @DisplayName("보존_모드가_아니면_종전_진입과_마감을_그대로_탄다")
    void nonPreservingModeKeepsLegacyPath() {
        newRaw(345L);
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.VLM);
        when(transitionService.markRawDataProcessingBlockedWithHeldClaim(345L)).thenReturn(false);

        orchestrator.processBundleRerun(345L, toggles, LsDataRaw.DATA_STTS_COMPLETED, false);

        verify(transitionService).markRawDataProcessingBlockedWithHeldClaim(345L);
        verify(transitionService).markRawDataCompleted(345L);
        verify(transitionService, never())
                .markRawDataProcessingWithHeldClaimPreservingReviewStatus(anyLong());
        verify(transitionService, never()).markRawDataCompletedPreservingReviewStatus(anyLong());
    }

    @Test
    @DisplayName("★★시계열_묶음을_재수행하면_트랙_보간이_돌지_않는다_사람이_고친_보간라벨_보존")
    void vlmBundleNeverRunsInterpolation() {
        // 이 단정이 무너지면 시계열 재수행이 사람이 손댄 보간 라벨을 복구 지점 없이 전량 삭제한다.
        //   그것을 막는 것이 이 엔드포인트가 전체 재기동과 분리된 유일한 이유다.
        newRaw(301L);
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.VLM);

        BatchStage result = orchestrator.processBundleRerun(
                301L, toggles, LsDataRaw.DATA_STTS_COMPLETED);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(trackInterpolationStep, never()).run(anyLong());
        // 대상 묶음은 실제로 수행된다(아무것도 안 하는 재수행이 아니다).
        verify(vlmTimeseriesStep).runWithMarking(eq(301L), any());
        // 묶음 밖 단계는 하나도 돌지 않는다.
        verify(frameExtractor, never()).extractByMarks(any(LsDataRaw.class), any());
        verify(yoloStep, never()).run(anyLong());
        verify(sam2Step, never()).run(anyLong(), any());
    }

    @Test
    @DisplayName("★★오토라벨_묶음을_재수행하면_AI탐지_AI분할_보간이_한꺼번에_돈다_쪼개지_않는다")
    void autolabelBundleRunsAllThreeMembers() {
        // 보간 재계산은 「오토라벨을 통째로 다시 만든다」의 예상되는 결과다 — 서버가 몰래 막지 않는다.
        //   반대로 셋 중 하나라도 빠지면 산출물끼리 어긋난다(옛 분할·옛 보간 잔존).
        newRaw(302L);
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL);

        BatchStage result = orchestrator.processBundleRerun(
                302L, toggles, LsDataRaw.DATA_STTS_COMPLETED);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(yoloStep).run(302L);
        verify(sam2Step).run(eq(302L), any());
        verify(trackInterpolationStep).run(302L);
        // 다른 묶음·전제 단계는 돌지 않는다.
        verify(vlmTimeseriesStep, never()).runWithMarking(anyLong(), any());
        verify(frameExtractor, never()).extractByMarks(any(LsDataRaw.class), any());
    }

    @Test
    @DisplayName("★묶음_밖_단계는_markStage도_타지_않는다_진행축_비오염")
    void outOfBundleStagesAreNotMarked() {
        newRaw(303L);
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.VLM);

        orchestrator.processBundleRerun(303L, toggles, LsDataRaw.DATA_STTS_COMPLETED);

        verify(statusService).markStage(eq(303L), eq(BatchStage.VLM));
        verify(statusService, never()).markStage(eq(303L), eq(BatchStage.INTERPOLATE));
        verify(statusService, never()).markStage(eq(303L), eq(BatchStage.YOLO));
    }

    @Test
    @DisplayName("토글을_주지_않으면_종전대로_전_단계가_실행된다_동작보존")
    void nullTogglesKeepFullPipeline() {
        // API-167(전체 재기동)·자동 재시도·마킹 브리지가 쓰는 경로다. 묶음 축 도입이 이들을 바꾸면 안 된다.
        newRaw(305L);

        BatchStage result = orchestrator.processWithHeldStageClaim(305L);

        assertThat(result).isEqualTo(BatchStage.COMPLETED);
        verify(vlmTimeseriesStep).runWithMarking(eq(305L), any());
        verify(frameExtractor).extractByMarks(any(LsDataRaw.class), any());
        verify(yoloStep).run(305L);
        verify(sam2Step).run(eq(305L), any());
        verify(trackInterpolationStep).run(305L);
    }

    @Test
    @DisplayName("★재스킵된_묶음은_재수행_토글이_켜져_있어도_실행되지_않는다_사람의_결정_우선")
    void manualSkipStillWinsOverToggles() {
        // 사람의 결정(다시 스킵)이 재수행 토글에 뒤집히면 안 된다. 묶음 단위라 구성원이 통째로 빠진다.
        newRaw(306L);
        when(statusService.isStageManuallySkipped(eq(306L), eq(BatchStage.YOLO))).thenReturn(true);
        when(statusService.isStageManuallySkipped(eq(306L), eq(BatchStage.SAM2))).thenReturn(true);
        when(statusService.isStageManuallySkipped(eq(306L), eq(BatchStage.INTERPOLATE))).thenReturn(true);
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL);

        orchestrator.processBundleRerun(306L, toggles, LsDataRaw.DATA_STTS_COMPLETED);

        verify(yoloStep, never()).run(anyLong());
        verify(sam2Step, never()).run(anyLong(), any());
        verify(trackInterpolationStep, never()).run(anyLong());
    }

    // ────────────────────────────────────────────────────────────────────────
    // ★★ 재수행 실패는 영상 상태를 훼손하지 않는다
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★재수행_중_실패해도_완주상태로_원상복구되고_FAILED로_강등되지_않는다")
    void rerunFailureRestoresInsteadOfDemoting() {
        // 스킵의 존재 이유가 "기다려도 성공하지 않는 작업"이라 되돌려 재수행하면 실패가 기대값이다.
        //   그 실패로 완주 영상이 FAILED 가 되면 화면에 없던 실패가 생기고 작업 상태까지 FAILED 로
        //   내려가 작업자가 검수 제출을 못 한다.
        newRaw(310L);
        when(yoloStep.run(310L)).thenThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "AI 서버 오류"));
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL);

        BatchStage result = orchestrator.processBundleRerun(
                310L, toggles, LsDataRaw.DATA_STTS_COMPLETED);

        assertThat(result).isEqualTo(BatchStage.FAILED);
        verify(transitionService).restoreAfterBundleRerunFailure(310L, LsDataRaw.DATA_STTS_COMPLETED);
        verify(transitionService, never()).markRawDataFailed(anyLong());
        verify(transitionService, never()).markRawDataCompleted(anyLong());
    }

    @Test
    @DisplayName("★★재수행_실패는_자동_재시도_큐에_들어가지_않는다_범위를_모르는_재시도가_보간라벨을_지운다")
    void rerunFailureIsNeverEnqueuedForAutomaticRetry() {
        // 자동 재시도 큐는 범위를 모른다 — 큐에 들어가면 전 단계를 도는 파이프라인이 다시 실행돼
        //   사람이 손댄 보간 라벨이 삭제·재생성된다(이 기능이 막으려던 바로 그 파괴).
        newRaw(311L);
        when(yoloStep.run(311L)).thenThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "AI 서버 오류"));
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL);

        orchestrator.processBundleRerun(311L, toggles, LsDataRaw.DATA_STTS_COMPLETED);

        verify(retryQueue, never()).enqueueIfRetryable(anyLong());
    }

    @Test
    @DisplayName("★재수행_실패_사실_자체는_기록된다_비동기라_이_기록이_유일한_흔적이다")
    void rerunFailureIsStillAudited() {
        newRaw(312L);
        when(yoloStep.run(312L)).thenThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "AI 서버 오류"));
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL);

        orchestrator.processBundleRerun(312L, toggles, LsDataRaw.DATA_STTS_COMPLETED);

        verify(statusService).markFailed(eq(312L), any(Throwable.class));
    }

    @Test
    @DisplayName("★전체_재기동_경로의_실패_처리는_그대로다_강등과_자동_재시도_유지")
    void fullRestartFailureKeepsLegacyBehaviour() {
        // 전체 재기동은 대상이 실패 영상이라 강등이 맞고, 그 영상엔 사람이 만든 라벨이 없다.
        //   묶음 재수행의 완화가 이 경로로 새면 실패 영상이 PROCESSING 으로 고착된다.
        newRaw(313L);
        when(yoloStep.run(313L)).thenThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "AI 서버 오류"));

        BatchStage result = orchestrator.processWithHeldStageClaim(313L);

        assertThat(result).isEqualTo(BatchStage.FAILED);
        verify(transitionService).markRawDataFailed(313L);
        verify(retryQueue).enqueueIfRetryable(313L);
        verify(transitionService, never()).restoreAfterBundleRerunFailure(anyLong(), anyString());
    }

    // ────────────────────────────────────────────────────────────────────────
    // ★★ Error 갈래도 같은 복구 계약을 따른다 [@design API-201]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★재수행_중_Error가_나도_완주상태로_원상복구되고_FAILED로_강등되지_않는다")
    void rerunFatalErrorRestoresInsteadOfDemoting() {
        // 오토라벨 재수행은 프레임 이미지 인코딩을 수반해 OutOfMemoryError·NoClassDefFoundError 가
        //   현실적인 실패 유형이다. 여기서 FAILED 로 강등되면 전체 재기동(API-167) 경로가 열려
        //   토글 없는 파이프라인이 사람이 손댄 보간 라벨을 전량 삭제·재생성한다.
        newRaw(320L);
        doThrow(new SimulatedFatalError("fatal in autolabel")).when(yoloStep).run(320L);
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL);

        assertThatThrownBy(() -> orchestrator.processBundleRerun(
                320L, toggles, LsDataRaw.DATA_STTS_COMPLETED))
                .isInstanceOf(SimulatedFatalError.class)
                .hasMessage("fatal in autolabel");

        verify(transitionService).restoreAfterBundleRerunFailure(320L, LsDataRaw.DATA_STTS_COMPLETED);
        verify(transitionService, never()).markRawDataFailed(anyLong());
        verify(transitionService, never()).markRawDataCompleted(anyLong());
        // Error 는 "재시도로 넘길 실패"가 아니다 — 부기·자동 재시도 큐는 두 갈래 모두 타지 않는다.
        verify(statusService, never()).markFailed(anyLong(), any(Throwable.class));
        verify(retryQueue, never()).enqueueIfRetryable(anyLong());
    }

    @Test
    @DisplayName("★Error_경로의_원상복구가_실패해도_원인_Error가_덮이지_않고_전파된다")
    void rerunFatalErrorRestoreFailureDoesNotMaskCause() {
        // 복구는 DB 를 건드리므로 그 자체가 다시 터질 수 있다. 그 예외가 원인 Error 를 가리면
        //   운영자가 보는 것은 "DB 오류"뿐이고 진짜 원인(OOM 등)이 사라진다.
        newRaw(321L);
        doThrow(new SimulatedFatalError("fatal in autolabel")).when(yoloStep).run(321L);
        doThrow(new IllegalStateException("db unavailable"))
                .when(transitionService)
                .restoreAfterBundleRerunFailure(321L, LsDataRaw.DATA_STTS_COMPLETED);
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL);

        assertThatThrownBy(() -> orchestrator.processBundleRerun(
                321L, toggles, LsDataRaw.DATA_STTS_COMPLETED))
                .isInstanceOf(SimulatedFatalError.class)
                .hasMessage("fatal in autolabel");

        verify(transitionService).restoreAfterBundleRerunFailure(321L, LsDataRaw.DATA_STTS_COMPLETED);
    }

    @Test
    @DisplayName("★전체_재기동_경로의_Error_처리는_그대로다_FAILED_해제_유지")
    void fullRestartFatalErrorKeepsLegacyRelease() {
        // 두 갈래의 차이를 고정한다 — 묶음 재수행의 완화가 전체 재기동으로 새면 실패 영상의 클레임이
        //   PROCESSING 으로 고착돼 이후 모든 진입이 409 가 된다.
        newRaw(322L);
        doThrow(new SimulatedFatalError("fatal in autolabel")).when(yoloStep).run(322L);

        assertThatThrownBy(() -> orchestrator.processWithHeldStageClaim(322L))
                .isInstanceOf(SimulatedFatalError.class);

        verify(transitionService).markRawDataFailed(322L);
        verify(transitionService, never()).restoreAfterBundleRerunFailure(anyLong(), anyString());
    }

    @Test
    @DisplayName("재수행이_진입가드에_막히면_단계를_한_건도_돌리지_않고_SKIPPED_다")
    void rerunBlockedByEntryGuard() {
        newRaw(314L);
        when(transitionService.markRawDataProcessingBlockedWithHeldClaim(314L)).thenReturn(true);
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL);

        BatchStage result = orchestrator.processBundleRerun(
                314L, toggles, LsDataRaw.DATA_STTS_COMPLETED);

        assertThat(result).isEqualTo(BatchStage.SKIPPED);
        verify(yoloStep, never()).run(anyLong());
        // 보상 롤백은 러너가 담당한다 — 오케스트레이터가 여기서 상태를 건드리면 이중 복구가 된다.
        verify(transitionService, never()).restoreAfterBundleRerunFailure(anyLong(), anyString());
    }
}
