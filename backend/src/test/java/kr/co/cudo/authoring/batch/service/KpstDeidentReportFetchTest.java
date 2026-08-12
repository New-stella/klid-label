package kr.co.cudo.authoring.batch.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.batch.dto.KpstDeidentReportSummary;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.client.KpstDeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.KpstProgressResponse;
import kr.co.cudo.authoring.common.client.dto.KpstReportResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.support.TestVideoFixtures;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R14 — 폴링 완료 시점의 <b>처리 결과 리포트</b>({@code GET /retrieve_report}) 조회·인계 검증.
 *
 * <p>핵심 계약은 하나다: <b>리포트 조회가 어떤 이유로 실패해도 비식별 완료 전이는 그대로 진행</b>한다.
 * 리포트는 부가 정보이며, 그것 때문에 영상이 {@code DE_IDNTF_YN='Y'} 로 못 가면 파이프라인 전체가
 * 외부 부가 API 하나에 인질로 잡힌다({@code resolveMaskingOptions} 의 fail-safe 와 같은 취지).
 */
class KpstDeidentReportFetchTest {

    @TempDir
    Path tmp;

    private static final long RAW_SN = 9001L;
    private static final long PROC_LOG_SN = 1L;
    private static final long PRJ_ID = 101L;
    private static final long DATASET_ID = 1270L;

    private KpstDeidentifyClient kpstClient;
    private KpstDeidentTxService txService;
    private KpstDeidentService service;
    private Path baseDeid;
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        kpstClient = mock(KpstDeidentifyClient.class);
        txService = mock(KpstDeidentTxService.class);
        VideoRepository videoRepository = mock(VideoRepository.class);
        LsDeidentProcLogRepository procLogRepository = mock(LsDeidentProcLogRepository.class);
        BatchTransitionService batchTransitionService = mock(BatchTransitionService.class);
        KpstSubmitOutcomeRecorder outcomeRecorder = mock(KpstSubmitOutcomeRecorder.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getInt(ConfigKeys.KPST_DEID_MASKING_TYPE)).thenReturn(0);
        when(systemConfigService.getInt(ConfigKeys.KPST_DEID_DB_SAVE)).thenReturn(0);
        when(systemConfigService.getDouble(ConfigKeys.KPST_DEID_MASKING_RANGE)).thenReturn(1.0);

        baseDeid = tmp.resolve("deid");
        service = new KpstDeidentService(kpstClient, videoRepository, procLogRepository, txService,
                ArtifactRootTestSupport.labelingRoot(tmp.resolve("labeling"), baseDeid),
                batchTransitionService, outcomeRecorder,
                reactor.core.scheduler.Schedulers.immediate(), systemConfigService);
        setField(service, "deidPath", baseDeid.toString());
        setField(service, "creatorId", "authoring");
        setField(service, "reqUserId", "authoring");
        setField(service, "pollMaxAttempts", 3);
        setField(service, "pollTimeoutMinutes", 60L);
        setField(service, "verifySourceExists", true);
        setField(service, "resultRecheckDelayMs", 0L);
        setField(service, "submitAckGraceSec", 180L);
        invoke(service, "initBasePath");

        logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger().addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        serviceLogger().detachAppender(logCapture);
    }

    private static ch.qos.logback.classic.Logger serviceLogger() {
        return (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KpstDeidentService.class);
    }

    private boolean warnLogged(String fragment) {
        return logCapture.list.stream()
                .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains(fragment));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** 완료(procState=2) 진행조회 응답 — fileName 은 실측 계약대로 원본 입력파일 경로다. */
    private KpstProgressResponse completedProgress() {
        KpstProgressResponse.DsStatus ds = new KpstProgressResponse.DsStatus(
                DATASET_ID, "/nas/raw/clip.mp4", 2, 100.0, 5400, "t0", "t1");
        return new KpstProgressResponse("success", new KpstProgressResponse.Data(1,
                List.of(new KpstProgressResponse.PrjStatus(PRJ_ID, "raw9001", 100.0, 1, List.of(ds)))));
    }

    /** 진행중(procState=1) 진행조회 응답. */
    private KpstProgressResponse inProgressProgress() {
        KpstProgressResponse.DsStatus ds = new KpstProgressResponse.DsStatus(
                DATASET_ID, "/nas/raw/clip.mp4", 1, 42.0, 5400, "t0", null);
        return new KpstProgressResponse("success", new KpstProgressResponse.Data(1,
                List.of(new KpstProgressResponse.PrjStatus(PRJ_ID, "raw9001", 42.0, 1, List.of(ds)))));
    }

    private KpstReportResponse reportWith(Long dsId) {
        KpstReportResponse.DsStatus ds = new KpstReportResponse.DsStatus(
                dsId, "/nas/raw/clip.mp4", 12L, 3L, 5400L,
                "2026-08-11 10:00:00", "2026-08-11 10:05:30");
        return new KpstReportResponse("success", new KpstReportResponse.Data(1,
                List.of(new KpstReportResponse.PrjStatus(PRJ_ID, 100.0, List.of(ds)))));
    }

    /** KPST 가 export_path 에 직접 쓴 산출물({@code {stem}-mask{ext}})을 실제로 만들어 둔다. */
    private void writeMaskArtifact() {
        Path dir = baseDeid.resolve("videos").resolve(String.valueOf(RAW_SN));
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        TestVideoFixtures.writeTinyMp4(dir.resolve("clip-mask.mp4"));
    }

    private LsDeidentProcLog submittedProcLog() {
        LsDeidentProcLog procLog = LsDeidentProcLog.request(RAW_SN, null, "/nas/raw/clip.mp4", "batch");
        setField(procLog, "procLogSn", PROC_LOG_SN);
        procLog.markKpstSubmitted(PRJ_ID, DATASET_ID);
        return procLog;
    }

    // ── cases ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("리포트_조회가_실패해도_비식별_완료_전이는_그대로_진행된다")
    void reportFailureDoesNotBlockCompletion() {
        // given — 진행조회는 완료(2), 산출물도 정상. 리포트만 외부 오류.
        writeMaskArtifact();
        when(kpstClient.retrieveProgress(anyString(), eq(PRJ_ID))).thenReturn(completedProgress());
        when(kpstClient.retrieveReport(anyString(), eq(PRJ_ID)))
                .thenThrow(new CustomException(ErrorCode.EXTERNAL_API_ERROR, "리포트 조회 실패"));

        // when
        service.pollOne(submittedProcLog());

        // then — 완료 전이는 호출되고, 리포트만 null 로 넘어간다.
        verify(txService).finishDownloadAndComplete(
                eq(RAW_SN), eq(PROC_LOG_SN), eq(DATASET_ID), anyString(), isNull());
        verify(txService, never()).failPolling(anyLong(), anyLong());
        assertThat(warnLogged("deident report")).isTrue();
    }

    @Test
    @DisplayName("리포트의_같은_데이터셋_행을_찾아_완료_전이에_함께_넘긴다")
    void reportSummaryIsPassedToCompletion() {
        writeMaskArtifact();
        when(kpstClient.retrieveProgress(anyString(), eq(PRJ_ID))).thenReturn(completedProgress());
        when(kpstClient.retrieveReport(anyString(), eq(PRJ_ID))).thenReturn(reportWith(DATASET_ID));

        service.pollOne(submittedProcLog());

        ArgumentCaptor<KpstDeidentReportSummary> captor =
                ArgumentCaptor.forClass(KpstDeidentReportSummary.class);
        verify(txService).finishDownloadAndComplete(
                eq(RAW_SN), eq(PROC_LOG_SN), eq(DATASET_ID), anyString(), captor.capture());
        KpstDeidentReportSummary summary = captor.getValue();
        assertThat(summary).isNotNull();
        assertThat(summary.faceCount()).isEqualTo(12L);
        assertThat(summary.lpCount()).isEqualTo(3L);
        assertThat(summary.totalFrame()).isEqualTo(5400L);
        assertThat(summary.startedAt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 10, 0, 0));
        assertThat(summary.endedAt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 10, 5, 30));
    }

    @Test
    @DisplayName("리포트에_매칭되는_데이터셋이_없으면_리포트없이_완료_전이한다")
    void unmatchedDatasetIsNotGuessed() {
        // given — 다른 데이터셋만 담긴 리포트. 남의 집계값을 우리 행에 적재하면 안 된다.
        writeMaskArtifact();
        when(kpstClient.retrieveProgress(anyString(), eq(PRJ_ID))).thenReturn(completedProgress());
        when(kpstClient.retrieveReport(anyString(), eq(PRJ_ID))).thenReturn(reportWith(999999L));

        service.pollOne(submittedProcLog());

        verify(txService).finishDownloadAndComplete(
                eq(RAW_SN), eq(PROC_LOG_SN), eq(DATASET_ID), anyString(), isNull());
        assertThat(warnLogged("deident report")).isTrue();
    }

    @Test
    @DisplayName("완료된_프로젝트가_없는_빈_리포트도_완료_전이를_막지_않는다")
    void emptyReportDoesNotBlockCompletion() {
        // given — 규격: "완료된 데이터셋이 없는 프로젝트는 결과에서 제외된다".
        writeMaskArtifact();
        when(kpstClient.retrieveProgress(anyString(), eq(PRJ_ID))).thenReturn(completedProgress());
        when(kpstClient.retrieveReport(anyString(), eq(PRJ_ID)))
                .thenReturn(new KpstReportResponse("success", new KpstReportResponse.Data(0, List.of())));

        service.pollOne(submittedProcLog());

        verify(txService).finishDownloadAndComplete(
                eq(RAW_SN), eq(PROC_LOG_SN), eq(DATASET_ID), anyString(), isNull());
    }

    @Test
    @DisplayName("진행중인_건에서는_리포트를_조회하지_않는다")
    void noReportCallWhileInProgress() {
        when(kpstClient.retrieveProgress(anyString(), eq(PRJ_ID))).thenReturn(inProgressProgress());

        service.pollOne(submittedProcLog());

        verify(kpstClient, never()).retrieveReport(anyString(), any());
        verify(txService).recordPollingProgress(eq(PROC_LOG_SN), eq(DATASET_ID));
    }

    @Test
    @DisplayName("리포트_조회_실패_로그에_외부_경로나_파일명을_남기지_않는다")
    void reportFailureLogHasNoPathLeak() {
        writeMaskArtifact();
        when(kpstClient.retrieveProgress(anyString(), eq(PRJ_ID))).thenReturn(completedProgress());
        when(kpstClient.retrieveReport(anyString(), eq(PRJ_ID)))
                .thenThrow(new CustomException(ErrorCode.EXTERNAL_API_ERROR, "boom"));

        service.pollOne(submittedProcLog());

        // CWE-117/359 — 식별자(rawSn/prjId)와 예외 클래스명만 남긴다.
        assertThat(logCapture.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains("/nas/raw") || m.contains("clip.mp4")))
                .isTrue();
    }

    // ── reflection helpers ──────────────────────────────────────────────────

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void invoke(Object target, String name) {
        try {
            Method m = target.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
