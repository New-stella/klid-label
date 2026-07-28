package kr.co.cudo.authoring.dataset.export;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.co.cudo.authoring.observability.metrics.DatasetExportMetrics;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.FrameContext;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.VideoExportContext;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 *   <li>승인 경로(force=true)는 무수정 재승인도 항상 새 버전 산출(R6).</li>
 *   <li>재동결 경로(onReExport, force=false)는 직전 해시 == 현재 해시면 멱등 skip 유지.</li>
 *   <li>버전 UK 충돌 시 재채번 재시도(count+1 TOCTOU 방어).</li>
 * </ul>
 */
class DatasetExportServiceTest {

    /** 산출 base 원천(원본 영상 경로) — 리졸버가 mock 이라 값 자체는 통과 여부에 영향 없음. */
    private static final String RAW_FILE_PATH = "/nas/clips/clip-001.mp4";

    private DatasetExportTxService txService;
    private DatasetExportWriter writer;
    private SimpleMeterRegistry registry;
    private DatasetExportPathResolver pathResolver;
    /** S7-EXPORT 게이트 — 기본 스텁은 "신고 아님"(false)이라 기존 시나리오는 그대로 통과한다. */
    private DeidentReportGate deidentReportGate;
    private DatasetExportService service;

    @BeforeEach
    void setUp() {
        txService = mock(DatasetExportTxService.class);
        writer = mock(DatasetExportWriter.class);
        // 실인스턴스 SimpleMeterRegistry 를 전용 컴포넌트로 감싸 주입 — 검증은 registry 에서 count 조회.
        registry = new SimpleMeterRegistry();
        pathResolver = mock(DatasetExportPathResolver.class);
        // 기본 스텁 — 산출 base 검증 통과(영상 루트 반환). base 거부 시나리오만 개별 테스트에서 재스텁한다.
        when(pathResolver.resolveVideoRoot(anyLong(), any()))
                .thenReturn(java.nio.file.Paths.get("/nas/clips/1"));
        deidentReportGate = mock(DeidentReportGate.class); // 기본 false — 일반 영상 흐름
        // H1 — 마감(성공/부분)은 RAW 잠금 하 재판정을 통과해야 이뤄진다. 기본은 통과(신고 없음);
        //   차단 시나리오만 개별 테스트에서 false 로 재스텁한다.
        when(txService.finalizeUnlessUnderDeidentReport(anyLong(), anyLong(), anyInt(), anyBoolean()))
                .thenReturn(true);
        service = new DatasetExportService(txService, writer, pathResolver,
                new DatasetExportMetrics(registry), deidentReportGate);
    }

    /** dataset.export.result{outcome=..} counter 값(미등록 시 0.0). */
    private double resultCount(String outcome) {
        var counter = registry.find("dataset.export.result").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** dataset.export.duration{outcome=..} timer 기록 횟수(미등록 시 0). */
    private long durationCount(String outcome) {
        var timer = registry.find("dataset.export.duration").tag("outcome", outcome).timer();
        return timer == null ? 0L : timer.count();
    }

    /** dataset.export.skipped_frames counter 값(미등록 시 0.0). */
    private double skippedFramesCount() {
        var counter = registry.find("dataset.export.skipped_frames").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private ExportPreparation prep(String contentHash, String lastExportedHash) {
        // ctx/frames 는 Writer 가 mock 이라 사용되지 않음 — 오케스트레이션 판단만 검증.
        VideoExportContext ctx = null;
        List<FrameContext> frames = List.of();
        return new ExportPreparation(ctx, frames, contentHash, lastExportedHash, RAW_FILE_PATH);
    }

    /** 프레임 목록을 실제로 담는 preparation — ORIGINAL 부재 판정(파생 여부)이 프레임 데이터에 의존한다. */
    private ExportPreparation prepWithFrames(String contentHash, String lastExportedHash,
                                             List<FrameContext> frames) {
        return new ExportPreparation(null, frames, contentHash, lastExportedHash, RAW_FILE_PATH);
    }

    /** 파생영상 프레임 — 원본 픽셀이 실재하지 않아 SRC_FILE_PATH_NM 이 null(E-ISSUE-41 정책 A). */
    private List<FrameContext> derivativeFrames(int count) {
        List<FrameContext> frames = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            kr.co.cudo.authoring.batch.entity.LsDataSrc f =
                    kr.co.cudo.authoring.batch.entity.LsDataSrc.create(
                            19L, i, (long) i, null, "/deid/frames/deid/19/frame-" + i + ".jpg", null);
            frames.add(new FrameContext(f, List.of()));
        }
        return frames;
    }

    /** 일반 영상 프레임 — 원본 경로 보유. */
    private List<FrameContext> normalFrames(int count) {
        List<FrameContext> frames = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            kr.co.cudo.authoring.batch.entity.LsDataSrc f =
                    kr.co.cudo.authoring.batch.entity.LsDataSrc.create(
                            26L, i, (long) i, "/raw/frames/raw/26/frame-" + i + ".jpg",
                            "/deid/frames/deid/26/frame-" + i + ".jpg", null);
            frames.add(new FrameContext(f, List.of()));
        }
        return frames;
    }

    private ExportResult result(ExportKind kind, int version, int written) {
        return new ExportResult(kind, version, written, 0, null);
    }

    private ExportResult result(ExportKind kind, int version, int written, int skipped) {
        return new ExportResult(kind, version, written, skipped, null);
    }

    @Test
    @DisplayName("승인_커밋후_original과_deid_export가_생성된다")
    void createsOriginalAndDeidExports() {
        long rawSn = 7L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(50L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(1), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 1, 5));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 5));

        service.export(rawSn);

        verify(writer).write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(1), any(), any());
        verify(writer).write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any());
        verify(txService).finalizeUnlessUnderDeidentReport(anyLong(), eq(50L), eq(10), eq(false));
        verify(txService, never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("skip이_없으면_SUCCEEDED로_전이한다 — markPartial/markFailed 미호출")
    void allWrittenNoSkipMarksSucceeded() {
        long rawSn = 20L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(200L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(1), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 1, 5, 0));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 5, 0));

        service.export(rawSn);

        verify(txService).finalizeUnlessUnderDeidentReport(anyLong(), eq(200L), eq(10), eq(false));
        verify(txService, never()).finalizeUnlessUnderDeidentReport(anyLong(), anyLong(), anyInt(), eq(true));
        verify(txService, never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("일부프레임_skip시_PARTIAL로_전이한다 — written>0 && skipped>0")
    void someSkippedMarksPartial() {
        long rawSn = 21L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(210L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(1), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 1, 4, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 4, 1));

        service.export(rawSn);

        // 정상 기록된 프레임 수(4+4)만 PARTIAL 로 반영, 성공/실패 전이는 미호출.
        verify(txService).finalizeUnlessUnderDeidentReport(anyLong(), eq(210L), eq(8), eq(true));
        verify(txService, never()).finalizeUnlessUnderDeidentReport(anyLong(), anyLong(), anyInt(), eq(false));
        verify(txService, never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("파생영상은_SRC_경로가_null_이고_ORIGINAL_export_가_생성되지_않음")
    void derivativeVideoSkipsOriginalKind() {
        long rawSn = 19L;
        when(txService.loadPreparation(rawSn))
                .thenReturn(Optional.of(prepWithFrames("h1", null, derivativeFrames(3))));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(300L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 3, 0));

        service.export(rawSn);

        // E-ISSUE-41 정책 A — 없는 원본을 있는 척 산출하지 않는다(ORIGINAL 벌 미생성).
        verify(writer, never()).write(anyLong(), any(), eq(ExportKind.ORIGINAL), anyInt(), any(), any());
        verify(writer).write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any());
    }

    @Test
    @DisplayName("파생영상_export_가_ORIGINAL_부재를_이유로_PARTIAL_로_떨어지지_않음")
    void derivativeVideoExportIsNotDowngradedToPartial() {
        long rawSn = 19L;
        when(txService.loadPreparation(rawSn))
                .thenReturn(Optional.of(prepWithFrames("h1", null, derivativeFrames(3))));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(301L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 3, 0));

        service.export(rawSn);

        // 파생영상은 ORIGINAL 부재가 정상이므로 skip 집계에 포함되지 않는다 → SUCCEEDED.
        verify(txService).finalizeUnlessUnderDeidentReport(anyLong(), eq(301L), eq(3), eq(false));
        verify(txService, never()).finalizeUnlessUnderDeidentReport(anyLong(), anyLong(), anyInt(), eq(true));
        verify(txService, never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("일반영상은_원본경로가_있으므로_ORIGINAL_export_가_계속_생성된다(회귀방지)")
    void normalVideoStillExportsOriginalKind() {
        long rawSn = 26L;
        when(txService.loadPreparation(rawSn))
                .thenReturn(Optional.of(prepWithFrames("h1", null, normalFrames(2))));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(302L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(1), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 1, 2, 0));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 2, 0));

        service.export(rawSn);

        verify(writer).write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(1), any(), any());
        verify(txService).finalizeUnlessUnderDeidentReport(anyLong(), eq(302L), eq(4), eq(false));
    }

    @Test
    @DisplayName("아무것도_산출못하면_FAILED로_전이한다 — totalWritten==0")
    void nothingWrittenMarksFailed() {
        long rawSn = 22L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(220L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(1), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 1, 0, 3));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 0, 3));

        service.export(rawSn);

        verify(txService).markFailed(220L);
        verify(txService, never()).finalizeUnlessUnderDeidentReport(anyLong(), anyLong(), anyInt(), eq(false));
        verify(txService, never()).finalizeUnlessUnderDeidentReport(anyLong(), anyLong(), anyInt(), eq(true));
    }

    @Test
    @DisplayName("파일산출_실패해도_승인은_롤백되지_않는다 — 예외 미전파 + FAILED 기록")
    void fileWriteFailureDoesNotRollbackApproval() {
        long rawSn = 8L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(60L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), anyInt(), any(), any()))
                .thenThrow(new RuntimeException("disk full"));

        // 예외가 전파되지 않아야 한다 (@Async 분리 + 승인 불변).
        assertThatCode(() -> service.export(rawSn)).doesNotThrowAnyException();

        verify(txService).markFailed(60L);
        verify(txService, never()).finalizeUnlessUnderDeidentReport(anyLong(), anyLong(), anyInt(), eq(false));
    }

    @Test
    @DisplayName("재동결_onReExport_경로는_동일해시면_멱등_skip_유지한다 (force=false)")
    void unchangedReExportIsIdempotentSkip() {
        long rawSn = 9L;
        // 재동결(onReExport, force=false): 직전 SUCCEEDED export 해시 == 현재 라벨 상태 해시 → skip.
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("same", "same")));

        service.export(rawSn, false);

        verify(txService, never()).insertNextVersion(anyLong(), any(), any());
        verify(writer, never()).write(anyLong(), any(), any(), anyInt(), any(), any());
        verify(txService, never()).finalizeUnlessUnderDeidentReport(anyLong(), anyLong(), anyInt(), eq(false));
    }

    @Test
    @DisplayName("무수정_재승인도_승인경로는_새버전_생성한다 (force=true, R6)")
    void unchangedReapproveOnApproveForcesNewVersion() {
        long rawSn = 90L;
        // R6 — 승인 경로(force=true): 직전과 동일 contentHash 여도 skip 하지 않고 새 버전 산출.
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("same", "same")));
        when(txService.insertNextVersion(eq(rawSn), eq("same"), any())).thenReturn(new InsertedExport(900L, 3));
        when(writer.write(eq(rawSn), any(), any(), eq(3), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 3, 5));

        service.export(rawSn, true);

        verify(txService).insertNextVersion(eq(rawSn), eq("same"), any());
        verify(writer, times(2)).write(eq(rawSn), any(), any(), eq(3), any(), any());
        verify(txService).finalizeUnlessUnderDeidentReport(anyLong(), eq(900L), anyInt(), eq(false));
    }

    @Test
    @DisplayName("최초_승인은_직전export_없어_force무관_생성한다 (경계, lastExportedHash=null)")
    void firstApproveCreatesVersionRegardlessOfForce() {
        long rawSn = 91L;
        // 직전 export 없음(lastExportedHash=null) → isUnchanged=false → force 여부와 무관하게 산출.
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(910L, 1));
        when(writer.write(eq(rawSn), any(), any(), eq(1), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 1, 5));

        service.export(rawSn, true);

        verify(txService).insertNextVersion(eq(rawSn), eq("h1"), any());
        verify(writer, times(2)).write(eq(rawSn), any(), any(), eq(1), any(), any());
        verify(txService).finalizeUnlessUnderDeidentReport(anyLong(), eq(910L), anyInt(), eq(false));
    }

    @Test
    @DisplayName("수정후_재승인은_새_버전을_생성한다 — 해시 상이 시 산출 진행")
    void modifiedReapproveCreatesNewVersion() {
        long rawSn = 10L;
        // 라벨이 바뀌어 현재 해시(h2)가 직전 성공 해시(h1)와 다르다 → v2 산출.
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h2", "h1")));
        when(txService.insertNextVersion(eq(rawSn), eq("h2"), any())).thenReturn(new InsertedExport(70L, 2));
        when(writer.write(eq(rawSn), any(), any(), eq(2), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 2, 3));

        service.export(rawSn);

        verify(txService).insertNextVersion(eq(rawSn), eq("h2"), any());
        verify(writer, times(2)).write(eq(rawSn), any(), any(), eq(2), any(), any());
        verify(txService).finalizeUnlessUnderDeidentReport(anyLong(), eq(70L), anyInt(), eq(false));
    }

    @Test
    @DisplayName("동시_승인_UK위반시_재시도로_다음버전_채번된다")
    void versionUkConflictRetriesToNextVersion() {
        long rawSn = 11L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        // 첫 채번(v1)이 동시 승인에 선점되어 UK 위반 → 재채번(v2) 성공.
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk"))
                .thenReturn(new InsertedExport(80L, 2));
        when(writer.write(eq(rawSn), any(), any(), eq(2), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 2, 1));

        service.export(rawSn);

        verify(txService, times(2)).insertNextVersion(eq(rawSn), eq("h1"), any());
        verify(writer, times(2)).write(eq(rawSn), any(), any(), eq(2), any(), any());
        verify(txService).finalizeUnlessUnderDeidentReport(anyLong(), eq(80L), anyInt(), eq(false));
    }

    @Test
    @DisplayName("UK위반이_재시도_상한_초과하면_산출을_중단한다 (UK 백스톱)")
    void versionUkConflictExhaustedAborts() {
        long rawSn = 12L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk"));

        assertThatCode(() -> service.export(rawSn)).doesNotThrowAnyException();

        verify(txService, times(DatasetExportService.MAX_VERSION_RETRY))
                .insertNextVersion(eq(rawSn), eq("h1"), any());
        verify(writer, never()).write(anyLong(), any(), any(), anyInt(), any(), any());
        verify(txService, never()).finalizeUnlessUnderDeidentReport(anyLong(), anyLong(), anyInt(), eq(false));
    }

    @Test
    @DisplayName("프레임_또는_활성메타_부재시_산출을_skip한다")
    void skipsWhenNoData() {
        long rawSn = 13L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.empty());

        service.export(rawSn);

        verify(txService, never()).insertNextVersion(anyLong(), any(), any());
        verify(writer, never()).write(anyLong(), any(), any(), anyInt(), any(), any());
    }

    // ── 관찰성 메트릭 (Micrometer) ──────────────────────────────────────────────
    // dataset.export.result{outcome} / dataset.export.skipped_frames / dataset.export.duration{outcome}
    // 각 export() 호출당 result counter 정확히 1회, duration timer 정확히 1회 stop(배타적).

    @Test
    @DisplayName("완전성공시_result_completed_1회_duration_completed_1회_skipped_frames_0증가")
    void metricsCompleted() {
        // given
        long rawSn = 30L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(300L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(1), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 1, 5, 0));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 5, 0));

        // when
        service.export(rawSn);

        // then — completed 만 1회, 다른 outcome 은 0(배타)
        assertThat(resultCount("completed")).isEqualTo(1.0);
        assertThat(resultCount("partial")).isEqualTo(0.0);
        assertThat(resultCount("failed")).isEqualTo(0.0);
        assertThat(durationCount("completed")).isEqualTo(1L);
        assertThat(skippedFramesCount()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("부분산출시_result_partial_1회_skipped_frames는_총skip만큼_증가")
    void metricsPartial() {
        // given — original skip 1 + deid skip 1 = 총 2
        long rawSn = 31L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(310L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(1), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 1, 4, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 4, 1));

        // when
        service.export(rawSn);

        // then
        assertThat(resultCount("partial")).isEqualTo(1.0);
        assertThat(resultCount("completed")).isEqualTo(0.0);
        assertThat(durationCount("partial")).isEqualTo(1L);
        assertThat(skippedFramesCount()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("아무것도_산출못하면_result_failed_1회_duration_failed_1회")
    void metricsFailedNothingWritten() {
        // given
        long rawSn = 32L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(320L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(1), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 1, 0, 3));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 0, 3));

        // when
        service.export(rawSn);

        // then
        assertThat(resultCount("failed")).isEqualTo(1.0);
        assertThat(resultCount("partial")).isEqualTo(0.0);
        assertThat(durationCount("failed")).isEqualTo(1L);
    }

    @Test
    @DisplayName("파일쓰기_예외시_result_failed_1회 — 예외경로도_배타적_1회")
    void metricsFailedOnWriteException() {
        // given
        long rawSn = 33L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(330L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), anyInt(), any(), any()))
                .thenThrow(new RuntimeException("disk full"));

        // when
        service.export(rawSn);

        // then — 성공/부분 분기 미도달, failed 만 1회(이중계상 없음)
        assertThat(resultCount("failed")).isEqualTo(1.0);
        assertThat(resultCount("completed")).isEqualTo(0.0);
        assertThat(resultCount("partial")).isEqualTo(0.0);
        assertThat(durationCount("failed")).isEqualTo(1L);
    }

    @Test
    @DisplayName("마감_직전_신고가_확인되면_FAILED로_마감하지_않고_예외로_이탈한다 (H1 — 통지 보류)")
    void finalizeBlockedByDeidentReportDoesNotMarkFailed() {
        // given — 진입부 게이트는 통과했고 파일도 다 썼는데(written>0), 마감 직전 잠금 재판정에서
        //   신고 구간이 확인된다(=쓰기 도중 신고 접수). 마감 게이트가 false 를 반환한다.
        long rawSn = 41L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(410L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(1), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 1, 5, 0));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 5, 0));
        when(txService.finalizeUnlessUnderDeidentReport(anyLong(), eq(410L), anyInt(), eq(false)))
                .thenReturn(false);

        // when — 예외로 이탈해야 러너(doExport)가 false 를 반환하고 통지가 보류된다.
        assertThatCode(() -> service.export(rawSn, true))
                .isInstanceOf(kr.co.cudo.authoring.common.exception.CustomException.class);

        // then — FAILED 오분류 없음(행은 마감 트랜잭션에서 삭제됨) + outcome=deident_blocked 로만 계상.
        verify(txService, never()).markFailed(anyLong());
        assertThat(resultCount("deident_blocked")).isEqualTo(1.0);
        assertThat(resultCount("failed")).isEqualTo(0.0);
        assertThat(resultCount("completed")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("상태전이_markSucceeded_예외시_inner_catch가_failed로_재분류한다 — record FAILED와 metric 정합")
    void metricsStateTransitionExceptionReclassifiedFailed() {
        // given — 파일은 다 썼는데(written>0, skip 0) markSucceeded 가 예외를 던진다.
        long rawSn = 37L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(370L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(1), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 1, 5, 0));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 5, 0));
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(txService).finalizeUnlessUnderDeidentReport(anyLong(), eq(370L), anyInt(), eq(false));

        // when — 예외 미전파(승인 불변)
        assertThatCode(() -> service.export(rawSn)).doesNotThrowAnyException();

        // then — inner catch 가 markFailed + outcome=failed 로 재분류(영속 record FAILED 와 metric 정합)
        verify(txService).markFailed(370L);
        assertThat(resultCount("failed")).isEqualTo(1.0);
        assertThat(resultCount("completed")).isEqualTo(0.0);
        assertThat(resultCount("partial")).isEqualTo(0.0);
        assertThat(durationCount("failed")).isEqualTo(1L);
    }

    @Test
    @DisplayName("재채번_소진시_result_version_exhausted_1회")
    void metricsVersionExhausted() {
        // given
        long rawSn = 34L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk"));

        // when
        service.export(rawSn);

        // then
        assertThat(resultCount("version_exhausted")).isEqualTo(1.0);
        assertThat(resultCount("failed")).isEqualTo(0.0);
        assertThat(durationCount("version_exhausted")).isEqualTo(1L);
    }

    @Test
    @DisplayName("재동결_onReExport_동일해시_멱등skip시_result_idempotent_skip_1회 (force=false)")
    void metricsIdempotentSkipOnReExport() {
        // given — 재동결 경로(force=false)에서만 멱등 skip outcome 이 발생한다(승인 경로 force=true 는 미발생).
        long rawSn = 35L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("same", "same")));

        // when
        service.export(rawSn, false);

        // then
        assertThat(resultCount("idempotent_skip")).isEqualTo(1.0);
        assertThat(resultCount("completed")).isEqualTo(0.0);
        assertThat(durationCount("idempotent_skip")).isEqualTo(1L);
    }

    @Test
    @DisplayName("승인경로_force_true_동일해시여도_idempotent_skip이_아니라_completed로_계상된다 (R6)")
    void metricsApproveForceNoIdempotentSkip() {
        // given — 승인 경로(force=true)는 동일 해시여도 skip 하지 않고 산출 → idempotent_skip outcome 미발생.
        long rawSn = 38L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("same", "same")));
        when(txService.insertNextVersion(eq(rawSn), eq("same"), any())).thenReturn(new InsertedExport(380L, 2));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(2), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 2, 5, 0));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(2), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 2, 5, 0));

        // when
        service.export(rawSn, true);

        // then
        assertThat(resultCount("idempotent_skip")).isEqualTo(0.0);
        assertThat(resultCount("completed")).isEqualTo(1.0);
        assertThat(durationCount("completed")).isEqualTo(1L);
    }

    @Test
    @DisplayName("입력부재시_result_no_input_1회")
    void metricsNoInput() {
        // given
        long rawSn = 36L;
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.empty());

        // when
        service.export(rawSn);

        // then
        assertThat(resultCount("no_input")).isEqualTo(1.0);
        assertThat(resultCount("failed")).isEqualTo(0.0);
        assertThat(durationCount("no_input")).isEqualTo(1L);
    }

    @Test
    @DisplayName("비식별_신고_상태면_산출을_시작하지도_않고_차단된다 (S7-EXPORT)")
    void deidentReportBlocksExportBeforeAnyWork() {
        // given — 신고 구간(DE_IDNTF_YN='F') 영상.
        long rawSn = 90L;
        when(deidentReportGate.isUnderDeidentReport(rawSn)).thenReturn(true);

        // when / then — 412 로 이탈한다(러너가 false 로 받아 통지를 보류하게 하는 신호).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.export(rawSn, true))
                .isInstanceOf(kr.co.cudo.authoring.common.exception.CustomException.class)
                .extracting(e -> ((kr.co.cudo.authoring.common.exception.CustomException) e).getErrorCode())
                .isEqualTo(kr.co.cudo.authoring.common.exception.ErrorCode.PRECONDITION_FAILED);

        // then — 로딩/버전채번/파일쓰기 어느 것도 시작되지 않는다(게이트가 최선두).
        verify(txService, never()).loadPreparation(anyLong());
        verify(txService, never()).insertNextVersion(anyLong(), any(), any());
        verify(writer, never()).write(anyLong(), any(), any(), anyInt(), any(), any());
        // 관측 — 실패가 아니라 전용 skip outcome 으로 정확히 1회 계상.
        assertThat(resultCount("deident_blocked")).isEqualTo(1.0);
        assertThat(durationCount("deident_blocked")).isEqualTo(1L);
        assertThat(resultCount("failed")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("게이트_판정조회가_실패하면_산출하지_않는다_fail_closed (S7-EXPORT)")
    void gateLookupFailureBlocksExport() {
        // given — 판정 자체가 DB 오류로 실패(fail-closed 여야 한다 — 통과로 흘리면 PII 유출).
        long rawSn = 91L;
        when(deidentReportGate.isUnderDeidentReport(rawSn))
                .thenThrow(new IllegalStateException("db down"));

        // when / then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.export(rawSn, true))
                .isInstanceOf(IllegalStateException.class);
        verify(txService, never()).loadPreparation(anyLong());
        verify(writer, never()).write(anyLong(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("신고상태가_아니면_export가_정상_수행된다_회귀방어 (S7-EXPORT)")
    void normalVideoUnaffectedByGate() {
        // given — DE_IDNTF_YN 이 'Y'/'N'/null 인 일반 영상(게이트 false).
        long rawSn = 92L;
        when(deidentReportGate.isUnderDeidentReport(rawSn)).thenReturn(false);
        when(txService.loadPreparation(rawSn)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(rawSn), eq("h1"), any())).thenReturn(new InsertedExport(920L, 1));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.ORIGINAL), eq(1), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, 1, 3, 0));
        when(writer.write(eq(rawSn), any(), eq(ExportKind.DEIDENTIFIED), eq(1), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, 1, 3, 0));

        // when
        assertThatCode(() -> service.export(rawSn, true)).doesNotThrowAnyException();

        // then
        verify(txService).finalizeUnlessUnderDeidentReport(anyLong(), eq(920L), eq(6), eq(false));
        assertThat(resultCount("completed")).isEqualTo(1.0);
        assertThat(resultCount("deident_blocked")).isEqualTo(0.0);
    }
}
