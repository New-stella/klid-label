package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.step.DeidentFrameAttacher;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 / UC018 — KpstDeidentTxService 상태 전이 단위 테스트.
 *
 * <p>완료 공유 로직(Y/MARKING_READY/락해제/신고해소/알림) · 타임아웃('F') · 다운로드 완료 · 폴링 진행 전이.
 */
class KpstDeidentTxServiceTest {

    @TempDir
    Path tmp;

    private VideoRepository videoRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private DeidentReportService deidentReportService;
    private NotificationService notificationService;
    private WorkLockService workLockService;
    private DeidentFrameAttacher deidentFrameAttacher;
    private KpstDeidentTxService tx;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        deidentReportService = mock(DeidentReportService.class);
        notificationService = mock(NotificationService.class);
        workLockService = mock(WorkLockService.class);
        deidentFrameAttacher = mock(DeidentFrameAttacher.class);
        tx = new KpstDeidentTxService(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService, deidentFrameAttacher);
    }

    private LsDataRaw newRaw() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/raw/clip.mp4", null, 60);
        setField(raw, "rawSn", 9001L);
        return raw;
    }

    private LsDeidentProcLog submitted() {
        LsDeidentProcLog p = LsDeidentProcLog.request(9001L, null, "/raw/clip.mp4", "batch");
        setField(p, "procLogSn", 1L);
        p.markKpstSubmitted(101L, null);
        return p;
    }

    /** 실재하는 비식별 파일(>0바이트) — completeDeidentification 의 파일 검증 통과용. */
    private String realDeidFile() {
        try {
            Path f = tmp.resolve("deid-9001.mp4");
            Files.writeString(f, "MASKED");
            return f.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("완료처리시_Y전이_MARKING_READY_락해제_신고해소_알림이_수행된다")
    void completeTransitionsAll() {
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(true);

        tx.completeDeidentification(9001L, realDeidFile());

        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(raw.getDataSttsCd()).isEqualTo("MARKING_READY");
        verify(workLockService).releaseRaw(eq(9001L), eq("batch"), eq("DEIDENT_SUCCEEDED"));
        verify(deidentReportService).resolveOpenReports(9001L);
        verify(notificationService).notifyReviewersOnLockRelease(raw);
    }

    @Test
    @DisplayName("비식별완료시_OPEN신고가_RESOLVED되고_알림이_발송된다_잠금없으면_release미호출")
    void completeResolvesReportsNoLock() {
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(false);

        tx.completeDeidentification(9001L, realDeidFile());

        verify(deidentReportService).resolveOpenReports(9001L);
        verify(notificationService).notifyReviewersOnLockRelease(raw);
        verify(workLockService, never()).releaseRaw(any(), any(), any());
    }

    @Test
    @DisplayName("완료처리시_비식별파일이_존재하지_않으면_F로_처리하고_Y전이하지_않는다")
    void completeRejectsMissingFile() {
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        String missing = tmp.resolve("nope.mp4").toString();

        assertThatThrownBy(() -> tx.completeDeidentification(9001L, missing))
                .isInstanceOf(CustomException.class);

        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService, never()).releaseRaw(any(), any(), any());
        verify(notificationService, never()).notifyReviewersOnLockRelease(any());
    }

    @Test
    @DisplayName("완료처리시_비식별파일이_0바이트면_F로_처리하고_Y전이하지_않는다")
    void completeRejectsZeroByteFile() throws Exception {
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path empty = tmp.resolve("empty.mp4");
        Files.createFile(empty);

        assertThatThrownBy(() -> tx.completeDeidentification(9001L, empty.toString()))
                .isInstanceOf(CustomException.class);

        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        verify(notificationService, never()).notifyReviewersOnLockRelease(any());
    }

    @Test
    @DisplayName("다운로드완료와_Y전이가_단일트랜잭션으로_원자적으로_수행된다")
    void finishDownloadAndCompleteIsAtomic() {
        LsDeidentProcLog p = submitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        String deid = realDeidFile();

        tx.finishDownloadAndComplete(9001L, 1L, 202L, deid);

        // procLog: DOWNLOADED/SUCCEEDED/datasetId + raw: Y/MARKING_READY 가 한 호출로 모두 반영.
        assertThat(p.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_DOWNLOADED);
        assertThat(p.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(p.getKpstDatasetId()).isEqualTo(202L);
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(raw.getDataSttsCd()).isEqualTo("MARKING_READY");
    }

    @Test
    @DisplayName("타임아웃_F마킹시_POLL_STTS가_POLL_FAILED_종료값으로_전이되어_재폴링대상에서_제외된다")
    void timeoutTransitionsPollStatusToTerminal() {
        LsDeidentProcLog p = submitted();
        setField(p, "pollAttemptCnt", 3);
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.markTimeoutIfExpired(1L, 3, 60L);

        assertThat(p.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
    }

    @Test
    @DisplayName("다운로드완료_위임시_DOWNLOADED_SUCCEEDED_datasetId가_기록된다")
    void finishDownloadRecords() {
        LsDeidentProcLog p = submitted();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));

        tx.finishDownload(1L, 202L, "/deid/9001/deidentified.mp4");

        assertThat(p.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_DOWNLOADED);
        assertThat(p.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(p.getKpstDatasetId()).isEqualTo(202L);
        assertThat(p.getDeIdntfFilePathNm()).isEqualTo("/deid/9001/deidentified.mp4");
    }

    @Test
    @DisplayName("폴링진행_위임시_POLLING_시도증가_datasetId보충")
    void recordPollingProgress() {
        LsDeidentProcLog p = submitted();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));

        tx.recordPollingProgress(1L, 202L);

        assertThat(p.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_POLLING);
        assertThat(p.getPollAttemptCnt()).isEqualTo(1);
        assertThat(p.getKpstDatasetId()).isEqualTo(202L);
    }

    @Test
    @DisplayName("폴링_시도횟수_초과시_F로_마킹하고_true반환")
    void timeoutByAttempts() {
        LsDeidentProcLog p = submitted();
        setField(p, "pollAttemptCnt", 3);
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        boolean timedOut = tx.markTimeoutIfExpired(1L, 3, 60L);

        assertThat(timedOut).isTrue();
        assertThat(p.getProcSttsCd()).isEqualTo(LsDeidentProcLog.FAILED);
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
    }

    @Test
    @DisplayName("위탁후_경과시간_초과시_F로_마킹한다")
    void timeoutByElapsed() {
        LsDeidentProcLog p = submitted();
        setField(p, "reqDt", LocalDateTime.now().minusHours(2));
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        boolean timedOut = tx.markTimeoutIfExpired(1L, 999, 60L);

        assertThat(timedOut).isTrue();
        assertThat(p.getProcSttsCd()).isEqualTo(LsDeidentProcLog.FAILED);
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
    }

    @Test
    @DisplayName("타임아웃_미도달이면_F전이없이_false반환")
    void notTimedOut() {
        LsDeidentProcLog p = submitted();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));

        boolean timedOut = tx.markTimeoutIfExpired(1L, 999, 9999L);

        assertThat(timedOut).isFalse();
        assertThat(p.getProcSttsCd()).isEqualTo(LsDeidentProcLog.REQUESTED);
    }

    // ────────────────────────── REDEIDENT 분기 (Phase 3 — APPROVED 강등 금지) ──────────────────────────

    private LsDeidentProcLog redeidentSubmitted() {
        LsDeidentProcLog p = submitted();
        p.markRedeident();
        return p;
    }

    @Test
    @DisplayName("REDEIDENT완료_after_APPROVED상태_유지된다_markMarkingReady_미호출")
    void redeidentKeepsApproved() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        String deid = realDeidFile();

        tx.finishDownloadAndComplete(9001L, 1L, 202L, deid);

        // APPROVED 유지 — LS_DATA_RAW 배치 상태(DATA_STTS_CD)는 MARKING_READY 로 강등되지 않는다.
        assertThat(raw.getDataSttsCd()).isNotEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        // 마킹 단계 재진입을 만드는 부수효과(신고 해소/마킹 알림) 미호출.
        verify(deidentReportService, never()).resolveOpenReports(any());
        verify(notificationService, never()).notifyReviewersOnLockRelease(any());
    }

    @Test
    @DisplayName("REDEIDENT완료_de_ident_yn_Y로_갱신된다")
    void redeidentMarksDeidentY() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("REDEIDENT완료시_DeidentFrameAttacher가_호출된다")
    void redeidentCallsAttacher() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        verify(deidentFrameAttacher).attachDeidentFrames(eq(raw), any());
    }

    @Test
    @DisplayName("REDEIDENT완료시_prvc_UNKNOWN이_PRVC로_정정된다")
    void redeidentCorrectsUnknownPrvc() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        setField(raw, "prvcTypeCd", LsDataRaw.PRVC_TYPE_UNKNOWN);
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        assertThat(raw.getPrvcTypeCd()).isEqualTo(LsDataRaw.PRVC_TYPE_PRVC);
        assertThat(raw.getPrvcYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("REDEIDENT_해상도불일치_완료시_예외전파되고_Y전이_PRVC정정_락해제_미수행_롤백")
    void redeidentResolutionMismatchRollsBack() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        // Attacher 가 해상도 불일치로 예외 → 전파 → 메인 완료 트랜잭션 전체 롤백. 종결(락해제/FAILED)은
        // 폴링 오케스트레이터가 failRedeidentCompletion 으로 별도 커밋한다(아래 별도 테스트로 검증).
        when(deidentFrameAttacher.attachDeidentFrames(eq(raw), any()))
                .thenThrow(new CustomException(ErrorCode.INVALID_INPUT, "해상도 불일치"));

        assertThatThrownBy(() -> tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile()))
                .isInstanceOf(CustomException.class);

        // attach 가 먼저 실패 — Y 마킹/PRVC 정정 미수행. 메인 트랜잭션 내 락 해제(성공 경로)도 미수행.
        assertThat(raw.getDeIdntfYn()).isNotEqualTo("Y");
        verify(workLockService, never()).releaseRaw(eq(9001L), any(), eq("REDEIDENT_SUCCEEDED"));
    }

    @Test
    @DisplayName("REDEIDENT_attach실패시_failRedeidentCompletion으로_락해제되고_procLog_FAILED_재요청가능")
    void redeidentAttachFailureReleasesLockAndMarksFailed() {
        LsDeidentProcLog p = redeidentSubmitted(); // POLL_STTS=WAITING
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(true);

        // 폴링 오케스트레이터가 메인 롤백 후 호출하는 종결 핸들러.
        tx.failRedeidentCompletion(1L, 9001L, "CustomException");

        // procLog: terminal(FAILED + POLL_FAILED) — 재폴링 대상에서 제외.
        assertThat(p.getProcSttsCd()).isEqualTo(LsDeidentProcLog.FAILED);
        assertThat(p.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
        // de_ident_yn=F (Y 미전이), 락 해제(재요청 가능).
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService).releaseRaw(eq(9001L), eq("batch"), eq("REDEIDENT_FAILED"));
    }

    @Test
    @DisplayName("REDEIDENT_attach실패_종결은_락없으면_release미호출하지만_procLog_FAILED_terminal도달")
    void redeidentAttachFailureNoLockStillTerminal() {
        LsDeidentProcLog p = redeidentSubmitted();
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(false);

        tx.failRedeidentCompletion(1L, 9001L, "CustomException");

        // terminal 도달 보장(무한 재폴링 차단) — 락 없어도 POLL_FAILED.
        assertThat(p.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
        verify(workLockService, never()).releaseRaw(any(), any(), any());
    }

    @Test
    @DisplayName("M1_비식별파일무효시_markRawDeidentFailed가_F를_별도커밋한다")
    void markRawDeidentFailedCommitsF() {
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.markRawDeidentFailed(9001L);

        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
    }

    @Test
    @DisplayName("기존_BATCH완료는_여전히_markMarkingReady를_호출한다_회귀_REQ_KIND_null")
    void batchCompletionStillMarksMarkingReady() {
        LsDeidentProcLog p = submitted(); // REQ_KIND null = 기존 배치 경로
        LsDataRaw raw = newRaw();
        when(procLogRepository.findById(1L)).thenReturn(Optional.of(p));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        tx.finishDownloadAndComplete(9001L, 1L, 202L, realDeidFile());

        // 기존 배치 경로 — MARKING_READY 전이 + 신고해소 + 알림 유지(회귀 금지). Attacher 미호출.
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        verify(deidentReportService, times(1)).resolveOpenReports(9001L);
        verify(notificationService, times(1)).notifyReviewersOnLockRelease(raw);
        verify(deidentFrameAttacher, never()).attachDeidentFrames(any(), any());
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
}
