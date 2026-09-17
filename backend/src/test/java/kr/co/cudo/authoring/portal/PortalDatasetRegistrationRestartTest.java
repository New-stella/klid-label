package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.dto.PortalDatasetRegistrationResponse;
import kr.co.cudo.authoring.portal.dto.PortalDatasetRegistrationState;
import kr.co.cudo.authoring.portal.dto.PortalDatasetVideoPageResponse;
import kr.co.cudo.authoring.portal.repository.PortalDatasetVideoRepository;
import kr.co.cudo.authoring.portal.repository.PortalUserWorkListRepository;
import kr.co.cudo.authoring.portal.service.PortalDatasetRegistrationFailureReason;
import kr.co.cudo.authoring.portal.service.PortalDatasetRegistrationRunner;
import kr.co.cudo.authoring.portal.service.PortalDatasetRegistrationService;
import kr.co.cudo.authoring.portal.service.PortalDatasetRegistrationStatus;
import kr.co.cudo.authoring.portal.service.PortalDatasetVideoService;
import kr.co.cudo.authoring.portal.service.PortalMaterialsWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 등록 재착수 창구의 판정·선점·되돌림을 <b>협력자를 대역으로</b> 고정한다(API-262 · AC-1132).
 *
 * <p>실 DB·실 파일 왕복은 {@link PortalDatasetRegistrationIT} 가 맡는다. 여기서는 실 DB 로는 만들기 어려운
 * 갈래 — 대기열 포화(503)에서 선점을 되돌리는가, 실패 표식에서 <b>실제로</b> 선점·기동을 부르는가 — 를 본다.
 *
 * @design API-262
 * @design API-253
 * @design AC-1132
 */
class PortalDatasetRegistrationRestartTest {

    private static final long DATASET = 4704L;
    private static final String USER = "portal-user-1";
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    private PortalMaterialsWorkspace workspace;
    private PortalDatasetRegistrationService registrationService;
    private PortalDatasetRegistrationRunner runner;
    private PortalDatasetVideoRepository videoRepository;
    private LsDataSrcRepository srcRepository;
    private PortalUserWorkListRepository workListRepository;
    private PortalDatasetVideoService service;

    @BeforeEach
    void setUp() {
        workspace = mock(PortalMaterialsWorkspace.class);
        registrationService = mock(PortalDatasetRegistrationService.class);
        runner = mock(PortalDatasetRegistrationRunner.class);
        videoRepository = mock(PortalDatasetVideoRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        workListRepository = mock(PortalUserWorkListRepository.class);
        service = new PortalDatasetVideoService(workspace, registrationService, runner, videoRepository,
                srcRepository, workListRepository, Clock.fixed(NOW, ZoneOffset.UTC));

        when(workspace.isReady(DATASET)).thenReturn(true);
        when(registrationService.isEnabled()).thenReturn(true);
        when(registrationService.inProgress(DATASET)).thenReturn(false);
        when(registrationService.tryClaim(DATASET)).thenReturn(true);
        when(videoRepository.findPage(anyLong(), any(Pageable.class)))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));
    }

    private void marker(PortalDatasetRegistrationStatus status) {
        when(workspace.readRegistration(DATASET)).thenReturn(status);
    }

    private static PortalDatasetRegistrationStatus failed(PortalDatasetRegistrationFailureReason reason) {
        return PortalDatasetRegistrationStatus.failed(reason, 0, 0, NOW.minusSeconds(3600));
    }

    // ==================================================== 재착수

    @Test
    @DisplayName("★실패_표식이면_선점하고_등록을_다시_시작시키며_IN_PROGRESS_와_빈_사유로_답한다")
    void failedMarkerRestarts() {
        marker(failed(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH));

        PortalDatasetRegistrationResponse res = service.restartRegistration(DATASET, USER);

        assertThat(res.registrationState()).isEqualTo(PortalDatasetRegistrationState.IN_PROGRESS);
        assertThat(res.registrationFailureReason()).as("재착수 접수 시 사유는 비어 있다").isNull();
        verify(registrationService).tryClaim(DATASET);
        verify(runner).runAsync(DATASET);
        verify(registrationService, never()).release(DATASET);
    }

    @Test
    @DisplayName("★대기열_포화면_선점을_되돌리고_503_이다")
    void queueFullReleasesClaimAnd503() {
        marker(failed(PortalDatasetRegistrationFailureReason.IO_ERROR));
        doThrow(new TaskRejectedException("full")).when(runner).runAsync(DATASET);

        assertThatThrownBy(() -> service.restartRegistration(DATASET, USER))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        verify(registrationService).tryClaim(DATASET);
        verify(registrationService).release(DATASET);
    }

    @Test
    @DisplayName("기동이_다른_예외로_깨져도_선점을_되돌리고_그_예외를_그대로_올린다")
    void otherRuntimeFailureReleasesClaim() {
        marker(failed(PortalDatasetRegistrationFailureReason.IO_ERROR));
        doThrow(new IllegalStateException("boom")).when(runner).runAsync(DATASET);

        assertThatThrownBy(() -> service.restartRegistration(DATASET, USER))
                .isInstanceOf(IllegalStateException.class);

        verify(registrationService).release(DATASET);
    }

    @Test
    @DisplayName("완료_표식이면_다시_하지_않고_DONE_을_답한다")
    void doneIsNoop() {
        marker(PortalDatasetRegistrationStatus.done(2, 0, NOW.minusSeconds(60)));

        PortalDatasetRegistrationResponse res = service.restartRegistration(DATASET, USER);

        assertThat(res.registrationState()).isEqualTo(PortalDatasetRegistrationState.DONE);
        assertThat(res.registrationFailureReason()).isNull();
        verify(registrationService, never()).tryClaim(anyLong());
        verify(runner, never()).runAsync(anyLong());
    }

    @Test
    @DisplayName("신선한_진행_중_표식이면_새로_시작하지_않고_IN_PROGRESS_를_답한다")
    void freshInProgressIsNoop() {
        marker(PortalDatasetRegistrationStatus.inProgress(1, 0, NOW.minusSeconds(30)));

        PortalDatasetRegistrationResponse res = service.restartRegistration(DATASET, USER);

        assertThat(res.registrationState()).isEqualTo(PortalDatasetRegistrationState.IN_PROGRESS);
        verify(registrationService, never()).tryClaim(anyLong());
        verify(runner, never()).runAsync(anyLong());
    }

    @Test
    @DisplayName("이_노드에서_진행_중이면_표식을_읽지_않고_IN_PROGRESS_를_답한다")
    void nodeInFlightIsNoop() {
        when(registrationService.inProgress(DATASET)).thenReturn(true);

        PortalDatasetRegistrationResponse res = service.restartRegistration(DATASET, USER);

        assertThat(res.registrationState()).isEqualTo(PortalDatasetRegistrationState.IN_PROGRESS);
        verify(registrationService, never()).tryClaim(anyLong());
    }

    @Test
    @DisplayName("★실패_표식인데_선점에_졌으면_다른_경로가_방금_시작한_것이라_IN_PROGRESS_를_답하고_기동하지_않는다")
    void lostClaimAnswersInProgress() {
        marker(failed(PortalDatasetRegistrationFailureReason.IO_ERROR));
        when(registrationService.tryClaim(DATASET)).thenReturn(false);

        PortalDatasetRegistrationResponse res = service.restartRegistration(DATASET, USER);

        assertThat(res.registrationState()).isEqualTo(PortalDatasetRegistrationState.IN_PROGRESS);
        verify(runner, never()).runAsync(anyLong());
        verify(registrationService, never()).release(anyLong());
    }

    @Test
    @DisplayName("★등록_토글이_꺼져_있고_실패_표식이면_409_이고_선점하지_않는다")
    void disabledIsConflict() {
        marker(failed(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH));
        when(registrationService.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.restartRegistration(DATASET, USER))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(registrationService, never()).tryClaim(anyLong());
    }

    @Test
    @DisplayName("소재_미준비_409_토큰_없음_401_식별자_불량_400_이며_상태_판정_전에_거른다")
    void guardsBeforeStateResolution() {
        when(workspace.isReady(DATASET)).thenReturn(false);
        assertThatThrownBy(() -> service.restartRegistration(DATASET, USER))
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> service.restartRegistration(DATASET, " "))
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        assertThatThrownBy(() -> service.restartRegistration(0, USER))
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        verify(workspace, never()).readRegistration(anyLong());
        verify(registrationService, never()).tryClaim(anyLong());
    }

    // ==================================================== 목록의 실패 사유 (API-253 v3)

    @Test
    @DisplayName("★목록은_실패_표식을_재시작하지_않고_실패_사유_분류를_싣는다")
    void listCarriesFailureReasonWithoutRestart() {
        marker(failed(PortalDatasetRegistrationFailureReason.VIDEO_FILENAME_MISSING));

        PortalDatasetVideoPageResponse page = service.list(DATASET, 0, 20, USER);

        assertThat(page.registrationState()).isEqualTo(PortalDatasetRegistrationState.FAILED);
        assertThat(page.registrationFailureReason())
                .isEqualTo(PortalDatasetRegistrationFailureReason.VIDEO_FILENAME_MISSING);
        verify(registrationService, never()).tryClaim(anyLong());
        verify(runner, never()).runAsync(anyLong());
    }

    @Test
    @DisplayName("목록은_완료_진행_중에서_실패_사유가_비어_있다")
    void listFailureReasonNullUnlessFailed() {
        marker(PortalDatasetRegistrationStatus.done(2, 0, NOW.minusSeconds(60)));
        assertThat(service.list(DATASET, 0, 20, USER).registrationFailureReason()).isNull();

        marker(PortalDatasetRegistrationStatus.inProgress(1, 0, NOW.minusSeconds(30)));
        assertThat(service.list(DATASET, 0, 20, USER).registrationFailureReason()).isNull();
    }

    @Test
    @DisplayName("★등록_토글이_꺼져_있고_표식이_없으면_목록은_FAILED_와_사유_DISABLED_를_싣고_선점하지_않는다")
    void listSynthesizesDisabledReasonWhenToggleOff() {
        marker(null);
        when(registrationService.isEnabled()).thenReturn(false);

        PortalDatasetVideoPageResponse page = service.list(DATASET, 0, 20, USER);

        assertThat(page.registrationState()).isEqualTo(PortalDatasetRegistrationState.FAILED);
        assertThat(page.registrationFailureReason()).isEqualTo(PortalDatasetRegistrationFailureReason.DISABLED);
        verify(registrationService, never()).tryClaim(anyLong());
    }

    @Test
    @DisplayName("표식에_사유가_있으면_토글이_꺼져_있어도_표식_사유가_우선이다")
    void markerReasonWinsOverDisabled() {
        marker(failed(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH));
        when(registrationService.isEnabled()).thenReturn(false);

        assertThat(service.list(DATASET, 0, 20, USER).registrationFailureReason())
                .isEqualTo(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH);
    }

    @Test
    @DisplayName("재착수도_표식_없음을_회복_판정으로_한_번만_시작시키고_IN_PROGRESS_를_답한다_이중_착수_없음")
    void restartOnMissingMarkerStartsExactlyOnce() {
        marker(null);

        var result = service.restartRegistration(DATASET, USER);

        assertThat(result.registrationState()).isEqualTo(PortalDatasetRegistrationState.IN_PROGRESS);
        assertThat(result.registrationFailureReason()).isNull();
        verify(registrationService, times(1)).tryClaim(eq(DATASET));
        verify(runner, times(1)).runAsync(DATASET);
    }

    @Test
    @DisplayName("목록은_표식_없음을_회복시키고_IN_PROGRESS_로_답한다_재착수_창구와_같은_판정을_쓴다")
    void listStillRecoversMissingMarker() {
        marker(null);

        PortalDatasetVideoPageResponse page = service.list(DATASET, 0, 20, USER);

        assertThat(page.registrationState()).isEqualTo(PortalDatasetRegistrationState.IN_PROGRESS);
        assertThat(page.registrationFailureReason()).isNull();
        verify(registrationService).tryClaim(eq(DATASET));
        verify(runner).runAsync(DATASET);
    }
}
