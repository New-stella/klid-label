package kr.co.cudo.authoring.dataset.export;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.co.cudo.authoring.dataset.export.event.DatasetExportCompletedEvent;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.FrameContext;
import kr.co.cudo.authoring.observability.metrics.DatasetExportMetrics;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-ISSUE-61 (CRITICAL) 회귀 가드 — <b>예외 없이 실패로 종결되는 export 경로에서 통지가 발송되지 않는다</b>.
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * 기존 {@link AsyncDatasetExportRunnerTest} 의 실패 케이스는 전부
 * {@code doThrow(new RuntimeException(...)).when(exportService).export(...)} — <b>예외 경로만</b> 검증했다.
 * 그런데 {@link DatasetExportService#export(long, boolean)} 은 예외를 던지지 않고 정상 반환하면서 실패로
 * 종결하는 경로가 4종이다(NO_INPUT · 산출 base 거부 · 버전 채번 소진 · 산출물 0건). 러너가 "예외 없음 =
 * 성공"으로 판정하는 한 이 4경로에서 TASK_COMPLETED/TASK_MODIFIED 가 그대로 나가고, 관제는
 * {@code V_COMPLETED_VIDEO.EXPORT_PATH_NM} 을 조회해 <b>존재하지 않거나 구 버전인 폴더</b>를 픽업한다
 * (CLAUDE.md "통지는 export 성공 후 발송한다" 구속 정책 위반 — 실측 rawSn=72).
 *
 * <p>그래서 이 테스트는 {@code DatasetExportService} 를 <b>실인스턴스</b>로 두고(협력자만 mock) 러너와
 * 실제로 배선해, "서비스가 어떻게 종결했는가 → 러너가 통지를 내보내는가"를 관통 검증한다. 서비스를 mock
 * 으로 두면 이 결함 자체가 표현되지 않는다.
 */
class DatasetExportNotifyGateTest {

    private static final String RAW_FILE_PATH = "/nas/clips/clip-001.mp4";
    private static final long RAW_SN = 72L;

    private DatasetExportTxService txService;
    private DatasetExportWriter writer;
    private DatasetExportPathResolver pathResolver;
    private DeidentReportGate deidentReportGate;
    private ApplicationEventPublisher eventPublisher;
    private AsyncDatasetExportRunner runner;

    @BeforeEach
    void setUp() {
        txService = mock(DatasetExportTxService.class);
        writer = mock(DatasetExportWriter.class);
        pathResolver = mock(DatasetExportPathResolver.class);
        deidentReportGate = mock(DeidentReportGate.class); // 기본 false — 신고 게이트는 이 테스트의 관심사 아님
        eventPublisher = mock(ApplicationEventPublisher.class);

        when(pathResolver.resolveVideoRoot(anyLong(), any()))
                .thenReturn(java.nio.file.Paths.get("/nas/clips/72"));
        when(txService.finalizeUnlessUnderDeidentReport(anyLong(), anyLong(), anyInt(), anyBoolean(), any()))
                .thenReturn(true);

        DatasetExportService exportService = new DatasetExportService(
                txService, writer, pathResolver,
                new DatasetExportMetrics(new SimpleMeterRegistry()), deidentReportGate,
                new DatasetExportFolderSizeCalculator());
        runner = new AsyncDatasetExportRunner(exportService, eventPublisher);
    }

    /** 원본+비식별 경로를 모두 가진 정상 프레임 — ORIGINAL 부재(파생) 판정에 걸리지 않게 한다. */
    private List<FrameContext> normalFrames(int count) {
        List<FrameContext> frames = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            kr.co.cudo.authoring.batch.entity.LsDataSrc f =
                    kr.co.cudo.authoring.batch.entity.LsDataSrc.create(
                            RAW_SN, i, (long) i, "/raw/frames/raw/72/frame-" + i + ".jpg",
                            "/deid/frames/deid/72/frame-" + i + ".jpg", null);
            frames.add(new FrameContext(f, List.of()));
        }
        return frames;
    }

    private ExportPreparation prep(String contentHash, String lastExportedHash) {
        return new ExportPreparation(null, normalFrames(2), contentHash, lastExportedHash, RAW_FILE_PATH);
    }

    private ExportResult result(ExportKind kind, int written, int skipped) {
        return new ExportResult(kind, 1, written, skipped, null);
    }

    /** 두 벌(ORIGINAL/DEIDENTIFIED) 쓰기 결과를 동일 값으로 스텁한다. */
    private void stubWriter(int writtenEach, int skippedEach) {
        when(writer.write(eq(RAW_SN), any(), eq(ExportKind.ORIGINAL), anyInt(), any(), any()))
                .thenReturn(result(ExportKind.ORIGINAL, writtenEach, skippedEach));
        when(writer.write(eq(RAW_SN), any(), eq(ExportKind.DEIDENTIFIED), anyInt(), any(), any()))
                .thenReturn(result(ExportKind.DEIDENTIFIED, writtenEach, skippedEach));
    }

    // ── 무예외 실패 4경로 — 통지 보류 ────────────────────────────────────────────────────

    @Test
    @DisplayName("산출물0건_FAILED_마감시_완료통지를_발행하지_않는다 — 예외 없는 실패 (D-ISSUE-61)")
    void nothingProduced_doesNotPublishCompleted() {
        // given — 프레임은 있으나 원천 이미지 부재로 writer 가 전량 skip → totalWritten == 0 → markFailed
        when(txService.loadPreparation(RAW_SN)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(RAW_SN), any(), any())).thenReturn(new InsertedExport(18L, 1));
        stubWriter(0, 2);

        // when
        runner.runApprovalAsync(RAW_SN);

        // then — export 는 FAILED 로 마감됐으므로 통지가 나가면 안 된다.
        verify(txService).markFailed(18L);
        verify(eventPublisher, never()).publishEvent(any(DatasetExportCompletedEvent.class));
    }

    @Test
    @DisplayName("산출base_거부시_완료통지를_발행하지_않는다 — 예외 없는 실패 (D-ISSUE-61)")
    void baseRejected_doesNotPublishCompleted() {
        // given — 원본 경로가 허용 마운트 루트 밖이라 리졸버가 거부 → markBaseRejected 후 정상 반환
        when(txService.loadPreparation(RAW_SN)).thenReturn(Optional.of(prep("h1", null)));
        when(pathResolver.resolveVideoRoot(anyLong(), any()))
                .thenThrow(new IllegalArgumentException("base rejected"));
        when(txService.insertNextVersion(eq(RAW_SN), any(), any())).thenReturn(new InsertedExport(19L, 1));

        // when
        runner.runApprovalAsync(RAW_SN);

        // then
        verify(eventPublisher, never()).publishEvent(any(DatasetExportCompletedEvent.class));
    }

    @Test
    @DisplayName("버전채번_소진시_완료통지를_발행하지_않는다 — 예외 없는 실패 (D-ISSUE-61)")
    void versionExhausted_doesNotPublishCompleted() {
        // given — UK 충돌이 재시도 상한까지 지속돼 예약 자체가 실패
        when(txService.loadPreparation(RAW_SN)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(RAW_SN), any(), any()))
                .thenThrow(new DataIntegrityViolationException("uk"));

        // when
        runner.runApprovalAsync(RAW_SN);

        // then
        verify(eventPublisher, never()).publishEvent(any(DatasetExportCompletedEvent.class));
    }

    @Test
    @DisplayName("입력부재_NO_INPUT시_완료통지를_발행하지_않는다 — 예외 없는 실패 (D-ISSUE-61)")
    void noInput_doesNotPublishCompleted() {
        // given — 프레임/활성 메타 부재로 산출할 것이 없다.
        when(txService.loadPreparation(RAW_SN)).thenReturn(Optional.empty());

        // when
        runner.runApprovalAsync(RAW_SN);

        // then — 산출물이 없으므로 관제에 완료를 알리면 안 된다.
        verify(eventPublisher, never()).publishEvent(any(DatasetExportCompletedEvent.class));
    }

    @Test
    @DisplayName("산출물0건_FAILED_마감시_수정통지_콜백도_실행하지_않는다 — 승인후수정 경로 (D-ISSUE-61)")
    void nothingProduced_doesNotRunModifiedCallback() {
        // given
        when(txService.loadPreparation(RAW_SN)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(RAW_SN), any(), any())).thenReturn(new InsertedExport(20L, 1));
        stubWriter(0, 2);
        Runnable notify = mock(Runnable.class);

        // when
        runner.runReExportThenNotify(RAW_SN, true, notify);

        // then — TASK_MODIFIED 통지 콜백도 보류돼야 한다.
        verify(notify, never()).run();
    }

    // ── 통지해야 하는 종결 — 회귀(과잉 차단) 방지 ───────────────────────────────────────

    @Test
    @DisplayName("정상_산출_완료시_완료통지를_발행한다 — 과잉 차단 회귀 방지")
    void completed_publishesCompleted() {
        when(txService.loadPreparation(RAW_SN)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(RAW_SN), any(), any())).thenReturn(new InsertedExport(21L, 1));
        stubWriter(5, 0);

        runner.runApprovalAsync(RAW_SN);

        verify(eventPublisher).publishEvent(new DatasetExportCompletedEvent(RAW_SN));
    }

    @Test
    @DisplayName("부분산출_PARTIAL_마감시_완료통지를_발행한다 — 뷰가 PARTIAL 을 노출하므로 통지 유지 (E-ISSUE-81)")
    void partial_publishesCompleted() {
        when(txService.loadPreparation(RAW_SN)).thenReturn(Optional.of(prep("h1", null)));
        when(txService.insertNextVersion(eq(RAW_SN), any(), any())).thenReturn(new InsertedExport(22L, 1));
        stubWriter(5, 1);

        runner.runApprovalAsync(RAW_SN);

        verify(eventPublisher).publishEvent(new DatasetExportCompletedEvent(RAW_SN));
    }

    @Test
    @DisplayName("멱등skip시_완료통지를_발행한다 — 직전 성공 산출물이 최신이라 관제 픽업이 유효")
    void idempotentSkip_publishesCompleted() {
        // given — 재동결 경로(force=false)에서 직전 export 와 콘텐츠 해시가 같아 재산출을 skip 한다.
        when(txService.loadPreparation(RAW_SN)).thenReturn(Optional.of(prep("same", "same")));
        Runnable notify = mock(Runnable.class);

        // when
        runner.runReExportThenNotify(RAW_SN, false, notify);

        // then — 디스크의 직전 산출물이 곧 최신이므로 통지는 정상 진행한다.
        verify(notify).run();
    }
}
