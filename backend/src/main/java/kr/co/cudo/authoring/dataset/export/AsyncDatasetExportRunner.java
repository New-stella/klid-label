package kr.co.cudo.authoring.dataset.export;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public AsyncDatasetExportRunner(DatasetExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * 승인/재동결 경로가 재생성 강제 여부를 관통시켜 산출을 시작한다.
     *
     * @param forceRegenerate 승인 경로(R6)면 {@code true} — 무수정 재승인도 전량 재생성. 재동결이면 {@code false} — 멱등 skip 유지.
     */
    @Async("batchAsyncExecutor")
    public void runAsync(Long rawSn, boolean forceRegenerate) {
        if (rawSn == null) {
            return;
        }
        log.info("[DatasetExport] async export starting rawSn={} forceRegenerate={}", rawSn, forceRegenerate);
        try {
            exportService.export(rawSn, forceRegenerate);
        } catch (Exception e) {
            // @Async — 승인은 이미 커밋됐고 산출은 무관하므로 예외를 전파하지 않는다.
            log.warn("[DatasetExport] async export failed rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }
}
