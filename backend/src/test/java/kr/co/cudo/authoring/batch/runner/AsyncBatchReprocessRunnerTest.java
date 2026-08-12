package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 수동 재기동 비동기 실행기 단위 테스트. [@design API-167]
 *
 * <p>핵심 수용 기준은 <b>PROCESSING 고착을 남기지 않는다</b>이다. 동기 시절에는 예외가 요청 스레드로
 * 올라와 처리됐지만 비동기에서는 아무도 받지 않으므로, 파이프라인이 자체 마감을 타지 못한 모든 이탈에서
 * 선점 클레임이 되돌아가야 한다(되돌아가지 않으면 그 영상은 이후 모든 재기동이 409 = 복구 불가).
 */
class AsyncBatchReprocessRunnerTest {

    private BatchOrchestrator orchestrator;
    private BatchTransitionService transitionService;
    private AsyncBatchReprocessRunner runner;

    @BeforeEach
    void setUp() {
        orchestrator = mock(BatchOrchestrator.class);
        transitionService = mock(BatchTransitionService.class);
        runner = new AsyncBatchReprocessRunner(orchestrator, transitionService);
    }

    @Test
    @DisplayName("클레임을_인계받아_전용_진입으로_파이프라인을_실행한다")
    void runsWithHeldStageClaimEntry() {
        // B-ISSUE-01 — 일반 진입(process)을 쓰면 진입 가드가 자기가 찍은 PROCESSING 에 막혀 전부 SKIPPED 다.
        when(orchestrator.processWithHeldStageClaim(eq(3L))).thenReturn(BatchStage.COMPLETED);

        runner.runAsync(3L, LsDataRaw.DATA_STTS_FAILED);

        verify(orchestrator).processWithHeldStageClaim(eq(3L));
        verify(orchestrator, never()).process(anyLong());
        verify(transitionService, never()).releaseReprocessClaim(anyLong(), anyString());
    }

    @Test
    @DisplayName("★SKIPPED면_클레임을_보상롤백해_PROCESSING_고착을_남기지_않는다")
    void compensatesClaimWhenSkipped() {
        // DEV_FIX H10 — 진입 가드가 막으면 step 0건 + 마감 전이 미실행이라 선점한 PROCESSING 을
        //   되돌릴 코드가 없다. 동기 시절 서비스가 하던 보상을 러너가 그대로 이어받는다.
        when(orchestrator.processWithHeldStageClaim(eq(7L))).thenReturn(BatchStage.SKIPPED);

        runner.runAsync(7L, LsDataRaw.DATA_STTS_FAILED);

        verify(transitionService).releaseReprocessClaim(7L, LsDataRaw.DATA_STTS_FAILED);
    }

    @Test
    @DisplayName("파이프라인_FAILED_종료는_보상롤백_대상이_아니다_오케스트레이터가_이미_마감한다")
    void doesNotCompensateWhenPipelineFailed() {
        // 과잉 보상 방지 — FAILED 는 오케스트레이터가 markRawDataFailed 로 이미 마감한 정상 종료다.
        when(orchestrator.processWithHeldStageClaim(eq(9L))).thenReturn(BatchStage.FAILED);

        runner.runAsync(9L, LsDataRaw.DATA_STTS_FAILED);

        verify(transitionService, never()).releaseReprocessClaim(anyLong(), anyString());
    }

    @Test
    @DisplayName("★예외로_이탈해도_클레임을_되돌리고_호출자에게_예외를_던지지_않는다")
    void compensatesAndSwallowsUnexpectedException() {
        // 오케스트레이터의 try 블록 이전(진입 조회 NOT_FOUND 등)에서 터지면 자체 마감을 타지 못한다.
        //   @Async 라 예외를 받아 줄 호출자도 없으므로 여기서 클레임을 되돌려야 한다.
        when(orchestrator.processWithHeldStageClaim(eq(13L)))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다 rawSn=13"));

        assertThatCode(() -> runner.runAsync(13L, LsDataRaw.DATA_STTS_FAILED)).doesNotThrowAnyException();

        verify(transitionService).releaseReprocessClaim(13L, LsDataRaw.DATA_STTS_FAILED);
    }

    @Test
    @DisplayName("★Error로_이탈하면_클레임을_되돌리되_삼키지_않고_그대로_전파한다")
    void compensatesButRethrowsFatalError() {
        // 오케스트레이터의 try 블록 이전(진입 조회·진입 가드)에서 Error 가 나면 자체 복구를 타지 못해
        //   선점 클레임이 그대로 남는다. 그렇다고 여기서 삼켜 정상 반환하면 OOM 같은 치명적 오류가
        //   "조용히 끝난 재기동"으로 위장된다 — 보상만 하고 되던진다.
        when(orchestrator.processWithHeldStageClaim(eq(17L)))
                .thenThrow(new SimulatedFatalError("fatal before pipeline"));

        assertThatThrownBy(() -> runner.runAsync(17L, LsDataRaw.DATA_STTS_FAILED))
                .isInstanceOf(SimulatedFatalError.class)
                .hasMessage("fatal before pipeline");

        verify(transitionService).releaseReprocessClaim(17L, LsDataRaw.DATA_STTS_FAILED);
    }

    @Test
    @DisplayName("★묶음_재수행이_Error로_이탈해도_보상은_선점_직전_상태로_되돌린다_완주가_실패로_뒤집히지_않는다")
    void bundleRerunFatalErrorCompensatesToOriginStatus() {
        // 보상 목표값이 FAILED 로 굳어 있으면 아무것도 실패하지 않은 완주 영상이 화면에서 실패로 보인다.
        //   (오케스트레이터가 이미 되돌렸으면 이 보상은 조건부 UPDATE 0행 → no-op 이다.)
        when(orchestrator.processBundleRerun(eq(19L), any(), eq(LsDataRaw.DATA_STTS_COMPLETED)))
                .thenThrow(new SimulatedFatalError("fatal in bundle rerun"));

        assertThatThrownBy(() -> runner.runBundleRerunAsync(
                19L, LsDataRaw.DATA_STTS_COMPLETED, Map.of(BatchStage.YOLO.name(), true)))
                .isInstanceOf(SimulatedFatalError.class);

        verify(transitionService).releaseReprocessClaim(19L, LsDataRaw.DATA_STTS_COMPLETED);
        verify(transitionService, never()).releaseReprocessClaim(anyLong(), eq(LsDataRaw.DATA_STTS_FAILED));
    }

    @Test
    @DisplayName("Error_경로에서_보상이_실패해도_원인_Error가_덮이지_않고_전파된다")
    void compensationFailureDoesNotMaskFatalError() {
        when(orchestrator.processWithHeldStageClaim(eq(23L)))
                .thenThrow(new SimulatedFatalError("fatal before pipeline"));
        when(transitionService.releaseReprocessClaim(eq(23L), anyString()))
                .thenThrow(new IllegalStateException("db unavailable"));

        assertThatThrownBy(() -> runner.runAsync(23L, LsDataRaw.DATA_STTS_FAILED))
                .isInstanceOf(SimulatedFatalError.class)
                .hasMessage("fatal before pipeline");
    }

    /**
     * 테스트 전용 {@code Error} 서브클래스 — 실제 OOM/StackOverflow 는 JVM 상태를 오염시키고
     * {@code AssertionError} 는 단언 프레임워크의 것과 구분되지 않는다.
     */
    private static final class SimulatedFatalError extends Error {
        SimulatedFatalError(String message) {
            super(message);
        }
    }
}
