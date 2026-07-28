package kr.co.cudo.authoring.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import kr.co.cudo.authoring.controlnotify.fallback.LsControlNotifyFallback;
import kr.co.cudo.authoring.controlnotify.fallback.LsControlNotifyFallbackRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 5 -- 관제서버 outbound 통지 Micrometer 메트릭.
 *
 * <p>{@code authoring.control-notify.enabled=true} 일 때만 Bean 등록.
 *
 * <p>메트릭:
 * <ul>
 *   <li>{@code control.notify.completed.success} — TASK_COMPLETED 전송 성공 카운터</li>
 *   <li>{@code control.notify.completed.failed} — TASK_COMPLETED 전송 실패 카운터</li>
 *   <li>{@code control.notify.modified.success} — TASK_MODIFIED 전송 성공 카운터</li>
 *   <li>{@code control.notify.modified.failed} — TASK_MODIFIED 전송 실패 카운터</li>
 *   <li>{@code control.notify.debounce.flush} — 디바운스 윈도우 flush 카운터</li>
 *   <li>{@code control.notify.dropped} — 폴백 큐 적재까지 실패해 소실된 통지 카운터</li>
 *   <li>{@code control.notify.fallback.depth} — 폴백 큐 깊이 게이지 (PENDING + RETRYING)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
public class ControlNotifyMetrics {

    private static final List<String> ACTIVE_STATUSES = List.of(
            LsControlNotifyFallback.STATUS_PENDING,
            LsControlNotifyFallback.STATUS_RETRYING
    );

    private final Counter completedSuccess;
    private final Counter completedFailed;
    private final Counter modifiedSuccess;
    private final Counter modifiedFailed;
    private final Counter debounceFlush;
    private final Counter unresolvedFrame;
    private final Counter selfHealCompletedToUpdated;
    private final Counter selfHealUpdatedToCompleted;
    private final Counter dropped;

    public ControlNotifyMetrics(MeterRegistry registry,
                                 LsControlNotifyFallbackRepository fallbackRepository) {
        this.completedSuccess = Counter.builder("control.notify.completed.success")
                .description("TASK_COMPLETED 전송 성공 건수")
                .register(registry);
        this.completedFailed = Counter.builder("control.notify.completed.failed")
                .description("TASK_COMPLETED 전송 실패 건수")
                .register(registry);
        this.modifiedSuccess = Counter.builder("control.notify.modified.success")
                .description("TASK_MODIFIED 전송 성공 건수")
                .register(registry);
        this.modifiedFailed = Counter.builder("control.notify.modified.failed")
                .description("TASK_MODIFIED 전송 실패 건수")
                .register(registry);
        this.debounceFlush = Counter.builder("control.notify.debounce.flush")
                .description("디바운스 윈도우 flush 건수")
                .register(registry);
        this.unresolvedFrame = Counter.builder("control.notify.modified.frame.unresolved")
                .description("변경 통지에서 SRC_SN→FRM_NO 해석에 실패한 프레임 건수(사일런트 드롭 감시)")
                .register(registry);
        this.selfHealCompletedToUpdated = Counter.builder("control.notify.selfheal.completed_to_updated")
                .description("완료 통지 409(중복) → 수정 통지 재전송 건수")
                .register(registry);
        this.selfHealUpdatedToCompleted = Counter.builder("control.notify.selfheal.updated_to_completed")
                .description("수정 통지 404(선행 완료 없음) → 완료 통지 폴백 건수")
                .register(registry);
        this.dropped = Counter.builder("control.notify.dropped")
                .description("폴백 큐 적재까지 실패해 영구 소실된 통지 건수(0 이 아니면 즉시 조사 대상)")
                .register(registry);

        Gauge.builder("control.notify.fallback.depth", fallbackRepository,
                        repo -> repo.countBySttsCdIn(ACTIVE_STATUSES))
                .description("통지 폴백 큐 깊이 (PENDING + RETRYING)")
                .register(registry);
    }

    public void incrementCompletedSuccess() { completedSuccess.increment(); }

    public void incrementCompletedFailed() { completedFailed.increment(); }

    public void incrementModifiedSuccess() { modifiedSuccess.increment(); }

    public void incrementModifiedFailed() { modifiedFailed.increment(); }

    public void incrementDebounceFlush() { debounceFlush.increment(); }

    /** SRC_SN→FRM_NO 해석 실패 건수 누적 (S10 — 사일런트 드롭 금지). */
    public void incrementUnresolvedFrame(int count) { unresolvedFrame.increment(count); }

    /** 완료 통지 409 → 수정 통지 자기치유 발동. */
    public void incrementSelfHealCompletedToUpdated() { selfHealCompletedToUpdated.increment(); }

    /** 수정 통지 404 → 완료 통지 자기치유 폴백 발동. */
    public void incrementSelfHealUpdatedToCompleted() { selfHealUpdatedToCompleted.increment(); }

    /**
     * 통지 영구 소실 — 전송 실패 후 폴백 큐 적재까지 실패한 건수(B-3).
     * ERROR 로그만으로는 감지되지 않아 별도 카운터로 관측한다.
     */
    public void incrementDropped() { dropped.increment(); }
}
