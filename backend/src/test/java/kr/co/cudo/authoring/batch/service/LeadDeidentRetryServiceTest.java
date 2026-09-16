package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 선두 비식별 실패 영상의 배치 재시작 — 형상 판정·거부 순서·잠금 선점 단위 시험.
 *
 * <p>실행기 호출이 이 빈에 없다는 것 자체가 「거부 시 선두 비식별 단계 0회」의 구조적 근거이며, 거부 사례마다
 * 잠금 선점이 호출되지 않았음을 본다(거부된 요청이 잠금을 남기지 않는다).
 *
 * @design AC-1133
 * @design AC-1134
 */
class LeadDeidentRetryServiceTest {

    private static final long RAW_SN = 900L;

    private VideoRepository videoRepository;
    private ReviewApprovalGate reviewApprovalGate;
    private LsDeidentReportRepository reportRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private WorkLockService workLockService;
    private LeadDeidentRetryService service;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        reviewApprovalGate = mock(ReviewApprovalGate.class);
        reportRepository = mock(LsDeidentReportRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        workLockService = mock(WorkLockService.class);
        service = new LeadDeidentRetryService(videoRepository, reviewApprovalGate, reportRepository,
                procLogRepository, workLockService);
    }

    private static LsDataRaw raw(String deIdntfYn, String dataSttsCd, boolean derivative) {
        LsDataRaw raw = mock(LsDataRaw.class);
        when(raw.getDeIdntfYn()).thenReturn(deIdntfYn);
        when(raw.getDataSttsCd()).thenReturn(dataSttsCd);
        when(raw.isDerivative()).thenReturn(derivative);
        return raw;
    }

    private void givenShape(boolean derivative) {
        LsDataRaw raw = raw("F", LsDataRaw.STATUS_PENDING, derivative);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
    }

    private CustomException claimRejected() {
        return catchThrowableOfType(() -> service.tryClaim(RAW_SN), CustomException.class);
    }

    @Test
    @DisplayName("★선두비식별_실패형상이면_잠금을_선점하고_true")
    void 형상이면_선점() {
        givenShape(false);

        assertThat(service.tryClaim(RAW_SN)).isTrue();

        verify(workLockService).lockRawForDeidentRetry(eq(RAW_SN), anyString(),
                eq(LeadDeidentRetryService.LOCKED_REASON));
    }

    @Test
    @DisplayName("★비식별이_실패하지_않은_영상은_형상이_아니라_false_이고_아무것도_잠그지_않는다")
    void 형상이_아니면_false() {
        // 'Y'·'N' 은 비식별 미실패, 'F' 라도 마킹 준비 이후(MARKING_READY·PROCESSING·COMPLETED·FAILED)는 형상 밖.
        List<LsDataRaw> notShapes = List.of(
                raw("Y", LsDataRaw.STATUS_PENDING, false),
                raw("N", LsDataRaw.STATUS_PENDING, false),
                raw("F", LsDataRaw.DATA_STTS_MARKING_READY, false),
                raw("F", LsDataRaw.DATA_STTS_PROCESSING, false),
                raw("F", LsDataRaw.DATA_STTS_COMPLETED, false),
                raw("F", LsDataRaw.DATA_STTS_FAILED, false));
        for (LsDataRaw r : notShapes) {
            when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(r));
            assertThat(service.tryClaim(RAW_SN)).isFalse();
        }
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());
        assertThat(service.tryClaim(RAW_SN)).isFalse();

        verify(workLockService, never()).lockRawForDeidentRetry(anyLong(), anyString(), anyString());
        verify(reviewApprovalGate, never()).hasEverApproved(anyLong());
    }

    @Test
    @DisplayName("파생영상은_400이고_잠금을_잡지_않는다")
    void 파생은_400() {
        givenShape(true);

        CustomException e = claimRejected();

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(e.getMessage()).isEqualTo(LeadDeidentRetryService.DERIVATIVE_REASON);
        verify(workLockService, never()).lockRawForDeidentRetry(anyLong(), anyString(), anyString());
        // 파생이 먼저다 — 승인 이력을 보지 않는다.
        verify(reviewApprovalGate, never()).hasEverApproved(anyLong());
    }

    @Test
    @DisplayName("승인_이력이_있으면_409이고_판정은_이력으로_한다")
    void 승인이력은_409() {
        givenShape(false);
        when(reviewApprovalGate.hasEverApproved(RAW_SN)).thenReturn(true);
        // 현재 상태 판정(isApproved)이 false 여도 이력이 있으면 막힌다.
        when(reviewApprovalGate.isApproved(RAW_SN)).thenReturn(false);

        CustomException e = claimRejected();

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(e.getMessage()).isEqualTo(LeadDeidentRetryService.APPROVED_HISTORY_REASON);
        verify(workLockService, never()).lockRawForDeidentRetry(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("열린_비식별_신고가_있으면_409")
    void 열린신고는_409() {
        givenShape(false);
        when(reportRepository.findAllByDataRawSnAndReportSttsCd(RAW_SN, LsDeidentReport.REPORT_OPEN))
                .thenReturn(List.of(mock(LsDeidentReport.class)));

        CustomException e = claimRejected();

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(e.getMessage()).isEqualTo(LeadDeidentRetryService.OPEN_REPORT_REASON);
        verify(workLockService, never()).lockRawForDeidentRetry(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("진행중_위탁이_있으면_409이고_조회는_WAITING_POLLING_두값만_쓴다")
    void 진행중위탁은_409() {
        givenShape(false);
        when(procLogRepository.existsByDataRawSnAndPollSttsCdIn(eq(RAW_SN), any())).thenReturn(true);

        CustomException e = claimRejected();

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(e.getMessage()).isEqualTo(LeadDeidentRetryService.IN_FLIGHT_REASON);
        verify(procLogRepository).existsByDataRawSnAndPollSttsCdIn(eq(RAW_SN), argThat(
                (Collection<String> c) -> Set.copyOf(c).equals(
                        Set.of(LsDeidentProcLog.POLL_WAITING, LsDeidentProcLog.POLL_POLLING))));
        verify(workLockService, never()).lockRawForDeidentRetry(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("이미_잠겨_있으면_409")
    void 이미잠김은_409() {
        givenShape(false);
        doThrow(new CustomException(ErrorCode.CONFLICT, LeadDeidentRetryService.LOCKED_REASON))
                .when(workLockService).lockRawForDeidentRetry(eq(RAW_SN), anyString(), anyString());

        CustomException e = claimRejected();

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(e.getMessage()).isEqualTo(LeadDeidentRetryService.LOCKED_REASON);
    }

    @Test
    @DisplayName("동시요청으로_잠금_유일인덱스가_거부하면_409로_바꾼다")
    void 유일인덱스위반은_409() {
        givenShape(false);
        doThrow(new DataIntegrityViolationException("ux_ls_auth_work_lock_raw_active"))
                .when(workLockService).lockRawForDeidentRetry(eq(RAW_SN), anyString(), anyString());

        CustomException e = claimRejected();

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(e.getMessage()).isEqualTo(LeadDeidentRetryService.LOCKED_REASON);
    }

    @Test
    @DisplayName("409_사유_문구는_모두_서로_다르다")
    void 사유문구는_서로_다르다() {
        assertThat(Set.of(
                LeadDeidentRetryService.APPROVED_HISTORY_REASON,
                LeadDeidentRetryService.OPEN_REPORT_REASON,
                LeadDeidentRetryService.IN_FLIGHT_REASON,
                LeadDeidentRetryService.LOCKED_REASON,
                BatchReprocessService.NOT_CLAIMABLE_REASON,
                BatchReprocessService.REVIEW_OWNED_REASON)).hasSize(6);
    }

    @Test
    @DisplayName("접수거부_보상은_이_기능의_잠금만_독립커밋으로_푼다")
    void 보상해제() {
        service.releaseClaim(RAW_SN);

        verify(workLockService).releaseDeidentRetryLockInNewTx(eq(RAW_SN), anyString(),
                eq(LeadDeidentRetryService.RELEASE_DISPATCH_REJECTED));
        verify(workLockService, never()).releaseRaw(anyLong(), anyString(), anyString());
    }
}
