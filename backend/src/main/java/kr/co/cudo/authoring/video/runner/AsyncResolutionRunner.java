package kr.co.cudo.authoring.video.runner;

import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.observability.metrics.ResolutionMetrics;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.service.ResolutionFileMaterializer;
import kr.co.cudo.authoring.video.service.ResolutionPersistService;
import kr.co.cudo.authoring.video.service.ResolutionSnapshot;
import kr.co.cudo.authoring.video.service.ResolutionSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 해상도 파생영상 확정 비동기 오케스트레이터 — 락-I/O 분리 리팩터 (RQ-SFR-06-03 파생영상).
 *
 * <p>{@code ResolutionReservationPersister} 가 파생 RAW 를 PENDING·deIdntfYn='N' 으로 커밋한
 * <b>직후(AFTER_COMMIT)</b> 호출된다. <b>비-{@code @Transactional}</b> 러너로서 2단계 잠금 A/B/C 를
 * <b>서로 다른 스프링 빈</b>으로 순차 호출한다(자기호출 프록시 우회 방지).
 *
 * <ul>
 *   <li>Phase A {@link ResolutionSnapshotService} — 짧은 REQUIRES_NEW + 부모 잠금, 검증·스냅샷 후 즉시 커밋</li>
 *   <li>Phase B {@link ResolutionFileMaterializer} — 잠금·트랜잭션 밖 순수 파일 I/O(비디오 복사·프레임 리스케일)</li>
 *   <li>Phase C {@link ResolutionPersistService} — 짧은 REQUIRES_NEW + 재잠금·재검증(PII TOCTOU 최종 게이트) 후 영속</li>
 * </ul>
 *
 * <h3>실패 정책 (all-or-nothing → FAILED)</h3>
 * <p>어느 단계 예외든 catch 에서 ① Phase B 산출 아티팩트 cleanup 을 동기 수행 + {@code Files.exists}
 * 재확인(잔존 시 ERROR 로그 + {@code resolution.cleanup.failed} 메트릭) ② markRawDataFailed <b>이전</b>
 * newRaw 최신 상태를 재조회해 이미 확정(Y/MARKING_READY)이면 스킵(중복 finalize 승자를 FAILED 로 덮어쓰지
 * 않음) ③ 예약 aug 행 삭제로 부분 유니크 슬롯을 해제한 뒤 파생 RAW 를 FAILED 로 전이하고 예외를 삼킨다(@Async).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncResolutionRunner {

    private final ResolutionSnapshotService snapshotService;
    private final ResolutionFileMaterializer fileMaterializer;
    private final ResolutionPersistService persistService;
    private final BatchTransitionService batchTransitionService;
    private final ResolutionMetrics resolutionMetrics;

    @Async("batchAsyncExecutor")
    public void runAsync(Long newRawSn, Long parentRawSn, Long dataAugSn, ResolutionPreset preset) {
        finalizeDerivative(newRawSn, parentRawSn, dataAugSn, preset);
    }

    /**
     * A→B→C 순차 오케스트레이션(동기 코어) — @Async 래퍼와 테스트가 공유한다.
     * 예외를 삼키므로 반환은 항상 정상(러너 계약 유지).
     */
    void finalizeDerivative(Long newRawSn, Long parentRawSn, Long dataAugSn, ResolutionPreset preset) {
        log.info("[AsyncResolutionRunner] starting resolution derivative finalize rawSn={} parentRawSn={} dataAugSn={} preset={}",
                newRawSn, parentRawSn, dataAugSn, preset != null ? preset.name() : "null");
        ResolutionSnapshot snapshot = null;
        try {
            // Phase A — 검증·스냅샷(짧은 REQUIRES_NEW, 부모 잠금). 이미 확정된 파생이면 멱등 skip.
            Optional<ResolutionSnapshot> opt = snapshotService.snapshot(newRawSn, parentRawSn, dataAugSn, preset);
            if (opt.isEmpty()) {
                log.info("[AsyncResolutionRunner] snapshot skipped (already finalized) rawSn={}", newRawSn);
                return;
            }
            snapshot = opt.get();

            // Phase B — 파일 I/O(잠금·트랜잭션 밖). 리사이즈 게이트로 동시 총량 통제.
            fileMaterializer.materialize(snapshot);

            // Phase C — 재검증·영속(짧은 REQUIRES_NEW, 재잠금). CAS skip 이면 중복 finalize 패자.
            ResolutionPersistService.Result result = persistService.persist(snapshot);
            if (result == ResolutionPersistService.Result.SKIPPED) {
                // #5 — 동시 finalize 승자가 이미 확정. 파일은 승자와 동일 경로라 cleanup 하면 승자 산출물을
                //      지우므로 절대 정리하지 않고 skip(승자 성공 훼손 방지).
                log.info("[AsyncResolutionRunner] persist skipped (concurrent winner) rawSn={}", newRawSn);
                return;
            }
            log.info("[AsyncResolutionRunner] resolution derivative finalize completed rawSn={}", newRawSn);
        } catch (RuntimeException e) {
            log.warn("[AsyncResolutionRunner] resolution derivative finalize failed rawSn={} dataAugSn={} cause={}",
                    newRawSn, dataAugSn, e.getClass().getSimpleName());
            handleFailure(newRawSn, dataAugSn, snapshot);
        }
    }

    /**
     * 실패 정리 — ①중복 finalize 승자 보호 선점검(cleanup·FAILED 전이 모두 스킵) ②Phase B 아티팩트 cleanup
     * (+잔존 시 ERROR·메트릭) ③예약 aug 슬롯 해제 + FAILED 전이. 모든 정리는 best-effort 로 원래 실패/전이를
     * 가리지 않는다.
     *
     * <p>MEDIUM (CWE-362, M-1) — <b>승자 존재 확인을 cleanup 보다 먼저</b> 수행한다. 파생 RAW/파일 경로는
     * 동시 finalize 승자와 동일하므로, 패자의 실패 정리가 cleanup 을 먼저 돌리면 승자가 방금 확정한 산출물
     * (프레임·비디오)을 삭제해버린다. 승자가 이미 확정(Y/MARKING_READY)이면 cleanup 과 FAILED 전이를 모두
     * 스킵해(SKIPPED 경로와 동일한 승자 산출물 보호 불변식을 예외 경로에도 대칭 적용) 승자 산출물을 보존한다.
     */
    private void handleFailure(Long newRawSn, Long dataAugSn, ResolutionSnapshot snapshot) {
        // ① 중복 finalize 승자 보호(선점검) — newRaw 최신 상태가 이미 확정(Y/MARKING_READY)이면
        //    cleanup·FAILED 전이 모두 스킵(패자 정리가 승자 산출물을 삭제하는 것 방지).
        boolean alreadyFinalized;
        try {
            // safe: persist() 의 REQUIRES_NEW 트랜잭션이 이미 커밋·잠금 해제된 뒤 호출된다(자기교착 없음).
            // 이 호출을 persist() 잠금 보유 스코프 안으로 옮기면 FOR UPDATE 재조회가 자기 잠금과 교착하니 금지.
            alreadyFinalized = persistService.isAlreadyFinalized(newRawSn);
        } catch (RuntimeException re) {
            alreadyFinalized = false; // 재조회 실패는 보수적으로 미확정 취급(cleanup + FAILED 전이 진행).
        }
        if (alreadyFinalized) {
            log.info("[AsyncResolutionRunner] skip cleanup + FAILED transition — concurrent winner already finalized rawSn={}",
                    newRawSn);
            return;
        }

        // ② Phase B 산출 아티팩트 정리(스냅샷이 있어야 Phase B 가 파일을 썼을 수 있음). 잔존 시 ERROR + 메트릭.
        if (snapshot != null) {
            try {
                boolean clean = fileMaterializer.cleanup(newRawSn, snapshot.videoDst());
                if (!clean) {
                    log.error("[AsyncResolutionRunner] derivative artifact cleanup incomplete — orphan files remain rawSn={}",
                            newRawSn);
                    resolutionMetrics.cleanupFailed();
                }
            } catch (RuntimeException ce) {
                log.error("[AsyncResolutionRunner] derivative artifact cleanup error rawSn={} cause={}",
                        newRawSn, ce.getClass().getSimpleName());
                resolutionMetrics.cleanupFailed();
            }
        }

        // ③ 예약 aug 슬롯 해제(best-effort) 후 FAILED 전이.
        try {
            persistService.releaseReservedAug(dataAugSn);
        } catch (RuntimeException re) {
            log.warn("[AsyncResolutionRunner] reserved aug release skipped dataAugSn={} cause={}",
                    dataAugSn, re.getClass().getSimpleName());
        }
        batchTransitionService.markRawDataFailed(newRawSn);
    }
}
