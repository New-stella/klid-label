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

        verify(orchestrator, never()).process(anyLong());
        verify(retryQueue, never()).clearIfIdle(anyLong());
    }

    @Test
    @DisplayName("배치재처리API_성공시_재배치가_기동된다")
    void triggersReprocessWhenFailed() {
        long rawSn = 2L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromFailed(rawSn)).thenReturn(true);
        when(orchestrator.process(rawSn)).thenReturn(BatchStage.COMPLETED);

        BatchReprocessResponse res = service.retry(rawSn);

        assertThat(res.rawSn()).isEqualTo(rawSn);
        assertThat(res.stage()).isEqualTo("COMPLETED");
        // 클레임 성공 후 유휴 대기 행만 리셋(RETRYING 부기 보존) → clearIfIdle.
        verify(retryQueue).clearIfIdle(rawSn);
        verify(orchestrator).process(rawSn);
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
        verify(orchestrator, never()).process(anyLong());
        verify(retryQueue, never()).clearIfIdle(anyLong());
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
        verify(orchestrator, never()).process(anyLong());
    }
}
