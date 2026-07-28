package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.dataset.export.event.DatasetExportCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Phase 4 — 검수 승인 후 학습데이터 파일 산출 비동기 실행기.
 *
 * <p>{@link DatasetExportBridge} 가 {@code ReviewApprovedEvent}(AFTER_COMMIT) 수신 후 호출한다.
 * 실제 산출은 {@code batchAsyncExecutor} 풀의 별도 스레드에서 수행하므로 검수 승인 응답이 지연되지 않는다
 * ({@code AsyncDeidentifyRunner} 패턴).
 *
 * <p><b>정합(HIGH)</b>: 승인은 이미 커밋되었고 산출은 이와 무관하다. 산출 중 어떤 예외도 여기서
 * 삼켜(로깅만) 호출부로 전파하지 않는다 — 파일 산출 실패가 승인/다른 흐름에 영향을 주지 않는다.
 * 예외 원인은 클래스명만 로깅한다(경로 원문/PII 미노출 — CWE-359/117).
 */
@Component
public class AsyncDatasetExportRunner {

    private static final Logger log = LoggerFactory.getLogger(AsyncDatasetExportRunner.class);

    private final DatasetExportService exportService;
    private final ApplicationEventPublisher eventPublisher;

    public AsyncDatasetExportRunner(DatasetExportService exportService,
                                    ApplicationEventPublisher eventPublisher) {
        this.exportService = exportService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 승인/재동결 경로가 재생성 강제 여부를 관통시켜 산출을 시작한다(통지 없음).
     *
     * <p>재동결({@code DatasetReExportEvent}) · 실패 export 회수({@code DatasetExportFailureRecoverer})가
     * 사용한다. 통지를 별도로 발행/재발행하지 않는다.
     *
     * @param forceRegenerate 승인 경로(R6)면 {@code true} — 무수정 재승인도 전량 재생성. 재동결이면 {@code false} — 멱등 skip 유지.
     */
    @Async("batchAsyncExecutor")
    public void runAsync(Long rawSn, boolean forceRegenerate) {
        if (rawSn == null) {
            return;
        }
        log.info("[DatasetExport] async export starting rawSn={} forceRegenerate={}", rawSn, forceRegenerate);
        doExport(rawSn, forceRegenerate);
    }

    /**
     * C-2 — <b>검수 승인</b> 경로 전용: 전량 재생성(force=true) 후 {@link DatasetExportCompletedEvent} 를
     * 발행해 통지가 export 종결 <b>이후에</b> 나가게 한다(관제 구 버전 폴더 픽업 방지).
     *
     * <p>완료 이벤트는 일반 {@code ApplicationEvent} 라 이 스레드에서 리스너가 동기 실행된다 —
     * export → 통지 순서가 보장된다.
     *
     * <h3>HIGH-D(Phase 5C) — export <b>성공(SUCCEEDED)</b> 시에만 완료 이벤트를 발행한다</h3>
     * 구 구현은 export 가 예외로 실패해도 완료 이벤트를 발행했는데, 그러면 관제가 통지를 받고
     * {@code V_COMPLETED_VIDEO.EXPORT_PATH_NM}(최신 SUCCEEDED)을 조회할 때 <b>이번 산출은 없고 구 버전
     * 폴더</b>를 픽업한다. 따라서 실패 시 통지를 <b>보류</b>하고, 실패 export 는
     * {@code DatasetExportFailureRecoverer} 가 재산출 <b>성공 후 이 메서드로 완료 이벤트를 재발행</b>한다
     * (통지 유실이 아니라 성공 시점으로 지연). 실패는 {@link #doExport} 가 WARN 로그 + LS_DATASET_EXPORT
     * FAILED 행(회수기 관측 지점)으로 남긴다.
     */
    @Async("batchAsyncExecutor")
    public void runApprovalAsync(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        log.info("[DatasetExport] async approval export starting rawSn={}", rawSn);
        if (doExport(rawSn, true)) {
            eventPublisher.publishEvent(new DatasetExportCompletedEvent(rawSn));
        }
    }

    /**
     * C-2 — <b>승인 후 수정</b>(라벨/촬영환경) 경로 전용: 전량 재생성 후 {@code afterExport} 콜백을 실행한다.
     *
     * <p>디바운스 flush(관제 통지)가 이 메서드로 export 를 먼저 마친 뒤 통지를 내보내기 위한 진입점이다.
     * 콜백(관제 통지 발송)은 export 종결 이후 같은 스레드에서 실행되므로 순서가 보장된다.
     *
     * <h3>HIGH-D(Phase 5C) — export <b>성공 시에만</b> 통지 콜백을 실행한다</h3>
     * 구 구현은 export 예외를 삼키고도 콜백(통지)을 무조건 실행했다. 그러면 관제가 재생성 안 된(또는 없는)
     * 버전 폴더를 픽업한다. 이제 {@link #doExport} 가 성공(true)일 때만 콜백을 실행하고, 실패면 통지를
     * 보류한다 — 실패 export 는 {@code DatasetExportFailureRecoverer} 가 성공 후 완료 이벤트로 통지를
     * 재개한다. 콜백 인자를 {@code Runnable} 로 둬 이 클래스가 통지 패키지에 컴파일 의존하지 않는다(경계 분리).
     */
    @Async("batchAsyncExecutor")
    public void runReExportThenNotify(Long rawSn, boolean forceRegenerate, Runnable afterExport) {
        if (rawSn == null) {
            return;
        }
        log.info("[DatasetExport] async re-export(+notify) starting rawSn={} forceRegenerate={}",
                rawSn, forceRegenerate);
        boolean succeeded = doExport(rawSn, forceRegenerate);
        if (succeeded && afterExport != null) {
            try {
                afterExport.run();
            } catch (Exception e) {
                log.warn("[DatasetExport] post-export notify action failed rawSn={} cause={}",
                        rawSn, e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 산출을 수행하고 <b>성공 여부</b>를 반환한다.
     *
     * <p>{@code @Async} — 승인은 이미 커밋됐고 산출은 무관하므로 예외를 전파하지 않는다. 대신 실패를
     * {@code false} 로 알려 상위(승인/수정 경로)가 통지를 보류하게 한다(HIGH-D). 실패는 WARN 로그 +
     * {@code LS_DATASET_EXPORT} FAILED 행으로 관측되고 회수기가 재산출한다.
     *
     * <p><b>S7-EXPORT</b>: 비식별 누락 신고 구간({@code DE_IDNTF_YN='F'})은
     * {@code DatasetExportService.export} 진입부 게이트가 예외로 이탈시키므로 여기서 {@code false} 가 되어
     * <b>통지(완료 이벤트/콜백)도 함께 보류</b>된다 — 관제가 신고 상태 영상의 새 버전 폴더를 픽업하지 않는다.
     *
     * @return 예외 없이 산출을 마쳤으면 {@code true}, 예외로 실패했거나 게이트에 차단됐으면 {@code false}
     */
    private boolean doExport(Long rawSn, boolean forceRegenerate) {
        try {
            exportService.export(rawSn, forceRegenerate);
            return true;
        } catch (Exception e) {
            log.warn("[DatasetExport] async export failed rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
            return false;
        }
    }
}
