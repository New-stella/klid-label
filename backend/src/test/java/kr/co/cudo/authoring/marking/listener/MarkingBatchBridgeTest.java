package kr.co.cudo.authoring.marking.listener;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.runner.AsyncBatchRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MarkingBatchBridge 단위 테스트 (Mockito).
 */
@ExtendWith(MockitoExtension.class)
class MarkingBatchBridgeTest {

    @Mock
    private LsRawDataStatusRepository statusRepository;

    @Mock
    private BatchStatusService batchStatusService;

    @Mock
    private AsyncBatchRunner asyncBatchRunner;

    @InjectMocks
    private MarkingBatchBridge bridge;

    @Test
    @DisplayName("이벤트_수신_시_상태_BATCH_QUEUED_전이")
    void onMarkingCompleted_assignedStatus_transitionsToBatchQueued() {
        // given
        Long rawSn = 1L;
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.markAssigned();
        when(statusRepository.findById(rawSn)).thenReturn(Optional.of(stts));

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 100L);

        // when
        bridge.onMarkingCompleted(event);

        // then
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_BATCH_QUEUED);
        verify(batchStatusService).markStage(eq(rawSn), eq(BatchStage.PENDING));
        verify(asyncBatchRunner).runAsync(eq(rawSn));
    }

    @Test
    @DisplayName("이미_PROCESSING인_영상_스킵")
    void onMarkingCompleted_processingStatus_skips() {
        // given
        Long rawSn = 2L;
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo("PROCESSING");
        when(statusRepository.findById(rawSn)).thenReturn(Optional.of(stts));

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 200L);

        // when
        bridge.onMarkingCompleted(event);

        // then
        assertThat(stts.getDataSttsCd()).isEqualTo("PROCESSING");
        verify(asyncBatchRunner, never()).runAsync(rawSn);
    }

    @Test
    @DisplayName("이미_BATCH_QUEUED인_영상_스킵")
    void onMarkingCompleted_batchQueuedStatus_skips() {
        // given
        Long rawSn = 3L;
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.markBatchQueued();
        when(statusRepository.findById(rawSn)).thenReturn(Optional.of(stts));

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 300L);

        // when
        bridge.onMarkingCompleted(event);

        // then
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_BATCH_QUEUED);
        verify(asyncBatchRunner, never()).runAsync(rawSn);
    }

    @Test
    @DisplayName("이미_COMPLETED인_영상_스킵")
    void onMarkingCompleted_completedStatus_skips() {
        // given
        Long rawSn = 4L;
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo("COMPLETED");
        when(statusRepository.findById(rawSn)).thenReturn(Optional.of(stts));

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 400L);

        // when
        bridge.onMarkingCompleted(event);

        // then
        assertThat(stts.getDataSttsCd()).isEqualTo("COMPLETED");
        verify(asyncBatchRunner, never()).runAsync(rawSn);
    }

    @Test
    @DisplayName("상태_행_미존재시_스킵")
    void onMarkingCompleted_noStatusRow_skips() {
        // given
        Long rawSn = 999L;
        when(statusRepository.findById(rawSn)).thenReturn(Optional.empty());

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 500L);

        // when
        bridge.onMarkingCompleted(event);

        // then
        verify(batchStatusService, never()).markStage(eq(rawSn), eq(BatchStage.PENDING));
        verify(asyncBatchRunner, never()).runAsync(rawSn);
    }
}
