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
}
