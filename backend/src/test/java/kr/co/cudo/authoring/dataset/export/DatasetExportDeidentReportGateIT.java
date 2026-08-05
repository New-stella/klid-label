package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyDebouncer;
import kr.co.cudo.authoring.controlnotify.service.ControlNotifyService;
import kr.co.cudo.authoring.dataset.dto.EnvironmentMetaUpdateRequest;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.entity.LsDatasetExport;
import kr.co.cudo.authoring.dataset.export.event.DatasetExportCompletedEvent;
import kr.co.cudo.authoring.dataset.export.repository.LsDatasetExportRepository;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.dataset.service.EnvironmentMetaService;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.observability.metrics.DatasetExportMetrics;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * S7-EXPORT (HIGH · CWE-359) — <b>비식별 누락 신고 구간 export 차단 게이트</b> 통합 테스트
 * (PostgreSQL Testcontainer + 실 임시 스토리지).
 *
 * <h3>무엇을 막는가</h3>
 * 비식별 누락 신고는 라벨을 <b>삭제하지 않고 보존</b>하는 정책(2026-07-27 반전)이라, 신고 구간
 * ({@code DE_IDNTF_YN='F'})에 산출이 돌면 <b>비식별 누락이 확인된 그 프레임 이미지</b>가 {@code v{n+1}}
 * 폴더로 전량 복사되고 {@code V_COMPLETED_VIDEO.EXPORT_PATH_NM} 이 그 폴더로 갱신돼 관제가 PII 원본을
 * 픽업한다. 게이트는 산출 경로의 <b>단일 통과 지점</b>인 {@link DatasetExportService#export(long, boolean)}
 * 진입부 한 곳에 있다.
 *
 * <h3>왜 실 파일 검증인가</h3>
 * 결함의 실체가 "디스크에 PII 이미지가 생겼다" 이므로 mock 단언으로 갈음하지 않는다. 모든 케이스가
 * 임시 스토리지에 실제 프레임 fixture 를 깔고 산출 폴더의 <b>존재/부재</b>를 직접 확인한다.
 *
 * <h3>비동기 처리 방식</h3>
 * 러너 3경로(승인·수정·회수) 검증은 {@link AsyncDatasetExportRunner} 를 <b>직접 생성해 동기 호출</b>한다
 * ({@code @Async} 프록시 미개입 — 스레드 타이밍 flakiness 제거). 단 촬영환경 수정 재현 경로만은 실서비스
 * 배선(이벤트 → 디바운서 → {@code @Async} 러너)을 그대로 태우고 Awaitility 로 관측한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 디바운스 윈도우 0 — flushExpiredWindows() 를 직접 호출하면 즉시 만료로 처리된다(결정적).
        "authoring.control-notify.debounce-window-sec=0",
        // 디바운서 자체 스케줄러는 끄고 테스트가 flush 시점을 통제한다.
        "authoring.dataset-export.regen-flush.enabled=false",
        // 통지 리스너(ControlNotifyEventListener)를 활성화해 "export 성공 → 완료 통지" 배선을 실측한다.
        // 발송 자체는 @MockBean ControlNotifyService 가 받는다(외부 호출 없음).
        "authoring.control-notify.enabled=true"
})
class DatasetExportDeidentReportGateIT {

    private static final int FRAME_COUNT = 2;
    private static final byte[] VIDEO_BYTES = {(byte) 0x00, (byte) 0x11};

    private static final Path STORAGE_ROOT = createStorageRoot();
    private static final Path LABELING_ROOT = STORAGE_ROOT.resolve("labeling");
    private static final Path RAW_ROOT = STORAGE_ROOT.resolve("raw");
    private static final Path VIDEO_DIR = RAW_ROOT.resolve("videos");
    private static final Path DEID_ROOT = STORAGE_ROOT.resolve("deid");

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("dataset-export-deident-gate");
        } catch (IOException e) {
            throw new IllegalStateException("임시 스토리지 생성 실패", e);
        }
    }

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("authoring.storage.labeling-path", LABELING_ROOT::toString);
        registry.add("authoring.storage.raw-path", RAW_ROOT::toString);
        registry.add("authoring.storage.deidentified-path", DEID_ROOT::toString);
    }

    @Autowired private DatasetExportService exportService;
    @Autowired private DatasetExportTxService txService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsDatasetVideoMetaRepository videoMetaRepository;
    @Autowired private LsDatasetExportRepository exportRepository;
    @Autowired private LsLabelRepository labelMasterRepository;
    @Autowired private LsRawDataStatusRepository rawDataStatusRepository;
    @Autowired private EnvironmentMetaService environmentMetaService;
    @Autowired private ControlNotifyDebouncer debouncer;
    // H1/M1/M2 — 훅 주입 서비스 재조립 · 신고 해소 재트리거 · 회수기 게이트 검증용.
    @Autowired private DatasetExportWriter writer;
    @Autowired private DatasetExportPathResolver pathResolver;
    @Autowired private DatasetExportMetrics metrics;
    @Autowired private DeidentReportGate deidentReportGate;
    @Autowired private DeidentReportService deidentReportService;
    @Autowired private LsDeidentReportRepository reportRepository;
    @Autowired private LsDeidentProcLogRepository procLogRepository;

    /** 통지 발송 여부 단언용 — 실 통지 토글과 무관하게 빈을 존재시켜 디바운서가 콜백을 만들게 한다. */
    @MockBean private ControlNotifyService notifyService;

    private final TransactionTemplate txTemplate;
    private final List<Long> createdRawSns = new ArrayList<>();
    private final List<Long> createdLabelIds = new ArrayList<>();
    private final List<Long> createdReportSns = new ArrayList<>();
    private final List<Long> createdProcLogSns = new ArrayList<>();

    DatasetExportDeidentReportGateIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @AfterEach
    void cleanup() {
        txTemplate.executeWithoutResult(s -> {
            reportRepository.deleteAllById(createdReportSns);
            procLogRepository.deleteAllById(createdProcLogSns);
            for (Long rawSn : createdRawSns) {
                labelRepository.deleteAll(labelRepository.findAllByRawSn(rawSn));
                srcRepository.deleteAll(srcRepository.findByRawSnOrderByFrameNoAsc(rawSn));
                videoMetaRepository.deleteAll(videoMetaRepository.findByRawSn(rawSn));
                exportRepository.deleteAll(exportRepository.findByDataRawSn(rawSn));
                rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn))
                        .forEach(rawDataStatusRepository::delete);
                videoRepository.findById(rawSn).ifPresent(videoRepository::delete);
            }
            labelMasterRepository.deleteAllById(createdLabelIds);
        });
        createdRawSns.clear();
        createdLabelIds.clear();
        createdReportSns.clear();
        createdProcLogSns.clear();
    }

    // ────────────────────────────── 테스트 ──────────────────────────────

    @Test
    @DisplayName("신고_상태_영상은_export_가_수행되지_않는다")
    void approvalExportBlockedWhileDeidentReported() {
        // given — 프레임 이미지 fixture 를 갖춘 영상에 비식별 누락 신고가 걸린 상태.
        long rawSn = seedVideoWithFrameFiles();
        markDeident(rawSn, "F");
        List<Object> published = new ArrayList<>();

        // when — 승인 경로(러너 동기 호출).
        syncRunner(published).runApprovalAsync(rawSn);

        // then — 산출 폴더도, 산출 레코드도, 완료 이벤트(=통지 트리거)도 없다.
        assertThat(videoRoot(rawSn)).doesNotExist();
        assertThat(exportRepository.findByDataRawSn(rawSn)).isEmpty();
        assertThat(published).noneMatch(DatasetExportCompletedEvent.class::isInstance);
    }

    @Test
    @DisplayName("신고_상태_영상은_재export_도_수행되지_않고_통지도_나가지_않는다")
    void reExportAndNotifyBlockedWhileDeidentReported() {
        // given — 승인 후 수정 경로(디바운서 flush 가 호출하는 진입점).
        long rawSn = seedVideoWithFrameFiles();
        markDeident(rawSn, "F");
        AtomicBoolean notified = new AtomicBoolean(false);

        // when
        syncRunner(new ArrayList<>())
                .runReExportThenNotify(rawSn, true, () -> notified.set(true));

        // then — 파일 미산출 + 통지 콜백 미실행(관제가 새 버전 폴더를 픽업하지 못한다).
        assertThat(videoRoot(rawSn)).doesNotExist();
        assertThat(exportRepository.findByDataRawSn(rawSn)).isEmpty();
        assertThat(notified).isFalse();
    }

    @Test
    @DisplayName("신고_상태_영상은_실패_회수_배치에서도_재산출되지_않는다")
    void failureRecoveryBlockedWhileDeidentReported() {
        // given — 직전 export 가 FAILED 로 남아 회수 대상인 영상에 신고가 걸린 상태.
        long rawSn = seedVideoWithFrameFiles();
        seedAgedFailedExport(rawSn);
        markDeident(rawSn, "F");
        long rowsBefore = exportRepository.countByDataRawSn(rawSn);

        // when — 회수 잡(러너는 동기 인스턴스로 주입해 즉시 산출까지 태운다).
        DatasetExportFailureRecoverer recoverer = newRecoverer(syncRunner(new ArrayList<>()));
        recoverer.recover();

        // then — 회수는 트리거되더라도 산출은 게이트에서 멈춘다(새 버전 폴더/레코드 없음).
        assertThat(videoRoot(rawSn)).doesNotExist();
        assertThat(exportRepository.countByDataRawSn(rawSn)).isEqualTo(rowsBefore);
    }

    // ───────────────── H1 — export 진행 중 신고 접수(TOCTOU 창) ─────────────────

    @Test
    @DisplayName("export_중_신고가_접수되면_산출물이_기록되지_않고_통지도_나가지_않는다")
    void reportArrivingMidExportDiscardsOutputAndWithholdsNotify() {
        // given — 진입부 게이트를 통과하는 정상('Y') 영상. 프레임 쓰기가 시작된 뒤(ORIGINAL 벌 완료 시점)
        //   비식별 누락 신고가 커밋되는 인터리빙을 훅으로 결정적으로 재현한다(sleep 없음).
        long rawSn = seedVideoWithFrameFiles();
        markDeident(rawSn, "Y");
        List<Object> published = new ArrayList<>();

        // when — 승인 경로 러너로 산출(게이트 통과 → 쓰기 → 그 사이 'F' 전이 → 마감 재판정).
        syncRunner(published, serviceReportingDuringWrite(rawSn, ExportKind.ORIGINAL))
                .runApprovalAsync(rawSn);

        // then — ①산출 레코드가 남지 않는다(SUCCEEDED/PARTIAL 마감 없음, FAILED 오분류도 없음)
        assertThat(exportRepository.findByDataRawSn(rawSn)).isEmpty();
        // ②이번 실행이 만든 v1 폴더(=신고된 프레임이 복사된 곳)가 삭제된다
        assertThat(videoRoot(rawSn).resolve("v1")).doesNotExist();
        // ③완료 이벤트(=관제 통지 트리거)가 발행되지 않는다
        assertThat(published).noneMatch(DatasetExportCompletedEvent.class::isInstance);
    }

    @Test
    @DisplayName("export_중_신고시_이번_실행이_만든_버전폴더가_삭제되고_이전_버전은_보존된다")
    void blockedMidExportPurgesOnlyCurrentVersionFolder() {
        // given — v1 이 정상 산출된 승인 영상(전 버전 보존 정책의 보호 대상).
        long rawSn = seedVideoWithFrameFiles();
        markDeident(rawSn, "Y");
        exportService.export(rawSn, true);
        Path v1Orgnl = versionDir(rawSn, 1, ExportKind.ORIGINAL);
        Path v1Deid = versionDir(rawSn, 1, ExportKind.DEIDENTIFIED);
        assertThat(v1Orgnl).isDirectory();
        long v1Files = countFiles(v1Orgnl) + countFiles(v1Deid);
        assertThat(v1Files).isPositive();
        Path originalVideo = Path.of(videoRepository.findById(rawSn).orElseThrow().getRawFilePathNm());
        Path deidFrame = DEID_ROOT.resolve("frames/deid/" + rawSn + "/frame-0.jpg");

        // when — v2 산출 도중 신고가 접수된다.
        syncRunner(new ArrayList<>(), serviceReportingDuringWrite(rawSn, ExportKind.ORIGINAL))
                .runApprovalAsync(rawSn);

        // then — v2 만 사라진다.
        assertThat(videoRoot(rawSn).resolve("v2")).doesNotExist();
        // and — 이전 버전(v1) 파일은 그대로 보존된다(전 버전 보존 정책).
        assertThat(v1Orgnl).isDirectory();
        assertThat(v1Deid).isDirectory();
        assertThat(countFiles(v1Orgnl) + countFiles(v1Deid)).isEqualTo(v1Files);
        // and — 원본 영상·비식별 프레임 원천도 불변.
        assertThat(originalVideo).isRegularFile();
        assertThat(deidFrame).isRegularFile();
        // and — v1 산출 레코드(SUCCEEDED)만 남고 v2 행은 생기지 않는다.
        assertThat(exportRepository.findByDataRawSn(rawSn))
                .singleElement()
                .extracting(LsDatasetExport::getExportSttsCd)
                .isEqualTo(LsDatasetExport.STATUS_SUCCEEDED);
    }

    // ───────────────── M1 — 차단분 복구(resolve 재트리거) ─────────────────

    @Test
    @DisplayName("신고_상태에서_승인된_영상도_resolve_후_export_와_통지가_정상_수행된다")
    void approvedVideoUnderReportRecoversAfterResolve() {
        // given — 검수 승인(APPROVED)된 영상에 비식별 누락 신고가 열려 있다. 승인 시점 export 는
        //   게이트에 차단되어 <b>레코드도 남지 않으므로</b> 실패 회수기(FAILED 행만 스캔) 대상이 아니다.
        long rawSn = seedVideoWithFrameFiles();
        seedApprovedStatus(rawSn);
        Long rprtSn = seedOpenReportWithDeidentArtifact(rawSn);
        markDeident(rawSn, "F");
        List<Object> published = new ArrayList<>();
        syncRunner(published).runApprovalAsync(rawSn);
        assertThat(exportRepository.findByDataRawSn(rawSn)).isEmpty();
        assertThat(published).noneMatch(DatasetExportCompletedEvent.class::isInstance);

        // when — 외부 솔루션 수동 비식별 완료 후 resolve(실서비스 배선: AFTER_COMMIT → 브릿지 → @Async 러너).
        deidentReportService.resolveManually(rprtSn, reviewer());

        // then — 보류됐던 산출이 수행되고(2벌 + SUCCEEDED) 관제 완료 통지도 재개된다.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(versionDir(rawSn, 1, ExportKind.ORIGINAL)).isDirectory();
            assertThat(versionDir(rawSn, 1, ExportKind.DEIDENTIFIED)).isDirectory();
            assertThat(exportRepository.findByDataRawSn(rawSn))
                    .singleElement()
                    .extracting(LsDatasetExport::getExportSttsCd)
                    .isEqualTo(LsDatasetExport.STATUS_SUCCEEDED);
            verify(notifyService).sendCompleted(rawSn);
        });
    }

    // ───────────────── M2 — 신고 구간에서 재시도 예산 미소진 ─────────────────

    @Test
    @DisplayName("회수기가_신고_구간에_재시도_상한을_소진하지_않는다")
    void recoveryPreservesRetryBudgetUnderReport() {
        // given — 회수 대상(FAILED + 유예 경과) 영상에 신고가 걸린 상태.
        long rawSn = seedVideoWithFrameFiles();
        seedAgedFailedExport(rawSn);
        markDeident(rawSn, "F");
        int attemptsBefore = retryAttempts(rawSn);

        // when — 회수 tick 을 3회(=max-attempts 기본값) 돌린다.
        DatasetExportFailureRecoverer recoverer = newRecoverer(syncRunner(new ArrayList<>()));
        for (int i = 0; i < 3; i++) {
            recoverer.recover();
        }

        // then — 클레임 자체를 하지 않아 시도 이력(RTY_NMTM)이 늘지 않는다(상한 소진 없음).
        assertThat(retryAttempts(rawSn)).isEqualTo(attemptsBefore);
        assertThat(videoRoot(rawSn)).doesNotExist();

        // and — 신고가 해소되면 같은 회수기가 정상적으로 집어 재산출한다(예산이 남아 있음).
        markDeident(rawSn, "Y");
        recoverer.recover();
        assertThat(retryAttempts(rawSn)).isEqualTo(attemptsBefore + 1);
        assertThat(versionDir(rawSn, 2, ExportKind.DEIDENTIFIED)).isDirectory();
    }

    @Test
    @DisplayName("resolve_후에는_export_재생성이_정상_동작한다")
    void exportResumesAfterResolve() {
        // given — 신고 구간에는 산출이 막혀 아무것도 생기지 않는다.
        long rawSn = seedVideoWithFrameFiles();
        markDeident(rawSn, "F");
        assertThatThrownBy(() -> exportService.export(rawSn, true)).isNotNull();
        assertThat(videoRoot(rawSn)).doesNotExist();

        // when — resolve 로 'F'→'Y' 복원(보존된 기존 라벨을 그대로 사용).
        markDeident(rawSn, "Y");
        exportService.export(rawSn, true);

        // then — 게이트가 열려 v1 이 정상 산출된다.
        assertThat(versionDir(rawSn, 1, ExportKind.ORIGINAL)).isDirectory();
        assertThat(versionDir(rawSn, 1, ExportKind.DEIDENTIFIED)).isDirectory();
        assertThat(exportRepository.findByDataRawSn(rawSn))
                .singleElement()
                .extracting(LsDatasetExport::getExportSttsCd)
                .isEqualTo(LsDatasetExport.STATUS_SUCCEEDED);
    }

    @Test
    @DisplayName("deIdntfYn_이_Y_인_일반영상의_export_는_영향받지_않는다")
    void normalVideoExportUnaffected() {
        // given — 신고 없는 일반 영상(비식별 완료).
        long rawSn = seedVideoWithFrameFiles();
        markDeident(rawSn, "Y");

        // when
        exportService.export(rawSn, true);

        // then — 기존 동작 그대로 2벌 산출 + SUCCEEDED.
        assertThat(versionDir(rawSn, 1, ExportKind.ORIGINAL)).isDirectory();
        assertThat(versionDir(rawSn, 1, ExportKind.DEIDENTIFIED)).isDirectory();
        assertThat(exportRepository.findByDataRawSn(rawSn))
                .singleElement()
                .extracting(LsDatasetExport::getExportSttsCd)
                .isEqualTo(LsDatasetExport.STATUS_SUCCEEDED);
    }

    @Test
    @DisplayName("신고_상태에서_촬영환경_수정시_v_n_plus_1_폴더가_생성되지_않는다")
    void environmentMetaEditDuringReportProducesNoNewVersion() {
        // given — ① 승인된 영상이 v1 까지 정상 산출된 뒤 ② 비식별 누락 신고가 접수됐다(적대검증 재현 경로).
        long rawSn = seedVideoWithFrameFiles();
        markDeident(rawSn, "Y");
        seedApprovedStatus(rawSn);
        exportService.export(rawSn, true);
        assertThat(versionDir(rawSn, 1, ExportKind.DEIDENTIFIED)).isDirectory();
        markDeident(rawSn, "F");

        // when — 실서비스 배선 그대로: 촬영환경 수정 → TaskModifiedEvent(regen=true) → 디바운스 flush
        //   → AsyncDatasetExportRunner(@Async) → export.
        environmentMetaService.update(rawSn,
                new EnvironmentMetaUpdateRequest("맑음", "DAY", "SUMMER"), reviewer());
        debouncer.flushExpiredWindows();

        // then — 재산출이 시작되지 않아 v2 폴더가 끝내 생기지 않고, 통지도 나가지 않는다.
        //   (비동기 경로라 "생기지 않음"을 관측 구간 동안 유지되는지로 확인한다.)
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(videoRoot(rawSn).resolve("v2")).doesNotExist();
                    assertThat(exportRepository.countByDataRawSn(rawSn)).isEqualTo(1L);
                });
        verify(notifyService, never()).sendModified(any(), any(), any(), anyBoolean());

        // and — resolve 로 복원하면 같은 경로가 정상 동작한다(게이트가 영구 차단이 아님을 같은 하네스로 증명).
        markDeident(rawSn, "Y");
        environmentMetaService.update(rawSn,
                new EnvironmentMetaUpdateRequest("흐림", "NGT", "WINTER"), reviewer());
        debouncer.flushExpiredWindows();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(versionDir(rawSn, 2, ExportKind.DEIDENTIFIED)).isDirectory());
    }

    // ────────────────────────────── 픽스처 ──────────────────────────────

    /** M2 — 회수기(신고 게이트 주입) 인스턴스. 러너만 동기 인스턴스로 바꿔 즉시 산출까지 태운다. */
    private DatasetExportFailureRecoverer newRecoverer(AsyncDatasetExportRunner runner) {
        return new DatasetExportFailureRecoverer(exportRepository, runner, txService, deidentReportGate);
    }

    /** 해당 영상의 누적 재시도 이력(RTY_NMTM) 합 — 클레임 시점에만 증가한다. */
    private int retryAttempts(long rawSn) {
        return exportRepository.findByDataRawSn(rawSn).stream()
                .mapToInt(LsDatasetExport::getRtyNmtm)
                .sum();
    }

    private long countFiles(Path dir) {
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new IllegalStateException("산출 파일 열거 실패", e);
        }
    }

    /**
     * H1 재현 훅 — 지정한 {@link ExportKind} 벌의 프레임 쓰기가 <b>끝난 직후</b> 비식별 누락 신고
     * ({@code DE_IDNTF_YN='F'})를 별도 트랜잭션으로 커밋한다.
     *
     * <p>즉 "진입부 게이트 통과 → 파일 기록 시작 → 신고 접수 → 마감" 인터리빙을 결정적으로 만든다
     * (하드코딩 sleep 없음). 훅 외의 산출 경로는 실제 빈 그대로다.
     */
    private DatasetExportService serviceReportingDuringWrite(long rawSn, ExportKind flipAfter) {
        DatasetExportWriter hooked = org.mockito.Mockito.spy(writer);
        org.mockito.Mockito.doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (invocation.getArgument(2) == flipAfter) {
                markDeident(rawSn, "F");
            }
            return result;
        }).when(hooked).write(org.mockito.ArgumentMatchers.anyLong(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
        return new DatasetExportService(txService, hooked, pathResolver, metrics, deidentReportGate);
    }

    /** 동기 실행용 러너 — {@code @Async} 프록시 없이 직접 생성한다(러너가 최종 호출하는 경로는 동일). */
    private AsyncDatasetExportRunner syncRunner(List<Object> published) {
        return syncRunner(published, exportService);
    }

    private AsyncDatasetExportRunner syncRunner(List<Object> published, DatasetExportService service) {
        ApplicationEventPublisher publisher = new ApplicationEventPublisher() {
            @Override
            public void publishEvent(Object event) {
                published.add(event);
            }

            @Override
            public void publishEvent(ApplicationEvent event) {
                published.add(event);
            }
        };
        return new AsyncDatasetExportRunner(service, publisher);
    }

    /**
     * M1 픽스처 — OPEN 신고 1건 + {@code resolveManually} 의 비식별 산출물 검증
     * ({@code verifyDeidentArtifact})을 통과할 최신 SUCCEEDED 처리이력·실파일을 함께 시딩한다.
     *
     * <p>파일 mtime 을 <b>신고 저장 이후에 명시적으로</b> 신고시각+1초로 옮겨 "신고 후 외부 솔루션이
     * 제자리 교체" 조건을 만족시킨다. 구 픽스처는 파일을 신고보다 먼저 쓰고도(=mtime 이 신고 이전)
     * 통과했는데, 이는 mtime 비교가 클럭스큐를 <b>감산</b> 방향으로 60초 관용했기 때문이다
     * (B-ISSUE-42 로 제거 — 신고 이전 산출물은 재비식별 증거가 아니다).
     */
    private Long seedOpenReportWithDeidentArtifact(long rawSn) {
        // 산출물 무결성 판정(DeidentArtifactIntegrity)이 정규파일+크기 하한+컨테이너 시그니처를 보므로
        // 더미 2바이트가 아니라 실제 최소 mp4 픽스처를 쓴다.
        Path deidVideo = kr.co.cudo.authoring.support.TestVideoFixtures.writeTinyMp4(
                DEID_ROOT.resolve("videos/" + rawSn + "/deidentified.mp4"));
        Long rprtSn = txTemplate.execute(s -> {
            LsDeidentProcLog procLog = LsDeidentProcLog.request(
                    rawSn, "req-" + rawSn, "orgnl.mp4", "tester");
            procLog.succeed(deidVideo.toString());
            createdProcLogSns.add(procLogRepository.save(procLog).getProcLogSn());
            Long sn = reportRepository.save(
                    LsDeidentReport.createReport(rawSn, 1L, "얼굴 노출")).getRprtSn();
            createdReportSns.add(sn);
            return sn;
        });
        LocalDateTime reportTime = reportRepository.findById(rprtSn).orElseThrow().getReportDt();
        try {
            Files.setLastModifiedTime(deidVideo, FileTime.from(
                    reportTime.plusSeconds(1).atZone(ZoneId.systemDefault()).toInstant()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rprtSn;
    }

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
                java.time.Instant.now().plusSeconds(600));
    }

    private void markDeident(long rawSn, String code) {
        txTemplate.executeWithoutResult(s ->
                videoRepository.findById(rawSn).orElseThrow().markDeidentified(code));
    }

    private void seedApprovedStatus(long rawSn) {
        txTemplate.executeWithoutResult(s -> {
            LsRawDataStatus status = LsRawDataStatus.initial(rawSn);
            status.transitionTo(LsRawDataStatus.STTS_APPROVED);
            rawDataStatusRepository.save(status);
        });
    }

    /** 회수 대상 앵커 — FAILED + 유예 경과(REG_DT 과거). */
    private void seedAgedFailedExport(long rawSn) {
        txTemplate.executeWithoutResult(s -> {
            LsDatasetExport e = LsDatasetExport.create(rawSn, 1, videoRoot(rawSn).resolve("v1").toString());
            e.markFailed();
            setField(e, "regDt", LocalDateTime.now().minusHours(1));
            exportRepository.save(e);
        });
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field f = LsDatasetExport.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 활성 메타 + 프레임(원본/비식별 실파일) + BBOX 라벨을 시딩한다. */
    private long seedVideoWithFrameFiles() {
        Long labelId = txTemplate.execute(s -> labelMasterRepository.save(
                LsLabel.create("car-" + System.nanoTime(), "#ff0000", "BBOX", 1, "tester")).getLabelId());
        createdLabelIds.add(labelId);
        long seededRawSn = txTemplate.execute(s -> {
            LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                    "clip-" + System.nanoTime(), "cctv-1", "EVT", "GOV",
                    LsDataRaw.PRVC_TYPE_PRVC, seedOriginalVideo().toString(), LocalDateTime.now(), 60));
            Long rawSn = raw.getRawSn();
            videoMetaRepository.save(LsDatasetVideoMeta.builder()
                    .rawSn(rawSn)
                    .snpshtHash("h-" + rawSn)
                    .activeYn(LsDatasetVideoMeta.ACTIVE_YES)
                    .vdoWdth(1920)
                    .vdoHgt(1080)
                    .rawFilePathNm(raw.getRawFilePathNm())
                    .regDt(LocalDateTime.now())
                    .build());
            for (int i = 0; i < FRAME_COUNT; i++) {
                String rawRel = "frames/raw/" + rawSn + "/frame-" + i + ".jpg";
                String deidRel = "frames/deid/" + rawSn + "/frame-" + i + ".jpg";
                writeDummyImage(RAW_ROOT.resolve(rawRel));
                writeDummyImage(DEID_ROOT.resolve(deidRel));
                LsDataSrc frame = LsDataSrc.create(rawSn, i, rawRel, LocalDateTime.now());
                frame.attachDeidPath(deidRel);
                frame = srcRepository.save(frame);
                labelRepository.save(LsDataLbl.createManual(
                        frame.getSrcSn(), "BBOX", labelId, "car", "[[1,2],[3,4]]", null));
            }
            return rawSn;
        });
        createdRawSns.add(seededRawSn);
        return seededRawSn;
    }

    private static Path seedOriginalVideo() {
        Path video = VIDEO_DIR.resolve("clip-" + System.nanoTime() + ".mp4");
        try {
            Files.createDirectories(VIDEO_DIR);
            Files.write(video, VIDEO_BYTES);
        } catch (IOException e) {
            throw new IllegalStateException("더미 원본 영상 생성 실패", e);
        }
        return video;
    }

    private static void writeDummyImage(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        } catch (IOException e) {
            throw new IllegalStateException("더미 이미지 생성 실패", e);
        }
    }

    /** 산출 루트 — 원본 영상 디렉터리 하위 {@code {rawSn}/}(Phase 5A co-locate). */
    private Path videoRoot(long rawSn) {
        return VIDEO_DIR.resolve(String.valueOf(rawSn));
    }

    private Path versionDir(long rawSn, int version, ExportKind kind) {
        return videoRoot(rawSn).resolve("v" + version).resolve(kind.segment());
    }
}
