package kr.co.cudo.authoring.batch.guard;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.runner.AsyncBatchRunner;
import kr.co.cudo.authoring.batch.service.BatchReprocessService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.status.LsBatchProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import kr.co.cudo.authoring.marking.listener.MarkingBatchBridge;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * DEV_FIX H1-a·H8·H9 회귀 IT — <b>배치 진입 경로</b>(마킹 완료 브리지 / 오케스트레이터 직접 호출)에서
 * 검수 소유 상태(APPROVED·PENDING 등) 영상의 배치가 <b>큐잉되지도 실행되지도 않는지</b>를
 * 실 DB(PostgreSQL Testcontainer)에서 고정한다.
 *
 * <h3>왜 기존 IT 로 부족했나 (H9)</h3>
 * 기존 {@code BatchTransitionReviewGuardIT} 는 {@code markRawDataProcessing/Completed/Failed} 를 직접
 * 호출할 뿐 {@link MarkingBatchBridge} → {@code tryClaimBatchQueued} 진입 경로를 타지 않았다. 실경로에서는
 * APPROVED 가 <b>먼저 BATCH_QUEUED 로 덮인 뒤</b> 오케스트레이터가 보는 현재값이 비-차단 상태가 되어
 * 새 가드가 한 번도 발화하지 않았다(H1-a).
 *
 * <h3>왜 "상태 보존"만으로 부족한가 (H8)</h3>
 * 상태 write 만 막고 파이프라인을 계속 돌리면 APPROVED 영상에 AUTO 라벨이 새로 적재되면서 상태는 APPROVED 로
 * 남아, export 폴더 JSON·{@code V_COMPLETED_*} 와 {@code LS_DATA_LBL} 이 재검수 없이 어긋나는
 * <b>탐지 불가능한 오염</b>이 된다. 따라서 각 테스트는 ①상태 보존 ②step 미실행(배치 로그·라벨 미생성)을
 * <b>둘 다</b> 단언한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class BatchReviewOwnedEntryGuardIT {

    @Autowired private MarkingBatchBridge bridge;
    @Autowired private BatchOrchestrator orchestrator;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository statusRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsBatchProcLogRepository procLogRepository;
    @Autowired private BatchReprocessService reprocessService;
    @Autowired private BatchTransitionService transitionService;

    /** 비동기 실행을 결정적으로 관측하기 위해 대체 — "배치가 시작되었는가" 를 호출 여부로 판정한다. */
    @MockBean private AsyncBatchRunner asyncBatchRunner;

    private final TransactionTemplate tx;

    BatchReviewOwnedEntryGuardIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.tx = new TransactionTemplate(controlTxManager);
    }

    /** 비식별 완료(deIdntfYn='Y') + MARKING_READY 영상 1건 + 프레임 1건 + 지정 작업 상태 row 를 만든다. */
    private long seed(String workStatus) {
        return tx.execute(s -> {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    "CLIP-GUARD-" + System.nanoTime(), "CCTV-1", "EVT-A", "11680",
                    LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/guard.mp4", LocalDateTime.now(), 30);
            raw.markDeidentified("Y");
            raw.markMarkingReady();
            Long rawSn = videoRepository.save(raw).getRawSn();
            srcRepository.save(LsDataSrc.create(rawSn, 0, "/var/raw/guard-f0.jpg", LocalDateTime.now()));
            statusRepository.save(LsRawDataStatus.builder()
                    .rawDataId(rawSn)
                    .dataSttsCd(workStatus)
                    .stpCycl(0)
                    .igiCycl(0)
                    .updDt(LocalDateTime.now())
                    .build());
            return rawSn;
        });
    }

    private String workStatus(long rawSn) {
        return tx.execute(s -> statusRepository.findById(rawSn).orElseThrow().getDataSttsCd());
    }

    private String batchStage(long rawSn) {
        return tx.execute(s -> videoRepository.findById(rawSn).orElseThrow().getDataSttsCd());
    }

    /** step 이 하나라도 돌면 BatchStatusService.markStage 가 배치 로그를 남긴다 — 실행 여부의 관측 지표. */
    private boolean batchLogExists(long rawSn) {
        return Boolean.TRUE.equals(tx.execute(s ->
                procLogRepository.findTopByDataRawSnOrderByRegDtDesc(rawSn).isPresent()));
    }

    private long labelCount(long rawSn) {
        return tx.execute(s -> labelRepository.countByRawSn(rawSn));
    }

    @Test
    @DisplayName("마킹완료_브리지경로_APPROVED_영상은_BATCH_QUEUED로_덮이지_않고_배치가_시작되지_않는다")
    void bridgeDoesNotQueueApprovedVideo() {
        // given — 검수 승인 완료된 영상(비식별 완료 + MARKING_READY 라 다른 가드는 모두 통과)
        long rawSn = seed(LsRawDataStatus.STTS_APPROVED);

        // when — 마킹 완료 이벤트가 실경로 그대로 브리지에 도달
        bridge.onMarkingCompleted(new MarkingCompletedEvent(rawSn, 1L));

        // then — ① 작업 상태 보존(BATCH_QUEUED 로 덮이면 출구 가드가 무력화된다)
        assertThat(workStatus(rawSn)).isEqualTo(LsRawDataStatus.STTS_APPROVED);
        // ② 배치 미시작 — step 실행/라벨 적재 없음
        verify(asyncBatchRunner, never()).runAsync(any());
        assertThat(batchLogExists(rawSn)).isFalse();
        assertThat(labelCount(rawSn)).isZero();
        assertThat(batchStage(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
    }

    @Test
    @DisplayName("마킹완료_브리지경로_검수대기_PENDING_영상도_배치가_큐잉되지_않는다")
    void bridgeDoesNotQueuePendingVideo() {
        // given — 작업자가 검수 제출한(PENDING) 영상
        long rawSn = seed(LsRawDataStatus.STTS_PENDING);

        // when
        bridge.onMarkingCompleted(new MarkingCompletedEvent(rawSn, 2L));

        // then
        assertThat(workStatus(rawSn)).isEqualTo(LsRawDataStatus.STTS_PENDING);
        verify(asyncBatchRunner, never()).runAsync(any());
        assertThat(batchLogExists(rawSn)).isFalse();
        assertThat(labelCount(rawSn)).isZero();
    }

    @Test
    @DisplayName("정상흐름_회귀_ASSIGNED_영상은_브리지가_BATCH_QUEUED로_큐잉하고_배치를_1회_시작한다")
    void bridgeStillQueuesAssignedVideo() {
        // given — 배정 완료(ASSIGNED) 영상 = 마킹 완료 정상 경로
        long rawSn = seed(LsRawDataStatus.STTS_ASSIGNED);

        // when
        bridge.onMarkingCompleted(new MarkingCompletedEvent(rawSn, 3L));

        // then — 가드 확장이 정상 파이프라인을 끊지 않는다
        assertThat(workStatus(rawSn)).isEqualTo(LsRawDataStatus.STTS_BATCH_QUEUED);
        verify(asyncBatchRunner, times(1)).runAsync(rawSn);
    }

    @Test
    @DisplayName("오케스트레이터_직접호출_APPROVED_영상은_step이_한건도_실행되지_않고_SKIPPED로_종료된다")
    void orchestratorSkipsApprovedVideoWithoutRunningAnyStep() {
        // given — dev 트리거/Quartz 큐/재시도 잡처럼 클레임 없이 process 로 바로 들어오는 경로
        long rawSn = seed(LsRawDataStatus.STTS_APPROVED);

        // when
        BatchStage stage = orchestrator.process(rawSn);

        // then — ① 진입 자체가 차단
        assertThat(stage).isEqualTo(BatchStage.SKIPPED);
        // ② 작업 상태·배치 단계 모두 불변 (LS_DATA_RAW 가 PROCESSING/FAILED 로 튀면 불일치쌍 발생)
        assertThat(workStatus(rawSn)).isEqualTo(LsRawDataStatus.STTS_APPROVED);
        assertThat(batchStage(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        // ③ step 미실행 — 배치 단계 로그도, AUTO 라벨도 생기지 않는다
        assertThat(batchLogExists(rawSn)).isFalse();
        assertThat(labelCount(rawSn)).isZero();
    }

    @Test
    @DisplayName("수동재처리_검수제출_영상은_stage가_PROCESSING으로_고착되지_않고_409로_거부되며_재시도가_가능하다")
    void manualReprocessDoesNotStickStageWhenReviewOwned() {
        // DEV_FIX H10 재현 시퀀스 — 배치 FAILED(work=FAILED, stage=FAILED) → 배정(ASSIGNED) →
        //   검수 제출(PENDING) → POST /v1/batches/{rawSn}/reprocess.
        //   구 동작: HTTP 200 "SKIPPED" + stage 는 PROCESSING 고착 → 이후 stage/work 어느 쪽도 FAILED 가
        //   아니라 <b>영구 409</b> 로 복구 불가.
        long rawSn = seed(LsRawDataStatus.STTS_FAILED);
        tx.executeWithoutResult(s -> {
            LsDataRaw raw = videoRepository.findById(rawSn).orElseThrow();
            raw.markBatchFailed();
            videoRepository.save(raw);
        });
        assertThat(batchStage(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_FAILED);
        // 배정 → 검수 제출: 작업 상태가 검수 소유(PENDING)로 넘어간다.
        tx.executeWithoutResult(s -> {
            LsRawDataStatus stts = statusRepository.findById(rawSn).orElseThrow();
            stts.transitionTo(LsRawDataStatus.STTS_ASSIGNED);
            stts.transitionTo(LsRawDataStatus.STTS_PENDING);
            statusRepository.save(stts);
        });

        // when — 수동 재처리
        assertThatThrownBy(() -> reprocessService.retry(rawSn))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // then — ① stage 가 PROCESSING 으로 고착되지 않는다(보상 롤백으로 FAILED 유지)
        assertThat(batchStage(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_FAILED);
        // ② 작업 상태(검수 제출)는 배치에 의해 덮이지 않는다
        assertThat(workStatus(rawSn)).isEqualTo(LsRawDataStatus.STTS_PENDING);
        // ③ step 미실행
        assertThat(batchLogExists(rawSn)).isFalse();
        assertThat(labelCount(rawSn)).isZero();
        // ④ 재시도가 여전히 가능하다 — 재처리 게이트(FAILED→PROCESSING 클레임)가 다시 열려 있다.
        //    파이프라인 전체를 다시 돌리면 외부 단계 의존이 생기므로, 게이트 재개방 여부로 판정한다
        //    (구 결함에서는 stage=PROCESSING 이라 이 클레임이 영원히 false 였다).
        assertThat(transitionService.tryClaimReprocessFromFailed(rawSn)).isTrue();
        transitionService.releaseReprocessClaim(rawSn); // 상태 원복(다른 테스트 영향 방지)
        assertThat(batchStage(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_FAILED);
    }

    @Test
    @DisplayName("오케스트레이터_직접호출_검수대기_PENDING_영상도_step_미실행_SKIPPED로_종료된다")
    void orchestratorSkipsPendingVideoWithoutRunningAnyStep() {
        // given
        long rawSn = seed(LsRawDataStatus.STTS_PENDING);

        // when
        BatchStage stage = orchestrator.process(rawSn);

        // then
        assertThat(stage).isEqualTo(BatchStage.SKIPPED);
        assertThat(workStatus(rawSn)).isEqualTo(LsRawDataStatus.STTS_PENDING);
        assertThat(batchStage(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        assertThat(batchLogExists(rawSn)).isFalse();
        assertThat(labelCount(rawSn)).isZero();
    }
}
