package kr.co.cudo.authoring.webhook.runner;

import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import kr.co.cudo.authoring.webhook.service.AugmentExtractPersist;
import kr.co.cudo.authoring.webhook.service.AugmentExtractPlan;
import kr.co.cudo.authoring.webhook.service.AugmentExtractSnapshot;
import kr.co.cudo.authoring.webhook.service.AugmentFrameProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 증강 신규 영상의 프레임 재추출 비동기 오케스트레이터 — 커넥션-점유 분리 리팩터(A/B/C 별도 빈).
 *
 * <p>{@link kr.co.cudo.authoring.webhook.service.AugmentResultService#handle} 이 신규 증강 RAW 를
 * PENDING·deIdntfYn='N' 으로 커밋한 <b>직후(AFTER_COMMIT)</b> 호출된다. <b>비-{@code @Transactional}</b>
 * 러너로서 A/B/C 를 <b>서로 다른 스프링 빈</b>으로 순차 호출한다(자기호출 프록시 우회 방지).
 *
 * <ul>
 *   <li>Phase A {@link AugmentExtractSnapshot} — 짧은 REQUIRES_NEW, 멱등 가드·검증·경로 스냅샷 후 즉시 커밋</li>
 *   <li>Phase B {@link AugmentFrameProducer} — 트랜잭션·커넥션 밖 순수 파일 I/O(ffmpeg 프레임 재추출)</li>
 *   <li>Phase C {@link AugmentExtractPersist} — 짧은 REQUIRES_NEW, Phase B 산출 파일을 DB 에 원자 영속</li>
 * </ul>
 *
 * <p>이 구조로 ffmpeg 대용량 I/O 구간에서 DB 커넥션을 점유하지 않는다 — 구 {@code AugmentFrameExtractionService}
 * 가 추출+DB 를 한 REQUIRES_NEW 트랜잭션에 묶어 다건 증강 동시 시 커넥션풀을 압박하던 병목(락-I/O HIGH)을
 * 제거한다. 이번 변경은 <b>순수 커넥션 분리</b>이며, 결과물(프레임·라벨·상태·통지)은 리팩터 전과 동일하다.
 *
 * <h3>동기/비동기 경계 (반드시 준수 — 설계 유지)</h3>
 * <p>부모 {@code findByRawSnForUpdate} 잠금·{@code deIdntfYn=='Y'} 게이트·콜백 멱등 앵커는 절대 이 비동기
 * 경로로 옮기지 않는다(동기 handle 시점의 부모 안전 판정으로만 유효 — CWE-359 PII TOCTOU). A/B/C 어디에도
 * 부모 재검증 게이트를 이식하지 않는다.
 *
 * <h3>실패 정책 (all-or-nothing → FAILED)</h3>
 * <p>어느 단계 예외든 catch 에서 ① Phase B 산출 아티팩트 cleanup 을 동기 수행 + {@code Files.exists}
 * 재확인(잔존 시 ERROR 로그 + {@code augment.cleanup.failed} 메트릭) ② 신규 RAW 를 FAILED 로 전이하고
 * 예외를 삼킨다(@Async). Phase A 자체 예외(파일 미산출)면 cleanup 대상이 없어 생략한다. Phase C 가
 * SKIPPED(A~C 창 중복 트리거 패자)면 파일은 승자 산출물이라 cleanup·FAILED 전이를 하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncAugmentFrameRunner {

    private final AugmentExtractSnapshot snapshotService;
    private final AugmentFrameProducer frameProducer;
    private final AugmentExtractPersist persistService;
    private final BatchTransitionService batchTransitionService;
    private final AugmentMetrics augmentMetrics;

    @Async("batchAsyncExecutor")
    public void runAsync(Long newRawSn, Long dataAugSn) {
        finalizeExtraction(newRawSn, dataAugSn);
    }

    /**
     * A→B→C 순차 오케스트레이션(동기 코어) — @Async 래퍼와 테스트가 공유한다.
     * 예외를 삼키므로 반환은 항상 정상(러너 계약 유지 — 콜백 200 유지).
     */
    void finalizeExtraction(Long newRawSn, Long dataAugSn) {
        log.info("[AsyncAugmentFrameRunner] starting augment frame re-extraction rawSn={} dataAugSn={}",
                newRawSn, dataAugSn);
        AugmentExtractPlan plan = null;
        try {
            // Phase A — 검증·스냅샷(짧은 REQUIRES_NEW). 이미 확정된 신규 RAW 면 멱등 skip.
            Optional<AugmentExtractPlan> opt = snapshotService.snapshot(newRawSn, dataAugSn);
            if (opt.isEmpty()) {
                log.info("[AsyncAugmentFrameRunner] snapshot skipped (already finalized) rawSn={}", newRawSn);
                return;
            }
            plan = opt.get();

            // Phase B — 파일 I/O(트랜잭션·커넥션 밖). ffmpeg 재추출을 파일로만 산출.
            frameProducer.produce(plan);

            // Phase C — Phase B 산출 파일을 DB 에 원자 영속(짧은 REQUIRES_NEW). SKIPPED 면 중복 트리거 패자.
            AugmentExtractPersist.Result result = persistService.persist(plan);
            if (result == AugmentExtractPersist.Result.SKIPPED) {
                // 파일은 승자와 동일 경로(같은 rawSn)라 cleanup 하면 승자 산출물을 지우므로 정리하지 않고 skip.
                log.info("[AsyncAugmentFrameRunner] persist skipped (duplicate trigger loser) rawSn={}", newRawSn);
                return;
            }
            log.info("[AsyncAugmentFrameRunner] augment frame re-extraction completed rawSn={}", newRawSn);
        } catch (RuntimeException e) {
            log.warn("[AsyncAugmentFrameRunner] augment frame re-extraction failed rawSn={} dataAugSn={} cause={}",
                    newRawSn, dataAugSn, e.getClass().getSimpleName());
            handleFailure(newRawSn, plan);
        }
    }

    /**
     * 실패 정리 — ① Phase B 아티팩트 cleanup(스냅샷이 있어야 Phase B 가 파일을 썼을 수 있음; 잔존 시 ERROR·
     * 메트릭) ② 신규 RAW 를 FAILED 로 전이. Phase C REQUIRES_NEW 트랜잭션은 이미 롤백되어 고아 프레임/라벨이
     * 없고, 여기서 파일 고아까지 정리한다(DB·파일 고아 0).
     */
    private void handleFailure(Long newRawSn, AugmentExtractPlan plan) {
        if (plan != null) {
            try {
                boolean clean = frameProducer.cleanup(newRawSn);
                if (!clean) {
                    log.error("[AsyncAugmentFrameRunner] frame artifact cleanup incomplete — orphan files remain rawSn={}",
                            newRawSn);
                    augmentMetrics.cleanupFailed();
                }
            } catch (RuntimeException ce) {
                log.error("[AsyncAugmentFrameRunner] frame artifact cleanup error rawSn={} cause={}",
                        newRawSn, ce.getClass().getSimpleName());
                augmentMetrics.cleanupFailed();
            }
        }
        batchTransitionService.markRawDataFailed(newRawSn);
    }
}
