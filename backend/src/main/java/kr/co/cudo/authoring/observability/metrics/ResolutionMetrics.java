package kr.co.cudo.authoring.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 해상도 파생영상 확정 관찰성 메트릭 — 락-I/O 분리 리팩터(#3 cleanup 실패 잔존 감시).
 *
 * <ul>
 *   <li>{@code resolution.cleanup.failed} (Counter) — Phase B 산출 아티팩트(파생 비디오/리스케일 프레임)
 *       cleanup 후에도 파일이 잔존(삭제 실패)한 건수. 증가 시 스토리지 고아 파일 누적 신호이므로
 *       운영자가 수동 정리·알림 대상으로 삼는다.</li>
 *   <li>{@code resolution.finalize.failed} (Counter) — 비동기 확정(A/B/C)이 실패해 파생 RAW·예약행이
 *       정리된 건수. <b>실패 흔적이 DB 에 남지 않는</b>(예약행 삭제가 의도된 설계 — 부분 유니크
 *       인덱스가 상태 무관이라 실패 행을 남기면 재요청이 영구 차단된다) 경로라, 이 카운터와 러너의
 *       WARN 로그가 유일한 관측 수단이다. 예약 시점 동기 검증({@code ParentDeidArtifactGuard})을
 *       통과한 뒤 확정 전에 산출물이 사라진 잔여 창이 여기에 잡힌다.</li>
 * </ul>
 */
@Component
public class ResolutionMetrics {

    private static final String CLEANUP_FAILED = "resolution.cleanup.failed";
    private static final String FINALIZE_FAILED = "resolution.finalize.failed";

    private final MeterRegistry registry;

    public ResolutionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Phase B cleanup 후에도 파생 아티팩트가 잔존(삭제 실패)했을 때 1 증가. */
    public void cleanupFailed() {
        registry.counter(CLEANUP_FAILED).increment();
    }

    /**
     * 비동기 확정 실패로 파생 RAW·예약행을 정리했을 때 1 증가(중복 finalize 패자 스킵은 제외).
     * 실패가 DB 에 남지 않는 경로라 이 카운터가 유일한 집계 근거다.
     */
    public void finalizeFailed() {
        registry.counter(FINALIZE_FAILED).increment();
    }
}
