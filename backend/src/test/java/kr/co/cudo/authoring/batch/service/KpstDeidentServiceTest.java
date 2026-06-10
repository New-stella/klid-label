package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.client.KpstDeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.KpstProgressResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import kr.co.cudo.authoring.common.client.dto.KpstUploadResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 / UC018 — KpstDeidentService 오케스트레이션 단위 테스트.
 *
 * <p>위탁(submit) / 폴링(pollOne) 분기 검증. 상태 전이의 실제 영속은 {@link KpstDeidentTxService}
 * 로 위임되므로 본 테스트는 위탁 호출·다운로드·tx 위임 호출을 검증한다.
 */
class KpstDeidentServiceTest {

    @TempDir
    Path tmp;

    private KpstDeidentifyClient kpstClient;
    private VideoRepository videoRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private KpstDeidentTxService txService;
    private KpstDeidentService service;
    private Path baseDeid;

    @BeforeEach
    void setUp() throws Exception {
        kpstClient = mock(KpstDeidentifyClient.class);
        videoRepository = mock(VideoRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        txService = mock(KpstDeidentTxService.class);

        service = new KpstDeidentService(kpstClient, videoRepository, procLogRepository, txService);
        baseDeid = tmp.resolve("deid");
        setField(service, "deidPath", baseDeid.toString());
        setField(service, "creatorId", "authoring");
        setField(service, "reqUserId", "authoring");
        setField(service, "exportPathBase", "/share/Deid-data/export/");
        setField(service, "pollMaxAttempts", 3);
        setField(service, "pollTimeoutMinutes", 60L);
        invoke(service, "initBasePath");

        when(procLogRepository.save(any(LsDeidentProcLog.class))).thenAnswer(inv -> {
            LsDeidentProcLog p = inv.getArgument(0);
            setField(p, "procLogSn", 1L);
            return p;
        });
    }

    private LsDataRaw newRaw() {
        Path rawFile = tmp.resolve("clip.mp4");
        try {
            java.nio.file.Files.writeString(rawFile, "video");
        } catch (Exception ignored) { /* test fixture */ }
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, rawFile.toString(), null, 60);
        setField(raw, "rawSn", 9001L);
        return raw;
    }

    private LsDeidentProcLog submittedProcLog() {
        LsDeidentProcLog procLog = LsDeidentProcLog.request(9001L, null, "/raw/clip.mp4", "batch");
        setField(procLog, "procLogSn", 1L);
        procLog.markKpstSubmitted(101L, null);
        return procLog;
    }

    private KpstProgressResponse progressWith(int procState, Long dsId) {
        KpstProgressResponse.DsStatus ds = new KpstProgressResponse.DsStatus(
                dsId, "clip.mp4", procState, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.PrjStatus prj = new KpstProgressResponse.PrjStatus(
                101L, "raw9001", 100.0, 1, List.of(ds));
        return new KpstProgressResponse("success",
                new KpstProgressResponse.Data(1, List.of(prj)));
    }

    // ────────────────────────── 위탁 ──────────────────────────

    @Test
    @DisplayName("KPST_submit이_upload_project위탁하고_WAITING과_prjId를_기록한다")
    void submitUploadsAndRecordsWaiting() {
        LsDataRaw raw = newRaw();
        when(kpstClient.upload(any(), any())).thenReturn(new KpstUploadResponse(
                "success", new KpstUploadResponse.Data("/share/upload/raw9001/", List.of("clip.mp4"), 1)));
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(new KpstProjectResponse("success", 101L));

        LsDeidentProcLog procLog = service.submit(raw);

        verify(kpstClient).upload(any(), any());
        verify(kpstClient).createProject(any(KpstProjectRequest.class));
        assertThat(procLog.getKpstPrjId()).isEqualTo(101L);
        assertThat(procLog.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_WAITING);
        // DE_IDNTF_YN 미전이(완료 대기)
        assertThat(raw.getDeIdntfYn()).isNotEqualTo("Y");
    }

    @Test
    @DisplayName("위탁_실패시_F로_마킹하고_예외전파한다")
    void submitFailureMarksF() {
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(kpstClient.upload(any(), any()))
                .thenThrow(new CustomException(ErrorCode.EXTERNAL_API_ERROR, "boom"));

        assertThatThrownBy(() -> service.submit(raw)).isInstanceOf(CustomException.class);
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
    }

    // ────────────────────────── 폴링 완료 ──────────────────────────

    @Test
    @DisplayName("폴링이_state2_완료감지시_download하고_다운로드와_Y전이를_원자적으로_위임한다")
    void pollCompletedDownloadsAndDelegates() throws Exception {
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        Path saved = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4");
        java.nio.file.Files.createDirectories(saved.getParent());
        java.nio.file.Files.writeString(saved, "MASKED");
        when(kpstClient.download(eq(202L), any(Path.class), any(Path.class))).thenReturn(saved);

        service.pollOne(procLog);

        verify(kpstClient).download(eq(202L), any(Path.class), any(Path.class));
        // 다운로드 완료 + Y 전이가 단일 REQUIRES_NEW 호출로 원자화 (stuck 창 제거).
        verify(txService).finishDownloadAndComplete(
                eq(9001L), eq(1L), eq(202L), eq(saved.toString()));
        verify(txService, never()).finishDownload(any(), any(), any());
    }

    @Test
    @DisplayName("다운로드_결과가_0바이트면_F로_처리하고_Y전이하지_않는다")
    void pollDownloadZeroByteMarksF() throws Exception {
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        Path saved = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4");
        java.nio.file.Files.createDirectories(saved.getParent());
        java.nio.file.Files.createFile(saved); // 0바이트
        when(kpstClient.download(eq(202L), any(Path.class), any(Path.class))).thenReturn(saved);
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        service.pollOne(procLog);

        // 불완전 산출물 — Y 전이/원자완료 호출 금지, 'F' 처리.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
    }

    @Test
    @DisplayName("다중_데이터셋_부분완료면_전체완료로_오판하지_않고_진행중으로_판정한다")
    void pollMultiDatasetPartialIsInProgress() {
        LsDeidentProcLog procLog = submittedProcLog();
        // ds[0]=완료(2), ds[1]=진행중(1) → 부분완료. 전체 AND 검증 시 진행중 처리.
        KpstProgressResponse.DsStatus done = new KpstProgressResponse.DsStatus(
                202L, "a.mp4", 2, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.DsStatus running = new KpstProgressResponse.DsStatus(
                203L, "b.mp4", 1, 40.0, 5400, "t0", "t1");
        KpstProgressResponse.PrjStatus prj = new KpstProgressResponse.PrjStatus(
                101L, "raw9001", 70.0, 2, List.of(done, running));
        KpstProgressResponse progress = new KpstProgressResponse("success",
                new KpstProgressResponse.Data(1, List.of(prj)));
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L))).thenReturn(progress);

        service.pollOne(procLog);

        // 부분완료를 완료로 오판하면 안 됨 — 다운로드/완료 금지, 진행중 위임.
        verify(kpstClient, never()).download(any(), any(), any());
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
        verify(txService).recordPollingProgress(eq(1L), any());
        verify(txService).markTimeoutIfExpired(eq(1L), eq(3), eq(60L));
    }

    // ────────────────────────── 폴링 진행중 ──────────────────────────

    @Test
    @DisplayName("폴링이_진행중이면_recordPollingProgress로_시도증가_위임_download_미수행")
    void pollInProgressDelegatesPolling() {
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(1, 202L)); // procState=1 (진행중)

        service.pollOne(procLog);

        verify(kpstClient, never()).download(any(), any(), any());
        verify(txService).recordPollingProgress(eq(1L), eq(202L));
        verify(txService).markTimeoutIfExpired(eq(1L), eq(3), eq(60L));
        verify(txService, never()).completeDeidentification(any(), any());
    }

    @Test
    @DisplayName("데이터셋_미생성이면_시도증가후_타임아웃검사만_위임한다")
    void pollNoDatasetDelegatesPollingAndTimeout() {
        LsDeidentProcLog procLog = submittedProcLog();
        KpstProgressResponse empty = new KpstProgressResponse("success",
                new KpstProgressResponse.Data(0, List.of()));
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L))).thenReturn(empty);

        service.pollOne(procLog);

        verify(kpstClient, never()).download(any(), any(), any());
        verify(txService).recordPollingProgress(eq(1L), isNull());
        verify(txService).markTimeoutIfExpired(eq(1L), eq(3), eq(60L));
    }

    @Test
    @DisplayName("prjId_미상이면_타임아웃검사만_수행하고_외부호출하지_않는다")
    void pollMissingPrjIdOnlyTimeout() {
        LsDeidentProcLog procLog = LsDeidentProcLog.request(9001L, null, "/raw/clip.mp4", "batch");
        setField(procLog, "procLogSn", 1L);
        // prjId 미기록(위탁 미완)

        service.pollOne(procLog);

        verify(kpstClient, never()).retrieveProgress(any(), any());
        verify(txService).markTimeoutIfExpired(eq(1L), eq(3), eq(60L));
    }

    // ────────────────────────── 완료 위임 ──────────────────────────

    @Test
    @DisplayName("completeDeidentification은_txService에_위임한다")
    void completeDelegatesToTx() {
        service.completeDeidentification(9001L, "/deid/9001/deidentified.mp4");
        verify(txService).completeDeidentification(9001L, "/deid/9001/deidentified.mp4");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
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

    private static void invoke(Object target, String method) {
        try {
            Method m = target.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
