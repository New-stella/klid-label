package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.client.KpstDeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.KpstProgressResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 3 / UC018 — KpstDeidentService 오케스트레이션 단위 테스트.
 *
 * <p>위탁(submit) / 폴링(pollOne) 분기 검증. 상태 전이의 실제 영속은 {@link KpstDeidentTxService}
 * 로 위임되므로 본 테스트는 위탁 호출·회수 경로 산출·tx 위임 호출을 검증한다.
 *
 * <p>shared-mount no-copy 모델: KPST 가 결과를 우리 비식별 저장소({@code {base}/videos/{rawSn}/})
 * 에 직접 쓰며, 완료 시 진행조회 응답의 {@code dsStatus.fileName} 을 그대로 회수 경로로 쓴다.
 * 복사·GET /download 는 없다(클라이언트가 KPST 출력 파일을 옮기지 않는다).
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
        return progressWithFileName(procState, dsId, "clip.mp4");
    }

    private KpstProgressResponse progressWithFileName(int procState, Long dsId, String fileName) {
        KpstProgressResponse.DsStatus ds = new KpstProgressResponse.DsStatus(
                dsId, fileName, procState, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.PrjStatus prj = new KpstProgressResponse.PrjStatus(
                101L, "raw9001", 100.0, 1, List.of(ds));
        return new KpstProgressResponse("success",
                new KpstProgressResponse.Data(1, List.of(prj)));
    }

    /** no-copy: KPST 가 export_path 에 직접 쓴 결과를 시뮬레이션하는 회수 경로(>0바이트). */
    private Path deidPathFor(String fileName) {
        return baseDeid.resolve("videos").resolve("9001").resolve(fileName);
    }

    private void writeDeidResult(String fileName, String content) throws Exception {
        Path p = deidPathFor(fileName);
        java.nio.file.Files.createDirectories(p.getParent());
        java.nio.file.Files.writeString(p, content);
    }

    // ────────────────────────── 위탁 ──────────────────────────

    @Test
    @DisplayName("submit이_upload_미호출하고_createProject만_호출한다")
    void submitCallsCreateProjectOnly() {
        // given — shared-mount 모델: 업로드 없이 공유 경로 참조로 createProject 만 호출
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(new KpstProjectResponse("success", 101L));

        // when
        LsDeidentProcLog procLog = service.submit(raw);

        // then — createProject 1회만, 그 외 kpstClient 상호작용 없음(upload/download 미호출)
        verify(kpstClient).createProject(any(KpstProjectRequest.class));
        verifyNoMoreInteractions(kpstClient);
        assertThat(procLog.getKpstPrjId()).isEqualTo(101L);
        assertThat(procLog.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_WAITING);
        // DE_IDNTF_YN 미전이(완료 대기)
        assertThat(raw.getDeIdntfYn()).isNotEqualTo("Y");
    }

    @Test
    @DisplayName("input_path는_원본_부모디렉터리에_끝슬래시_포함이다")
    void submitInputPathIsParentWithTrailingSlash() {
        // given
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(new KpstProjectResponse("success", 101L));

        // when
        service.submit(raw);

        // then — input_path = 원본 부모디렉터리 + 끝 슬래시(규격 §22.3.3)
        ArgumentCaptor<KpstProjectRequest> captor = ArgumentCaptor.forClass(KpstProjectRequest.class);
        verify(kpstClient).createProject(captor.capture());
        assertThat(captor.getValue().inputPath()).isEqualTo(tmp.toString() + "/");
    }

    @Test
    @DisplayName("submit_export_path는_우리base_videos_rawSn_슬래시이고_디렉터리가_사전생성된다")
    void submitExportPathIsOurBaseAndDirCreated() {
        // given
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(new KpstProjectResponse("success", 101L));

        // when
        service.submit(raw);

        // then — export_path = {STORAGE_DEIDENTIFIED_PATH}/videos/{rawSn}/ (KPST 결과 WRITE 대상)
        ArgumentCaptor<KpstProjectRequest> captor = ArgumentCaptor.forClass(KpstProjectRequest.class);
        verify(kpstClient).createProject(captor.capture());
        Path exportDir = baseDeid.resolve("videos").resolve("9001");
        assertThat(captor.getValue().exportPath()).isEqualTo(exportDir + "/");
        // KPST 쓰기 대상 디렉터리가 사전 생성되었는지
        assertThat(exportDir).exists();
    }

    @Test
    @DisplayName("files는_원본_파일명만_포함한다")
    void submitFilesContainsOriginalFileNameOnly() {
        // given
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(new KpstProjectResponse("success", 101L));

        // when
        service.submit(raw);

        // then — files 는 디렉터리 없는 원본 파일명만
        ArgumentCaptor<KpstProjectRequest> captor = ArgumentCaptor.forClass(KpstProjectRequest.class);
        verify(kpstClient).createProject(captor.capture());
        assertThat(captor.getValue().files()).containsExactly("clip.mp4");
        assertThat(captor.getValue().projectName()).isEqualTo("raw9001");
    }

    @Test
    @DisplayName("위탁실패시_procLog_F_raw_F_마킹은_기존과_동일하다")
    void submitFailureMarksF() {
        // given — createProject(외부 호출) 실패
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenThrow(new CustomException(ErrorCode.EXTERNAL_API_ERROR, "boom"));

        // when / then — 기존과 동일: 'F' 마킹 후 예외 전파
        assertThatThrownBy(() -> service.submit(raw)).isInstanceOf(CustomException.class);
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
    }

    @Test
    @DisplayName("원본경로_부모디렉터리가_null이면_F마킹하고_예외전파_createProject미호출")
    void submitNullParentRejected() {
        // given — 부모 디렉터리가 없는 비정상 경로(파일명만) → 경계 방어(CWE-22/입력검증)
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "clip.mp4", null, 60);
        setField(raw, "rawSn", 9001L);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        // when / then — createProject 호출 전에 거부, F 마킹 + 예외 전파
        assertThatThrownBy(() -> service.submit(raw)).isInstanceOf(CustomException.class);
        verify(kpstClient, never()).createProject(any());
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
    }

    // ────────────────────────── 폴링 완료 (no-copy 회수) ──────────────────────────

    @Test
    @DisplayName("downloadResult가_GET_download_미호출_복사미수행_응답fileName으로_경로를_구성한다")
    void completionUsesResponseFileNameNoCopy() throws Exception {
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L)); // fileName "clip.mp4"
        // KPST 가 export_path 에 직접 쓴 결과(복사 없음 — 우리는 경로만 구성).
        writeDeidResult("clip.mp4", "MASKED");
        Path expected = deidPathFor("clip.mp4");

        service.pollOne(procLog);

        // GET /download·복사 없음 — kpstClient 는 retrieveProgress 외 상호작용이 없어야 한다.
        verify(kpstClient).retrieveProgress(eq("authoring"), eq(101L));
        verifyNoMoreInteractions(kpstClient);
        // 회수 경로 = {base}/videos/{rawSn}/{응답 fileName} 으로 Y 전이 원자 위임.
        verify(txService).finishDownloadAndComplete(
                eq(9001L), eq(1L), eq(202L), eq(expected.toString()));
    }

    @Test
    @DisplayName("완료시_악성fileName이면_예외전파없이_failPolling으로_terminal종결된다")
    void completionRejectsMaliciousFileName() {
        // 응답 fileName 은 외부값(CWE-22) — plain filename 만 허용. 위반 시 Y 전이 차단 +
        // 예외 전파 없이 terminal 종결(HIGH-1: 무한 재폴링·raw PENDING stuck 방지).
        List<String> evils = List.of("../escape.mp4", "a/b.mp4", "/etc/passwd", "..\\x.mp4", "..");
        for (String evil : evils) {
            LsDeidentProcLog procLog = submittedProcLog();
            when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                    .thenReturn(progressWithFileName(2, 202L, evil));
            // sanitize 실패가 pollOne 밖으로 전파되면 안 됨(잡이 swallow 하면 시도증가 없이 영구 재폴링).
            service.pollOne(procLog);
        }
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
        verify(txService, org.mockito.Mockito.times(evils.size())).failPolling(eq(1L), eq(9001L));
    }

    @Test
    @DisplayName("완료시_불량fileName이면_failPolling으로_terminal종결되고_재폴링안된다")
    void pollCompletionBadFileNameTerminatesNonRedeident() {
        // HIGH-1: 완료(procState=2) 인데 fileName 이 불량(상위참조) → sanitize 가 던지는 예외를
        // finishDownloadAndComplete 실패와 동일하게 terminal 처리(failPolling)하여 무한 재폴링을 끊는다.
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWithFileName(2, 202L, "../escape.mp4"));

        service.pollOne(procLog); // 예외 전파 없이 정상 반환

        verify(txService).failPolling(eq(1L), eq(9001L));
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
        // 시도증가/타임아웃 카운터를 거치지 않고 즉시 종결(완료 분기이므로).
        verify(txService, never()).markTimeoutIfExpired(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("완료시_null_fileName이면_terminal종결된다")
    void pollCompletionNullFileNameTerminates() {
        // null/빈 fileName 은 침해 없이도 가능한 정상 엣지 — KPST 완료 응답에 fileName 누락 시.
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWithFileName(2, 202L, null));

        service.pollOne(procLog);

        verify(txService).failPolling(eq(1L), eq(9001L));
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("REDEIDENT_완료시_불량fileName이면_failRedeidentCompletion으로_락해제된다")
    void pollCompletionBadFileNameRedeidentReleasesLock() {
        // HIGH-1(REDEIDENT): 위탁 시 잡은 작업락이 sanitize 실패로 영구 미해제되면 안 됨 →
        // failRedeidentCompletion 으로 'F' + 락 해제 종결.
        LsDeidentProcLog procLog = submittedProcLog();
        procLog.markRedeident();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWithFileName(2, 202L, ""));

        service.pollOne(procLog);

        verify(txService).failRedeidentCompletion(eq(1L), eq(9001L), any());
        verify(txService, never()).failPolling(any(), any());
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("sanitizeFileName_NUL바이트면_CustomException_원문미노출")
    void sanitizeFileNameNulByteRejectedNoLeak() throws Exception {
        // HIGH-2: NUL바이트는 Paths.get 이 InvalidPathException(메시지에 입력 원문 포함)을 던진다 →
        // CustomException(INVALID_INPUT) 으로 정규화하고 fileName 원문을 메시지에 노출하지 않는다(CWE-209).
        String evil = "x" + "\u0000" + ".mp4";
        Method m = KpstDeidentService.class.getDeclaredMethod("sanitizeFileName", String.class);
        m.setAccessible(true);
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> {
            try {
                m.invoke(service, evil);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        // 원문(NUL 포함) 미노출.
        assertThat(thrown.getMessage()).doesNotContain(evil);
        assertThat(thrown.getMessage()).doesNotContain(" ");
    }

    @Test
    @DisplayName("isUsableDeidFile_심볼릭링크는_정규파일로_통과하지않는다")
    void isUsableDeidFileRejectsSymlink() throws Exception {
        // LOW-1: KPST 출력 디렉터리 내 심링크가 정규파일로 통과하지 않도록 NOFOLLOW_LINKS 적용(공급망 방어심도).
        Path target = tmp.resolve("real.mp4");
        java.nio.file.Files.writeString(target, "MASKED");
        Path link = tmp.resolve("link.mp4");
        try {
            java.nio.file.Files.createSymbolicLink(link, target);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "심링크 미지원 환경 — skip");
        }
        Method m = KpstDeidentService.class.getDeclaredMethod("isUsableDeidFile", String.class);
        m.setAccessible(true);
        boolean usable = (boolean) m.invoke(service, link.toString());
        assertThat(usable).isFalse();
    }

    @Test
    @DisplayName("완료감지_산출물_미존재면_F처리되어_Y전이안된다")
    void pollMissingDeidFileMarksF() {
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        // KPST 가 결과를 쓰지 않은 상황 — 회수 경로 파일 미존재.
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        service.pollOne(procLog);

        // 불완전 산출물 — Y 전이/원자완료 호출 금지, 'F' 처리.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
    }

    @Test
    @DisplayName("완료감지_산출물_0바이트면_F처리되어_Y전이안된다")
    void pollZeroByteDeidFileMarksF() throws Exception {
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        Path saved = deidPathFor("clip.mp4");
        java.nio.file.Files.createDirectories(saved.getParent());
        java.nio.file.Files.createFile(saved); // 0바이트
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        service.pollOne(procLog);

        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
    }

    @Test
    @DisplayName("REDEIDENT_완료시_불완전산출물_미존재면_failRedeidentCompletion으로_락해제된다")
    void pollMissingDeidFileRedeidentReleasesLock() {
        // HIGH-1(REDEIDENT): 완료(procState=2) + 정상 fileName 이나 KPST 가 export_path 에 0바이트/미기록한
        // 불완전 산출물 → 비-REDEIDENT 처럼 failPolling 으로 끝내면 작업락이 영구 미해제(409 영구 차단).
        // REDEIDENT 는 failRedeidentCompletion(F + 락 해제 + terminal)으로 종결해야 한다.
        LsDeidentProcLog procLog = submittedProcLog();
        procLog.markRedeident();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L)); // fileName "clip.mp4" 정상이나 파일 미존재
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        service.pollOne(procLog);

        verify(txService).failRedeidentCompletion(eq(1L), eq(9001L), any());
        verify(txService, never()).failPolling(any(), any());
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("REDEIDENT_완료시_0바이트산출물이면_failRedeidentCompletion으로_락해제된다")
    void pollZeroByteDeidFileRedeidentReleasesLock() throws Exception {
        // HIGH-1(REDEIDENT): 0바이트 산출물도 불완전 산출물 — 락 해제 포함 종결.
        LsDeidentProcLog procLog = submittedProcLog();
        procLog.markRedeident();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        Path saved = deidPathFor("clip.mp4");
        java.nio.file.Files.createDirectories(saved.getParent());
        java.nio.file.Files.createFile(saved); // 0바이트
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        service.pollOne(procLog);

        verify(txService).failRedeidentCompletion(eq(1L), eq(9001L), any());
        verify(txService, never()).failPolling(any(), any());
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
    }

    @Test
    @DisplayName("비_REDEIDENT_불완전산출물은_기존대로_failPolling이다")
    void pollMissingDeidFileNonRedeidentStillFailPolling() {
        // 회귀(HIGH-1): REQ_KIND null(배치 경로)은 락이 없어 기존 failPolling 유지 — REDEIDENT 분기 미적용.
        LsDeidentProcLog procLog = submittedProcLog(); // REQ_KIND null
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        service.pollOne(procLog);

        verify(txService).failPolling(eq(1L), eq(9001L));
        verify(txService, never()).failRedeidentCompletion(any(), any(), any());
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
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

        // 부분완료를 완료로 오판하면 안 됨 — 완료 금지, 진행중 위임.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
        verify(txService).recordPollingProgress(eq(1L), any());
        verify(txService).markTimeoutIfExpired(eq(1L), eq(3), eq(60L));
    }

    @Test
    @DisplayName("미시작_null_procState_데이터셋이_있으면_완료로_오판하지_않고_진행중으로_판정한다")
    void pollNullProcStateIsInProgress() {
        LsDeidentProcLog procLog = submittedProcLog();
        // ds[0]=완료(2), ds[1]=미시작(null) → null 가드가 없으면 NPE 또는 오판. 진행중이어야 한다.
        KpstProgressResponse.DsStatus done = new KpstProgressResponse.DsStatus(
                202L, "a.mp4", 2, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.DsStatus notStarted = new KpstProgressResponse.DsStatus(
                203L, "b.mp4", null, 0.0, null, "None", "None");
        KpstProgressResponse.PrjStatus prj = new KpstProgressResponse.PrjStatus(
                101L, "raw9001", 50.0, 2, List.of(done, notStarted));
        KpstProgressResponse progress = new KpstProgressResponse("success",
                new KpstProgressResponse.Data(1, List.of(prj)));
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L))).thenReturn(progress);

        service.pollOne(procLog);

        // null(미시작)을 완료로 오판/NPE 없이 진행중 위임.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
        verify(txService).recordPollingProgress(eq(1L), any());
    }

    @Test
    @DisplayName("모든_데이터셋이_procState2면_완료감지하여_첫_데이터셋_fileName으로_회수한다")
    void pollAllStateTwoCompletes() throws Exception {
        LsDeidentProcLog procLog = submittedProcLog();
        KpstProgressResponse.DsStatus a = new KpstProgressResponse.DsStatus(
                202L, "a.mp4", 2, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.DsStatus b = new KpstProgressResponse.DsStatus(
                203L, "b.mp4", 2, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.PrjStatus prj = new KpstProgressResponse.PrjStatus(
                101L, "raw9001", 100.0, 2, List.of(a, b));
        KpstProgressResponse progress = new KpstProgressResponse("success",
                new KpstProgressResponse.Data(1, List.of(prj)));
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L))).thenReturn(progress);
        writeDeidResult("a.mp4", "MASKED"); // 첫 데이터셋 fileName
        Path expected = deidPathFor("a.mp4");

        service.pollOne(procLog);

        verify(txService).finishDownloadAndComplete(
                eq(9001L), eq(1L), eq(202L), eq(expected.toString()));
    }

    // ────────────────────────── 폴링 터미널-실패 fast-fail ──────────────────────────

    @Test
    @DisplayName("procState99_에러sentinel이면_타임아웃대기없이_즉시_failPolling으로_F처리한다")
    void pollProcState99FastFailsImmediately() {
        // given — 실측 확인된 에러 sentinel 99 (progressRate 0, totalFrame 정상)
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(99, 202L));

        // when
        service.pollOne(procLog);

        // then — 타임아웃 카운터를 기다리지 않고 즉시 종결. 완료 금지.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
        verify(txService, never()).markTimeoutIfExpired(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(txService, never()).recordPollingProgress(any(), any());
    }

    @Test
    @DisplayName("procState5_정지상태면_즉시_failPolling으로_F처리한다")
    void pollProcState5FastFailsImmediately() {
        // given — PDF §2.5.2 표상 정지(실측 미확인) 5
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(5, 202L));

        // when
        service.pollOne(procLog);

        // then
        verify(txService).failPolling(eq(1L), eq(9001L));
        verify(txService, never()).markTimeoutIfExpired(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("procState4_실행중지상태면_즉시_failPolling으로_F처리한다")
    void pollProcState4FastFailsImmediately() {
        // given — PDF §2.5.2 표상 실행중지(실측 미확인) 4
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(4, 202L));

        // when
        service.pollOne(procLog);

        // then — 타임아웃 대기 없이 즉시 종결. 완료/시도증가 금지.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
        verify(txService, never()).markTimeoutIfExpired(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(txService, never()).recordPollingProgress(any(), any());
    }

    @Test
    @DisplayName("procState6_실행정지상태면_즉시_failPolling으로_F처리한다")
    void pollProcState6FastFailsImmediately() {
        // given — PDF §2.5.2 표상 실행정지(실측 미확인) 6
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(6, 202L));

        // when
        service.pollOne(procLog);

        // then — 타임아웃 대기 없이 즉시 종결. 완료/시도증가 금지.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
        verify(txService, never()).markTimeoutIfExpired(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(txService, never()).recordPollingProgress(any(), any());
    }

    @Test
    @DisplayName("다중데이터셋_2와99가_섞이면_완료로_오판하지않고_즉시_F처리한다")
    void pollMultiDatasetWithFailureFastFails() {
        // given — ds[0]=완료(2), ds[1]=실패(99). 하나라도 실패면 실패가 우선.
        LsDeidentProcLog procLog = submittedProcLog();
        KpstProgressResponse.DsStatus done = new KpstProgressResponse.DsStatus(
                202L, "a.mp4", 2, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.DsStatus failed = new KpstProgressResponse.DsStatus(
                203L, "b.mp4", 99, 0.0, 5400, "t0", "t1");
        KpstProgressResponse.PrjStatus prj = new KpstProgressResponse.PrjStatus(
                101L, "raw9001", 50.0, 2, List.of(done, failed));
        KpstProgressResponse progress = new KpstProgressResponse("success",
                new KpstProgressResponse.Data(1, List.of(prj)));
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L))).thenReturn(progress);

        // when
        service.pollOne(procLog);

        // then — 완료 오판 금지, 즉시 F.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
    }

    @Test
    @DisplayName("REDEIDENT경로의_터미널실패는_락해제포함_failRedeidentCompletion으로_종결한다")
    void pollRedeidentTerminalFailReleasesLock() {
        // given — REDEIDENT procLog 가 procState 99 → 락 영구잠금 방지 위해 failRedeidentCompletion 종결
        LsDeidentProcLog procLog = submittedProcLog();
        procLog.markRedeident();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(99, 202L));

        // when
        service.pollOne(procLog);

        // then — 락 해제 포함 종결(failRedeidentCompletion), failPolling 미사용.
        verify(txService).failRedeidentCompletion(eq(1L), eq(9001L), any());
        verify(txService, never()).failPolling(any(), any());
        verify(txService, never()).markTimeoutIfExpired(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    // ────────────────────────── 폴링 진행중 ──────────────────────────

    @Test
    @DisplayName("폴링이_진행중이면_recordPollingProgress로_시도증가_위임_완료_미수행")
    void pollInProgressDelegatesPolling() {
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(1, 202L)); // procState=1 (진행중)

        service.pollOne(procLog);

        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
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

        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any());
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

    @Test
    @DisplayName("M1_completeDeidentification_검증실패시_비REDEIDENT면_markRawDeidentFailed로_F를_별도커밋한다")
    void completeFailureCommitsFForBatch() {
        // 콜백/배치 경로(REQ_KIND null) — 파일 무효로 tx 가 예외. F-마킹이 별도 커밋되어야 한다(M-1).
        LsDeidentProcLog batchLog = submittedProcLog(); // REQ_KIND null
        when(procLogRepository.findAllByDataRawSnOrderByReqDtDesc(9001L)).thenReturn(List.of(batchLog));
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.INVALID_INPUT, "invalid"))
                .when(txService).completeDeidentification(eq(9001L), any());

        assertThatThrownBy(() -> service.completeDeidentification(9001L, "/deid/x.mp4"))
                .isInstanceOf(CustomException.class);

        verify(txService).markRawDeidentFailed(9001L);
    }

    @Test
    @DisplayName("M1_completeDeidentification_검증실패라도_REDEIDENT면_markRawDeidentFailed_미호출_폴링경로종결위임")
    void completeFailureSkipsFForRedeident() {
        LsDeidentProcLog redeidentLog = submittedProcLog();
        redeidentLog.markRedeident();
        when(procLogRepository.findAllByDataRawSnOrderByReqDtDesc(9001L)).thenReturn(List.of(redeidentLog));
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.INVALID_INPUT, "invalid"))
                .when(txService).completeDeidentification(eq(9001L), any());

        assertThatThrownBy(() -> service.completeDeidentification(9001L, "/deid/x.mp4"))
                .isInstanceOf(CustomException.class);

        // REDEIDENT 는 폴링 경로(failRedeidentCompletion)가 종결 — 여기선 F-마킹 보정하지 않는다.
        verify(txService, never()).markRawDeidentFailed(any());
    }

    @Test
    @DisplayName("REDEIDENT_attach실패_무한재폴링안되고_terminal도달_failRedeidentCompletion위임_재throw안함")
    void pollRedeidentAttachFailureTerminatesNotRepoll() throws Exception {
        // 완료 감지 후 finishDownloadAndComplete(REDEIDENT) 가 attach 실패로 예외 → 메인 롤백.
        // 폴링은 failRedeidentCompletion(별도 커밋)으로 terminal 종결하고 예외를 재throw 하지 않는다
        // (재throw 시 procLog 가 WAITING/POLLING 으로 남아 무한 재폴링).
        LsDeidentProcLog procLog = submittedProcLog();
        procLog.markRedeident();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        writeDeidResult("clip.mp4", "MASKED");
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.INVALID_INPUT, "해상도 불일치"))
                .when(txService).finishDownloadAndComplete(eq(9001L), eq(1L), eq(202L), any());

        // 재throw 하면 안 됨 — terminal 종결 후 정상 반환.
        service.pollOne(procLog);

        verify(txService).failRedeidentCompletion(eq(1L), eq(9001L), any());
    }

    @Test
    @DisplayName("비REDEIDENT_완료후처리실패는_여전히_예외전파_failRedeidentCompletion미호출_회귀")
    void pollBatchCompletionFailureStillThrows() throws Exception {
        LsDeidentProcLog procLog = submittedProcLog(); // REQ_KIND null
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        writeDeidResult("clip.mp4", "MASKED");
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.INVALID_INPUT, "boom"))
                .when(txService).finishDownloadAndComplete(eq(9001L), eq(1L), eq(202L), any());

        // 기존 BATCH 경로는 현행 유지 — 예외 전파(잡이 건별 격리), REDEIDENT 종결 핸들러 미호출.
        assertThatThrownBy(() -> service.pollOne(procLog)).isInstanceOf(CustomException.class);
        verify(txService, never()).failRedeidentCompletion(any(), any(), any());
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
