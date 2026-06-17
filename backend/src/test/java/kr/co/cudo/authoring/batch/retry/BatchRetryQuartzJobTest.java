package kr.co.cudo.authoring.batch.retry;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-2 — 재시도 큐 잔존 방지 검증.
 *
 * <p>{@code process()} 가 try 블록 진입 전 단계(loadRaw NOT_FOUND, markRawDataProcessing 상태머신 위반)에서
 * 예외를 throw 하면 자체 catch 의 enqueueIfRetryable 을 호출하지 못한다. pollReady() 가 이미
 * nextAttemptAt=null 로 "처리중" 표시했으므로, QuartzJob 이 예외 시 큐를 재무장하지 않으면 엔트리가
 * 영구 잔존하여 재시도가 무음 중단된다. 본 테스트는 QuartzJob 이 예외 시 재무장함을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class BatchRetryQuartzJobTest {

    @Mock
    private BatchOrchestrator orchestrator;

    private final BatchRetryQueue retryQueue = new BatchRetryQueue(3, 60);

    private BatchRetryQuartzJob newJob() {
        BatchRetryQuartzJob job = new BatchRetryQuartzJob();
        ReflectionTestUtils.setField(job, "retryQueue", retryQueue);
        ReflectionTestUtils.setField(job, "orchestrator", orchestrator);
        return job;
    }

    @Test
    @DisplayName("process가_예외를_throw하면_재시도큐를_재무장하여_영구잔존_방지")
    void processThrows_reArmsRetryQueue() {
        // given — rawSn 이 큐에 등록되고, pollReady 가 처리중(nextAttemptAt=null)으로 표시되도록 즉시 도래시킨다.
        Long rawSn = 100L;
        retryQueue.enqueueIfRetryable(rawSn); // attempt=1
        forceReady(rawSn);
        // process 가 try 진입 전 예외(loadRaw NOT_FOUND 등)를 throw 하는 경로 시뮬레이션
        when(orchestrator.process(rawSn))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // when — Job 실행 (pollReady → process throw → catch 재무장)
        newJob().execute(null);

        // then — 엔트리가 제거/잔존이 아니라 재무장되어 nextAttemptAt 이 다시 채워진다.
        var entry = retryQueue.rawEntries().get(rawSn);
        assertThat(entry).as("엔트리가 잔존(=null처리중)으로 남지 않고 재무장돼야 한다").isNotNull();
        assertThat(entry.nextAttemptAt).as("nextAttemptAt 이 재무장되어 다음 pollReady 에서 다시 픽업 가능").isNotNull();
        // attempt 증가 확인 (enqueue 재호출됨) — 1(초기) → 2(재무장)
        assertThat(retryQueue.retryCount(rawSn)).isEqualTo(2);
    }

    @Test
    @DisplayName("process가_정상이면_재무장하지_않음")
    void processSucceeds_noReArm() {
        // given
        Long rawSn = 200L;
        retryQueue.enqueueIfRetryable(rawSn);
        forceReady(rawSn);
        when(orchestrator.process(rawSn))
                .thenReturn(kr.co.cudo.authoring.batch.orchestrator.BatchStage.COMPLETED);

        // when
        newJob().execute(null);

        // then — 예외가 없으므로 catch 의 재무장이 호출되지 않는다(attempt 그대로).
        assertThat(retryQueue.retryCount(rawSn)).isEqualTo(1);
    }

    @Test
    @DisplayName("maxAttempts_초과시_재무장은_FAILED_고정으로_종료")
    void reArmStopsAtMaxAttempts() {
        // given — max-attempts=3. attempt 를 3까지 끌어올린 뒤 예외 → 재무장 시 attempt=4 > max → false.
        Long rawSn = 300L;
        retryQueue.enqueueIfRetryable(rawSn); // 1
        retryQueue.enqueueIfRetryable(rawSn); // 2
        retryQueue.enqueueIfRetryable(rawSn); // 3
        forceReady(rawSn);
        when(orchestrator.process(rawSn))
                .thenThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "fail"));

        // when
        newJob().execute(null);

        // then — 재무장 시 attempt=4 가 max(3) 초과 → enqueueIfRetryable false → nextAttemptAt 미설정(FAILED 고정).
        var entry = retryQueue.rawEntries().get(rawSn);
        assertThat(entry).isNotNull();
        assertThat(entry.nextAttemptAt).as("max 초과면 재무장하지 않아 더 이상 픽업되지 않는다").isNull();
    }

    /** pollReady 가 즉시 픽업하도록 nextAttemptAt 을 과거로 강제. */
    private void forceReady(Long rawSn) {
        var entry = retryQueue.rawEntries().get(rawSn);
        // rawEntries() 는 복사본이므로 원본 엔트리에 직접 접근해 nextAttemptAt 을 과거로 설정.
        // 동일 객체 참조이므로(맵만 복사) 필드 변경은 원본에 반영된다.
        entry.nextAttemptAt = java.time.Instant.now().minusSeconds(1);
    }
}
