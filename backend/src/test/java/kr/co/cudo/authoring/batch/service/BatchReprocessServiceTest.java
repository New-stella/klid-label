package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.dto.BatchReprocessResponse;
import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3 — 배치 재처리(FAILED 복구) 서비스 단위 테스트 (Mockito).
 *
 * <p>HIGH #1(CWE-362) 수정 후: 상태 판정·전이는 {@link BatchTransitionService#tryClaimReprocessFromFailed}
 * 원자 클레임으로 통합된다. 본 테스트는 클레임 결과(성공/실패)에 따른 재기동/거부 분기를 검증한다.
 */
class BatchReprocessServiceTest {

    private VideoRepository videoRepository;
    private BatchTransitionService transitionService;
    private BatchOrchestrator orchestrator;
    private BatchRetryQueue retryQueue;
    private BatchReprocessService service;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        transitionService = mock(BatchTransitionService.class);
        orchestrator = mock(BatchOrchestrator.class);
        retryQueue = mock(BatchRetryQueue.class);
        service = new BatchReprocessService(videoRepository, transitionService, orchestrator, retryQueue);
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

        verify(orchestrator, never()).processWithHeldStageClaim(anyLong());
        verify(retryQueue, never()).clearIfIdle(anyLong());
    }

    @Test
    @DisplayName("배치재처리API_성공시_재배치가_기동된다")
    void triggersReprocessWhenFailed() {
        long rawSn = 2L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);
        when(orchestrator.processWithHeldStageClaim(rawSn)).thenReturn(BatchStage.COMPLETED);

        BatchReprocessResponse res = service.retry(rawSn);

        assertThat(res.rawSn()).isEqualTo(rawSn);
        assertThat(res.stage()).isEqualTo("COMPLETED");
        // 클레임 성공 후 유휴 대기 행만 리셋(RETRYING 부기 보존) → clearIfIdle.
        verify(retryQueue).clearIfIdle(rawSn);
        // B-ISSUE-01 — 클레임을 이미 보유한 경로이므로 <b>인계 전용 진입</b>을 써야 한다. 일반 진입을
        //   쓰면 진입 가드의 원자 클레임이 자기가 찍은 PROCESSING 에 막혀 재처리가 전부 409 가 된다.
        verify(orchestrator).processWithHeldStageClaim(rawSn);
        verify(orchestrator, never()).process(anyLong());
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

        // 클레임에 진 호출은 파이프라인을 기동하지 않고 큐도 건드리지 않는다(이중 실행 차단).
        verify(orchestrator, never()).processWithHeldStageClaim(anyLong());
        verify(retryQueue, never()).clearIfIdle(anyLong());
    }

    @Test
    @DisplayName("배치재처리_SKIPPED면_클레임을_보상롤백하고_409로_거부한다")
    void compensatesClaimAndRejectsWhenSkipped() {
        // DEV_FIX H10 — 클레임(FAILED→PROCESSING)은 작업 상태를 보지 않으므로, 검수 소유 상태 영상에서도
        //   성공한다. 이어지는 process() 가 SKIPPED 를 반환하면 markRawDataFailed/Completed 가 호출되지
        //   않아 PROCESSING 이 되돌려지지 않고 stage 가 영구 고착됐다(이후 모든 재처리 409).
        long rawSn = 7L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);
        when(orchestrator.processWithHeldStageClaim(rawSn)).thenReturn(BatchStage.SKIPPED);

        assertThatThrownBy(() -> service.retry(rawSn))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // ① 클레임 보상 롤백 — stage 를 FAILED 로 되돌려 고착을 남기지 않는다.
        verify(transitionService).releaseReprocessClaim(rawSn);
    }

    @Test
    @DisplayName("배치재처리_정상완료시에는_보상롤백을_하지_않는다")
    void doesNotCompensateOnNormalCompletion() {
        long rawSn = 8L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);
        when(orchestrator.processWithHeldStageClaim(rawSn)).thenReturn(BatchStage.COMPLETED);

        service.retry(rawSn);

        verify(transitionService, never()).releaseReprocessClaim(anyLong());
    }

    @Test
    @DisplayName("배치재처리_파이프라인_FAILED_종료는_409가_아니라_정상응답이다")
    void failedStageIsReportedNotRejected() {
        // 보상 롤백 분기가 "실패로 끝난 재처리"까지 409 로 바꿔 버리지 않는지 고정(과잉 차단 회귀 방지).
        long rawSn = 9L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);
        when(orchestrator.processWithHeldStageClaim(rawSn)).thenReturn(BatchStage.FAILED);

        BatchReprocessResponse res = service.retry(rawSn);

        assertThat(res.stage()).isEqualTo("FAILED");
        verify(transitionService, never()).releaseReprocessClaim(anyLong());
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
        verify(orchestrator, never()).processWithHeldStageClaim(anyLong());
    }
}
