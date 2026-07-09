package kr.co.cudo.authoring.augment.integration;

/**
 * 외부 SFR-07 증강 시스템 연동 클라이언트 — 콜백 충실 플로우 Phase 1 에서 인터페이스화.
 *
 * <p>V1.5 정책: 증강 본체는 외부 시스템 책임이며, 본 저작도구는 (1) 검수 결과(ACCEPT/REJECT)를
 * 외부에 통보하고, (2) 증강 요청 시 콜백 컨텍스트(originAugSn·idempotencyKey·externalJobId·callbackUrl)를
 * 외부에 전달한다. 외부 호출은 best-effort 패턴 — 호출자 트랜잭션은 외부 실패 영향을 받지 않는다.
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>{@link NoopExternalAugmentClient} — 운영 기본. 실제 외부 호출 없이 로그만 남긴다.
 *       dev 환경 실제 콜백 호출 구현은 콜백 충실 플로우 Phase 2 에서 추가 예정.</li>
 * </ul>
 */
public interface ExternalAugmentClient {

    /**
     * 외부 시스템에 검수 결정을 통보. 실패 시 로그만 남기고 호출자 트랜잭션은 유지.
     *
     * @param dataAugSn   증강 행 식별자 (LS_DATA_AUG.DATA_AUG_SN)
     * @param decision    검수 결정 (ACCEPTED/REJECTED)
     * @param reasonOrNull 반려 사유 (ACCEPT 시 null)
     * @return 외부 시스템 ack 여부
     */
    boolean syncDecision(Long dataAugSn, String decision, String reasonOrNull);

    /**
     * 외부 시스템에 증강 요청을 전달하며 콜백 컨텍스트를 함께 넘긴다.
     *
     * <p>외부 시스템은 증강을 완료하면 {@code callbackUrl} 로 결과를 push 하며, 이때
     * {@code idempotencyKey}·{@code externalJobId}·{@code originAugSn} 을 그대로 회신하여
     * 본 도구가 멱등성 판별 + PENDING 행 매칭을 수행할 수 있게 한다.
     *
     * @param originAugSn   PENDING 으로 생성된 LS_DATA_AUG.DATA_AUG_SN (콜백 회신 매칭 키)
     * @param augType       증강 유형 (WINTER/NIGHT/RAIN)
     * @param idempotencyKey 본 도구가 발급한 멱등 키 (^[A-Za-z0-9_-]+$, ≤64)
     * @param externalJobId 외부 작업 식별자 (≤128)
     * @param callbackUrl   결과 회신 URL (예: http://host/api/v1/aug/callback)
     * @return 외부 시스템 ack 여부
     */
    boolean requestAugment(Long originAugSn, String augType, String idempotencyKey,
                           String externalJobId, String callbackUrl);
}
