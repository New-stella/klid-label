package kr.co.cudo.authoring.marking.listener;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.runner.AsyncBatchRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MarkingBatchBridge 단위 테스트 (Mockito).
 *
 * <p>Phase 2: 비식별 미완료 영상은 배치 트리거를 차단한다 (deIdntfYn != 'Y' → skip).
 * 정상흐름 테스트는 deIdntfYn='Y' 인 비식별 완료 영상을 전제로 한다.
 */
@ExtendWith(MockitoExtension.class)
class MarkingBatchBridgeTest {

    @Mock
    private LsRawDataStatusRepository statusRepository;

    @Mock
    private BatchStatusService batchStatusService;

    @Mock
    private AsyncBatchRunner asyncBatchRunner;

    @Mock
    private VideoRepository videoRepository;

    @InjectMocks
    private MarkingBatchBridge bridge;

    /** 비식별 완료(deIdntfYn='Y') 영상 stub. */
    private LsDataRaw deidentifiedRaw() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip", "cctv-1", "EVT", "GOV", LsDataRaw.PRVC_TYPE_PRVC, "raw/path.mp4", null, 60);
        raw.markDeidentified("Y");
        return raw;
    }

    private LsDataRaw rawWithDeid(String deidCode) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip", "cctv-1", "EVT", "GOV", LsDataRaw.PRVC_TYPE_PRVC, "raw/path.mp4", null, 60);
        raw.markDeidentified(deidCode);
        return raw;
    }

    @Test
    @DisplayName("이벤트_수신_시_상태_BATCH_QUEUED_전이")
    void onMarkingCompleted_assignedStatus_transitionsToBatchQueued() {
        // given — 비식별 완료 영상
        Long rawSn = 1L;
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.markAssigned();
        when(statusRepository.findById(rawSn)).thenReturn(Optional.of(stts));
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidentifiedRaw()));

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 100L);

        // when
        bridge.onMarkingCompleted(event);

        // then
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_BATCH_QUEUED);
        verify(batchStatusService).markStage(eq(rawSn), eq(BatchStage.PENDING));
        verify(asyncBatchRunner).runAsync(eq(rawSn));
    }

    @Test
    @DisplayName("비식별_미완료영상은_배치트리거_안함 — deIdntfYn=N")
    void onMarkingCompleted_notDeidentified_skips() {
        // given — 비식별 미완료(N)
        Long rawSn = 11L;
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.markAssigned();
        when(statusRepository.findById(rawSn)).thenReturn(Optional.of(stts));
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(rawWithDeid("N")));

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 1100L);

        // when
        bridge.onMarkingCompleted(event);

        // then — 배치 트리거 차단
        verify(asyncBatchRunner, never()).runAsync(rawSn);
    }

    @Test
    @DisplayName("비식별_실패영상은_배치트리거_안함 — deIdntfYn=F")
    void onMarkingCompleted_deidentFailed_skips() {
        // given — 비식별 실패(F)
        Long rawSn = 12L;
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.markAssigned();
        when(statusRepository.findById(rawSn)).thenReturn(Optional.of(stts));
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(rawWithDeid("F")));

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 1200L);

        // when
        bridge.onMarkingCompleted(event);

        // then
        verify(asyncBatchRunner, never()).runAsync(rawSn);
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
