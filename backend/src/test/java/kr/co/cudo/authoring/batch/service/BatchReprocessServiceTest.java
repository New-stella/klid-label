package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.dto.BatchReprocessResponse;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.runner.AsyncBatchReprocessRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3 — 배치 재처리(FAILED 복구) <b>접수</b> 서비스 단위 테스트 (Mockito). [@design API-167]
 *
 * <p>HIGH #1(CWE-362) 수정 후: 상태 판정·전이는 {@link BatchTransitionService#tryClaimReprocessFromFailed}
 * 원자 클레임으로 통합된다. 본 테스트는 클레임 결과(성공/실패)에 따른 <b>접수/거부</b> 분기를 검증한다.
 *
 * <p>★ 이 서비스는 더 이상 {@code BatchOrchestrator} 를 알지 못한다 — 파이프라인 실행은
 * {@link AsyncBatchReprocessRunner}(별도 빈, {@code @Async})가 전담한다. 즉 <b>요청 스레드에서
 * 파이프라인이 도는 일이 구조적으로 불가능</b>하며, 그 사실을 의존성 목록이 고정한다.
 */
class BatchReprocessServiceTest {

    private VideoRepository videoRepository;
    private BatchTransitionService transitionService;
    private BatchStatusService batchStatusService;
    private AsyncBatchReprocessRunner reprocessRunner;
    private BatchRetryQueue retryQueue;
    private LeadDeidentRetryService leadDeidentRetryService;
    private BatchReprocessService service;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        transitionService = mock(BatchTransitionService.class);
        batchStatusService = mock(BatchStatusService.class);
        reprocessRunner = mock(AsyncBatchReprocessRunner.class);
        retryQueue = mock(BatchRetryQueue.class);
        // 기본은 「선두 비식별 실패 형상이 아님」(mock 기본 false) — 기존 시험은 기존 경로를 그대로 탄다.
        leadDeidentRetryService = mock(LeadDeidentRetryService.class);
        service = new BatchReprocessService(videoRepository, transitionService, batchStatusService,
                reprocessRunner, retryQueue, leadDeidentRetryService);
    }

    // ── 선두 비식별 실패 형상 갈래 [@design API-167] [@design AC-1133] [@design AC-1134] [@design AC-1135] ──

    @Test
    @DisplayName("★선두비식별_실패영상은_잠금선점후_선두비식별_실행기로_넘기고_단계_PENDING을_돌려준다")
    void 선두비식별_실패영상은_실행기로_넘기고_PENDING() {
        long rawSn = 501L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(leadDeidentRetryService.tryClaim(rawSn)).thenReturn(true);

        BatchReprocessResponse res = service.retry(rawSn);

        assertThat(res.rawSn()).isEqualTo(rawSn);
        assertThat(res.stage()).isEqualTo(LsDataRaw.STATUS_PENDING);
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(leadDeidentRetryService, reprocessRunner);
        inOrder.verify(leadDeidentRetryService).tryClaim(rawSn);
        // 수동 재기동 전용 풀(포화 시 거부)의 진입으로 넘긴다 — 선두 비식별 풀(호출자 실행)이 아니다.
        inOrder.verify(reprocessRunner).runLeadDeidentRetryAsync(rawSn);
        // 배치 단계 선점·선점 표식·자동 재시도 대기 행 정리·마킹 이후 재기동을 하지 않는다.
        verify(transitionService, never()).tryClaimReprocessFromFailed(anyLong());
        verify(transitionService, never()).isReviewOwnedWorkStatus(anyLong());
        verify(batchStatusService, never()).recordReprocessClaimOpened(anyLong(), anyString());
        verify(retryQueue, never()).clearIfIdle(anyLong());
        verify(reprocessRunner, never()).runAsync(anyLong(), anyString());
        verify(leadDeidentRetryService, never()).releaseClaim(anyLong());
    }

    @Test
    @DisplayName("★선두비식별_형상의_거부는_그대로_전달되고_실행기도_기존경로도_타지_않는다")
    void 선두비식별_거부는_전달되고_실행_0() {
        long rawSn = 502L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(leadDeidentRetryService.tryClaim(rawSn)).thenThrow(
                new CustomException(ErrorCode.CONFLICT, LeadDeidentRetryService.OPEN_REPORT_REASON));

        assertThatThrownBy(() -> service.retry(rawSn))
                .isInstanceOf(CustomException.class)
                .hasMessage(LeadDeidentRetryService.OPEN_REPORT_REASON);

        verify(reprocessRunner, never()).runLeadDeidentRetryAsync(anyLong());
        verify(transitionService, never()).tryClaimReprocessFromFailed(anyLong());
        verify(reprocessRunner, never()).runAsync(anyLong(), anyString());
    }

    @Test
    @DisplayName("★선두비식별_재시도_디스패치가_거부되면_잡은_잠금을_풀고_503")
    void 선두비식별_디스패치거부는_잠금해제_503() {
        long rawSn = 503L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(leadDeidentRetryService.tryClaim(rawSn)).thenReturn(true);
        doThrow(new TaskRejectedException("full")).when(reprocessRunner).runLeadDeidentRetryAsync(rawSn);

        assertThatThrownBy(() -> service.retry(rawSn))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);

        verify(leadDeidentRetryService).releaseClaim(rawSn);
        verify(transitionService, never()).releaseReprocessClaim(anyLong(), anyString());
    }

    @Test
    @DisplayName("선두비식별_실패형상이_아니면_기존_재기동_경로를_그대로_탄다")
    void 형상이_아니면_기존경로() {
        long rawSn = 504L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(leadDeidentRetryService.tryClaim(rawSn)).thenReturn(false);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);

        BatchReprocessResponse res = service.retry(rawSn);

        assertThat(res.stage()).isEqualTo(LsDataRaw.DATA_STTS_PROCESSING);
        verify(reprocessRunner).runAsync(rawSn, LsDataRaw.DATA_STTS_FAILED);
        verify(reprocessRunner, never()).runLeadDeidentRetryAsync(anyLong());
    }

    @Test
    @DisplayName("영상이_없으면_선두비식별_판정전에_404")
    void 영상없음은_판정전_404() {
        when(videoRepository.existsById(505L)).thenReturn(false);

        assertThatThrownBy(() -> service.retry(505L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(leadDeidentRetryService, never()).tryClaim(anyLong());
    }

    @Test
    @DisplayName("배치재처리API_FAILED가_아니면_거부된다")
    void rejectsWhenNotFailed() {
        long rawSn = 1L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        // FAILED 아님 → 원자 클레임 실패(영향 행수 0).
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(false);

        assertThatThrownBy(() -> service.retry(rawSn))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(reprocessRunner, never()).runAsync(anyLong(), anyString());
        verify(retryQueue, never()).clearIfIdle(anyLong());
    }

    @Test
    @DisplayName("★배치재처리API_요청은_선점까지만_하고_실행은_비동기로_넘긴다")
    void claimsSynchronouslyAndDispatchesAsync() {
        long rawSn = 2L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);

        BatchReprocessResponse res = service.retry(rawSn);

        assertThat(res.rawSn()).isEqualTo(rawSn);
        // 접수 시점 단계 — 클레임이 배치 단계를 PROCESSING 으로 선점했다. 파이프라인 최종 결과가 아니다.
        assertThat(res.stage()).isEqualTo(LsDataRaw.DATA_STTS_PROCESSING);
        // 클레임 성공 후에만 유휴 대기 행 리셋(RETRYING 부기 보존) → clearIfIdle.
        verify(retryQueue).clearIfIdle(rawSn);
        // 실행은 별도 빈으로 넘긴다 — 요청 스레드에서 파이프라인이 돌지 않는다(FE 30s 타임아웃 결함 차단).
        verify(reprocessRunner).runAsync(rawSn, LsDataRaw.DATA_STTS_FAILED);
    }

    /**
     * ★선점 출발 상태는 <b>DB 에</b> 남아야 한다 — 인자로만 존재하면 노드가 죽을 때 함께 사라진다.
     *
     * <p>선점 후 큐 대기 중 재기동·재배포가 나면 선점 표시({@code PROCESSING})만 DB 에 남고 "어디로
     * 되돌릴지" 는 사라져, 그 영상은 이후 모든 재기동이 409 이며 앱 안에 복구 수단이 0 이 된다.
     * 표식은 <b>디스패치 전에</b> 남긴다 — 뒤에 남기면 이미 끝난 실행의 닫힘 행이 먼저 적재돼
     * 열림/닫힘 순서가 뒤집힌다.
     */
    @Test
    @DisplayName("★선점하면_출발상태_FAILED를_표식으로_남기고_그_뒤에_디스패치한다")
    void recordsClaimOriginMarkerBeforeDispatch() {
        long rawSn = 41L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);

        service.retry(rawSn);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(batchStatusService, reprocessRunner);
        inOrder.verify(batchStatusService)
                .recordReprocessClaimOpened(rawSn, LsDataRaw.DATA_STTS_FAILED);
        inOrder.verify(reprocessRunner).runAsync(rawSn, LsDataRaw.DATA_STTS_FAILED);
    }

    @Test
    @DisplayName("★접수가_거부되면_선점을_되돌리면서_표식도_닫는다")
    void closesClaimMarkerWhenDispatchRejected() {
        long rawSn = 42L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("full"))
                .when(reprocessRunner).runAsync(rawSn, LsDataRaw.DATA_STTS_FAILED);

        assertThatThrownBy(() -> service.retry(rawSn)).isInstanceOf(CustomException.class);

        // 표식을 열어 둔 채로 두면 나중에 다른 경로로 고착된 이 영상을 회수 스윕이 옛 출발 상태로 되돌린다.
        verify(batchStatusService).recordReprocessClaimClosed(
                rawSn, BatchReprocessService.CLAIM_CLOSED_DISPATCH_REJECTED);
    }

    /**
     * ★표식 닫기가 실패해도 <b>의도한 503</b> 이 나가야 한다.
     *
     * <p>기록 실패가 그대로 올라가면 사용자는 "접수 실패(잠시 후 재시도)" 대신 알 수 없는 오류를 본다 —
     * 상태(PROCESSING)는 이미 되돌아갔는데도. 러너({@code AsyncBatchReprocessRunner})가 같은 관례로
     * 감싸고 있으므로 여기만 비워 두면 같은 기록 실패가 경로에 따라 다르게 드러난다.
     */
    @Test
    @DisplayName("★표식_닫기가_실패해도_응답은_503으로_유지된다")
    void markerCloseFailureDoesNotFlipTheResponse() {
        long rawSn = 43L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("full"))
                .when(reprocessRunner).runAsync(rawSn, LsDataRaw.DATA_STTS_FAILED);
        org.mockito.Mockito.doThrow(new IllegalStateException("marker write failed"))
                .when(batchStatusService).recordReprocessClaimClosed(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.retry(rawSn))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("배치재처리_동시요청시_한쪽만_기동된다")
    void onlyOneClaimSucceedsUnderConcurrency() {
        long rawSn = 5L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        // 다른 주체(자동 폴러/동시 수동 요청)가 이미 FAILED→PROCESSING 을 클레임 → 이번 호출은 0건 → 409.
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(false);

        assertThatThrownBy(() -> service.retry(rawSn))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 클레임에 진 호출은 파이프라인을 접수하지 않고 큐도 건드리지 않는다(이중 실행 차단).
        verify(reprocessRunner, never()).runAsync(anyLong(), anyString());
        verify(retryQueue, never()).clearIfIdle(anyLong());
    }

    @Test
    @DisplayName("★검수소유_작업상태는_클레임_전에_409로_거부된다_접수됨으로_가려지지_않는다")
    void rejectsReviewOwnedBeforeClaiming() {
        // 실행이 비동기가 되면 진입 가드의 SKIPPED 를 요청이 볼 수 없다. 사전 차단이 없으면 "못 돌리는
        //   영상"이 200(접수됨)으로 가려지고 사용자는 원인을 알 방법이 없다.
        long rawSn = 7L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.isReviewOwnedWorkStatus(rawSn)).thenReturn(true);

        assertThatThrownBy(() -> service.retry(rawSn))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 클레임 자체를 하지 않으므로 되돌릴 것도 없다(보상 롤백 불필요).
        verify(transitionService, never()).tryClaimReprocessFromFailed(anyLong());
        verify(transitionService, never()).releaseReprocessClaim(anyLong(), anyString());
        verify(reprocessRunner, never()).runAsync(anyLong(), anyString());
    }

    @Test
    @DisplayName("★디스패치가_거부되면_접수_실패다_클레임을_되돌리고_503으로_알린다")
    void compensatesClaimWhenDispatchRejected() {
        // 전용 풀은 포화 시 AbortPolicy 로 거부한다(CallerRuns 로 요청 스레드에서 돌리지 않는다).
        //   거부를 조용히 삼키면 ①사용자는 접수됐다고 믿는데 아무것도 돌지 않고 ②선점한 PROCESSING 이
        //   되돌려지지 않아 그 영상은 이후 영구 409 가 된다.
        long rawSn = 11L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);
        doThrow(new TaskRejectedException("queue full")).when(reprocessRunner).runAsync(rawSn, LsDataRaw.DATA_STTS_FAILED);

        assertThatThrownBy(() -> service.retry(rawSn))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);

        verify(transitionService).releaseReprocessClaim(rawSn, LsDataRaw.DATA_STTS_FAILED);
    }

    @Test
    @DisplayName("디스패치_거부_사유에_내부_큐나_예외메시지가_실리지_않는다")
    void dispatchRejectionReasonHidesInternals() {
        long rawSn = 12L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);
        doThrow(new TaskRejectedException(
                "Executor [java.util.concurrent.ThreadPoolExecutor@1a2b3c] did not accept task"))
                .when(reprocessRunner).runAsync(rawSn, LsDataRaw.DATA_STTS_FAILED);

        assertThatThrownBy(() -> service.retry(rawSn))
                .isInstanceOf(CustomException.class)
                .hasMessage(BatchReprocessService.DISPATCH_REJECTED_REASON)
                .hasMessageNotContaining("ThreadPoolExecutor");
    }

    @Test
    @DisplayName("정상_접수시에는_보상롤백을_하지_않는다")
    void doesNotCompensateOnNormalDispatch() {
        long rawSn = 8L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);

        service.retry(rawSn);

        verify(transitionService, never()).releaseReprocessClaim(anyLong(), anyString());
    }

    @Test
    @DisplayName("존재하지_않는_영상_재처리시_NOT_FOUND_404")
    void notFoundWhenVideoMissing() {
        when(videoRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.retry(99L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(transitionService, never()).tryClaimReprocessFromFailed(anyLong());
        verify(reprocessRunner, never()).runAsync(anyLong(), anyString());
    }

    // ────────────────────────────────────────────────────────────────────────
    // ★ 완주 영상은 이 경로의 대상이 아니다 [@design API-167]
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★완주영상은_해제된_스킵이_있어도_이_경로로는_재기동되지_않는다_보간_전량삭제_차단")
    void rejectsCompletedVideoEvenWhenManualSkipWasRestored() {
        // 완주 영상을 받으면 파이프라인 전체가 순회하는데 트랙 보간은 건너뛰는 조건이 없어 항상 돌고,
        //   사람이 고친 보간 라벨을 복구 지점 없이 전량 삭제·재생성한다. 건너뛰기를 해제한 단계의 재수행은
        //   단계 지목 재수행 API 가 담당한다(문제가 생긴 곳부터 재시도).
        long rawSn = 21L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(false); // 완주 상태라 0행

        assertThatThrownBy(() -> service.retry(rawSn))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 완주 축 클레임은 이 경로에 존재하지 않는다 — 있으면 데이터 파괴 경로가 되살아난다.
        verify(transitionService, never()).tryClaimReprocessFromCompleted(anyLong());
        verify(reprocessRunner, never()).runAsync(anyLong(), anyString());
    }

    @Test
    @DisplayName("★실패_영상은_승인_이력을_묻지_않고_종전대로_재기동된다_기존_계약_보존")
    void failedPathDoesNotConsultApprovalHistory() {
        // 실패 영상은 검수가 승인된 적이 없다(승인은 배치 완주 이후의 워크플로우다). 승인 이력 게이트를
        //   이 경로에 두면 도달 불가능한 조건으로 기존 계약을 좁히기만 한다 — 의존성 자체를 두지 않는다.
        long rawSn = 25L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);

        service.retry(rawSn);

        verify(reprocessRunner).runAsync(rawSn, LsDataRaw.DATA_STTS_FAILED);
    }
}
