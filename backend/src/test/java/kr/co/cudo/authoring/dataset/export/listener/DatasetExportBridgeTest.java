package kr.co.cudo.authoring.dataset.export.listener;

import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * {@link DatasetExportBridge} 단위 테스트 — ReviewApprovedEvent(AFTER_COMMIT) 수신 시
 * {@link AsyncDatasetExportRunner} 로 위임하는지 검증(얇은 브릿지).
 */
@ExtendWith(MockitoExtension.class)
class DatasetExportBridgeTest {

    @Mock
    private AsyncDatasetExportRunner runner;

    @InjectMocks
    private DatasetExportBridge bridge;

    @Test
    @DisplayName("ReviewApprovedEvent_수신_시_AsyncDatasetExportRunner_위임")
    void onReviewApproved_delegatesToRunner() {
        ReviewApprovedEvent event = new ReviewApprovedEvent(99L, 1L, Instant.now());

        bridge.onReviewApproved(event);

        verify(runner).runAsync(eq(99L));
    }
}
