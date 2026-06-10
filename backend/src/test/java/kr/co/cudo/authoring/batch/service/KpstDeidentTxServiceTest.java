package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
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
    private KpstDeidentTxService tx;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        deidentReportService = mock(DeidentReportService.class);
        notificationService = mock(NotificationService.class);
        workLockService = mock(WorkLockService.class);
        tx = new KpstDeidentTxService(videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService);
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
