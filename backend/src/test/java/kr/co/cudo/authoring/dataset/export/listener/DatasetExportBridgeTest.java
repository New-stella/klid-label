package kr.co.cudo.authoring.dataset.export.listener;

import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
import kr.co.cudo.authoring.dataset.export.event.DatasetReExportEvent;
import kr.co.cudo.authoring.label.event.DeidentReportResolvedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    @DisplayName("onReviewApproved는_runApprovalAsync를_호출한다 (R6 강제 재생성 + C-2 export후 완료통지)")
    void onReviewApproved_delegatesToApprovalRunner() {
        ReviewApprovedEvent event = new ReviewApprovedEvent(99L, 1L, Instant.now());

        bridge.onReviewApproved(event);

        // C-2 — 승인 경로는 export 종결 후 완료 이벤트를 발행하는 전용 러너로 위임한다.
        verify(runner).runApprovalAsync(eq(99L));
    }

    @Test
    @DisplayName("onReExport는_forceFalse로_runAsync를_호출한다 (재동결 — 현행 멱등 유지)")
    void onReExport_delegatesWithForceFalse() {
        DatasetReExportEvent event = new DatasetReExportEvent(77L);

        bridge.onReExport(event);

        verify(runner).runAsync(eq(77L), eq(false));
    }

    // ─── 신고 해소 → export 재산출 (발행 측이 APPROVED 를 게이팅한다) ───

    @Test
    @DisplayName("신고_해소_이벤트를_받으면_export_를_전량_재생성한다")
    void onDeidentReportResolved_triggersExport() {
        bridge.onDeidentReportResolved(new DeidentReportResolvedEvent(88L));

        verify(runner).runApprovalAsync(eq(88L));
    }
}
