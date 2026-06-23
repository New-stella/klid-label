package kr.co.cudo.authoring.marking.listener;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.runner.AsyncBatchRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.Set;

/**
 * 마킹 완료 이벤트 수신 → 배치 파이프라인 트리거.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 로 마킹 트랜잭션 커밋 이후에만 실행.
 * 이미 배치 큐에 들어갔거나 처리 중/완료 상태인 영상은 중복 실행을 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkingBatchBridge {

    private static final Set<String> SKIP_STATUSES = Set.of(
            LsRawDataStatus.STTS_BATCH_QUEUED,
            LsRawDataStatus.STTS_PROCESSING,
            LsRawDataStatus.STTS_COMPLETED);

    /**
     * 배치 단계(LS_DATA_RAW.DATA_STTS_CD) 기준 재트리거 스킵 집합.
     *
     * <p>{@link #SKIP_STATUSES} 는 작업 상태(LS_RAW_DATA_STATUS)와 비교하는데, 배치 완료 시 작업 상태는
     * {@code markRawDataCompleted} 가 ASSIGNED 로 리셋하므로 이미 처리된 영상을 못 거른다. 배치 단계 상태는
     * 별도 컬럼(LS_DATA_RAW.DATA_STTS_CD)에 PROCESSING/COMPLETED 로 남으므로 이를 직접 검사해
     * 완료/처리중 영상에 대한 배치 재트리거(→ COMPLETED→PROCESSING 역전)를 차단한다.
     */
    private static final Set<String> SKIP_BATCH_STAGES = Set.of(
            LsDataRaw.DATA_STTS_PROCESSING, LsDataRaw.DATA_STTS_COMPLETED);

    /** 비식별 완료 마킹 값 (LS_DATA_RAW.DE_IDENT_YN). */
    private static final String DEIDENTIFIED = "Y";

    private final BatchTransitionService batchTransitionService;
    private final BatchStatusService batchStatusService;
    private final AsyncBatchRunner asyncBatchRunner;
    private final VideoRepository videoRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMarkingCompleted(MarkingCompletedEvent event) {
        Long rawSn = event.rawSn();
        log.info("[MarkingBatchBridge] handling marking completed rawSn={}", rawSn);

        // LS_DATA_RAW 기반 가드 — 영상이 존재해야 deid/배치단계 검사가 가능하다. 미존재면 트리거하지 않는다.
        Optional<LsDataRaw> rawOpt = videoRepository.findById(rawSn);
        if (rawOpt.isEmpty()) {
            log.warn("[MarkingBatchBridge] no raw video rawSn={} — skipping batch trigger", rawSn);
            return;
        }
        LsDataRaw raw = rawOpt.get();

        // 배치 단계 역전 차단 가드 — 이미 처리중(PROCESSING)/완료(COMPLETED)인 영상은 배치 재트리거 불가.
        // 작업 상태(LS_RAW_DATA_STATUS)는 배치 완료 시 ASSIGNED 로 리셋되어 SKIP_STATUSES 에 안 걸리므로,
        // 배치 단계(LS_DATA_RAW.DATA_STTS_CD)를 직접 검사해 COMPLETED→PROCESSING 상태 역전을 차단한다.
        if (SKIP_BATCH_STAGES.contains(raw.getDataSttsCd())) {
            log.info("[MarkingBatchBridge] batch stage {} rawSn={} — skipping re-trigger",
                    sanitize(raw.getDataSttsCd()), rawSn);
            return;
        }

        // Phase 2 deid 가드 — 비식별이 완료(deIdntfYn='Y')되지 않은 영상은 배치 트리거 불가(조기 차단).
        // (SKIP_STATUSES 는 LS_RAW_DATA_STATUS 값이고 비식별 신호는 LS_DATA_RAW 라 별도 가드로 대체.)
        if (!DEIDENTIFIED.equals(raw.getDeIdntfYn())) {
            log.warn("[MarkingBatchBridge] not deidentified rawSn={} deIdntfYn={} — skipping batch trigger",
                    rawSn, sanitize(raw.getDeIdntfYn()));
            return;
        }

        // D1/D2 — 조건부 원자 전이(check-and-set)로 BATCH_QUEUED 를 즉시 커밋 영속하고 멱등성을 보장한다.
        // 작업 상태가 SKIP 대상(BATCH_QUEUED/PROCESSING/COMPLETED)이 아닐 때만 전이에 성공하며, 동시 2개
        // 마킹 이벤트 중 정확히 1건만 전이 권한(true)을 획득한다. 전이 실패(false)면 배치 트리거를 건너뛴다.
        boolean claimed = batchTransitionService.tryClaimBatchQueued(rawSn, SKIP_STATUSES);
        if (!claimed) {
            log.info("[MarkingBatchBridge] batch already claimed/in-progress rawSn={} — skipping", rawSn);
            return;
        }

        batchStatusService.markStage(rawSn, BatchStage.PENDING);
        asyncBatchRunner.runAsync(rawSn);
        log.info("[MarkingBatchBridge] enqueued rawSn={}", rawSn);
    }

    /**
     * 로그 인젝션(CWE-117) 방어 — DB 유래값을 로그에 출력하기 전 개행(CR/LF)을 제거한다.
     * null-safe (deid 미설정 케이스 등).
     */
    private static String sanitize(String value) {
        return value == null ? null : value.replace("\n", "").replace("\r", "");
    }
}
