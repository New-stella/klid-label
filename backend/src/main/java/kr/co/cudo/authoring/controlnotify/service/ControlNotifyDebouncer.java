package kr.co.cudo.authoring.controlnotify.service;

import jakarta.annotation.PreDestroy;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 관제서버 수정 통지 디바운서 — 영상(rawSn) 단위 60초 윈도우로 변경을 모아 1회 전송한다.
 *
 * <h3>이 클래스가 닫는 결함</h3>
 * <ul>
 *   <li><b>D-ISSUE-42</b>: 프레임↔변경종류 페어링 보존 ({@code Map<srcSn, Set<changeType>>}).</li>
 *   <li><b>D-ISSUE-43</b>: 영상 단위 변경({@code srcSn=null})을 프레임 변경과 <b>분리 축적</b>한다.
 *       구 구조는 {@code ConcurrentHashMap.newKeySet().add(null)} 로 NPE 를 던졌고, 호출 경로가
 *       {@code @TransactionalEventListener(AFTER_COMMIT)} 라 예외가 삼켜져 통지가 조용히 유실됐다.
 *       영상 단위만 바뀌어도 윈도우는 생성되고 flush 시 통지가 발송된다(changed_items 는 빈 리스트).</li>
 *   <li><b>S6 중복 flush</b>: 만료 스캔과 {@link #flushAll()}(@PreDestroy)가 동시에 같은 rawSn 을
 *       집어 2회 전송하던 창을 막는다. 전송은 {@code windows.remove(rawSn)} 가 <b>non-null 을 반환한
 *       스레드만</b> 수행한다 — ConcurrentHashMap.remove 는 원자적이라 정확히 한 스레드만 승리한다.</li>
 * </ul>
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
     * 이벤트 수신 — 윈도우에 축적.
     *
     * <p>{@code srcSn} 이 null(영상 단위 메타 변경)이어도 예외 없이 축적된다.
     */
    public void accumulate(TaskModifiedEvent event) {
        windows.compute(event.rawSn(), (key, existing) -> {
            DebouncedWindow window = existing == null ? new DebouncedWindow() : existing;
            window.add(event.srcSn(), event.changeType(), event.exportRegenerated());
            return window;
        });
    }

    /** 10초마다 만료 윈도우 스캔 — flush. */
    @Scheduled(fixedDelay = 10_000)
    public void flushExpiredWindows() {
        long cutoff = System.currentTimeMillis() - windowMillis;
        for (Map.Entry<Long, DebouncedWindow> entry : windows.entrySet()) {
            if (entry.getValue().getCreatedAt() > cutoff) {
                continue;
            }
            if (claimAndSendIsolated(entry.getKey())) {
                metrics.incrementDebounceFlush();
            }
        }
    }

    @PreDestroy
    public void flushAll() {
        for (Long rawSn : List.copyOf(windows.keySet())) {
            claimAndSendIsolated(rawSn);
        }
    }

    /**
     * 윈도우 1건의 전송 실패를 <b>루프에서 격리</b>한다(A-1).
     *
     * <p>{@link #claimAndSend} 는 {@code windows.remove} 로 소유권을 먼저 가져간다 — 여기서 예외가
     * 루프 밖으로 나가면 <b>이미 제거된 윈도우는 복구 불가로 소실</b>되고, 같은 tick 의 나머지 윈도우도
     * 스캔이 끊겨 함께 지연·소실된다({@code @PreDestroy} 경로에서는 종료와 함께 전부 사라진다).
     * 통지 유실 최소화를 위해 개별 실패는 로그만 남기고 다음 윈도우로 진행한다.
     *
     * @return 전송을 수행했으면 true (실패해도 소유권을 획득했으면 true — flush 시도 자체는 발생)
     */
    private boolean claimAndSendIsolated(Long rawSn) {
        try {
            return claimAndSend(rawSn);
        } catch (Exception e) {
            log.error("[ControlNotifyDebounce] window flush failed (isolated) rawSn={} reason={}",
                    rawSn, e.getClass().getSimpleName());
            return true;
        }
    }

    /**
     * 윈도우 소유권을 원자적으로 획득한 스레드만 전송한다(S6 — 중복 전송 차단).
     *
     * @return 전송을 수행했으면 true
     */
    private boolean claimAndSend(Long rawSn) {
        DebouncedWindow claimed = windows.remove(rawSn);
        if (claimed == null) {
            return false;
        }
        notifyService.sendModified(rawSn, claimed.toFrameChanges(), claimed.getVideoLevelChangeTypes(),
                claimed.isExportRegenerated());
        return true;
    }

    /** 디바운스 윈도우 — 동일 rawSn 에 대한 변경 축적. */
    static class DebouncedWindow {

        private long createdAt = System.currentTimeMillis();

        /** 프레임 단위 변경 — srcSn → 변경 종류 집합 (페어링 보존). */
        private final ConcurrentHashMap<Long, Set<String>> frameChanges = new ConcurrentHashMap<>();

        /** 영상 단위 변경(srcSn=null) 의 변경 종류 — 프레임 축적과 분리한다. */
        private final Set<String> videoLevelChangeTypes = ConcurrentHashMap.newKeySet();

        /**
         * 윈도우에 축적된 변경 중 <b>하나라도</b> export 폴더 재생성을 동반했는가(A-2).
         * 재생성이 섞여 있으면 산출물이 전량 바뀌므로 전 프레임을 실어야 한다 — OR 누적이 맞다.
         * {@code volatile} — flush 스레드가 축적 스레드의 기록을 반드시 관측해야 한다.
         */
        private volatile boolean exportRegenerated;

        void add(Long srcSn, String changeType, boolean exportRegeneratedChange) {
            if (changeType == null) {
                return;
            }
            if (exportRegeneratedChange) {
                exportRegenerated = true;
            }
            if (srcSn == null) {
                videoLevelChangeTypes.add(changeType);
                return;
            }
            frameChanges.computeIfAbsent(srcSn, k -> ConcurrentHashMap.newKeySet()).add(changeType);
        }

        long getCreatedAt() {
            return createdAt;
        }

        List<FrameChangeSet> toFrameChanges() {
            List<FrameChangeSet> result = new ArrayList<>(frameChanges.size());
            frameChanges.forEach((srcSn, types) -> result.add(new FrameChangeSet(srcSn, types)));
            return List.copyOf(result);
        }

        Set<String> getVideoLevelChangeTypes() {
            return Set.copyOf(videoLevelChangeTypes);
        }

        boolean isExportRegenerated() {
            return exportRegenerated;
        }
    }
}
