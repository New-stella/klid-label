package kr.co.cudo.authoring.marking.listener;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.runner.AsyncBatchRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import kr.co.cudo.authoring.marking.service.MarkingSkipTxService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 마킹 완료 이벤트 수신 → 배치 파이프라인 트리거.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 로 마킹 트랜잭션 커밋 이후에만 실행.
 * 이미 배치 큐에 들어갔거나 처리 중/완료 상태인 영상은 중복 실행을 방지한다.
 *
 * <h3>skip 은 반드시 마킹 종결을 동반한다 (B-ISSUE-41)</h3>
 * <p>마킹 저장(커밋)과 배치 트리거 판단이 분리돼 있어, 여기서 skip 을 결정해도 방금 커밋된
 * {@code PENDING} 마킹은 그대로 남는다. {@code PENDING} 을 전이시키는 주체는 <b>VLM 단계뿐</b>이고 그
 * 단계는 배치가 돌아야 도달하므로, skip 된 마킹은 아무도 종결시키지 않는 <b>영구 고아</b>가 되고
 * 활성 마킹 유일성(V142)에 걸려 그 영상은 <b>다시는 마킹할 수 없게</b> 된다(재마킹 409 /
 * {@code batch/retry} 도 stage 가 FAILED 가 아니라 409 — 복구 API 부재). 따라서 <b>모든 skip 분기</b>는
 * {@link #skip(Long, Long, String)} 를 거쳐 그 마킹을 {@code SKIPPED} 로 종결한다. 종결은
 * {@code PENDING} 한정이라 진행 중/종결된 마킹을 덮지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkingBatchBridge {

    /**
     * BATCH_QUEUED 클레임을 건너뛸 작업 상태 집합 (= 배치 <b>입구</b> 가드).
     *
     * <p>① 이미 큐잉/처리중/완료(BATCH_QUEUED·PROCESSING·COMPLETED) — 중복 실행 방지.
     * ② 검수 소유 상태({@link BatchTransitionService#REVIEW_OWNED_STATUSES} —
     * PENDING·IN_REVIEW·APPROVED·REJECTED) — <b>DEV_FIX H1-a</b>: 이 상태들을 넣지 않으면 APPROVED 영상도
     * 먼저 {@code BATCH_QUEUED} 로 덮인 뒤 오케스트레이터 진입 가드가 보는 현재값이 BATCH_QUEUED(비-차단)가
     * 되어 <b>가드가 한 번도 발화하지 않는다</b>. 입구에서 막아야 출구 가드가 의미를 갖는다.
     *
     * <p>따라서 클레임이 허용되는 작업 상태는 {@code ASSIGNED}(정상 마킹 완료 경로)와
     * {@code FAILED}(실패 후 재마킹) 두 가지뿐이며, 작업 상태 row 자체가 없으면
     * {@link BatchTransitionService#tryCreateBatchQueuedRow} 가 새로 생성한다(미배정 직접 마킹).
     *
     * <h3>{@code REJECTED} 를 왜 유지하는가 — 도달성 분석 결론 (DEV_FIX H11)</h3>
     * <p>"반려 후 재마킹이 무음 차단된다"는 지적을 코드로 확정한 결과:
     * <ol>
     *   <li><b>배치가 돌았던 영상의 재마킹은 여기까지 오지도 못한다.</b>
     *       {@code MarkingGuards.requirePreconditions} 가 {@code LS_DATA_RAW.DATA_STTS_CD ==
     *       MARKING_READY} 를 요구하는데, 배치 완료는 {@code COMPLETED}, 배치 실패는 {@code FAILED} 로
     *       바꾼다. 반려({@code ReviewService.reject})는 작업 상태만 REJECTED 로 바꾸고 배치 단계는
     *       건드리지 않으므로, 재마킹 요청은 마킹 API 단계에서 412(PRECONDITION_FAILED)로 거부된다.
     *       즉 {@code SKIP_STATUSES} 의 REJECTED 항목과 무관하게 이 시나리오는 도달 불가다.</li>
     *   <li><b>도달 가능한 조합은 "배치가 한 번도 안 돈 채 반려된 영상"</b>이다
     *       (stage=MARKING_READY 인데 work=REJECTED — 배정→검수제출→반려로 만들 수 있다).
     *       이때 REJECTED 를 차단 집합에서 빼면 배치가 실제로 돌고, 완료 시
     *       {@code markRawDataCompleted} 가 작업 상태를 <b>ASSIGNED 로 되돌려 반려 사실이 소실</b>된다.
     *       즉 REJECTED 제외는 "재마킹 허용"이 아니라 "반려 상태 유실"을 만든다.</li>
     * </ol>
     * <b>결론: REJECTED 는 차단 집합에 유지한다(fail-closed).</b> 대신 차단이 무음이면 안 되므로
     * {@link MarkingBatchTriggerReport} 로 사유를 응답에 되돌린다. "반려 후 재마킹" 동선 자체의 부재는
     * 마킹 프리컨디션(설계)의 문제이며 본 차단 집합과 별개 사안이다.
     */
    private static final Set<String> SKIP_STATUSES = Stream.concat(
                    Stream.of(LsRawDataStatus.STTS_BATCH_QUEUED,
                            LsRawDataStatus.STTS_PROCESSING,
                            LsRawDataStatus.STTS_COMPLETED),
                    BatchTransitionService.REVIEW_OWNED_STATUSES.stream())
            .collect(Collectors.toUnmodifiableSet());

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
    /**
     * skip 된 마킹의 종결 처리기 (B-ISSUE-41). AFTER_COMMIT 컨텍스트라 활성 트랜잭션이 없으므로
     * 별도 빈의 {@code REQUIRES_NEW} 로 즉시 커밋시킨다.
     */
    private final MarkingSkipTxService markingSkipTxService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMarkingCompleted(MarkingCompletedEvent event) {
        Long rawSn = event.rawSn();
        Long markingSn = event.markingSn();
        log.info("[MarkingBatchBridge] handling marking completed rawSn={}", rawSn);

        // LS_DATA_RAW 기반 가드 — 영상이 존재해야 deid/배치단계 검사가 가능하다. 미존재면 트리거하지 않는다.
        Optional<LsDataRaw> rawOpt = videoRepository.findById(rawSn);
        if (rawOpt.isEmpty()) {
            log.warn("[MarkingBatchBridge] no raw video rawSn={} — skipping batch trigger", rawSn);
            skip(rawSn, markingSn, MarkingBatchTriggerReport.REASON_VIDEO_NOT_FOUND);
            return;
        }
        LsDataRaw raw = rawOpt.get();

        // 배치 단계 역전 차단 가드 — 이미 처리중(PROCESSING)/완료(COMPLETED)인 영상은 배치 재트리거 불가.
        // 작업 상태(LS_RAW_DATA_STATUS)는 배치 완료 시 ASSIGNED 로 리셋되어 SKIP_STATUSES 에 안 걸리므로,
        // 배치 단계(LS_DATA_RAW.DATA_STTS_CD)를 직접 검사해 COMPLETED→PROCESSING 상태 역전을 차단한다.
        if (SKIP_BATCH_STAGES.contains(raw.getDataSttsCd())) {
            log.warn("[MarkingBatchBridge] batch stage {} rawSn={} — skipping re-trigger",
                    sanitize(raw.getDataSttsCd()), rawSn);
            skip(rawSn, markingSn, MarkingBatchTriggerReport.REASON_STAGE_ALREADY_RUN);
            return;
        }

        // Phase 2 deid 가드 — 비식별이 완료(deIdntfYn='Y')되지 않은 영상은 배치 트리거 불가(조기 차단).
        // (SKIP_STATUSES 는 LS_RAW_DATA_STATUS 값이고 비식별 신호는 LS_DATA_RAW 라 별도 가드로 대체.)
        if (!DEIDENTIFIED.equals(raw.getDeIdntfYn())) {
            log.warn("[MarkingBatchBridge] not deidentified rawSn={} deIdntfYn={} — skipping batch trigger",
                    rawSn, sanitize(raw.getDeIdntfYn()));
            skip(rawSn, markingSn, MarkingBatchTriggerReport.REASON_NOT_DEIDENTIFIED);
            return;
        }

        // D1/D2/FIX B — BATCH_QUEUED 클레임을 2단계 독립 트랜잭션으로 수행해 즉시 커밋 영속·멱등성·
        // 미배정 고착 제거를 함께 달성한다(CRITICAL 재설계).
        //  tx1: tryClaimBatchQueued — 작업 상태 row 가 존재하고 SKIP 대상({@link #SKIP_STATUSES} — 진행 중
        //       3종 + 검수 소유 4종)이 아닐 때만 단일 조건부 UPDATE 로 BATCH_QUEUED 전이.
        //       DB 직렬화로 동시 2 이벤트 중 1건만 true.
        //  tx2: tryCreateBatchQueuedRow — tx1 이 false(=row 부재 또는 이미 SKIP)일 때만 호출. row 부재면
        //       새 BATCH_QUEUED row 를 생성(미배정 REVIEWER 직접 마킹 고착 제거), 이미 존재하면 false(멱등 스킵).
        // 두 단계를 별도 REQUIRES_NEW(=새 커넥션)로 나누는 이유: 할당형 PK 라 insert flush 가 커밋까지 지연되고
        // PostgreSQL 은 unique 위반 시 tx 전체를 abort 하므로, "같은 tx 내 재시도"는 구조적으로 불가능하다.
        // tx2 의 saveAndFlush 가 동시 노드와 경합해 던지는 DataIntegrityViolationException 은 활성 tx 없는
        // AFTER_COMMIT 컨텍스트인 여기서 안전하게 잡아 skip 처리한다 → 정확히 1건만 배치를 트리거한다(CWE-362).
        boolean claimed = batchTransitionService.tryClaimBatchQueued(rawSn, SKIP_STATUSES);
        if (!claimed) {
            try {
                claimed = batchTransitionService.tryCreateBatchQueuedRow(rawSn);
            } catch (DataIntegrityViolationException e) {
                // 동시 노드가 먼저 row 를 생성 → 그 노드가 트리거를 소유. 이번 호출은 조용히 스킵한다.
                log.info("[MarkingBatchBridge] concurrent row creation rawSn={} — skipping", rawSn);
                claimed = false;
            }
        }
        if (!claimed) {
            // DEV_FIX H11 — 여기까지 온 미클레임은 "검수 소유 상태(PENDING/IN_REVIEW/APPROVED/REJECTED)"
            //   이거나 "이미 큐잉/처리중/완료"다. 둘 다 배치를 돌리면 안 되는 정당한 차단이지만, 마킹 API 는
            //   201 을 반환하므로 사용자는 배치가 시작된 줄 안다. 사유를 응답으로 되돌려 무음 스킵을 없앤다.
            log.warn("[MarkingBatchBridge] batch already claimed/in-progress or review-owned rawSn={} — skipping",
                    rawSn);
            skip(rawSn, markingSn, MarkingBatchTriggerReport.REASON_ALREADY_CLAIMED);
            return;
        }

        batchStatusService.markStage(rawSn, BatchStage.PENDING);
        asyncBatchRunner.runAsync(rawSn);
        MarkingBatchTriggerReport.triggered();
        log.info("[MarkingBatchBridge] enqueued rawSn={}", rawSn);
    }

    /**
     * skip 확정 처리 (B-ISSUE-41) — <b>①방금 커밋된 마킹을 종결</b>시키고 ②사유를 응답으로 되돌린다.
     *
     * <p>종결이 없으면 그 마킹은 아무도 전이시키지 않는 영구 고아가 되어 영상이 재마킹 409 로 잠긴다
     * (클래스 Javadoc 참조). 종결은 {@code PENDING} 한정이라 진행 중/종결 마킹을 덮지 않으며, 실패해도
     * 마킹 API 응답(201 + skip 사유)에는 영향을 주지 않는다.
     *
     * @param reason {@link MarkingBatchTriggerReport} 의 <b>고정 상수</b>만 전달한다(CWE-209/117).
     */
    private void skip(Long rawSn, Long markingSn, String reason) {
        markingSkipTxService.terminateSkipped(markingSn, rawSn);
        MarkingBatchTriggerReport.skipped(reason);
    }

    /**
     * 로그 인젝션(CWE-117) 방어 — DB 유래값을 로그에 출력하기 전 개행(CR/LF)을 제거한다.
     * null-safe (deid 미설정 케이스 등).
     */
    private static String sanitize(String value) {
        return value == null ? null : value.replace("\n", "").replace("\r", "");
    }
}
