package kr.co.cudo.authoring.webhook.runner;

import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.webhook.service.AugmentFrameExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 증강 신규 영상의 프레임 재추출 비동기 실행기 (Phase 11 — 증강본 프레임 복사→재추출 전환).
 *
 * <p>{@link kr.co.cudo.authoring.webhook.service.AugmentResultService#handle} 이 신규 증강 RAW 를
 * PENDING·deIdntfYn='N' 으로 커밋한 <b>직후(AFTER_COMMIT)</b> 호출된다.
 * {@link AsyncDeidentifyRunner} 패턴을 따라 @Async 로 트랜잭션 밖에서 블로킹 추출을 수행하고,
 * 실제 DB 쓰기는 {@link AugmentFrameExtractionService#extractAndCopy} 의 REQUIRES_NEW 독립
 * 트랜잭션에 위임한다(자기호출 우회 방지 — 별도 빈).
 *
 * <h3>동기/비동기 경계 (Phase 11 — 반드시 준수)</h3>
 * <p>부모 {@code findByRawSnForUpdate} 잠금·{@code deIdntfYn=='Y'} 게이트·콜백 멱등 앵커는 절대 이
 * 비동기 경로로 옮기지 않는다. 그것들은 동기 handle 트랜잭션 시점의 부모 안전 판정으로만 유효하며
 * (CWE-359 PII TOCTOU), 여기로 옮기면 커밋~async 사이 비식별 신고가 부모를 'F' 로 되돌려도 못 막아
 * Phase 5 의 PII 노출 창을 재개방한다. 본 러너는 신규 RAW 의 프레임/라벨을 채우는 후속 단계만 담당한다.
 *
 * <h3>실패 정책</h3>
 * <p>추출/복사 실패 시 {@link AugmentFrameExtractionService} 의 REQUIRES_NEW 트랜잭션이 롤백되어
 * 고아 프레임/라벨이 남지 않는다. 여기서는 {@link BatchTransitionService#markRawDataFailed}(별도
 * REQUIRES_NEW)로 신규 RAW 를 FAILED 로 전이해 "마킹 대기" 고착을 방지하고 예외를 삼킨다(@Async).
 * 콜백 응답은 handle 이 이 러너보다 앞선 커밋에서 이미 200 을 반환하므로 영향받지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncAugmentFrameRunner {

    private final AugmentFrameExtractionService extractionService;
    private final BatchTransitionService batchTransitionService;

    @Async("batchAsyncExecutor")
    public void runAsync(Long newRawSn, Long dataAugSn) {
        log.info("[AsyncAugmentFrameRunner] starting augment frame re-extraction rawSn={} dataAugSn={}",
                newRawSn, dataAugSn);
        try {
            extractionService.extractAndCopy(newRawSn, dataAugSn);
            log.info("[AsyncAugmentFrameRunner] augment frame re-extraction completed rawSn={}", newRawSn);
        } catch (RuntimeException e) {
            // 실패 — 추출/복사 트랜잭션은 이미 롤백됨(고아 없음). 신규 RAW 를 FAILED 로 전이(별도 커밋)해
            // MARKING_READY 고착을 막고 예외를 삼킨다(@Async). 자동 재시도 큐는 두지 않는다.
            log.warn("[AsyncAugmentFrameRunner] augment frame re-extraction failed rawSn={} cause={}",
                    newRawSn, e.getClass().getSimpleName());
            batchTransitionService.markRawDataFailed(newRawSn);
        }
    }
}
