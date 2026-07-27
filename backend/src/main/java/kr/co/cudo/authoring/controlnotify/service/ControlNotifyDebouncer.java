package kr.co.cudo.authoring.controlnotify.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
 *
 * <h3>HIGH-E(Phase 5C) — export 재생성 트리거는 통지 토글과 분리한다(항상 활성)</h3>
 * 이 빈은 더 이상 {@code authoring.control-notify.enabled} 로 게이팅하지 <b>않는다</b>(항상 활성). 승인 후
 * 라벨/촬영환경 수정이 export 폴더를 새 버전으로 재생성하는 것은 데이터마트 동기화 요구(사업 요구)라
 * 통지 토글과 무관해야 하기 때문이다. 구 구현은 이 빈과 통지 리스너를 모두 토글로 게이팅해, dev/stg/prd
 * (토글 false)에서 승인 후 수정 시 {@code TaskModifiedEvent(regen=true)} 가 소비자 없이 드롭돼 <b>재생성이
 * 전혀 일어나지 않았다</b>.
 * <ul>
 *   <li>export 재생성 위임({@link AsyncDatasetExportRunner#runReExportThenNotify})은 항상 실행된다.</li>
 *   <li>통지({@link ControlNotifyService#sendModified})는 여전히 토글 종속이다 — 토글 false 면
 *       {@code ControlNotifyService}/{@code ControlNotifyMetrics} 빈이 없어 {@code null} 로 주입되고
 *       ({@link Nullable}), 통지·메트릭은 생략된다. export 는 그대로 나간다.</li>
 * </ul>
 * <h3>flush 스케줄링 — 전용 executor(MED-1 — Phase 5C)</h3>
 * 만료 flush({@link #flushExpiredWindows})는 export 재생성 발화의 <b>유일한 경로</b>다. 구 구현은 이를
 * {@code @Scheduled} 로 걸어, 컨텍스트의 {@code @EnableScheduling} 이 <b>무관한 토글 3곳</b>
 * ({@code control-notify.enabled} · {@code work-lock.sweep.enabled} · {@code resolution-backfill.sweep.enabled})
 * 중 하나로 켜질 때만 tick 했다. 셋을 모두 끈 형상(예: {@code CONTROL_NOTIFY_ENABLED=false} + 두 sweep
 * 유지보수 off)에서는 flush 가 영영 발화하지 않아, 승인 후 수정이 디바운스 윈도우에 축적만 된 채 export
 * 재생성이 무증상 중단됐다(HIGH-E 잔여, 데이터마트 라벨 동기화 요구 위반).
 *
 * <p>이제 flush 는 <b>이 빈이 소유한 데몬 스레드 1개짜리 전용 {@link ScheduledExecutorService}</b> 로
 * 자가 스케줄한다({@code RoleClaimAttemptPurgeJob}/{@code WebhookGuardPurgeJob} 동형). 그래서:
 * <ul>
 *   <li>flush 는 무관한 스케줄러 토글의 on/off 와 <b>독립적으로 항상 tick</b> 한다 — export 재생성 기능이
 *       살아있는 한(=이 빈이 존재하는 한) 재생성이 도달한다.</li>
 *   <li>무조건적 {@code @EnableScheduling} 을 새로 켜지 않으므로, 조건 없이 등록된 다른 {@code @Scheduled}
 *       잡({@code PortalUploadSweepJob}/{@code TusUploadCleanupJob} 등)을 <b>부수적으로 발화시키지 않는다</b>
 *       (전 환경·전 테스트). 즉 다른 잡의 발화 여부를 바꾸지 않는다.</li>
 * </ul>
 * <p>전용 스케줄러는 {@code authoring.dataset-export.regen-flush.enabled}(기본 {@code true} — export 재생성
 * 기능 자신에 묶인 토글)로 게이팅한다. 테스트는 이 값을 {@code false} 로 두어 격리하고, flush 로직은
 * {@link #flushExpiredWindows()} 를 직접 호출해 검증한다(스케줄 발화 배선은 별도 테스트가 검증).
 */
@Component
@Slf4j
public class ControlNotifyDebouncer {

    private final ConcurrentHashMap<Long, DebouncedWindow> windows = new ConcurrentHashMap<>();
    /** 통지 서비스 — 통지 토글 off(dev/stg/prd)면 빈이 없어 {@code null}. export 는 무관하게 트리거된다. */
    @Nullable
    private final ControlNotifyService notifyService;
    private final long windowMillis;
    /** 통지 메트릭 — 통지 토글 off 면 빈이 없어 {@code null}(호출 전 null-guard). */
    @Nullable
    private final ControlNotifyMetrics metrics;
    /** C-2 — export 재생성을 동반한 수정은 export 를 먼저 마친 뒤 통지하도록 재산출을 위임한다(항상 활성). */
    private final AsyncDatasetExportRunner exportRunner;

    /** MED-1 — flush 전용 스케줄러 활성 여부(export 재생성 기능 자신에 묶인 토글, 기본 true). 테스트만 false. */
    private final boolean flushSchedulerEnabled;
    /** MED-1 — flush tick 간격(ms). 기본 10초. 최소 1ms 로 하한. */
    private final long flushIntervalMillis;
    /** MED-1 — 이 빈이 소유한 데몬 스레드 1개짜리 flush 스케줄러(전 환경 항상 tick, @EnableScheduling 비의존). */
    @Nullable
    private ScheduledExecutorService flushScheduler;

    public ControlNotifyDebouncer(@Nullable ControlNotifyService notifyService,
                                  @Value("${authoring.control-notify.debounce-window-sec:60}") long windowSec,
                                  @Nullable ControlNotifyMetrics metrics,
                                  AsyncDatasetExportRunner exportRunner,
                                  @Value("${authoring.dataset-export.regen-flush.enabled:true}") boolean flushSchedulerEnabled,
                                  @Value("${authoring.control-notify.debounce-flush-interval-ms:10000}") long flushIntervalMillis) {
        this.notifyService = notifyService;
        this.windowMillis = windowSec * 1000L;
        this.metrics = metrics;
        this.exportRunner = exportRunner;
        this.flushSchedulerEnabled = flushSchedulerEnabled;
        this.flushIntervalMillis = Math.max(1L, flushIntervalMillis);
    }

    /**
     * MED-1 — 전용 flush 스케줄러 기동. {@code @EnableScheduling} 에 의존하지 않으므로 무관한 스케줄러
     * 토글의 상태와 독립적으로 flush 가 항상 tick 한다. 예외를 삼켜 스케줄러 스레드 사망을 막는다.
     */
    @PostConstruct
    void startFlushScheduler() {
        if (!flushSchedulerEnabled) {
            log.info("[ControlNotifyDebounce] flush scheduler disabled (regen-flush off) — export re-generation will not auto-trigger");
            return;
        }
        flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "control-notify-debounce-flush");
            t.setDaemon(true);
            return t;
        });
        flushScheduler.scheduleWithFixedDelay(this::flushExpiredWindowsSafely,
                flushIntervalMillis, flushIntervalMillis, TimeUnit.MILLISECONDS);
        log.info("[ControlNotifyDebounce] flush scheduler started intervalMs={}", flushIntervalMillis);
    }

    /** 스케줄러 스레드에서 flush 가 던진 예외로 {@code scheduleWithFixedDelay} 가 영구 정지하지 않도록 격리한다. */
    private void flushExpiredWindowsSafely() {
        try {
            flushExpiredWindows();
        } catch (Throwable e) {
            // RuntimeException 만 잡으면 Error 파생이 스케줄러 스레드를 죽여 flush 가 영구 정지한다.
            log.error("[ControlNotifyDebounce] scheduled flush failed reason={}", e.getClass().getSimpleName());
        }
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

    /** 만료 윈도우 스캔 — flush. 전용 스케줄러가 {@link #flushIntervalMillis} 간격으로 호출한다(기본 10초). */
    public void flushExpiredWindows() {
        long cutoff = System.currentTimeMillis() - windowMillis;
        for (Map.Entry<Long, DebouncedWindow> entry : windows.entrySet()) {
            if (entry.getValue().getCreatedAt() > cutoff) {
                continue;
            }
            if (claimAndSendIsolated(entry.getKey()) && metrics != null) {
                metrics.incrementDebounceFlush();
            }
        }
    }

    /**
     * 종료 시 전용 스케줄러를 먼저 멈춘 뒤(새 tick 차단) 남은 윈도우를 전부 drain 한다.
     * flush 스케줄러와 종료 drain 이 같은 rawSn 을 동시에 집어도 S6(원자 remove)로 1회만 전송된다.
     */
    @PreDestroy
    public void flushAll() {
        if (flushScheduler != null) {
            flushScheduler.shutdownNow();
            flushScheduler = null;
        }
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
            // MED-3 — 여기 도달하면 windows.remove 로 소유권을 가져간 뒤 발송/재산출 위임이 예외로 끊긴
            //   것이라 그 윈도우의 통지는 <b>실제로 소실</b>된다(되살릴 상위 주체 없음). 구 구현은 true 를
            //   반환해 이 소실을 debounce.flush(성공)로 계상하고 control.notify.dropped 를 0 으로 유지했다.
            //   실제 드롭을 dropped 카운터에 계상하고 flush 성공으로 세지 않는다(false 반환).
            if (metrics != null) {
                metrics.incrementDropped();
            }
            log.error("[ControlNotifyDebounce] window flush failed (isolated, notification lost) rawSn={} reason={}",
                    rawSn, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 윈도우 소유권을 원자적으로 획득한 스레드만 전송한다(S6 — 중복 전송 차단).
     *
     * <h3>C-2 / HIGH-E — export 재생성 동반 시 export → 통지 순서 보장, export 는 토글과 무관</h3>
     * 재생성을 동반한 윈도우({@code exportRegenerated=true}, 라벨/촬영환경 승인 후 수정)는 통지를 바로
     * 보내면 관제가 조회하는 {@code EXPORT_PATH_NM} 이 아직 새 버전 폴더를 담지 못한다. 그래서 재산출을
     * {@link AsyncDatasetExportRunner#runReExportThenNotify} 에 위임해 <b>export 를 먼저 마친 뒤</b> 통지
     * 콜백을 실행하게 한다(force=true — 승인 경로와 동일 전량 재생성). 재생성이 없는 윈도우(그 외 메타
     * 수정)는 디스크가 그대로이므로 즉시 통지한다.
     *
     * <p><b>HIGH-E</b>: export 재산출 위임은 통지 토글과 무관하게 <b>항상</b> 실행한다. 통지({@code sendModified})
     * 만 토글 종속이다 — {@code notifyService} 가 {@code null}(토글 off)이면 재생성 윈도우는 export 만 하고
     * 통지 콜백을 붙이지 않으며, 비재생성 윈도우는 아무것도 하지 않는다(디스크 무변경 + 통지 off).
     *
     * @return 클레임(윈도우 remove)에 성공해 처리했으면 true
     */
    private boolean claimAndSend(Long rawSn) {
        DebouncedWindow claimed = windows.remove(rawSn);
        if (claimed == null) {
            return false;
        }
        var frameChanges = claimed.toFrameChanges();
        var videoLevelChangeTypes = claimed.getVideoLevelChangeTypes();
        boolean exportRegenerated = claimed.isExportRegenerated();
        // LOW-1 — 프레임↔변경종류 페어(FrameChangeSet.changeTypes)를 관측에 반영한다. 관제 전송 계약은
        //   파일명 목록만 담으므로 페어링 자체는 실리지 않지만, 최소한 flush 요약 로그로 감사 가능하게 남긴다.
        if (log.isInfoEnabled() && !frameChanges.isEmpty()) {
            log.info("[ControlNotifyDebounce] flush rawSn={} regen={} frames={}",
                    rawSn, exportRegenerated, summarizeChangeTypes(frameChanges));
        }
        if (exportRegenerated) {
            // HIGH-E — export(전량 재생성)는 통지 토글과 무관하게 항상 위임한다(dev/stg/prd 포함).
            //   통지가 켜져 있으면(notifyService!=null) export 종결 후 통지 콜백을 실행해 순서를 직렬화한다(C-2).
            //   통지가 꺼져 있으면 콜백 없이 export 만 수행한다.
            Runnable notifyCallback = notifyService == null ? null
                    : () -> notifyService.sendModified(rawSn, frameChanges, videoLevelChangeTypes, true);
            exportRunner.runReExportThenNotify(rawSn, true, notifyCallback);
            return true;
        }
        // 재생성 없음 — 디스크 산출물 그대로. 통지만(토글 on 일 때). 토글 off 면 할 일 없음.
        if (notifyService != null) {
            notifyService.sendModified(rawSn, frameChanges, videoLevelChangeTypes, false);
        }
        return true;
    }

    /** LOW-1 — 프레임별 변경종류 페어를 로그용 요약 문자열로. */
    private static String summarizeChangeTypes(List<FrameChangeSet> changes) {
        StringBuilder sb = new StringBuilder();
        for (FrameChangeSet c : changes) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c.srcSn()).append('=').append(c.changeTypes());
        }
        return sb.toString();
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
