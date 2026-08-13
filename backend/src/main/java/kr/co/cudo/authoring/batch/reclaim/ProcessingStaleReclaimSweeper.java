package kr.co.cudo.authoring.batch.reclaim;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.co.cudo.authoring.batch.status.ReprocessClaimOrigin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「처리 중」으로 <b>고착된 영상 회수 스윕</b> — 애플리케이션 안의 유일한 복구 수단.
 *
 * <h3>막는 실패 모드 (이게 없으면 DB 직접 수정뿐이다)</h3>
 * <p>재기동({@code BatchReprocessService})·묶음 재수행({@code BatchStageRerunService})은 요청 안에서
 * 배치 단계를 {@code PROCESSING} 으로 <b>선점</b>하고 실행은 전용 풀({@code batchReprocessExecutor} —
 * core 2 / 큐 4)로 넘긴다. 그 <b>대기 중에 노드가 재시작·재배포되면 큐가 통째로 사라지는데 선점 표시는
 * DB 에 남는다</b>. 그 영상은 이후 모든 재기동이 409 로 막히고 자동 재시도 대기 행도 접수 때 지워져
 * 있어 복구 경로가 남지 않는다. 한 번에 고착될 수 있는 최대 폭은 <b>8건</b>(실행 4 + 큐 4)이다.
 *
 * <p>기존 3중 보상({@code releaseReprocessClaim} · {@code restoreAfterBundleRerunFailure} ·
 * 오케스트레이터 자체 마감)은 모두 <b>러너가 실제로 돈 뒤</b>를 전제하므로 이 경로를 하나도 덮지 못한다.
 *
 * <h3>★판정 축은 "진행이 멈췄는가"다 — 경과 시간이 아니다</h3>
 * <p>「{@code PROCESSING} 인 지 N 시간」으로 판정하면 실제로 돌고 있는 파이프라인을 죽이고, 그 작업이
 * 나중에 끝나면서 상태를 덮어써 뒤죽박죽이 된다. 판정 규칙은
 * {@link ProcessingStaleReclaimTxService#decide} 가 소유한다 — 선점 시각과 진행 로그 갱신 시각 중 더
 * 나중이 임계보다 오래됐고, 그 표식이 <b>이번 에피소드</b>의 것일 때만 고착으로 본다. 여기서 규칙을
 * 재유도하지 않는다.
 *
 * <h3>★되돌릴 곳은 선점 출발 상태가 정한다 (데이터 파괴 차단)</h3>
 * <p>무조건 {@code FAILED} 로 되돌리면 완주 영상이 실패로 뒤집혀 전체 재기동 경로가 열리고, 그 경로는
 * 파이프라인을 통째로 순회해 사람이 손댄 보간 라벨을 전량 삭제·재생성한다(복구 지점 0). 출발 상태는
 * 선점 시점에 {@code LS_BATCH_PROC_LOG} 표식으로 남고({@code ReprocessClaimMarker}) 축 매핑은
 * {@link ReprocessClaimOrigin} 이 소유한다. <b>알 수 없으면 회수하지 않고 보류한다</b>(fail-closed).
 *
 * <h3>2노드 Active-Active 정합 — 원자 클레임 (CWE-362)</h3>
 * <p>회수 자체가 조건부 UPDATE({@code BatchTransitionService#reclaimStuckProcessing} — "지금
 * {@code PROCESSING} 일 때만")라 두 노드가 같은 영상을 동시에 집어도 정확히 1건만 성공하고, 진 쪽은
 * 감사 기록도 남기지 않는다. Quartz 클러스터링은 트리거 중복만 막고 잡 내부 레이스는 막지 못한다.
 *
 * <h3>★자동 재시도 큐에 넣지 않는다</h3>
 * <p>회수는 상태를 되돌리기만 하고 <b>사람이 다시 누르게</b> 한다. 자동으로 다시 돌면 같은 이유로 또
 * 멈추고, 완주 축 영상이면 위 파괴와 연결된다.
 *
 * <h3>왜 {@code @Scheduled} 가 아닌 전용 executor 인가</h3>
 * <p>본 애플리케이션은 {@code @EnableScheduling} 을 특정 기능 플래그가 켜질 때만 활성화한다. 무조건적
 * {@code @EnableScheduling} 은 게이팅 없이 등록된 <b>남의</b> {@code @Scheduled} 잡까지 전 환경·전
 * 테스트에서 깨운다. 그래서 데몬 스레드 1개짜리 전용 스케줄러를 쓴다
 * ({@code BatchRetryStaleReclaimSweeper}/{@code VlmSubmitPendingSweeper} 동형).
 *
 * <h3>토글 독립</h3>
 * <p>자기 토글({@code authoring.batch.stuck-reclaim.enabled}, 기본 true)만 본다 — 무관한 토글에 얹혀
 * 운영에서만 조용히 멈추던 사고 패턴을 피한다. 재기동을 쓰지 않는 환경에서는 후보 0건으로 무해하게 돈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessingStaleReclaimSweeper {

    /** 보류 요약 로그에 예시로 붙이는 rawSn 최대 개수 — 로그 폭주 방지. */
    static final int WITHHELD_SAMPLE_LIMIT = 5;

    private final ProcessingStaleReclaimProperties properties;
    private final ProcessingStaleReclaimTxService reclaimTxService;

    /**
     * ★<b>회전 커서</b> — 다음 tick 이 훑기 시작할 지점({@code RAW_SN} 배타적 하한).
     *
     * <h3>없으면 스윕이 조용히 무력화된다</h3>
     * <p>후보 조회는 {@code RAW_SN} 오름차순 + 상한이라 <b>매 tick 같은 앞줄</b>을 뽑는다. 그런데 판정이
     * <b>보류</b>된 후보는 상태가 그대로여서 다음 tick 에도 같은 자리에 다시 뽑힌다 — 표식 없는
     * {@code PROCESSING} 은 선점 직전 상태를 기록하지 않는 다른 진입 경로가 남기며 <b>회수해 줄 다른
     * 주체가 없다</b>. 그런 영상이 상한만큼 쌓이면 그 뒤의 <b>진짜 회수 대상이 영영 판정되지 않는다</b>.
     *
     * <p>그래서 한 tick 이 상한을 꽉 채웠으면 다음 tick 은 그 뒤부터 이어서 훑고, 상한에 못 미쳤으면
     * (=끝에 닿았으면) 처음으로 돌아온다. 상한 자체는 그대로 유지한다(자원 보호, CWE-770).
     *
     * <p><b>노드별 인메모리 상태</b>다 — 새 DB 컬럼·테이블을 만들지 않는다. 재기동하면 처음부터 훑으며,
     * 커서가 흔들려도 잃는 것은 "한 tick 늦게 본다"뿐이라 정확성 요구가 없다. 스윕은 단일 데몬 스레드가
     * 돌지만 테스트가 {@link #run()} 을 직접 부르므로 가시성 보장을 위해 원자 변수로 둔다.
     */
    private final AtomicLong scanCursor = new AtomicLong(0L);

    private ScheduledExecutorService scheduler;

    @PostConstruct
    void start() {
        if (!properties.enabled()) {
            log.info("[Batch][StuckReclaim] 「처리 중」 고착 회수 스윕 비활성");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "batch-stuck-reclaim");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::run,
                properties.initialDelayMs(), properties.intervalMs(), TimeUnit.MILLISECONDS);
        log.info("[Batch][StuckReclaim] 고착 회수 스윕 예약 intervalMs={} staleTimeoutMinutes={} batchSize={}",
                properties.intervalMs(), properties.staleTimeoutMinutes(), properties.batchSize());
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** 전용 스케줄러가 떠 있는가 — 배선 고정 테스트가 컨텍스트 경유로 확인한다. */
    public boolean isScheduled() {
        return scheduler != null;
    }

    /**
     * 스윕 1회 — 예외를 삼켜 스케줄러 스레드 사망을 막는다(죽으면 회수가 조용히 영구 정지한다).
     *
     * <p>{@code RuntimeException} 만 잡으면 Error 계열이 스레드를 죽여 {@code scheduleWithFixedDelay} 가
     * 영구 정지하므로 {@code Throwable} 을 잡는다.
     *
     * @return 이번 tick 이 <b>회수에 성공한</b> 건수(다른 노드가 가져간 건·판정 보류 건은 제외)
     */
    public int run() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(properties.staleTimeoutMinutes());
            int limit = properties.batchSize();
            long cursor = scanCursor.get();
            List<Long> candidates = reclaimTxService.findStaleCandidates(cutoff, cursor, limit);
            advanceCursor(candidates, limit);

            int reclaimed = 0;
            List<Long> withheld = new ArrayList<>();
            for (Long rawSn : candidates) {
                switch (reclaimOne(rawSn, cutoff)) {
                    case RECLAIMED -> reclaimed++;
                    case WITHHELD -> withheld.add(rawSn);
                    default -> { /* 살아 있음·경합 패배 — 정상이므로 기록하지 않는다. */ }
                }
            }
            if (reclaimed > 0) {
                log.warn("[Batch][StuckReclaim] 고착 선점 회수 count={} candidates={} staleTimeoutMinutes={}",
                        reclaimed, candidates.size(), properties.staleTimeoutMinutes());
            }
            logWithheldSummary(withheld);
            return reclaimed;
        } catch (Throwable e) {
            log.error("[Batch][StuckReclaim] 스윕 실패 reason={}", e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * 회전 커서 전진 — 상한을 꽉 채웠으면 마지막 후보 뒤로, 못 채웠으면(끝에 닿았으면) 처음으로.
     *
     * <p>커서를 <b>판정 결과와 무관하게</b> 옮기는 것이 핵심이다. "보류된 것만 건너뛴다" 로 만들면
     * 보류가 상한을 채운 tick 에서 커서가 제자리라 같은 무력화가 그대로 남는다.
     */
    private void advanceCursor(List<Long> candidates, int limit) {
        if (candidates.size() < limit) {
            scanCursor.set(0L); // 끝까지 훑었다 — 다음 tick 은 처음부터.
            return;
        }
        scanCursor.set(candidates.get(candidates.size() - 1));
    }

    /**
     * 보류 요약 — <b>tick 당 1줄</b>. 건별로 찍으면 15분 주기 × 최대 상한만큼 같은 경고가 반복돼
     * <b>진짜 회수 경고가 그 잡음에 묻힌다</b>(이 리포의 "WARN 은 배포 로그에 묻힌다" 관례).
     * 다만 <b>보류 사실 자체는 반드시 남긴다</b> — 그 영상은 애플리케이션이 스스로 풀 수 없어 사람이
     * 봐야 한다.
     */
    private void logWithheldSummary(List<Long> withheld) {
        if (withheld.isEmpty()) {
            return;
        }
        log.warn("[Batch][StuckReclaim] 판정 불가로 회수 보류 count={} sample={} (수동 확인 필요 — "
                        + "선점 출발 상태를 알 수 없거나 옛 에피소드의 잔재 표식이다)",
                withheld.size(), withheld.stream().limit(WITHHELD_SAMPLE_LIMIT).toList());
    }

    /** 후보 1건 처리 결과 — tick 요약 집계의 입력. */
    private enum CandidateOutcome { RECLAIMED, WITHHELD, SKIPPED }

    /** 후보 1건 회수 — 건별 예외는 스윕 전체를 멈추지 않는다. */
    private CandidateOutcome reclaimOne(Long rawSn, LocalDateTime cutoff) {
        try {
            ReclaimDecision decision = reclaimTxService.decide(rawSn, cutoff);
            if (decision.isWithheld()) {
                log.debug("[Batch][StuckReclaim] 회수 보류 rawSn={} reason={}",
                        rawSn, decision.reason().description());
                return CandidateOutcome.WITHHELD;
            }
            if (!decision.isReclaimable()) {
                return CandidateOutcome.SKIPPED; // 아직 살아 있다 — 정상.
            }
            ReprocessClaimOrigin origin = decision.origin();
            // ★ 되돌리기와 표식 닫기를 한 트랜잭션으로 — 사이에서 끊기면 표식이 열린 채 남아 이후
            //   다른 경로로 고착된 같은 영상을 옛 출발 상태로 되돌리게 된다(완주 영상 FAILED 강등).
            //   CWE-362 — 그 안의 조건부 UPDATE 가 0행이면 다른 노드가 이미 가져간 것이다.
            if (!reclaimTxService.reclaimAndClose(rawSn, origin)) {
                return CandidateOutcome.SKIPPED;
            }
            log.warn("[Batch][StuckReclaim] 회수 완료 rawSn={} origin={} stage={} work={} "
                            + "(자동 재시도하지 않는다 — 사용자가 다시 요청해야 한다)",
                    rawSn, origin, origin.stageStatus(), origin.workStatus());
            return CandidateOutcome.RECLAIMED;
        } catch (RuntimeException e) {
            log.warn("[Batch][StuckReclaim] 회수 실패 rawSn={} reason={}",
                    rawSn, e.getClass().getSimpleName());
            return CandidateOutcome.SKIPPED;
        }
    }
}
