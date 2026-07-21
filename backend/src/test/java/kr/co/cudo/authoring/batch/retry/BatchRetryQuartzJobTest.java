package kr.co.cudo.authoring.batch.retry;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-2 — 재시도 큐 잔존 방지 검증 (B2 DB 큐 재작성 반영, mock 기반).
 *
 * <p>{@code process()} 가 try 블록 진입 전 단계(loadRaw NOT_FOUND, markRawDataProcessing 상태머신 위반)에서
 * 예외를 throw 하면 자체 catch 의 enqueueIfRetryable 을 호출하지 못한다. QuartzJob 이 예외 시 재무장(전이적
 * 실패) 또는 큐 제거(영구 실패 NOT_FOUND)를 수행함을 검증한다. 재시도 큐 자체는 DB 영속으로 전환되어
 * ({@link BatchRetryQueue}) 여기서는 mock 으로 상호작용만 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class BatchRetryQuartzJobTest {

    @Mock
    private BatchOrchestrator orchestrator;

    @Mock
    private BatchRetryQueue retryQueue;

    private BatchRetryQuartzJob newJob() {
        BatchRetryQuartzJob job = new BatchRetryQuartzJob();
        ReflectionTestUtils.setField(job, "retryQueue", retryQueue);
        ReflectionTestUtils.setField(job, "orchestrator", orchestrator);
        return job;
    }

    @Test
    @DisplayName("M1_NOT_FOUND_영구실패시_재무장않고_큐에서제거_FAILED확정")
    void processThrowsNotFound_clearsQueueNoReArm() {
        Long rawSn = 100L;
        when(retryQueue.pollReady()).thenReturn(Optional.of(rawSn));
        when(orchestrator.process(rawSn))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        newJob().execute(null);

        // 영구 실패 → 큐에서 제거(clear), 재무장(enqueue) 없음.
        verify(retryQueue).clear(rawSn);
        verify(retryQueue, never()).enqueueIfRetryable(rawSn);
    }

    @Test
    @DisplayName("M1_일시적_RuntimeException은_기존대로_재무장하여_영구잔존_방지")
    void processThrowsTransient_reArmsRetryQueue() {
        Long rawSn = 110L;
        when(retryQueue.pollReady()).thenReturn(Optional.of(rawSn));
        when(orchestrator.process(rawSn))
                .thenThrow(new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 서버 일시 오류"));

        newJob().execute(null);

        // 전이적 실패 → 재무장(enqueue), clear 없음.
        verify(retryQueue).enqueueIfRetryable(rawSn);
        verify(retryQueue, never()).clear(rawSn);
    }

    @Test
    @DisplayName("process가_정상이면_재무장하지_않음")
    void processSucceeds_noReArm() {
        Long rawSn = 200L;
        when(retryQueue.pollReady()).thenReturn(Optional.of(rawSn));
        when(orchestrator.process(rawSn)).thenReturn(BatchStage.COMPLETED);

        newJob().execute(null);

        verify(retryQueue, never()).enqueueIfRetryable(rawSn);
        verify(retryQueue, never()).clear(rawSn);
    }

    @Test
    @DisplayName("pollReady_비어있으면_process_미호출")
    void emptyQueue_noProcess() {
        when(retryQueue.pollReady()).thenReturn(Optional.empty());

        newJob().execute(null);

        verify(orchestrator, never()).process(org.mockito.ArgumentMatchers.anyLong());
    }
}
