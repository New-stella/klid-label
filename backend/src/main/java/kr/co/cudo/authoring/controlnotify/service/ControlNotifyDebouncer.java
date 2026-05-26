package kr.co.cudo.authoring.controlnotify.service;

import jakarta.annotation.PreDestroy;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 3 -- 관제서버 TASK_MODIFIED 통지 디바운서.
 *
 * <p>ConcurrentMap 기반 60초 윈도우. 10초마다 만료 윈도우 스캔 후 flush.
 * 셧다운 시 잔여 윈도우 {@link #flushAll()} 로 모두 전송.
 */
@Component
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
@Slf4j
public class ControlNotifyDebouncer {

    private final ConcurrentHashMap<Long, DebouncedWindow> windows = new ConcurrentHashMap<>();
    private final ControlNotifyService notifyService;
    private final long windowMillis;
    private final ControlNotifyMetrics metrics;

    public ControlNotifyDebouncer(ControlNotifyService notifyService,
                                  @Value("${authoring.control-notify.debounce-window-sec:60}") long windowSec,
                                  ControlNotifyMetrics metrics) {
        this.notifyService = notifyService;
        this.windowMillis = windowSec * 1000L;
        this.metrics = metrics;
    }

    /**
     * 이벤트 수신 -- 윈도우에 축적.
     */
    public void accumulate(TaskModifiedEvent event) {
        windows.compute(event.rawSn(), (key, existing) -> {
            if (existing == null) {
                existing = new DebouncedWindow();
            }
            existing.addFrame(event.srcSn(), event.changeType());
            return existing;
        });
    }

    /** 10초마다 만료 윈도우 스캔 -- flush. */
    @Scheduled(fixedDelay = 10_000)
    public void flushExpiredWindows() {
        long cutoff = System.currentTimeMillis() - windowMillis;
        windows.forEach((rawSn, window) -> {
            if (window.getCreatedAt() <= cutoff) {
                windows.remove(rawSn);
                notifyService.sendModified(rawSn, window.getFrameIds(), window.getChangeTypes());
                metrics.incrementDebounceFlush();
            }
        });
    }

    @PreDestroy
    public void flushAll() {
        windows.forEach((rawSn, window) -> {
            windows.remove(rawSn);
            notifyService.sendModified(rawSn, window.getFrameIds(), window.getChangeTypes());
        });
    }

    /** 디바운스 윈도우 -- 동일 rawSn 에 대한 변경 축적. */
    static class DebouncedWindow {
        private long createdAt = System.currentTimeMillis();
        private final Set<Long> frameIds = ConcurrentHashMap.newKeySet();
        private final Set<String> changeTypes = ConcurrentHashMap.newKeySet();

        void addFrame(Long srcSn, String changeType) {
            frameIds.add(srcSn);
            changeTypes.add(changeType);
        }

        long getCreatedAt() {
            return createdAt;
        }

        List<Long> getFrameIds() {
            return List.copyOf(frameIds);
        }

        List<String> getChangeTypes() {
            return List.copyOf(changeTypes);
        }
    }
}
