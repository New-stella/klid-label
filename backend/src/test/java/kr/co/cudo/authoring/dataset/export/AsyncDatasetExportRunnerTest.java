package kr.co.cudo.authoring.dataset.export;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AsyncDatasetExportRunner} 단위 테스트 — 위임 + @Async 예외 삼킴 검증.
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
    @DisplayName("runAsync가_export로_위임한다")
    void delegatesToExport() {
        runner.runAsync(42L);

        verify(exportService).export(42L);
    }

    @Test
    @DisplayName("Async_러너_예외는_삼켜진다 — 호출부로 전파 안 됨")
    void swallowsExportException() {
        doThrow(new RuntimeException("boom")).when(exportService).export(eq(7L));

        // @Async 이므로 산출 예외가 호출부(승인 이후 스케줄)로 전파되지 않는다.
        assertThatCode(() -> runner.runAsync(7L)).doesNotThrowAnyException();

        verify(exportService).export(7L);
    }

    @Test
    @DisplayName("rawSn이_null이면_무처리")
    void nullRawSnNoOp() {
        runner.runAsync(null);

        verify(exportService, never()).export(anyLong());
    }
}
