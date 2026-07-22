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
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
 * <p>D1/D2/FIX B(CRITICAL 재설계): BATCH_QUEUED 클레임은 2단계 독립 트랜잭션으로 위임된다.
 * tx1 {@link BatchTransitionService#tryClaimBatchQueued}(조건부 원자 전이) → false 면 tx2
 * {@link BatchTransitionService#tryCreateBatchQueuedRow}(row 부재 시 별도 tx 로 생성). tx2 가
 * 동시 노드와 경합해 {@link DataIntegrityViolationException} 을 던지면 브리지가 잡아 skip 한다 →
 * 정확히 1건만 배치를 트리거한다.
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
    @DisplayName("이벤트_수신_시_tx1_조건부전이가_성공하면_트리거한다 — row생성_불필요")
    void onMarkingCompleted_tx1Claims_triggers() {
        // given — 비식별 완료 영상 + tx1 전이 권한 획득(true)
        Long rawSn = 1L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidentifiedRaw()));
        when(batchTransitionService.tryClaimBatchQueued(eq(rawSn), anyCollection())).thenReturn(true);

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 100L);

        // when
        bridge.onMarkingCompleted(event);

        // then — tx1 만으로 클레임 성공, tx2(row 생성)는 호출하지 않음
        verify(batchTransitionService).tryClaimBatchQueued(eq(rawSn), anyCollection());
        verify(batchTransitionService, never()).tryCreateBatchQueuedRow(any());
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

        // then — 배치 트리거 차단 + 클레임 시도조차 안 함
        verify(batchTransitionService, never()).tryClaimBatchQueued(any(), anyCollection());
        verify(batchTransitionService, never()).tryCreateBatchQueuedRow(any());
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
        verify(batchTransitionService, never()).tryCreateBatchQueuedRow(any());
        verify(asyncBatchRunner, never()).runAsync(rawSn);
    }

    @Test
    @DisplayName("tx1_false_and_tx2_row존재로_false면_스킵 — 이미_진행중(BATCH_QUEUED/PROCESSING/COMPLETED)")
    void onMarkingCompleted_bothClaimFalse_skips() {
        // given — 비식별 완료지만 tx1 조건부 전이 false(이미 SKIP) + tx2 도 row 존재로 false.
        Long rawSn = 3L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidentifiedRaw()));
        when(batchTransitionService.tryClaimBatchQueued(eq(rawSn), anyCollection())).thenReturn(false);
        when(batchTransitionService.tryCreateBatchQueuedRow(eq(rawSn))).thenReturn(false);

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 300L);

        // when
        bridge.onMarkingCompleted(event);

        // then — 트리거 차단
        verify(batchStatusService, never()).markStage(eq(rawSn), eq(BatchStage.PENDING));
        verify(asyncBatchRunner, never()).runAsync(rawSn);
    }

    @Test
    @DisplayName("FIX_B_미배정영상_작업상태row_없어도_tx2가_row생성후_true면_배치가_트리거된다")
    void onMarkingCompleted_unassignedRowAbsent_tx2Creates_triggers() {
        // given — 미배정 REVIEWER 직접 마킹: tx1 은 row 부재로 false, tx2 가 row 를 생성해 true 반환(고착 제거).
        Long rawSn = 55L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidentifiedRaw()));
        when(batchTransitionService.tryClaimBatchQueued(eq(rawSn), anyCollection())).thenReturn(false);
        when(batchTransitionService.tryCreateBatchQueuedRow(eq(rawSn))).thenReturn(true);

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 5500L);

        // when
        bridge.onMarkingCompleted(event);

        // then — 배치 트리거됨(MARKING_READY 고착 안 됨)
        verify(batchTransitionService).tryClaimBatchQueued(eq(rawSn), anyCollection());
        verify(batchTransitionService).tryCreateBatchQueuedRow(eq(rawSn));
        verify(batchStatusService).markStage(eq(rawSn), eq(BatchStage.PENDING));
        verify(asyncBatchRunner).runAsync(eq(rawSn));
    }

    @Test
    @DisplayName("동시_row생성경합_tx2가_DataIntegrityViolationException_던지면_잡아서_스킵 — 정확히1건만_트리거(CWE-362)")
    void onMarkingCompleted_tx2ConcurrentInsertConflict_skips() {
        // given — tx1 false(row 부재) + tx2 saveAndFlush 가 PK 충돌(다른 노드가 먼저 생성) → DIV 전파.
        Long rawSn = 56L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidentifiedRaw()));
        when(batchTransitionService.tryClaimBatchQueued(eq(rawSn), anyCollection())).thenReturn(false);
        when(batchTransitionService.tryCreateBatchQueuedRow(eq(rawSn)))
                .thenThrow(new DataIntegrityViolationException("dup pk"));

        MarkingCompletedEvent event = new MarkingCompletedEvent(rawSn, 5600L);

        // when — 예외가 브리지 밖으로 전파되지 않고 내부에서 잡혀 조용히 skip 되어야 한다
        bridge.onMarkingCompleted(event);

        // then — 이 노드는 트리거하지 않는다(상대 노드가 소유)
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

        // then — 배치 재트리거 없음 + 클레임 시도조차 안 함 + LsDataRaw 배치 단계 COMPLETED 불변
        verify(batchTransitionService, never()).tryClaimBatchQueued(any(), anyCollection());
        verify(batchTransitionService, never()).tryCreateBatchQueuedRow(any());
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
        verify(batchTransitionService, never()).tryCreateBatchQueuedRow(any());
        verify(asyncBatchRunner, never()).runAsync(rawSn);
        org.assertj.core.api.Assertions.assertThat(raw.getDataSttsCd())
                .isEqualTo(LsDataRaw.DATA_STTS_PROCESSING);
    }

    @Test
    @DisplayName("MARKING_READY_최초_마킹은_정상적으로_1회_트리거된다")
    void onMarkingCompleted_markingReady_triggersOnce() {
        // given — 비식별 완료 + MARKING_READY + tx1 전이 권한 획득(true) 인 최초 마킹
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
    @DisplayName("동일_rawSn_마킹이벤트_2회_동시발생시_tx1이_1건만_true라_배치는_1회만_트리거된다")
    void onMarkingCompleted_concurrentDoubleMarking_triggersOnce() throws Exception {
        // given — 동일 rawSn 에 비식별 완료 영상. tx1 조건부 원자 전이는 DB 직렬화로 정확히 1건만 true 를
        //         반환한다. 이를 첫 호출만 true, 이후 호출은 false 를 반환하는 스텁으로 모사한다.
        //         두 번째 호출은 tx2 로 넘어가지만 row 가 이미 존재해 false(멱등 스킵).
        Long rawSn = 30L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(deidentifiedRaw()));

        AtomicInteger claimCount = new AtomicInteger(0);
        when(batchTransitionService.tryClaimBatchQueued(eq(rawSn), anyCollection()))
                .thenAnswer(inv -> claimCount.getAndIncrement() == 0);
        lenient().when(batchTransitionService.tryCreateBatchQueuedRow(eq(rawSn))).thenReturn(false);

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
        verify(batchTransitionService, never()).tryCreateBatchQueuedRow(any());
        verify(batchStatusService, never()).markStage(eq(rawSn), eq(BatchStage.PENDING));
        verify(asyncBatchRunner, never()).runAsync(rawSn);
    }
}
