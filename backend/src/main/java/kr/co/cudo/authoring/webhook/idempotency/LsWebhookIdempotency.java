package kr.co.cudo.authoring.webhook.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Webhook idempotency 원장 영속 엔티티 — Phase 2 보강 (DEV_FIX 1차).
 *
 * <p>{@code WebhookIdempotencyLedger} 의 in-memory 구현을 대체하는 영속 저장소.
 * 재시작/멀티 인스턴스 환경에서도 발급(ISSUED)/처리완료(PROCESSED) 상태가 유지되어
 * allowlist 손실(S-1) 및 중복 적재(S-2) 위험을 차단한다.
 */
@Entity
@Table(name = "LS_WEBHOOK_IDEMPOTENCY")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsWebhookIdempotency {

    public static final String STATE_ISSUED = "ISSUED";
    /**
     * 위탁 <b>수락(ACK) 수신</b> — 외부가 요청을 받아들였고 결과 콜백만 남은 상태 (H1).
     *
     * <p><b>왜 ISSUED 와 구분하는가</b>: 미결 회수 스윕은 {@code ISSUED + MDFCN_DT 경과} 를 후보로 삼는데,
     * ACK 수신 사실이 원장에 남지 않으면 <b>정상 진행 중</b>(콜백 대기)인 위탁까지 회수해 같은 영상을
     * 중복 위탁한다. VLM describe 는 영상 길이에 따라 콜백까지 수십 분이 걸릴 수 있어 ACK 창(수십 초)과
     * 콜백 창(수십 분)을 하나의 임계로 덮을 수 없다. KPST 가 {@code prjId} 유무로 같은 문제를 푸는 것의
     * 대칭이다.
     *
     * <p><b>콜백 시맨틱은 불변</b>: {@code PersistentWebhookIdempotencyLedger.toEntry} 는 비-PROCESSED 를
     * 모두 {@code ISSUED} 로 매핑하므로, ACCEPTED 행에 도착한 콜백도 종전과 동일하게 발급 게이트를
     * 통과하고 멱등 판정도 그대로다(지각 콜백이 401 이 되지 않는다).
     */
    public static final String STATE_ACCEPTED = "ACCEPTED";
    public static final String STATE_PROCESSED = "PROCESSED";
    public static final String STATE_FAILED = "FAILED";

    public static final String CHANNEL_DEIDENTIFY = "DEIDENTIFY";
    /**
     * 시계열 분석 <b>묘사</b> 위탁 채널.
     *
     * <p>값이 {@code "VLM"} 인 것은 의도다 — 이 축은 구 단일 위탁이 채우던 자리를 그대로 이어받으므로,
     * 값을 바꾸면 이미 적재된 미결 행을 미결 스위퍼·역조회가 찾지 못한다.
     */
    public static final String CHANNEL_VLM = "VLM";

    /**
     * 시계열 분석 <b>추가 질문</b> 위탁 채널 — 결과가 이벤트 어노테이션의 질의응답 축 초안을 채운다.
     *
     * <p>콜백 바디에 창구 구분자가 없어, 이 채널 값이 어느 창구의 결과인지 되짚는 유일한 축이다.
     */
    public static final String CHANNEL_VLM_SUB = "VLM_SUB";
    // AUGMENT 채널은 제거됐다 — 증강 request_id 발급 원장은 LS_DATA_AUG_JOB.IDMP_KEY 이며
    // 본 원장의 AUGMENT 행은 어느 경로에서도 읽히지 않는 죽은 write 였다(기존 행은 이력으로만 잔존).

    @Id
    @Column(name = "IDMP_KEY", length = 128, nullable = false)
    private String idmpKey;

    @Column(name = "CHNL_CD", length = 32, nullable = false)
    private String chnlCd;

    @Column(name = "STTS_CD", length = 16, nullable = false)
    private String sttsCd;

    @Column(name = "OTSD_JOB_ID", length = 200)
    private String otsdJobId;

    /**
     * 위탁 요청 대상 영상의 RAW_SN — VLM describe 콜백 정합용.
     * 콜백 바디가 request_id 만 전달하는 규격에서 request_id→rawSn 역조회에 사용된다. 매핑 없으면 null.
     */
    @Column(name = "RAW_SN")
    private Long rawSn;

    /**
     * <b>적용일시</b> — 콜백 처리 완료({@code PROCESSED})를 원장에 반영한 시각. 미처리 행은 null 이다.
     *
     * <p><b>물리명은 {@code APLCN_DT} 다</b>(V8 개명 — 구 {@code APLY_DT}). 행안부 공통표준용어에
     * 「신청일시 = {@code APLY_DT}」와 「적용일시 = {@code APLCN_DT}」가 <b>둘 다</b> 등록돼 있는데
     * 뜻이 다른 앞쪽을 쓰고 있었다. 논리명은 처음부터 '적용일시'였으므로 바뀐 것은 물리명뿐이다.
     */
    @Column(name = "APLCN_DT")
    private LocalDateTime aplcnDt;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

    public static LsWebhookIdempotency issue(String idempotencyKey, String channel, String externalJobId) {
        return issue(idempotencyKey, channel, externalJobId, null);
    }

    public static LsWebhookIdempotency issue(String idempotencyKey, String channel, String externalJobId, Long rawSn) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 는 필수입니다.");
        }
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel 은 필수입니다.");
        }
        LsWebhookIdempotency entity = new LsWebhookIdempotency();
        entity.idmpKey = idempotencyKey;
        entity.chnlCd = channel;
        entity.sttsCd = STATE_ISSUED;
        entity.otsdJobId = externalJobId;
        entity.rawSn = rawSn;
        LocalDateTime now = LocalDateTime.now();
        entity.regDt = now;
        entity.mdfcnDt = now;
        return entity;
    }

    public void markProcessed(String externalJobId) {
        this.sttsCd = STATE_PROCESSED;
        if (externalJobId != null && !externalJobId.isBlank()) {
            this.otsdJobId = externalJobId;
        }
        this.aplcnDt = LocalDateTime.now();
        this.mdfcnDt = this.aplcnDt;
    }

    public void markFailed() {
        this.sttsCd = STATE_FAILED;
        this.mdfcnDt = LocalDateTime.now();
    }

    public boolean isProcessed() {
        return STATE_PROCESSED.equals(this.sttsCd);
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.regDt == null) this.regDt = now;
        if (this.mdfcnDt == null) this.mdfcnDt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.mdfcnDt = LocalDateTime.now();
    }
}
