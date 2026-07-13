package kr.co.cudo.authoring.batch.listener;

import kr.co.cudo.authoring.batch.runner.AsyncVideoMetaRunner;
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
 * {@link VideoMetaExtractBridge} 단위 테스트 — NIA export Phase 2.
 *
 * <p>VideoIngestedEvent 수신 시 rawSn 을 추출해 AsyncVideoMetaRunner.runAsync 로 위임하는지 검증한다
 * (자매 {@code IngestDeidentifyBridgeTest} 와 동일 구조 — AFTER_COMMIT 리스너는 동기 위임, @Async 는 러너 분리).
 */
@ExtendWith(MockitoExtension.class)
class VideoMetaExtractBridgeTest {

    @Mock
    private AsyncVideoMetaRunner asyncVideoMetaRunner;

    @InjectMocks
    private VideoMetaExtractBridge bridge;

    @Test
    @DisplayName("VideoIngestedEvent_수신시_AsyncVideoMetaRunner_위임")
    void onVideoIngested_delegatesToRunner() {
        // given
        VideoIngestedEvent event = new VideoIngestedEvent(77L);

        // when
        bridge.onVideoIngested(event);

        // then: 이벤트의 rawSn 으로 러너에 위임
        verify(asyncVideoMetaRunner).runAsync(eq(77L));
    }
}
