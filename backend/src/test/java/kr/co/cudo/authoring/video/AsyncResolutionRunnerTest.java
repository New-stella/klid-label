package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.runner.AsyncResolutionRunner;
import kr.co.cudo.authoring.video.service.ResolutionDerivativeFinalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 해상도 파생영상 비동기 러너 단위 테스트 — Phase 2.
 * 확정 실패 시 all-or-nothing 롤백 후 파생 RAW 를 FAILED 로 전이하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AsyncResolutionRunnerTest {

    @Mock ResolutionDerivativeFinalizer finalizer;
    @Mock BatchTransitionService batchTransitionService;

    AsyncResolutionRunner runner;

    @BeforeEach
    void setup() {
        runner = new AsyncResolutionRunner(finalizer, batchTransitionService);
    }

    @Test
    @DisplayName("추출성공시_markMarkingReady로_전이해_파이프라인에_진입한다")
    void successDelegatesToFinalizerWithoutFailedTransition() {
        runner.runAsync(700L, 200L, 9L, ResolutionPreset.RES_720P);

        verify(finalizer).finalizeDerivative(700L, 200L, 9L, ResolutionPreset.RES_720P);
        verify(batchTransitionService, never()).markRawDataFailed(any());
    }

    @Test
    @DisplayName("프레임_일부_실패시_all_or_nothing으로_해당RAW가_FAILED_전이된다")
    void failurePropagatesToFailedTransition() {
        doThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "resize failed"))
                .when(finalizer).finalizeDerivative(701L, 200L, 9L, ResolutionPreset.RES_720P);

        runner.runAsync(701L, 200L, 9L, ResolutionPreset.RES_720P);

        verify(batchTransitionService).markRawDataFailed(eq(701L));
    }

    @Test
    @DisplayName("확정실패시_고아파일_정리를_호출한다")
    void failureTriggersOrphanArtifactCleanup() {
        doThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "resize failed"))
                .when(finalizer).finalizeDerivative(702L, 200L, 9L, ResolutionPreset.RES_720P);

        runner.runAsync(702L, 200L, 9L, ResolutionPreset.RES_720P);

        // MEDIUM — DB 롤백만으로는 남는 파생 비디오/리스케일 프레임 파일을 best-effort 정리해야 한다.
        verify(finalizer).cleanupDerivativeArtifacts(eq(702L));
        verify(batchTransitionService).markRawDataFailed(eq(702L));
    }
}
