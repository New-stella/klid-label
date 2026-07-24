package kr.co.cudo.authoring.dataset.export;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AsyncDatasetExportRunner} 단위 테스트 — 위임(forceRegenerate 관통) + @Async 예외 삼킴 검증.
 */
class AsyncDatasetExportRunnerTest {

    private DatasetExportService exportService;
    private AsyncDatasetExportRunner runner;

    @BeforeEach
    void setUp() {
        exportService = mock(DatasetExportService.class);
        runner = new AsyncDatasetExportRunner(exportService);
    }

    @Test
    @DisplayName("runAsync가_forceTrue를_export로_관통위임한다 (승인 경로 R6)")
    void delegatesToExportWithForceTrue() {
        runner.runAsync(42L, true);

        verify(exportService).export(42L, true);
    }

    @Test
    @DisplayName("runAsync가_forceFalse를_export로_관통위임한다 (재동결 경로)")
    void delegatesToExportWithForceFalse() {
        runner.runAsync(43L, false);

        verify(exportService).export(43L, false);
    }

    @Test
    @DisplayName("Async_러너_예외는_삼켜진다 — 호출부로 전파 안 됨")
    void swallowsExportException() {
        doThrow(new RuntimeException("boom")).when(exportService).export(eq(7L), anyBoolean());

        // @Async 이므로 산출 예외가 호출부(승인 이후 스케줄)로 전파되지 않는다.
        assertThatCode(() -> runner.runAsync(7L, true)).doesNotThrowAnyException();

        verify(exportService).export(7L, true);
    }

    @Test
    @DisplayName("rawSn이_null이면_무처리")
    void nullRawSnNoOp() {
        runner.runAsync(null, true);

        verify(exportService, never()).export(anyLong(), anyBoolean());
    }
}
