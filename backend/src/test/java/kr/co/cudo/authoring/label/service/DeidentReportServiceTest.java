package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 — DeidentReportService 단위 테스트 (Mockito 기반).
 *
 * <p>신규 인프라 의존:
 * <ul>
 *   <li>{@link WorkLockService} — LS_AUTH_WORK_LOCK 기반 잠금 (LsDataRaw.attachLockStts/releaseLock 대체).</li>
 *   <li>{@link LsDeidentReport#createReport} — REPORT_STTS_CD='OPEN' 사용자 신고 row.</li>
 *   <li>{@link LsDeidentReportRepository#findAllByDataRawSnAndReportSttsCd} — OPEN 신고 일괄 RESOLVED 전이.</li>
 * </ul>
 */
class DeidentReportServiceTest {

    private LabelAccessGuard accessGuard;
    private VideoRepository videoRepository;
    private LsDeidentReportRepository reportRepository;
    private BatchRetryQueue retryQueue;
    private NotificationService notificationService;
    private WorkLockService workLockService;
    private DeidentReportService service;

    private TokenClaims workerActor;

    @BeforeEach
    void setUp() {
        accessGuard = mock(LabelAccessGuard.class);
        videoRepository = mock(VideoRepository.class);
        reportRepository = mock(LsDeidentReportRepository.class);
        retryQueue = mock(BatchRetryQueue.class);
        notificationService = mock(NotificationService.class);
        workLockService = mock(WorkLockService.class);
        service = new DeidentReportService(accessGuard, videoRepository, reportRepository,
                retryQueue, notificationService, workLockService);

        workerActor = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(60));
        when(accessGuard.parseUserNo("100")).thenReturn(100L);
    }

    private LsDataSrc src(long srcSn, long rawSn) {
        LsDataSrc s = LsDataSrc.create(rawSn, 0, "/raw/0.jpg", LocalDateTime.now());
        setField(s, "srcSn", srcSn);
        return s;
    }

    private LsDataRaw raw(long rawSn, String prvc) {
        LsDataRaw r = LsDataRaw.createFromIngest(
                "C-" + rawSn, "CCTV", "EVT", "GOV",
                prvc, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        setField(r, "rawSn", rawSn);
        return r;
    }

    @Test
    @DisplayName("정상_신고시_LS_DEIDENT_REPORT_저장_+_WorkLock_LOCKED_+_DE_IDNTF_F_+_재시도_큐_+_REVIEWER_알림")
    void normalReportSavesAndLocks() {
        LsDataSrc s = src(1L, 9001L);
        LsDataRaw r = raw(9001L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(1L), any())).thenReturn(s);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9001L)).thenReturn(false);
        when(reportRepository.save(any(LsDeidentReport.class))).thenAnswer(inv -> {
            LsDeidentReport arg = inv.getArgument(0);
            setField(arg, "deidentReportSn", 555L);
            return arg;
        });
        when(retryQueue.enqueueIfRetryable(9001L)).thenReturn(true);

        Long rprtSn = service.report(1L, "얼굴 미블러", workerActor);

        assertThat(rprtSn).isEqualTo(555L);
        // 비식별 상태가 'F' 로 갱신되었는지 확인.
        assertThat(r.getDeIdntfYn()).isEqualTo("F");
        verify(workLockService).lockRawForRedeident(9001L, "100");
        verify(retryQueue).enqueueIfRetryable(9001L);
        verify(notificationService).notifyReviewersOnDeidentReport(r, 100L, "얼굴 미블러");
        verify(reportRepository).save(any(LsDeidentReport.class));
    }

    @Test
    @DisplayName("이미_잠금_영상_신고시_CONFLICT_409_+_저장_없음_+_LOCK_재요청_없음")
    void alreadyLockedConflict() {
        LsDataSrc s = src(2L, 9002L);
        LsDataRaw r = raw(9002L, LsDataRaw.PRVC_TYPE_PRVC);
        when(accessGuard.verifyAndGet(eq(2L), any())).thenReturn(s);
        when(videoRepository.findById(9002L)).thenReturn(Optional.of(r));
        when(workLockService.isRawLocked(9002L)).thenReturn(true);

        assertThatThrownBy(() -> service.report(2L, "재신고", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(reportRepository, never()).save(any());
        verify(retryQueue, never()).enqueueIfRetryable(anyLong());
        verify(workLockService, never()).lockRawForRedeident(anyLong(), anyString());
    }

    @Test
    @DisplayName("존재하지_않는_영상_신고시_NOT_FOUND_404")
    void unknownVideoNotFound() {
        LsDataSrc s = src(3L, 9999L);
        when(accessGuard.verifyAndGet(eq(3L), any())).thenReturn(s);
        when(videoRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.report(3L, "사유", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("reason_누락_빈문자열은_INVALID_INPUT_400")
    void blankReasonRejected() {
        assertThatThrownBy(() -> service.report(1L, "  ", workerActor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.report(1L, null, workerActor))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("resolveOpenReports_REPORT_STTS_OPEN_신고_일괄_RESOLVED_전이_+_WorkLock_해제")
    void resolveOpenReportsTransitions() {
        LsDeidentReport r1 = LsDeidentReport.createReport(9100L, 100L, "사유1");
        LsDeidentReport r2 = LsDeidentReport.createReport(9100L, 101L, "사유2");
        when(reportRepository.findAllByDataRawSnAndReportSttsCd(9100L, LsDeidentReport.REPORT_OPEN))
                .thenReturn(List.of(r1, r2));

        int n = service.resolveOpenReports(9100L);

        assertThat(n).isEqualTo(2);
        assertThat(r1.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_RESOLVED);
        assertThat(r2.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_RESOLVED);
        assertThat(r1.getResolvedDt()).isNotNull();
        verify(workLockService).releaseRaw(9100L, "system", "DEIDENT_SUCCEEDED");
    }

    @Test
    @DisplayName("resolveOpenReports_rawSn_null_안전_종료_0")
    void resolveNullRawSnReturnsZero() {
        assertThat(service.resolveOpenReports(null)).isZero();
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("createReport_factory_는_REPORT_STTS_CD_OPEN_및_REPORTER_NO_REASON_설정")
    void createReportFactoryFields() {
        LsDeidentReport r = LsDeidentReport.createReport(9200L, 200L, "테스트 사유");

        assertThat(r.getDataRawSn()).isEqualTo(9200L);
        assertThat(r.getReporterNo()).isEqualTo(200L);
        assertThat(r.getRsn()).isEqualTo("테스트 사유");
        assertThat(r.getReportSttsCd()).isEqualTo(LsDeidentReport.REPORT_OPEN);
        assertThat(r.getReportDt()).isNotNull();
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
