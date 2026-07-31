package kr.co.cudo.authoring.augment.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.co.cudo.authoring.augment.config.AugmentDiscardProperties;
import kr.co.cudo.authoring.augment.repository.LsDataAugDscdRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 폐기 유예가 지난 파생영상을 <b>실삭제</b>하는 스윕 — Phase 7.
 *
 * <h2>축은 둘이다</h2>
 * <ol>
 *   <li><b>실삭제</b> — 유예 경과 표식을 클레임 → DB 삭제(트랜잭션) → 커밋 후 파일 삭제.</li>
 *   <li><b>파일 정리 재시도</b> — DB 는 지웠는데 파일 삭제가 실패한 비석({@code FILE_DEL_DT IS NULL}).
 *       이 축이 없으면 NAS 순단 한 번에 고아 파일이 영구히 남는다.</li>
 * </ol>
 *
 * <h2>왜 {@code @Scheduled} 가 아닌 전용 executor 인가</h2>
 * <p>본 애플리케이션은 {@code @EnableScheduling} 을 특정 기능 플래그가 켜질 때만 활성화한다. 무조건적
 * {@code @EnableScheduling} 은 게이팅 없이 등록된 <b>남의</b> {@code @Scheduled} 잡까지 전 환경·전
 * 테스트에서 깨운다({@code AugmentJobExpirySweeper} 와 동일한 이유·동일한 구조).
 *
 * <h2>토글 독립</h2>
 * <p>자기 토글({@code authoring.augment.discard.enabled})만 본다. 외부 위탁 모드·관제 통지 토글 같은
 * 남의 스위치에 얹지 않는다 — 이 리포에는 무관한 토글에 종속돼 운영에서만 무증상 중단된 사고가 있다.
 *
 * <h2>2노드 Active-Active</h2>
 * <p>Quartz 클러스터링은 트리거 중복 발화만 막는다. 여기서는 <b>조건부 UPDATE 클레임</b>이 잡 내부
 * 레이스를 막는다(둘은 서로를 대체하지 않는다).
 */
@Slf4j
@Component
public class AugmentDiscardPurgeSweeper {

    private final LsDataAugDscdRepository discardRepository;
    private final AugmentDiscardPurgeTxService purgeTxService;
    private final DerivativeArtifactRemover artifactRemover;
    private final AugmentDiscardProperties properties;

    private ScheduledExecutorService scheduler;

    public AugmentDiscardPurgeSweeper(LsDataAugDscdRepository discardRepository,
                                      AugmentDiscardPurgeTxService purgeTxService,
                                      DerivativeArtifactRemover artifactRemover,
                                      AugmentDiscardProperties properties) {
        this.discardRepository = discardRepository;
        this.purgeTxService = purgeTxService;
        this.artifactRemover = artifactRemover;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        if (!properties.enabled()) {
            log.info("[Augment][Discard] 실삭제 스윕 비활성 — 표식만 적재되고 삭제는 일어나지 않습니다");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "augment-discard-purge-sweep");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::run, properties.initialDelayMs(),
                properties.intervalMs(), TimeUnit.MILLISECONDS);
        log.info("[Augment][Discard] 실삭제 스윕 예약 graceDays={} intervalMs={} batchSize={}",
                properties.graceDays(), properties.intervalMs(), properties.batchSize());
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
     * 스윕 1회 — 예외를 삼켜 스케줄러 스레드 사망을 막는다(죽으면 삭제가 조용히 영구 정지한다).
     *
     * @return 이번 tick 이 실삭제한 파생 수
     */
    public int run() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.graceDays());
        int purged = runIsolated(() -> purgeExpired(cutoff));
        runIsolated(this::retryFileCleanup);
        return purged;
    }

    private int runIsolated(java.util.function.IntSupplier axis) {
        try {
            return axis.getAsInt();
        } catch (Throwable e) { // Error 까지 잡지 않으면 스케줄러가 영구 정지한다.
            log.error("[Augment][Discard] 스윕 실패 reason={}", e.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * 유예가 지난 폐기 표식을 실삭제한다.
     *
     * <h3>cutoff 는 신뢰하지 않고 클램프한다 (FIX-1)</h3>
     * <p>최종 DELETE 의 다른 두 조건({@code ORGNL_RAW_SN IS NOT NULL} · {@code NOT EXISTS(APPROVED)})은
     * SQL 리터럴이라 호출처가 무엇을 넘겨도 무력화되지 않는데, <b>유예만 파라미터</b>였다. 그러면 나중에
     * 누군가 "즉시 폐기" 버튼이나 dev 트리거를 만들며 {@code purgeExpired(now())} 를 부르는 순간
     * <b>반려 직후 DB 행과 NAS 파일이 영구 삭제</b>된다 — 클레임·최종 DELETE 도 같은 cutoff 를 믿으므로
     * 아무 가드도 걸리지 않는다. 그래서 진입부에서 {@link AugmentDiscardProperties#clampCutoff} 로
     * <b>유예를 좁히는 방향의 값을 하드 컷오프로 되돌린다</b>(넓히는 방향은 그대로 허용).
     *
     * @param cutoff 이 시각 이전에 폐기 표식이 찍힌 건만 대상. 하드 상한은 {@code now - graceDays} 이며
     *               그보다 늦은(= 유예를 좁히는) 값은 무시된다.
     */
    public int purgeExpired(LocalDateTime cutoff) {
        cutoff = properties.clampCutoff(cutoff);
        LocalDateTime claimStaleCutoff =
                LocalDateTime.now().minusMinutes(properties.claimStaleMinutes());
        List<Long> candidates = discardRepository.findPurgeCandidates(
                cutoff, claimStaleCutoff, properties.batchSize());
        if (candidates.isEmpty()) {
            log.debug("[Augment][Discard] 실삭제 대상 없음");
            return 0;
        }
        int purged = 0;
        for (Long dscdSn : candidates) {
            if (purgeOne(dscdSn, cutoff, claimStaleCutoff)) {
                purged++;
            }
        }
        if (purged > 0) {
            log.warn("[Augment][Discard] 유예 경과 파생영상 실삭제 count={} candidates={} graceDays={}",
                    purged, candidates.size(), properties.graceDays());
        }
        return purged;
    }

    /** 파생 1건 집행 — 클레임 → DB 삭제(tx) → 커밋 후 파일 삭제. */
    private boolean purgeOne(Long dscdSn, LocalDateTime cutoff, LocalDateTime claimStaleCutoff) {
        if (!purgeTxService.claim(dscdSn, cutoff, claimStaleCutoff)) {
            return false; // 다른 노드가 선점했거나 그 사이 복구됐다.
        }
        AugmentDiscardPurgeTxService.Outcome outcome;
        try {
            outcome = purgeTxService.purge(dscdSn, cutoff);
        } catch (AugmentDiscardPurgeTxService.DiscardPurgeAbortedException e) {
            if (e.isInvariantViolation()) {
                // 삭제 조건 드리프트 — 사람이 봐야 한다(그대로 두면 고아가 남는다). 아무것도 지워지지
                // 않았고(전체 롤백) 클레임은 만료 후 재시도되므로 같은 신호가 반복 노출된다.
                log.error("[Augment][Discard] 실삭제 중단(불변식 위반 — 확인 필요) dscdSn={}", dscdSn);
                return false;
            }
            // 정상 흐름 — 클레임 이후 복구가 이겼다. 아무것도 지워지지 않았다(전체 롤백).
            log.info("[Augment][Discard] 실삭제 중단(복구 우선) dscdSn={}", dscdSn);
            return false;
        } catch (RuntimeException e) {
            log.error("[Augment][Discard] 실삭제 실패 dscdSn={} reason={}",
                    dscdSn, e.getClass().getSimpleName());
            purgeTxService.releaseClaim(dscdSn); // 스트랜드 클레임 방지 — 다음 tick 이 재시도한다.
            return false;
        }
        if (outcome.result() != AugmentDiscardPurgeTxService.Result.PURGED) {
            purgeTxService.releaseClaim(dscdSn);
            return false;
        }
        cleanupFiles(dscdSn, outcome.rawSn(), outcome.videoPath());
        return true;
    }

    /**
     * DB 삭제 커밋 이후의 파일 정리. 실패해도 예외를 던지지 않고 재시도 축에 남긴다(H5).
     *
     * <h3>재시도는 반드시 수렴한다 (FIX-3)</h3>
     * <p>파생 프레임 트리에 <b>우리가 의도적으로 지우지 않는</b> 항목(심링크·비정규 파일)이 있으면 정리는
     * 재시도해도 영원히 완료되지 않는다. 구 구현은 상한도 종결 표시도 없어 그런 비석이 재시도 큐
     * (오래된 순)의 앞자리를 <b>영구 점유</b>했고, batch-size 만큼 쌓이면 이후 비석의 파일 정리가 전면
     * 정지했다. 지금은 삭제기가 사유를 구분해 돌려주므로
     * ({@link DerivativeArtifactRemover.Outcome#UNRESOLVABLE}) 즉시 종결하고, 일시적 실패도 상한
     * ({@code file-cleanup-max-attempts})을 넘으면 종결해 큐에서 뺀다(사람이 수동 정리).
     */
    private void cleanupFiles(Long dscdSn, Long rawSn, String videoPath) {
        if (rawSn == null) {
            purgeTxService.markFilesDeleted(dscdSn);
            return;
        }
        DerivativeArtifactRemover.Outcome outcome;
        try {
            outcome = artifactRemover.remove(rawSn, videoPath);
        } catch (RuntimeException e) {
            log.warn("[Augment][Discard] 파일 삭제 실패(재시도 대상) dscdSn={} rawSn={} reason={}",
                    dscdSn, rawSn, e.getClass().getSimpleName());
            purgeTxService.recordFileCleanupFailure(dscdSn, false, e.getClass().getSimpleName());
            return;
        }
        if (outcome == DerivativeArtifactRemover.Outcome.COMPLETE) {
            purgeTxService.markFilesDeleted(dscdSn);
            log.info("[Augment][Discard] 파생 산출물 파일 정리 완료 dscdSn={} rawSn={}", dscdSn, rawSn);
            return;
        }
        boolean unresolvable = outcome == DerivativeArtifactRemover.Outcome.UNRESOLVABLE;
        boolean abandoned = purgeTxService.recordFileCleanupFailure(dscdSn, unresolvable, outcome.name());
        if (!abandoned) {
            log.warn("[Augment][Discard] 파일 일부 잔존 — 다음 tick 재시도 dscdSn={} rawSn={}", dscdSn, rawSn);
        }
    }

    /**
     * 축 ② — DB 는 지웠는데 파일 정리가 남은 비석을 재시도한다.
     *
     * <p>후보 조회는 종결(데드레터)된 비석을 제외하므로 이 큐는 <b>반드시 비워진다</b> — 상한 안에서
     * 성공하거나, 상한을 넘겨 종결되거나 둘 중 하나다(FIX-3).
     */
    public int retryFileCleanup() {
        List<Long> pending = discardRepository.findFileCleanupPending(properties.batchSize());
        int done = 0;
        for (Long dscdSn : pending) {
            AugmentDiscardPurgeTxService.FileCleanupRef ref = purgeTxService.loadCleanupRef(dscdSn);
            if (ref == null) {
                continue;
            }
            cleanupFiles(ref.dscdSn(), ref.rawSn(), ref.videoPath());
            done++;
        }
        return done;
    }
}
