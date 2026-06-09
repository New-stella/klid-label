package kr.co.cudo.authoring.batch.listener;

import kr.co.cudo.authoring.batch.runner.AsyncDeidentifyRunner;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * IngestDeidentifyBridge 단위 테스트 (Phase 2).
 *
 * <p>VideoIngestedEvent 수신 시 AsyncDeidentifyRunner.runAsync 로 위임하는지 검증.
 * (MarkingBatchBridge 와 동일 구조 — AFTER_COMMIT 리스너는 동기, @Async 는 러너가 분리.)
 */
@ExtendWith(MockitoExtension.class)
class IngestDeidentifyBridgeTest {

    @Mock
    private AsyncDeidentifyRunner asyncDeidentifyRunner;

    @InjectMocks
    private IngestDeidentifyBridge bridge;

    @Test
    @DisplayName("VideoIngestedEvent_수신_시_AsyncDeidentifyRunner_위임")
    void onVideoIngested_delegatesToRunner() {
        VideoIngestedEvent event = new VideoIngestedEvent(42L);

        bridge.onVideoIngested(event);

        verify(asyncDeidentifyRunner).runAsync(eq(42L));
    }
}
