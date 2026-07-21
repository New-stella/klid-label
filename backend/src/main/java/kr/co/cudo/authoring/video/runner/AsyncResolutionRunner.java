package kr.co.cudo.authoring.video.runner;

import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.service.ResolutionDerivativeFinalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 해상도 파생영상 확정 비동기 실행기 — Phase 2 (RQ-SFR-06-03 파생영상).
 *
 * <p>{@code ResolutionReservationPersister} 가 파생 RAW 를 PENDING·deIdntfYn='N' 으로 커밋한
 * <b>직후(AFTER_COMMIT)</b> 호출된다. {@code AsyncAugmentFrameRunner} 패턴을 따라 @Async 로 트랜잭션
 * 밖에서 블로킹 복사/리스케일을 수행하고, 실제 DB 쓰기는 {@link ResolutionDerivativeFinalizer}
 * (REQUIRES_NEW)에 위임한다(자기호출 우회 방지 — 별도 빈).
 *
 * <h3>실패 정책 (all-or-nothing → FAILED)</h3>
 * <p>확정 실패 시 finalizer 의 REQUIRES_NEW 트랜잭션이 롤백되어 고아 프레임/라벨이 남지 않는다.
 * 여기서는 {@link BatchTransitionService#markRawDataFailed}(별도 REQUIRES_NEW)로 파생 RAW 를 FAILED 로
 * 전이해 "마킹 대기" 고착을 막고 예외를 삼킨다(@Async).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncResolutionRunner {

    private final ResolutionDerivativeFinalizer finalizer;
    private final BatchTransitionService batchTransitionService;

    @Async("batchAsyncExecutor")
    public void runAsync(Long newRawSn, Long parentRawSn, Long resExportSn, ResolutionPreset preset) {
        log.info("[AsyncResolutionRunner] starting resolution derivative finalize rawSn={} parentRawSn={} preset={}",
                newRawSn, parentRawSn, preset != null ? preset.name() : "null");
        try {
            finalizer.finalizeDerivative(newRawSn, parentRawSn, resExportSn, preset);
            log.info("[AsyncResolutionRunner] resolution derivative finalize completed rawSn={}", newRawSn);
        } catch (RuntimeException e) {
            // 실패 — 확정 트랜잭션은 이미 롤백됨(고아 없음). 파생 RAW 를 FAILED 로 전이(별도 커밋)해
            // MARKING_READY 고착을 막고 예외를 삼킨다(@Async). 자동 재시도 큐는 두지 않는다.
            log.warn("[AsyncResolutionRunner] resolution derivative finalize failed rawSn={} cause={}",
                    newRawSn, e.getClass().getSimpleName());
            // MEDIUM — DB 는 REQUIRES_NEW 롤백으로 고아가 없으나 트랜잭션 밖에서 이미 쓰인 파생 비디오/
            // 리스케일 프레임 파일은 남는다. best-effort 로 파생 산출 디렉토리/파일을 정리한다(예외 무시).
            try {
                finalizer.cleanupDerivativeArtifacts(newRawSn);
            } catch (RuntimeException ce) {
                log.warn("[AsyncResolutionRunner] derivative artifact cleanup skipped rawSn={} cause={}",
                        newRawSn, ce.getClass().getSimpleName());
            }
            batchTransitionService.markRawDataFailed(newRawSn);
        }
    }
}
