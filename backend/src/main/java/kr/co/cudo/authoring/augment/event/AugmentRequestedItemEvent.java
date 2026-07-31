package kr.co.cudo.authoring.augment.event;

/**
 * 증강 요청 건별 이벤트 — 콜백 충실 플로우 Phase 1.
 *
 * <p>{@code AugmentRequestService.request()} 가 (영상 × 종류) distinct 건마다 PENDING 행을
 * 저장한 뒤 발행한다. 본 이벤트는 {@code AugmentRequestBridge} 가
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 으로 수신하여, 요청 트랜잭션이
 * <b>실제로 커밋된 이후에만</b> {@code AugmentJobSubmitService.submit(...)}
 * (비식별 프레임 100장 단위 분할 위탁)을 수행한다. 위탁 request_id 선기록은 그 서비스가
 * {@code LS_DATA_AUG_JOB.IDMP_KEY} 로 수행한다 — 구 {@code LS_WEBHOOK_IDEMPOTENCY}
 * ({@code CHANNEL_AUGMENT}) 선행 write 는 어디서도 읽히지 않아 제거됐다.
 *
 * <p>요청 트랜잭션이 롤백되면 본 리스너가 발화하지 않으므로 {@code LS_DATA_AUG} 행 없이
 * 외부 위탁만 나가는 일이 없다.
 *
 * <p>Phase 7-A1 변경: {@code externalJobId} 필드 제거 — job_id 는 외부가 202 응답으로 발급한다.
 * 대신 {@code rawSn} 을 실어 위탁 시점에 해당 영상의 <b>비식별 프레임 경로</b>를 조회할 수 있게 한다.
 *
 * <p>2026-07-31 변경: {@code prompt} 를 함께 싣는다. REVIEWER 입력 5필드가 그대로 외부로 나가므로
 * 위탁 시점에 그 값이 필요한데, 위탁은 커밋 이후 <b>다른 스레드</b>에서 일어나기 때문이다. 발행 시점에
 * 조립한 dict 를 그대로 옮겨 <b>DB 에 적재한 원문과 외부로 나가는 값이 같은 출처</b>가 되게 한다
 * (위탁 측에서 DB 를 다시 읽어 재조립하면 두 벌이 되어 어긋날 수 있다).
 *
 * @param originAugSn    PENDING 으로 커밋된 LS_DATA_AUG.DATA_AUG_SN (결과 매칭 키)
 * @param rawSn          증강 대상 영상 식별자 (비식별 프레임 경로 조회용)
 * @param augType        증강 유형 (WINTER/NIGHT/RAIN)
 * @param prompt         외부로 전송할 생성 조건 dict (정규화 완료, 5필드)
 * @param idempotencyKey 본 도구가 발급한 aug 단위 멱등 키 (^[A-Za-z0-9_-]+$, ≤64)
 * @param callbackUrl    결과 회신 URL
 * @param requestUserNo  요청자 토큰 sub (외부 요청의 request_user_id)
 */
public record AugmentRequestedItemEvent(
        Long originAugSn,
        Long rawSn,
        String augType,
        java.util.Map<String, Object> prompt,
        String idempotencyKey,
        String callbackUrl,
        String requestUserNo) {

    /**
     * 이벤트는 스레드를 건너 전달되므로 방어적 복사로 불변을 보장한다.
     * {@code Map.copyOf} 가 아니라 {@code LinkedHashMap} 복사인 이유는 <b>키 순서 보존</b>이다 —
     * 외부로 나가는 JSON 이 명세서 샘플과 같은 순서로 읽혀야 대조·디버깅이 쉽다.
     */
    public AugmentRequestedItemEvent {
        prompt = prompt == null
                ? java.util.Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(prompt));
    }
}
