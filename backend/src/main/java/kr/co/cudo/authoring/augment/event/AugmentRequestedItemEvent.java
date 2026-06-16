package kr.co.cudo.authoring.augment.event;

/**
 * 증강 요청 건별 이벤트 — 콜백 충실 플로우 Phase 1 (DEV_FIX: 고아 ledger 키 제거).
 *
 * <p>{@code AugmentRequestService.request()} 가 (영상 × 종류) distinct 건마다 PENDING 행을
 * 저장한 뒤 발행한다. 본 이벤트는 {@code AugmentRequestBridge} 가
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 으로 수신하여, 요청 트랜잭션이
 * <b>실제로 커밋된 이후에만</b> 다음을 수행한다:
 *
 * <ol>
 *   <li>{@code WebhookIdempotencyLedger.recordIssued(idempotencyKey, "AUGMENT", externalJobId)}</li>
 *   <li>{@code ExternalAugmentClient.requestAugment(...)} 콜백 컨텍스트 전달</li>
 * </ol>
 *
 * <p>이로써 요청 트랜잭션이 롤백되면 LS_DATA_AUG 행과 함께 ledger 키도 생성되지 않아
 * <b>고아 멱등 키가 남지 않는다</b>. ledger 발급/외부 호출 실패는 건별로 격리되며
 * (양성 상태 — 콜백 미수신은 재시도 가능, 고아 키보다 안전) WARN 로그를 남긴다.
 *
 * <p>본 AFTER_COMMIT 구조는 Phase 2(dev 콜백 시뮬레이션)에서 그대로 재사용된다.
 *
 * @param originAugSn    PENDING 으로 커밋된 LS_DATA_AUG.DATA_AUG_SN (콜백 회신 매칭 키)
 * @param augType        증강 유형 (WINTER/NIGHT/RAIN)
 * @param idempotencyKey 본 도구가 발급한 멱등 키 (^[A-Za-z0-9_-]+$, ≤64)
 * @param externalJobId  외부 작업 식별자 (≤128)
 * @param callbackUrl    결과 회신 URL
 */
public record AugmentRequestedItemEvent(
        Long originAugSn,
        String augType,
        String idempotencyKey,
        String externalJobId,
        String callbackUrl) {
}
