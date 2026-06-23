package kr.co.cudo.authoring.marking.listener;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.runner.AsyncBatchRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MarkingBatchBridge 단위 테스트 (Mockito).
 *
 * <p>Phase 2: 비식별 미완료 영상은 배치 트리거를 차단한다 (deIdntfYn != 'Y' → skip).
 * 정상흐름 테스트는 deIdntfYn='Y' 인 비식별 완료 영상을 전제로 한다.
 *
 * <p>D1/D2: BATCH_QUEUED 전이는 {@link BatchTransitionService#tryClaimBatchQueued} 의 조건부
 * 원자 전이(check-and-set)로 위임된다. 트리거 여부는 이 메서드의 반환값(true=권한 획득)에 좌우되며,
 * 동시 2개 마킹 이벤트는 그 중 1건만 true 를 받아 배치가 1회만 트리거된다.
 */
@ExtendWith(MockitoExtension.class)
class MarkingBatchBridgeTest {

    @Mock
    private BatchTransitionService batchTransitionService;

    @Mock
    private BatchStatusService batchStatusService;

    @Mock
    private AsyncBatchRunner asyncBatchRunner;

    @Mock
    private VideoRepository videoRepository;

    @InjectMocks
    private MarkingBatchBridge bridge;

    /** 비식별 완료(deIdntfYn='Y') + 마킹 준비(MARKING_READY) 영상 stub — 정상 트리거 대상. */
    private LsDataRaw deidentifiedRaw() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip", "cctv-1", "EVT", "GOV", LsDataRaw.PRVC_TYPE_PRVC, "raw/path.mp4", null, 60);
        raw.markDeidentified("Y");
        raw.markMarkingReady();
        return raw;
    }

    /** 비식별 완료 + 지정한 배치 단계(DATA_STTS_CD) 영상 stub. */
    private LsDataRaw deidentifiedRawWithStage(String dataSttsCd) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip", "cctv-1", "EVT", "GOV", LsDataRaw.PRVC_TYPE_PRVC, "raw/path.mp4", null, 60);
        raw.markDeidentified("Y");
        raw.changeStatus(dataSttsCd);
        return raw;
    }

    private LsDataRaw rawWithDeid(String deidCode) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip", "cctv-1", "EVT", "GOV", LsDataRaw.PRVC_TYPE_PRVC, "raw/path.mp4", null, 60);
        raw.markDeidentified(deidCode);
        return raw;
    }

    @Test
    @DisplayName("이벤트_수신_시_BATCH_QUEUED_전이를_원자전이서비스에_위임하고_트리거한다")
    void onMarkingCompleted_assignedStatus_claimsAndTriggers() {
        // given — 비식별 완료 영상 + 전이 권한 획득(true)
        Long rawSn = 1L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidentifiedRaw()));
        when(batchTransitionService.tryClaimBatchQueued(eq(rawSn), anyCollection())).thenReturn(true);

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 100L);

        // when
        bridge.onMarkingCompleted(event);

        // then
        verify(batchTransitionService).tryClaimBatchQueued(eq(rawSn), anyCollection());
        verify(batchStatusService).markStage(eq(rawSn), eq(BatchStage.PENDING));
        verify(asyncBatchRunner).runAsync(eq(rawSn));
    }

    @Test
    @DisplayName("비식별_미완료영상은_배치트리거_안함 — deIdntfYn=N")
    void onMarkingCompleted_notDeidentified_skips() {
        // given — 비식별 미완료(N)
        Long rawSn = 11L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(rawWithDeid("N")));

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 1100L);

        // when
        bridge.onMarkingCompleted(event);

        // then — 배치 트리거 차단 + 전이 시도조차 안 함
        verify(batchTransitionService, never()).tryClaimBatchQueued(any(), anyCollection());
        verify(asyncBatchRunner, never()).runAsync(rawSn);
    }

    @Test
    @DisplayName("비식별_실패영상은_배치트리거_안함 — deIdntfYn=F")
    void onMarkingCompleted_deidentFailed_skips() {
        // given — 비식별 실패(F)
        Long rawSn = 12L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(rawWithDeid("F")));

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 1200L);

        // when
        bridge.onMarkingCompleted(event);

        // then
        verify(batchTransitionService, never()).tryClaimBatchQueued(any(), anyCollection());
        verify(asyncBatchRunner, never()).runAsync(rawSn);
    }

    @Test
    @DisplayName("전이권한_미획득시_스킵 — 이미_진행중(BATCH_QUEUED/PROCESSING/COMPLETED)")
    void onMarkingCompleted_claimFailed_skips() {
        // given — 비식별 완료지만 조건부 전이가 false(이미 진행 중 또는 row 없음)
        Long rawSn = 3L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidentifiedRaw()));
        when(batchTransitionService.tryClaimBatchQueued(eq(rawSn), anyCollection())).thenReturn(false);

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 300L);

        // when
        bridge.onMarkingCompleted(event);

        // then — 트리거 차단
        verify(batchStatusService, never()).markStage(eq(rawSn), eq(BatchStage.PENDING));
        verify(asyncBatchRunner, never()).runAsync(rawSn);
    }

    // ── 배치 단계(LsDataRaw.DATA_STTS_CD) 역전 차단 가드 ──

    @Test
    @DisplayName("배치_COMPLETED_영상에_마킹이벤트_재발생시_배치_재트리거되지_않고_LsDataRaw_가_COMPLETED_유지된다")
    void onMarkingCompleted_rawDataCompleted_skips() {
        // given — LsDataRaw 가 COMPLETED (배치 단계 가드에서 차단).
        Long rawSn = 20L;
        LsDataRaw raw = deidentifiedRawWithStage(LsDataRaw.DATA_STTS_COMPLETED);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 2000L);

        // when
        bridge.onMarkingCompleted(event);

        // then — 배치 재트리거 없음 + 전이 시도조차 안 함 + LsDataRaw 배치 단계 COMPLETED 불변
        verify(batchTransitionService, never()).tryClaimBatchQueued(any(), anyCollection());
        verify(asyncBatchRunner, never()).runAsync(rawSn);
        verify(batchStatusService, never()).markStage(eq(rawSn), eq(BatchStage.PENDING));
        org.assertj.core.api.Assertions.assertThat(raw.getDataSttsCd())
                .isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
    }

    @Test
    @DisplayName("배치_PROCESSING_중_마킹이벤트_재발생시_재트리거되지_않는다")
    void onMarkingCompleted_rawDataProcessing_skips() {
        // given — LsDataRaw 가 PROCESSING(배치 진행 중)
        Long rawSn = 21L;
        LsDataRaw raw = deidentifiedRawWithStage(LsDataRaw.DATA_STTS_PROCESSING);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 2100L);

        // when
        bridge.onMarkingCompleted(event);

        // then
        verify(batchTransitionService, never()).tryClaimBatchQueued(any(), anyCollection());
        verify(asyncBatchRunner, never()).runAsync(rawSn);
        org.assertj.core.api.Assertions.assertThat(raw.getDataSttsCd())
                .isEqualTo(LsDataRaw.DATA_STTS_PROCESSING);
    }

    @Test
    @DisplayName("MARKING_READY_최초_마킹은_정상적으로_1회_트리거된다")
    void onMarkingCompleted_markingReady_triggersOnce() {
        // given — 비식별 완료 + MARKING_READY + 전이 권한 획득(true) 인 최초 마킹
        Long rawSn = 22L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidentifiedRaw()));
        when(batchTransitionService.tryClaimBatchQueued(eq(rawSn), anyCollection())).thenReturn(true);

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 2200L);

        // when
        bridge.onMarkingCompleted(event);

        // then — 정상 트리거(회귀 가드) 정확히 1회
        verify(batchStatusService, times(1)).markStage(eq(rawSn), eq(BatchStage.PENDING));
        verify(asyncBatchRunner, times(1)).runAsync(eq(rawSn));
    }

    @Test
    @DisplayName("동일_rawSn_마킹이벤트_2회_동시발생시_배치는_1회만_트리거된다")
    void onMarkingCompleted_concurrentDoubleMarking_triggersOnce() throws Exception {
        // given — 동일 rawSn 에 비식별 완료 영상. 조건부 원자 전이는 DB 직렬화로 정확히 1건만 true 를
        //         반환한다. 이를 첫 호출만 true, 이후 호출은 false 를 반환하는 스텁으로 모사한다.
        Long rawSn = 30L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidentifiedRaw()));

        AtomicInteger claimCount = new AtomicInteger(0);
        when(batchTransitionService.tryClaimBatchQueued(eq(rawSn), anyCollection()))
                .thenAnswer(inv -> claimCount.getAndIncrement() == 0);

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 3000L);

        // when — 두 마킹 이벤트를 (의사)동시 호출
        Runnable task = () -> bridge.onMarkingCompleted(event);
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // then — 두 브리지 중 1건만 전이 권한 획득 → 배치 트리거 정확히 1회
        verify(asyncBatchRunner, times(1)).runAsync(eq(rawSn));
        verify(batchStatusService, times(1)).markStage(eq(rawSn), eq(BatchStage.PENDING));
    }

    @Test
    @DisplayName("영상_행_미존재시_스킵")
    void onMarkingCompleted_noRawVideo_skips() {
        // given — 영상 row 없음
        Long rawSn = 999L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.empty());

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 500L);

        // when
        bridge.onMarkingCompleted(event);

        // then
        verify(batchTransitionService, never()).tryClaimBatchQueued(any(), anyCollection());
        verify(batchStatusService, never()).markStage(eq(rawSn), eq(BatchStage.PENDING));
        verify(asyncBatchRunner, never()).runAsync(rawSn);
    }
}
