package kr.co.cudo.authoring.dataset.export.listener;

import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
import kr.co.cudo.authoring.dataset.export.event.DatasetReExportEvent;
import kr.co.cudo.authoring.label.event.DeidentReportResolvedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 4 — 검수 승인 → 학습데이터 파일 산출 트리거 브릿지.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 로 <b>검수 승인 트랜잭션이 커밋된 이후에만</b>
 * 산출을 시작한다(롤백 시 미호출). 리스너는 얇게 위임만 하고, 실제 산출은
 * {@link AsyncDatasetExportRunner#runAsync(Long, boolean)} 가 별도 스레드에서 수행한다
 * ({@code IngestDeidentifyBridge}/{@code ControlNotifyEventListener} 와 동일 구조).
 *
 * <p>같은 {@code ReviewApprovedEvent} 를 소비하는 기존 리스너({@code ControlNotifyEventListener})는
 * 유지되며, 본 리스너는 <b>추가</b> 소비자다(리스너 간 독립).
 *
 * <p>{@code authoring.dataset-export.enabled} 로 토글한다(기본 활성 — {@code matchIfMissing=true}).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "authoring.dataset-export", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DatasetExportBridge {

    private final AsyncDatasetExportRunner runner;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewApproved(ReviewApprovedEvent event) {
        Long rawSn = event.rawSn();
        log.info("[DatasetExportBridge] review approved rawSn={} — triggering dataset export (force regenerate)", rawSn);
        // R6 — 승인마다 내용 변경 여부와 무관하게 전량 재생성(force=true). 멱등 skip 미적용.
        // C-2 — 승인 경로는 export 종결 후 DatasetExportCompletedEvent 를 발행해 통지가 뒤따르게 한다.
        runner.runApprovalAsync(rawSn);
    }

    /**
     * 재동결로 동결 스냅샷이 갱신된 뒤 export 재생성을 트리거한다.
     * {@code onReviewApproved} 와 동일하게 AFTER_COMMIT 로 재동결 커밋 후에만 산출을 시작하며,
     * TASK_COMPLETED 재발행 없이 export 만 갱신한다(통지는 발행 측이 TASK_MODIFIED 로 별도 처리).
     *
     * <p><b>휴면 리스너(Phase 5C LOW-3)</b>: {@link DatasetReExportEvent} 는 현재 <b>발행처가 없다</b>
     * (event_annotation 지연 승인 경로가 {@code TaskModifiedEvent(regen=true)} 단일 축으로 통일됨 — MED-F).
     * 이 배선은 향후 재동결형 재산출 경로를 위한 테스트된 확장점으로 존치한다(사유는 {@link DatasetReExportEvent}
     * javadoc 참조).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReExport(DatasetReExportEvent event) {
        Long rawSn = event.rawSn();
        log.info("[DatasetExportBridge] re-export requested rawSn={} — triggering dataset export", rawSn);
        // 재동결 경로(R6 스코프 밖) — 현행 멱등 skip 유지(force=false). 동일 해시면 재산출하지 않는다.
        runner.runAsync(rawSn, false);
    }

    /**
     * M1 — 비식별 누락 신고 해소 후, 신고 구간에 <b>보류됐던 산출·통지를 복구</b>하기 위해 정의된 리스너.
     *
     * <p><b>★현재 발행처가 없어 실행되지 않는다 — 휴면(dormant) 확장점이다.</b> 산출·통지 트리거가
     * <b>검수 승인 한 곳</b>으로 일원화되면서, 해소 시점에는 {@code TaskModifiedEvent}
     * (exportRegenerated=true · needsRecheck=true)가 <b>재검토 표시만</b> 세우고 실제 재산출·재통지는
     * <b>재승인 시점</b>에 나간다. 즉 지금의 복구 경로는 "재검토 표시 → 재승인"이다.
     * 배선을 지우지 않는 이유·보존된 근거는 {@link DeidentReportResolvedEvent} javadoc 참조.
     *
     * <p>신고 구간 차단이 {@code LS_DATASET_EXPORT} 행을 남기지 않아 실패 회수기
     * ({@code DatasetExportFailureRecoverer}, FAILED 행만 스캔)가 집지 못한다는 <b>전제 자체는 그대로</b>다 —
     * 그래서 재검토 표시가 유실 차단을 대신 맡는다.
     *
     * <p><b>범위는 신고가 해소된 그 영상 하나</b>다: 신고 게이트가 자기 rawSn 행만 보므로(파생영상은
     * 원본 신고와 무관 — 2026-07-29 확정 정책) 파생영상으로의 팬아웃이 없다. "APPROVED 인가" 판정도
     * 트랜잭션·리포지토리 컨텍스트가 있는 발행 측
     * ({@code DeidentReportService.publishResolvedForExportRecovery})이 수행하고, 이 리스너는 얇은
     * 소비자로 남는다(리스너에 조회 책임을 넣지 않는다).
     *
     * <p>{@code runApprovalAsync} 로 위임해 ①force=true 전량 재생성(교체된 비식별 이미지 반영)
     * ②성공 시 {@code DatasetExportCompletedEvent} 발행으로 보류됐던 관제 통지 재개를 함께 얻는다.
     * AFTER_COMMIT 이라 {@code DE_IDNTF_YN 'Y'} 복원이 커밋된 뒤에 실행된다 — 커밋 전에 돌면 export
     * 진입부 게이트가 아직 {@code 'F'} 를 읽어 스스로 막힌다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeidentReportResolved(DeidentReportResolvedEvent event) {
        Long rawSn = event.rawSn();
        log.info("[DatasetExportBridge] deident report resolved rawSn={} — re-triggering withheld export/notify",
                rawSn);
        runner.runApprovalAsync(rawSn);
    }
}
