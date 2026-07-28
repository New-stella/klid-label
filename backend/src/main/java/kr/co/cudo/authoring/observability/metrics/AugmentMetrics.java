package kr.co.cudo.authoring.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 외부 증강 요청 관찰성 메트릭 (콜백 충실 플로우 Phase 1 — DEV_FIX MEDIUM-3).
 *
 * <ul>
 *   <li>{@code augment.external.request} (Counter, tag status=success|failure) —
 *       AFTER_COMMIT 리스너에서 {@code ExternalAugmentClient.requestAugment} 호출 결과 집계.
 *       장애 시 failure 카운트로 외부 연동 가용성을 모니터링한다.</li>
 *   <li>{@code augment.cleanup.failed} (Counter) — 프레임 재추출 Phase B 산출 아티팩트(재추출 프레임
 *       디렉토리) cleanup 후에도 파일이 잔존(삭제 실패)한 건수. 증가 시 스토리지 고아 파일 누적 신호이므로
 *       운영자가 수동 정리·알림 대상으로 삼는다(커넥션-점유 분리 리팩터 — 증강 경로).</li>
 * </ul>
 */
@Component
public class AugmentMetrics {

    /** 생성형 AI 콜백을 <b>상태 변경 없이</b> 거부한 건수(tag reason). */
    private static final String CALLBACK_REJECTED = "augment.callback.rejected";
    private static final String TAG_REASON = "reason";

    /** 산출 경로가 읽기 허용 루트 밖 — 벤더 재시도 소진 시 증강이 PENDING 에 고착된다. */
    public static final String REASON_OUTPUT_PATH = "output_path";
    /** SUCCEEDED 인데 {@code results[]} 가 비어 있음(계약 위반). */
    public static final String REASON_MISSING_RESULTS = "missing_results";

    private static final String EXTERNAL_REQUEST = "augment.external.request";
    private static final String CLEANUP_FAILED = "augment.cleanup.failed";
    private static final String TAG_STATUS = "status";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILURE = "failure";

    private final MeterRegistry registry;

    public AugmentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void externalRequestSuccess() {
        registry.counter(EXTERNAL_REQUEST, TAG_STATUS, STATUS_SUCCESS).increment();
    }

    public void externalRequestFailure() {
        registry.counter(EXTERNAL_REQUEST, TAG_STATUS, STATUS_FAILURE).increment();
    }

    /**
     * 생성형 AI 콜백을 상태 변경 없이 거부했을 때 1 증가.
     *
     * <p>이 카운터가 오르는 동안 해당 job 은 비종결로 남아 증강 1건이 PENDING 이다(만료 스윕 없음).
     * 운영은 이 값 + {@code LS_DATA_AUG_JOB} 비종결 행 경과시간으로 고착을 감지한다.
     */
    public void callbackRejected(String reason) {
        registry.counter(CALLBACK_REJECTED, TAG_REASON, reason).increment();
    }

    /** 프레임 재추출 Phase B cleanup 후에도 아티팩트가 잔존(삭제 실패)했을 때 1 증가. */
    public void cleanupFailed() {
        registry.counter(CLEANUP_FAILED).increment();
    }
}
