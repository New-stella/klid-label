package kr.co.cudo.authoring.controlnotify.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.co.cudo.authoring.controlnotify.debounce.ControlNotifyDebounceStore;
import kr.co.cudo.authoring.controlnotify.debounce.DebounceWindow;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
 *   <li><b>S6 중복 flush</b>: 만료 스캔과 {@link #flushAll()}(종료 drain)가 동시에 같은 윈도우를
 *       집어 2회 전송하던 창을 막는다. 전송은 {@link ControlNotifyDebounceStore#claim} 에
 *       <b>성공한 주체만</b> 수행한다.</li>
 *   <li><b>Phase 9-C — 크로스노드 디바운스</b>: 윈도우가 JVM 로컬 {@code ConcurrentHashMap} 이라
 *       2노드 Active-Active 에서 ①같은 영상의 수정이 양쪽에 나뉘어 축적되면 <b>export 재생성·관제
 *       통지가 2회</b> 나가고(관제가 같은 영상을 두 버전으로 픽업), ②노드가 flush 전에 죽으면
 *       <b>축적분이 통째로 유실</b>됐다. 이제 윈도우의 내용과 소유권을 모두 공유 DB
 *       ({@link ControlNotifyDebounceStore})에 두어, 어느 노드에서 축적됐든 <b>한 노드가 전량을 1회만</b>
 *       flush 하고 노드가 죽어도 축적분이 남는다.</li>
 * </ul>
 *
 * <h3>HIGH-E(Phase 5C) — export 재생성 트리거는 통지 토글과 분리한다(항상 활성)</h3>
 * 이 빈은 {@code authoring.control-notify.enabled} 로 게이팅하지 <b>않는다</b>(항상 활성). 승인 후
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
 * ({@code control-notify.enabled} · {@code work-lock.sweep.enabled} ·
 * {@code resolution-backfill.sweep.enabled} — 마지막 키는 2026-07-30 백필 제거와 함께 사라졌다)
 * 중 하나로 켜질 때만 tick 했다. 셋을 모두 끈 형상에서는 flush 가 영영 발화하지 않아, 승인 후 수정이
 * 디바운스 윈도우에 축적만 된 채 export 재생성이 무증상 중단됐다(HIGH-E 잔여).
 *
 * <p>이제 flush 는 <b>이 빈이 소유한 데몬 스레드 1개짜리 전용 {@link ScheduledExecutorService}</b> 로
 * 자가 스케줄한다({@code RoleClaimAttemptPurgeJob}/{@code AugmentJobExpirySweeper} 동형). 그래서 flush 는
 * 무관한 스케줄러 토글의 on/off 와 <b>독립적으로 항상 tick</b> 하고, 무조건적 {@code @EnableScheduling} 을
 * 켜지 않으므로 남의 {@code @Scheduled} 잡을 부수적으로 발화시키지도 않는다.
 *
 * <p>전용 스케줄러는 {@code authoring.dataset-export.regen-flush.enabled}(기본 {@code true} — export 재생성
 * 기능 자신에 묶인 토글)로 게이팅한다. 테스트는 이 값을 {@code false} 로 두어 격리하고, flush 로직은
 * {@link #flushExpiredWindows()} 를 직접 호출해 검증한다(스케줄 발화 배선은 별도 테스트가 검증).
 *
 * <h3>종료 drain 게이팅 — {@code authoring.control-notify.debounce-shutdown-flush-enabled}(기본 true)</h3>
 * {@link #onShutdown()}({@code @PreDestroy})은 컨텍스트를 닫는 중에 <b>DB 트랜잭션</b>을 연다
 * ({@link ControlNotifyDebounceStore#findFlushableIds}). 운영에서는 노드 1개가 1회 수행하는 정상 동작이라
 * 기본값을 {@code true}(현행 동작 유지)로 둔다.
 *
 * <p>반면 테스트는 {@code @SpringBootTest} 컨텍스트가 <b>수십 개</b> 캐시됐다가 JVM 종료 훅에서 일제히
 * 닫히므로 같은 drain 이 컨텍스트 수만큼 반복된다. 이때 커넥션 풀은 이미 축소돼 있고(테스트 풀 크기 2)
 * Testcontainers 가 먼저 내려가 있을 수도 있어, drain 이 커넥션을 얻지 못한 채 타임아웃까지 대기하며
 * <b>종료 훅이 테스트 실행 시간보다 오래 걸리는</b> 상태를 만든다. 그래서 테스트 프로파일만 이 값을
 * {@code false} 로 두어 종료 drain 을 생략한다 — 축적분은 DB 에 남으므로 유실이 아니고(Phase 9-C),
 * drain 로직 자체는 {@link #flushAll()} 직접 호출로 검증된다.
 */
@Component
@Slf4j
public class ControlNotifyDebouncer {

    /**
     * 종료 drain 라운드 상한 — 다른 노드가 계속 새 윈도우를 열어도 {@code @PreDestroy} 가 무한정
     * 붙잡히지 않게 한다(종료 지연 방지). 남은 윈도우는 DB 에 남아 살아 있는 노드가 처리한다.
     */
    private static final int MAX_DRAIN_ROUNDS = 20;

    /** 임차 하한(ms) — 이보다 짧으면 정상 발송 중인 윈도우를 다른 노드가 뺏어 이중 통지가 된다. */
    private static final long MIN_LEASE_MILLIS = 60_000L;

    /** 오설정(0/음수) 시 flush 배치 폴백. */
    private static final int DEFAULT_FLUSH_BATCH_SIZE = 100;

    /** 윈도우 저장소 — 축적 내용과 flush 소유권을 노드 밖(공유 DB)에 둔다(Phase 9-C). */
    private final ControlNotifyDebounceStore store;
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
    /**
     * Phase 9-C — flush 클레임 임차(ms). 클레임한 노드가 이 시간 안에 발송을 마치지 못하면(=죽으면)
     * 다른 노드가 그 윈도우를 재클레임한다(축적분 유실 방지). 임차가 너무 짧으면 정상 발송 중인
     * 윈도우를 뺏어 이중 통지가 되므로 {@link #MIN_LEASE_MILLIS} 로 하한 clamp 한다(오설정 fail-safe).
     */
    private final long leaseMillis;
    /** Phase 9-C — 한 tick 이 처리할 윈도우 상한(무제한 조회 금지, OWASP API4). */
    private final int flushBatchSize;
    /**
     * 종료 drain({@code @PreDestroy}) 수행 여부. 기본 {@code true} = 현행 운영 동작 유지.
     * 테스트 프로파일만 {@code false}(다수 캐시 컨텍스트가 동시에 닫히며 축소된 풀에서 커넥션을 기다리는
     * 종료 지연 방지). 축적분은 DB 에 남으므로 생략해도 유실되지 않는다.
     */
    private final boolean shutdownFlushEnabled;

    /** MED-1 — 이 빈이 소유한 데몬 스레드 1개짜리 flush 스케줄러(전 환경 항상 tick, @EnableScheduling 비의존). */
    @Nullable
    private ScheduledExecutorService flushScheduler;

    public ControlNotifyDebouncer(ControlNotifyDebounceStore store,
                                  @Nullable ControlNotifyService notifyService,
                                  @Value("${authoring.control-notify.debounce-window-sec:60}") long windowSec,
                                  @Nullable ControlNotifyMetrics metrics,
                                  AsyncDatasetExportRunner exportRunner,
                                  @Value("${authoring.dataset-export.regen-flush.enabled:true}") boolean flushSchedulerEnabled,
                                  @Value("${authoring.control-notify.debounce-flush-interval-ms:10000}") long flushIntervalMillis,
                                  @Value("${authoring.control-notify.debounce-lease-sec:300}") long leaseSec,
                                  @Value("${authoring.control-notify.debounce-flush-batch-size:100}") int flushBatchSize,
                                  @Value("${authoring.control-notify.debounce-shutdown-flush-enabled:true}") boolean shutdownFlushEnabled) {
        this.store = store;
        this.notifyService = notifyService;
        this.windowMillis = Math.max(0L, windowSec) * 1000L;
        this.metrics = metrics;
        this.exportRunner = exportRunner;
        this.flushSchedulerEnabled = flushSchedulerEnabled;
        this.flushIntervalMillis = Math.max(1L, flushIntervalMillis);
        this.leaseMillis = Math.max(MIN_LEASE_MILLIS, leaseSec * 1000L);
        this.flushBatchSize = flushBatchSize < 1 ? DEFAULT_FLUSH_BATCH_SIZE : flushBatchSize;
        this.shutdownFlushEnabled = shutdownFlushEnabled;
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
        log.info("[ControlNotifyDebounce] flush scheduler started intervalMs={} leaseMs={}",
                flushIntervalMillis, leaseMillis);
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
     * 이벤트 수신 — 윈도우에 축적한다.
     *
     * <p>{@code srcSn} 이 null(영상 단위 메타 변경)이어도 예외 없이 축적된다. 축적은 공유 DB 에 남으므로
     * <b>이 노드가 flush 전에 죽어도</b> 다른 노드가 이어서 flush 한다(Phase 9-C).
     */
    public void accumulate(TaskModifiedEvent event) {
        store.accumulate(event.rawSn(), event.srcSn(), event.changeType(), event.exportRegenerated());
    }

    /** 만료 윈도우 스캔 — flush. 전용 스케줄러가 {@link #flushIntervalMillis} 간격으로 호출한다(기본 10초). */
    public void flushExpiredWindows() {
        LocalDateTime now = LocalDateTime.now();
        flush(now.minus(Duration.ofMillis(windowMillis)), leaseCutoff(now));
    }

    /**
     * 컨텍스트 종료 훅 — 전용 스케줄러 정지는 <b>항상</b>, 남은 윈도우 drain 은 토글이 켜져 있을 때만.
     *
     * <p>스케줄러 정지를 게이팅하지 않는 이유: 그것은 DB 를 건드리지 않는 순수 자원 해제라, 끄면 데몬
     * 스레드가 컨텍스트보다 오래 남는다(자원 누수). 게이팅 대상은 DB 트랜잭션을 여는 drain 뿐이다.
     */
    @PreDestroy
    void onShutdown() {
        stopFlushScheduler();
        if (!shutdownFlushEnabled) {
            log.info("[ControlNotifyDebounce] shutdown drain disabled — pending windows remain in store for other nodes");
            return;
        }
        drain();
    }

    /**
     * 종료 시 전용 스케줄러를 먼저 멈춘 뒤(새 tick 차단) 남은 윈도우를 <b>만료를 기다리지 않고</b> drain 한다.
     *
     * <p>다른 노드/스케줄러와 같은 윈도우를 동시에 집어도 원자 클레임으로 1회만 전송된다(S6). drain 하지
     * 못한 윈도우는 DB 에 남아 살아 있는 노드가 처리하므로 유실되지 않는다(Phase 9-C).
     *
     * <p>{@code @PreDestroy} 는 {@link #onShutdown()} 이 담당한다 — 이 메서드는 종료 drain 토글과 무관하게
     * <b>호출하면 항상 drain 한다</b>(테스트가 drain 로직을 직접 검증하는 진입점).
     */
    public void flushAll() {
        stopFlushScheduler();
        drain();
    }

    /** 전용 flush 스케줄러 정지(멱등) — DB 를 건드리지 않는 순수 자원 해제. */
    private void stopFlushScheduler() {
        if (flushScheduler != null) {
            flushScheduler.shutdownNow();
            flushScheduler = null;
        }
    }

    /** 만료를 기다리지 않고 남은 윈도우를 라운드 상한까지 비운다. */
    private void drain() {
        for (int round = 0; round < MAX_DRAIN_ROUNDS; round++) {
            LocalDateTime now = LocalDateTime.now();
            // windowCutoff=now — 만료 여부와 무관하게 지금까지 열린 모든 윈도우를 대상으로 한다.
            if (flush(now, leaseCutoff(now)) == 0) {
                return;
            }
        }
    }

    /**
     * 후보를 조회해 클레임에 성공한 윈도우만 발송한다.
     *
     * @return 이번 라운드에 실제로 처리(클레임 성공 + 발송)한 윈도우 수
     */
    private int flush(LocalDateTime windowCutoff, LocalDateTime leaseCutoff) {
        List<Long> candidates = store.findFlushableIds(windowCutoff, leaseCutoff, flushBatchSize);
        int processed = 0;
        for (Long acmlSn : candidates) {
            if (claimAndSendIsolated(acmlSn, windowCutoff, leaseCutoff)) {
                processed++;
                if (metrics != null) {
                    metrics.incrementDebounceFlush();
                }
            }
        }
        return processed;
    }

    /** 임차 만료 기준 시각 — 이 시각 이전에 클레임된 flush 는 크래시 잔재로 보고 재클레임한다. */
    private LocalDateTime leaseCutoff(LocalDateTime now) {
        return now.minus(Duration.ofMillis(leaseMillis));
    }

    /**
     * 윈도우 1건의 전송 실패를 <b>루프에서 격리</b>한다(A-1).
     *
     * <p>여기서 예외가 루프 밖으로 나가면 같은 tick 의 나머지 윈도우도 스캔이 끊겨 함께 지연된다.
     * 개별 실패는 로그만 남기고 다음 윈도우로 진행한다.
     *
     * <p><b>Phase 9-C</b>: 실패한 윈도우는 {@link ControlNotifyDebounceStore#complete} 를 호출하지 않아
     * 저장소에 {@code FLUSHING} 으로 남는다 — 임차가 만료되면 다음 tick(또는 다른 노드)이 재클레임하므로
     * 통지가 <b>소실되지 않고 지연</b>된다. 구 인메모리 구현은 이 지점에서 실제로 소실됐다.
     *
     * @return 클레임에 성공해 전송까지 마쳤으면 true
     */
    private boolean claimAndSendIsolated(Long acmlSn, LocalDateTime windowCutoff, LocalDateTime leaseCutoff) {
        Optional<DebounceWindow> claimed;
        try {
            claimed = store.claim(acmlSn, windowCutoff, leaseCutoff);
        } catch (Exception e) {
            log.error("[ControlNotifyDebounce] window claim failed (retried after lease) acmlSn={} reason={}",
                    acmlSn, e.getClass().getSimpleName());
            return false;
        }
        if (claimed.isEmpty()) {
            return false;
        }
        DebounceWindow window = claimed.get();
        try {
            send(window);
            store.complete(window.acmlSn());
            return true;
        } catch (Exception e) {
            // MED-3 — 발송/재산출 위임이 예외로 끊긴 경우. 이제 윈도우는 저장소에 FLUSHING 으로 남아
            //   임차 만료 후 재클레임되므로 영구 소실이 아니지만, 통지가 즉시 나가지 못한 사실 자체는
            //   관측돼야 하므로 dropped 로 계상하고 flush 성공(debounceFlush)으로는 세지 않는다.
            if (metrics != null) {
                metrics.incrementDropped();
            }
            log.error("[ControlNotifyDebounce] window flush failed (deferred to lease recovery) rawSn={} reason={}",
                    window.rawSn(), e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 클레임한 윈도우를 실제로 발송한다.
     *
     * <h3>C-2 / HIGH-E — export 재생성 동반 시 export → 통지 순서 보장, export 는 토글과 무관</h3>
     * 재생성을 동반한 윈도우({@code exportRegenerated=true}, 라벨/촬영환경 승인 후 수정)는 통지를 바로
     * 보내면 관제가 조회하는 <b>뷰 출력</b> {@code V_COMPLETED_VIDEO.OUTPUT_PATH_NM} 이 아직 새 버전 폴더를
     * 담지 못한다. 그래서 재산출을
     * {@link AsyncDatasetExportRunner#runReExportThenNotify} 에 위임해 <b>export 를 먼저 마친 뒤</b> 통지
     * 콜백을 실행하게 한다(force=true — 승인 경로와 동일 전량 재생성). 재생성이 없는 윈도우(그 외 메타
     * 수정)는 디스크가 그대로이므로 즉시 통지한다.
     *
     * <p><b>HIGH-E</b>: export 재산출 위임은 통지 토글과 무관하게 <b>항상</b> 실행한다. 통지({@code sendModified})
     * 만 토글 종속이다 — {@code notifyService} 가 {@code null}(토글 off)이면 재생성 윈도우는 export 만 하고
     * 통지 콜백을 붙이지 않으며, 비재생성 윈도우는 아무것도 하지 않는다(디스크 무변경 + 통지 off).
     */
    private void send(DebounceWindow window) {
        Long rawSn = window.rawSn();
        List<FrameChangeSet> frameChanges = window.frameChanges();
        var videoLevelChangeTypes = window.videoLevelChangeTypes();
        // LOW-1 — 프레임↔변경종류 페어(FrameChangeSet.changeTypes)를 관측에 반영한다. 관제 전송 계약은
        //   파일명 목록만 담으므로 페어링 자체는 실리지 않지만, 최소한 flush 요약 로그로 감사 가능하게 남긴다.
        if (log.isInfoEnabled() && !frameChanges.isEmpty()) {
            log.info("[ControlNotifyDebounce] flush rawSn={} regen={} frames={}",
                    rawSn, window.exportRegenerated(), summarizeChangeTypes(frameChanges));
        }
        if (window.exportRegenerated()) {
            // HIGH-E — export(전량 재생성)는 통지 토글과 무관하게 항상 위임한다(dev/stg/prd 포함).
            //   통지가 켜져 있으면(notifyService!=null) export 종결 후 통지 콜백을 실행해 순서를 직렬화한다(C-2).
            //   통지가 꺼져 있으면 콜백 없이 export 만 수행한다.
            Runnable notifyCallback = notifyService == null ? null
                    : () -> notifyService.sendModified(rawSn, frameChanges, videoLevelChangeTypes, true);
            exportRunner.runReExportThenNotify(rawSn, true, notifyCallback);
            return;
        }
        // 재생성 없음 — 디스크 산출물 그대로. 통지만(토글 on 일 때). 토글 off 면 할 일 없음.
        if (notifyService != null) {
            notifyService.sendModified(rawSn, frameChanges, videoLevelChangeTypes, false);
        }
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
}
