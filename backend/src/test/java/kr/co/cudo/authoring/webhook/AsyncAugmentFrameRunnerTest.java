package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import kr.co.cudo.authoring.webhook.runner.AsyncAugmentFrameRunner;
import kr.co.cudo.authoring.webhook.service.AugmentExtractPersist;
import kr.co.cudo.authoring.webhook.service.AugmentExtractPlan;
import kr.co.cudo.authoring.webhook.service.AugmentExtractSnapshot;
import kr.co.cudo.authoring.webhook.service.AugmentFrameProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 증강 프레임 재추출 비동기 오케스트레이터 단위 테스트 — 커넥션-점유 분리 리팩터(A/B/C 별도 빈).
 *
 * <p>러너가 Phase A(스냅샷)→B(파일)→C(영속)를 순차 호출하고, 실패 시 Phase B 아티팩트 cleanup(+잔존 시
 * 메트릭) + FAILED 전이를 수행하는지 검증한다. 부모 재검증 게이트가 없는 순수 커넥션 분리 구조임을 반영한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncAugmentFrameRunnerTest {

    @Mock AugmentExtractSnapshot snapshotService;
    @Mock AugmentFrameProducer frameProducer;
    @Mock AugmentExtractPersist persistService;
    @Mock BatchTransitionService batchTransitionService;
    @Mock AugmentMetrics augmentMetrics;

    AsyncAugmentFrameRunner runner;

    @BeforeEach
    void setup() {
        runner = new AsyncAugmentFrameRunner(snapshotService, frameProducer, persistService,
                batchTransitionService, augmentMetrics);
        when(frameProducer.cleanup(any())).thenReturn(true);
    }

    private AugmentExtractPlan plan(long newRawSn, long parentRawSn, long dataAugSn) {
        return new AugmentExtractPlan(newRawSn, parentRawSn, dataAugSn, "rev1",
                Paths.get("/base/frames/deid/" + parentRawSn + "/frame-0.jpg"),
                Paths.get("/base/frames/deid/" + newRawSn), List.of());
    }

    @Test
    @DisplayName("정상확정시_A_B_C를_순차호출하고_FAILED전이나_cleanup을_하지않는다")
    void successRunsAllPhasesWithoutFailureHandling() {
        AugmentExtractPlan p = plan(9001L, 100L, 20L);
        when(snapshotService.snapshot(9001L, 20L)).thenReturn(Optional.of(p));
        when(persistService.persist(p)).thenReturn(AugmentExtractPersist.Result.PERSISTED);

        runner.runAsync(9001L, 20L);

        verify(frameProducer).produce(p);
        verify(persistService).persist(p);
        verify(batchTransitionService, never()).markRawDataFailed(any());
        verify(frameProducer, never()).cleanup(any());
    }

    @Test
    @DisplayName("PhaseA가_멱등skip이면_B_C_cleanup_전이_모두_수행하지않는다")
    void snapshotEmptySkipsEverything() {
        when(snapshotService.snapshot(9002L, 21L)).thenReturn(Optional.empty());

        runner.runAsync(9002L, 21L);

        verify(frameProducer, never()).produce(any());
        verify(persistService, never()).persist(any());
        verify(frameProducer, never()).cleanup(any());
        verify(batchTransitionService, never()).markRawDataFailed(any());
    }

    @Test
    @DisplayName("PhaseC가_SKIPPED_중복트리거패자면_cleanup도_FAILED전이도_하지않는다")
    void persistSkippedDoesNotCleanupWinnerArtifacts() {
        AugmentExtractPlan p = plan(9003L, 100L, 22L);
        when(snapshotService.snapshot(9003L, 22L)).thenReturn(Optional.of(p));
        when(persistService.persist(p)).thenReturn(AugmentExtractPersist.Result.SKIPPED);

        runner.runAsync(9003L, 22L);

        // 파일은 승자와 동일 경로(같은 rawSn) — 정리하면 승자 산출물을 지우므로 절대 cleanup/FAILED 안 함.
        verify(frameProducer, never()).cleanup(any());
        verify(batchTransitionService, never()).markRawDataFailed(any());
    }

    @Test
    @DisplayName("PhaseB_실패시_아티팩트정리후_FAILED전이한다")
    void produceFailureCleansUpAndFails() {
        AugmentExtractPlan p = plan(9004L, 100L, 23L);
        when(snapshotService.snapshot(9004L, 23L)).thenReturn(Optional.of(p));
        doThrow(new CustomException(ErrorCode.INVALID_INPUT, "증강 영상을 찾을 수 없습니다"))
                .when(frameProducer).produce(p);

        runner.runAsync(9004L, 23L);

        verify(frameProducer).cleanup(9004L);
        verify(batchTransitionService).markRawDataFailed(9004L);
    }

    @Test
    @DisplayName("PhaseC_실패시_복사된_아티팩트정리후_FAILED전이한다")
    void persistFailureCleansUpAndFails() {
        AugmentExtractPlan p = plan(9005L, 100L, 24L);
        when(snapshotService.snapshot(9005L, 24L)).thenReturn(Optional.of(p));
        doThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "persist failed"))
                .when(persistService).persist(p);

        runner.runAsync(9005L, 24L);

        verify(frameProducer).cleanup(9005L);
        verify(batchTransitionService).markRawDataFailed(9005L);
    }

    @Test
    @DisplayName("cleanup이_잔존false를_반환하면_cleanupFailed_메트릭을_올리고_FAILED전이는_진행한다")
    void cleanupResidualEmitsMetric() {
        AugmentExtractPlan p = plan(9006L, 100L, 25L);
        when(snapshotService.snapshot(9006L, 25L)).thenReturn(Optional.of(p));
        doThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "extract failed"))
                .when(frameProducer).produce(p);
        when(frameProducer.cleanup(9006L)).thenReturn(false); // 삭제 후에도 잔존

        runner.runAsync(9006L, 25L);

        verify(augmentMetrics).cleanupFailed();
        verify(batchTransitionService).markRawDataFailed(9006L);
    }

    @Test
    @DisplayName("PhaseA_예외시_스냅샷이없어_cleanup은_생략하고_FAILED전이만_한다")
    void snapshotPhaseFailureSkipsCleanupButFails() {
        // Phase A 자체 예외 — 파일이 안 쓰였으므로 cleanup 대상 없음(plan==null).
        doThrow(new CustomException(ErrorCode.NOT_FOUND, "신규 영상 없음"))
                .when(snapshotService).snapshot(9007L, 26L);

        runner.runAsync(9007L, 26L);

        verify(frameProducer, never()).cleanup(any());
        verify(batchTransitionService).markRawDataFailed(9007L);
    }

    @Test
    @DisplayName("실패했지만_예외가_밖으로_전파되지_않는다_콜백200유지")
    void failureIsSwallowed() {
        doThrow(new CustomException(ErrorCode.NOT_FOUND, "없음"))
                .when(snapshotService).snapshot(9008L, 27L);

        assertThatCode(() -> runner.runAsync(9008L, 27L)).doesNotThrowAnyException();
        verify(batchTransitionService).markRawDataFailed(9008L);
    }
}
