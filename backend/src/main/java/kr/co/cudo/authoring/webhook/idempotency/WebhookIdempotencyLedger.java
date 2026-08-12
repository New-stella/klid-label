package kr.co.cudo.authoring.webhook.idempotency;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Webhook 멱등성 원장 — Phase 2 도입, Phase 2 보강 (DEV_FIX 1차) 에서 인터페이스화.
 *
 * <p>외부 시스템(Deidentify/VLM/Augment) 위탁 시 본 도구가 발급한 {@code idempotencyKey} 를
 * "발급(issued)" 상태로 기록한 뒤, 결과 webhook 수신 시 다음 두 가지를 판별한다:
 *
 * <ol>
 *   <li><b>allowlist (S-1 보강)</b>: 발급한 적 없는 idempotencyKey 의 결과 인계는 거부 (UNAUTHORIZED)</li>
 *   <li><b>idempotency (S-2)</b>: 이미 처리(processed) 된 키의 재인계는 적재 스킵하고 200 OK 반환</li>
 * </ol>
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>{@link PersistentWebhookIdempotencyLedger} — {@code LS_WEBHOOK_IDEMPOTENCY} 영속 (운영 기본)</li>
 *   <li>{@link InMemoryWebhookIdempotencyLedger} — ConcurrentHashMap (단위 테스트 전용)</li>
 * </ul>
 *
 * <p>두 구현 모두 동일한 메서드 시그니처를 유지하여 기존 ResultService 코드는 변경 없이 사용할 수 있다.
 */
public interface WebhookIdempotencyLedger {

    enum State {
        ISSUED,
        PROCESSED
    }

    /**
     * @param rawSn 위탁 요청 대상 영상의 RAW_SN. 콜백 바디에 rawSn 이 없는 규격(예: VLM describe 콜백)에서
     *              request_id 로 rawSn 을 역조회하기 위한 슬롯. 매핑이 없으면 null.
     * @param issuedAt 발급(위탁 개시) 시각. 콜백 수신부가 "이 위탁보다 <b>나중에 생긴</b> 대상"을
     *                 건드리지 않도록 판정하는 데 쓴다(L6 — 지각 콜백이 새 마킹을 완료시키는 문제).
     *                 알 수 없으면 null(= 판정 미적용, 종전 동작).
     */
    record Entry(State state, String externalJobId, Long rawSn, LocalDateTime issuedAt) {

        /** 발급 시각을 모르는 호출부용 축약 생성자(종전 3-인자 계약 유지). */
        public Entry(State state, String externalJobId, Long rawSn) {
            this(state, externalJobId, rawSn, null);
        }
    }

    /**
     * 외부 위탁 시 발급한 idempotencyKey 를 등록한다.
     * 동일 키가 이미 존재하면 무시 (불변 보장).
     *
     * <p>본 오버로드는 externalJobId 만 받는다 (channel 미상 — UNKNOWN 으로 기록).
     */
    void recordIssued(String idempotencyKey, String externalJobId);

    /**
     * Phase 3 — channel 명시 발급 기록.
     *
     * <p>운영에서는 {@link PersistentWebhookIdempotencyLedger} 가
     * {@code LsWebhookIdempotency.CHANNEL_*} 상수를 그대로 영속한다.
     * 호환을 위해 디폴트 구현은 channel 을 externalJobId 위치로 전달 (구 시그니처와 동일 동작).
     * 명시 구현체는 channel/externalJobId 슬롯을 분리한다.
     */
    default void recordIssued(String idempotencyKey, String channel, String externalJobId) {
        recordIssued(idempotencyKey, externalJobId);
    }

    /**
     * VLM describe 콜백 정합 — channel + rawSn 명시 발급 기록.
     *
     * <p>콜백 바디에 rawSn 이 없는 규격(VLM describe: {@code request_id} 만 전달)에서는
     * 위탁 요청 시 {@code (request_id, rawSn)} 매핑을 여기에 등록해 두고,
     * 결과 수신부가 {@link #resolveRawSn(String)} 로 역조회한다.
     *
     * <p>디폴트 구현은 rawSn 을 버리고 3-인자 시그니처로 위임한다(구 호환).
     * 명시 구현체({@link InMemoryWebhookIdempotencyLedger}/{@link PersistentWebhookIdempotencyLedger})는 rawSn 을 영속한다.
     */
    default void recordIssued(String idempotencyKey, String channel, String externalJobId, Long rawSn) {
        recordIssued(idempotencyKey, channel, externalJobId);
    }

    /**
     * H1 — 위탁 <b>수락(ACK) 수신</b> 사실을 원장에 남긴다(발급 상태에서만 전이).
     *
     * <p>이 기록이 없으면 미결 회수 스윕이 "ACK 조차 못 받은 건"과 "결과 콜백을 기다리는 정상 건"을
     * 구분하지 못해 진행 중인 위탁을 뺏고 중복 위탁한다. 콜백 시맨틱(발급 게이트·멱등)은 불변이다 —
     * 영속 구현은 비-PROCESSED 를 모두 {@code ISSUED} 로 노출한다.
     *
     * <p>디폴트 구현(in-memory)은 no-op — 상태 모델에 수락 단계가 없고, 회수 스윕은 영속 원장만 본다.
     */
    default void recordAckReceived(String idempotencyKey) {
        // no-op
    }

    /**
     * 해당 채널·영상에 <b>미결 위탁</b>(결과를 기다리는 중)이 남아 있는가 — 재실행 중복 위탁 차단 (@req R1).
     *
     * <p>배치 자동 재시도는 파이프라인을 선두부터 전부 다시 돈다. 위탁 스텝이 이 판정을 하지 않으면 매번
     * 새 {@code request_id} 를 발급해 외부로 다시 위탁하고, 같은 영상에 <b>상관키가 둘 이상</b> 생긴다
     * (외부 비용·레이트리밋 + 어느 콜백이 정본인지 모호해진다). 미결의 회수는 미결 스위퍼의 책임이다.
     *
     * <p>디폴트 구현은 {@code false} — 미결 개념이 없는 구현(in-memory 등)에서 기존 동작을 바꾸지 않는다.
     * 운영 영속 구현이 실제 판정을 제공한다.
     *
     * @param channel {@code LsWebhookIdempotency.CHANNEL_*}
     * @param rawSn   영상 식별자(null 이면 판정 불가 → {@code false})
     */
    default boolean hasOutstandingSubmit(String channel, Long rawSn) {
        return false;
    }

    /**
     * request_id(=idempotencyKey) 로 위탁 대상 영상의 rawSn 을 역조회한다.
     * 발급 기록이 없거나 rawSn 매핑이 없으면 empty.
     */
    default Optional<Long> resolveRawSn(String idempotencyKey) {
        return lookup(idempotencyKey).flatMap(e -> Optional.ofNullable(e.rawSn()));
    }

    /**
     * 처리 완료 마킹 — 이후 동일 키 재인계는 멱등 200 OK 로 응답되도록 한다.
     * UNIQUE 제약 위반(동시 호출)은 멱등 반환 (예외 미발생).
     *
     * <p><b>트랜잭션 경계</b>: 영속 구현은 {@code REQUIRES_NEW} 로 독립 커밋한다(Augment 콜백 경로 유지).
     * 콜백 side-effect 와 <b>원자적</b>으로 커밋/롤백해야 하는 경로(VLM 결과 수신)는
     * {@link #markProcessedInTx(String, String)} 를 사용한다.
     */
    void markProcessed(String idempotencyKey, String externalJobId);

    /**
     * 처리 완료 마킹 — <b>호출자의 트랜잭션에 참여</b>한다(REQUIRED). CWE-362/데이터 유실 차단.
     *
     * <p>콜백 처리(META upsert·검수큐·마킹 전이)와 동일 트랜잭션에서 마지막에 호출되어,
     * 이후 커밋이 실패하면 원장 PROCESSED 전이도 함께 롤백된다 → 벤더 재전송으로 복구 가능.
     * 독립 커밋({@link #markProcessed})은 outer 롤백 시 PROCESSED 만 남아 결과가 영구 유실될 수 있다.
     *
     * <p>디폴트 구현(in-memory)은 트랜잭션이 없으므로 {@link #markProcessed} 와 동일 동작.
     */
    default void markProcessedInTx(String idempotencyKey, String externalJobId) {
        markProcessed(idempotencyKey, externalJobId);
    }

    Optional<Entry> lookup(String idempotencyKey);

    /**
     * 처리 목적의 원장 조회 — 영속 구현은 행 <b>비관적 락(FOR UPDATE)</b>으로 조회하여
     * 동일 idempotencyKey 의 동시 콜백을 직렬화한다(TOCTOU/검수큐 중복 적재 차단, CWE-362).
     *
     * <p>반드시 활성 트랜잭션 안에서 호출한다(콜백 처리 트랜잭션 시작 직후 1회). 반환된 Entry 로
     * 발급 여부(존재)·처리 여부(state)·rawSn 을 재사용하여 중복 SELECT 를 제거한다.
     *
     * <p>디폴트 구현(in-memory)은 락 없이 {@link #lookup} 과 동일.
     */
    default Optional<Entry> lookupForProcessing(String idempotencyKey) {
        return lookup(idempotencyKey);
    }

    /** 발급 여부만 빠르게 확인. */
    default boolean isIssued(String idempotencyKey) {
        return lookup(idempotencyKey).isPresent();
    }

    /** 이미 처리 완료된 키인지 확인. */
    default boolean isProcessed(String idempotencyKey) {
        return lookup(idempotencyKey).map(e -> e.state() == State.PROCESSED).orElse(false);
    }

    /** 테스트 전용 — 상태 초기화. 운영 구현에서는 no-op 가능. */
    void clear();
}
