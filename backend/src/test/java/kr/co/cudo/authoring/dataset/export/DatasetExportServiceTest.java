package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.FrameContext;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.VideoExportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DatasetExportService} 오케스트레이터 단위 테스트 (Mockito).
 *
 * <p>검증 초점(HIGH):
 * <ul>
 *   <li>승인 후 원본+비식별 2벌 산출 → Writer 2회 + SUCCEEDED 전이.</li>
 *   <li>파일 산출 실패가 예외를 전파하지 않고 FAILED 로만 기록(승인 불변).</li>
 *   <li>무수정 재승인 멱등 skip(직전 성공 해시 == 현재 해시).</li>
 *   <li>버전 UK 충돌 시 재채번 재시도(count+1 TOCTOU 방어).</li>
 * </ul>
 */
class DatasetExportServiceTest {

    private DatasetExportTxService txService;
    private DatasetExportWriter writer;
    private DatasetExportService service;

    @BeforeEach
    void setUp() {
        txService = mock(DatasetExportTxService.class);
        writer = mock(DatasetExportWriter.class);
        service = new DatasetExportService(txService, writer);
    }

    private ExportPreparation prep(String contentHash, String lastSucceededHash) {
        // ctx/frames 는 Writer 가 mock 이라 사용되지 않음 — 오케스트레이션 판단만 검증.
        VideoExportContext ctx = null;
        List<FrameContext> frames = List.of();
        return new ExportPreparation(ctx, frames, contentHash, lastSucceededHash);
    }

    private ExportResult result(ExportKind kind, int version, int written) {
        return new ExportResult(kind, version, written, 0, null);
    }

    @Test
    @DisplayName("승인_커밋후_original과_deid_export가_생성된다")
    void createsOriginalAndDeidExports() {
        long rawSn = 7L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"))).thenReturn(new InsertedExport(50L, 1));
        when(writer.write(eq(rawSn), eq(ExportKind.ORIGINAL), eq(1), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 1, 5));
        when(writer.write(eq(rawSn), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 5));

        service.export(rawSn);

        verify(writer).write(eq(rawSn), eq(ExportKind.ORIGINAL), eq(1), any(), any());
        verify(writer).write(eq(rawSn), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any());
        verify(txService).markSucceeded(50L, 10);
        verify(txService, never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("파일산출_실패해도_승인은_롤백되지_않는다 — 예외 미전파 + FAILED 기록")
    void fileWriteFailureDoesNotRollbackApproval() {
        long rawSn = 8L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"))).thenReturn(new InsertedExport(60L, 1));
        when(writer.write(eq(rawSn), eq(ExportKind.ORIGINAL), anyInt(), any(), any()))
                .thenThrow(new RuntimeException("disk full"));

        // 예외가 전파되지 않아야 한다 (@Async 분리 + 승인 불변).
        assertThatCode(() -> service.export(rawSn)).doesNotThrowAnyException();

        verify(txService).markFailed(60L);
        verify(txService, never()).markSucceeded(anyLong(), anyInt());
    }

    @Test
    @DisplayName("무수정_재승인은_멱등_skip된다 — 신규 export 미생성")
    void unchangedReapproveIsIdempotentSkip() {
        long rawSn = 9L;
        // 직전 SUCCEEDED export 의 해시 == 현재 라벨 상태 해시 → skip.
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("same", "same")));

        service.export(rawSn);

        verify(txService, never()).insertNextVersion(anyLong(), any());
        verify(writer, never()).write(anyLong(), any(), anyInt(), any(), any());
        verify(txService, never()).markSucceeded(anyLong(), anyInt());
    }

    @Test
    @DisplayName("수정후_재승인은_새_버전을_생성한다 — 해시 상이 시 산출 진행")
    void modifiedReapproveCreatesNewVersion() {
        long rawSn = 10L;
        // 라벨이 바뀌어 현재 해시(h2)가 직전 성공 해시(h1)와 다르다 → v2 산출.
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h2", "h1")));
        when(txService.insertNextVersion(eq(rawSn), eq("h2"))).thenReturn(new InsertedExport(70L, 2));
        when(writer.write(eq(rawSn), any(), eq(2), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 2, 3));

        service.export(rawSn);

        verify(txService).insertNextVersion(rawSn, "h2");
        verify(writer, times(2)).write(eq(rawSn), any(), eq(2), any(), any());
        verify(txService).markSucceeded(eq(70L), anyInt());
    }

    @Test
    @DisplayName("동시_승인_UK위반시_재시도로_다음버전_채번된다")
    void versionUkConflictRetriesToNextVersion() {
        long rawSn = 11L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        // 첫 채번(v1)이 동시 승인에 선점되어 UK 위반 → 재채번(v2) 성공.
        when(txService.insertNextVersion(eq(rawSn), eq("h1")))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk"))
                .thenReturn(new InsertedExport(80L, 2));
        when(writer.write(eq(rawSn), any(), eq(2), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 2, 1));

        service.export(rawSn);

        verify(txService, times(2)).insertNextVersion(rawSn, "h1");
        verify(writer, times(2)).write(eq(rawSn), any(), eq(2), any(), any());
        verify(txService).markSucceeded(eq(80L), anyInt());
    }

    @Test
    @DisplayName("UK위반이_재시도_상한_초과하면_산출을_중단한다 (UK 백스톱)")
    void versionUkConflictExhaustedAborts() {
        long rawSn = 12L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1")))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk"));

        assertThatCode(() -> service.export(rawSn)).doesNotThrowAnyException();

        verify(txService, times(DatasetExportService.MAX_VERSION_RETRY))
                .insertNextVersion(rawSn, "h1");
        verify(writer, never()).write(anyLong(), any(), anyInt(), any(), any());
        verify(txService, never()).markSucceeded(anyLong(), anyInt());
    }

    @Test
    @DisplayName("프레임_또는_활성메타_부재시_산출을_skip한다")
    void skipsWhenNoData() {
        long rawSn = 13L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.empty());

        service.export(rawSn);

        verify(txService, never()).insertNextVersion(anyLong(), any());
        verify(writer, never()).write(anyLong(), any(), anyInt(), any(), any());
    }
}
