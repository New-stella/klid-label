package kr.co.cudo.authoring.dataset.export.listener;

import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
import kr.co.cudo.authoring.dataset.export.event.DatasetReExportEvent;
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
    @DisplayName("onReviewApproved는_forceTrue로_runAsync를_호출한다 (R6 — 승인마다 강제 재생성)")
    void onReviewApproved_delegatesWithForceTrue() {
        ReviewApprovedEvent event = new ReviewApprovedEvent(99L, 1L, Instant.now());

        bridge.onReviewApproved(event);

        verify(runner).runAsync(eq(99L), eq(true));
    }

    @Test
    @DisplayName("onReExport는_forceFalse로_runAsync를_호출한다 (재동결 — 현행 멱등 유지)")
    void onReExport_delegatesWithForceFalse() {
        DatasetReExportEvent event = new DatasetReExportEvent(77L);

        bridge.onReExport(event);

        verify(runner).runAsync(eq(77L), eq(false));
    }
}
