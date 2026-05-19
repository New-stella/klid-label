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
    public static final String STATE_PROCESSED = "PROCESSED";
    public static final String STATE_FAILED = "FAILED";

    public static final String CHANNEL_DEIDENTIFY = "DEIDENTIFY";
    public static final String CHANNEL_VLM = "VLM";
    public static final String CHANNEL_AUGMENT = "AUGMENT";

    @Id
    @Column(name = "IDEMPOTENCY_KEY", length = 64, nullable = false)
    private String idempotencyKey;

    @Column(name = "CHANNEL", length = 32, nullable = false)
    private String channel;

    @Column(name = "STATE", length = 16, nullable = false)
    private String state;

    @Column(name = "EXTERNAL_JOB_ID", length = 128)
    private String externalJobId;

    @Column(name = "APPLIED_AT")
    private LocalDateTime appliedAt;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    public static LsWebhookIdempotency issue(String idempotencyKey, String channel, String externalJobId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 는 필수입니다.");
        }
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel 은 필수입니다.");
        }
        LsWebhookIdempotency entity = new LsWebhookIdempotency();
        entity.idempotencyKey = idempotencyKey;
        entity.channel = channel;
        entity.state = STATE_ISSUED;
        entity.externalJobId = externalJobId;
        LocalDateTime now = LocalDateTime.now();
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    public void markProcessed(String externalJobId) {
        this.state = STATE_PROCESSED;
        if (externalJobId != null && !externalJobId.isBlank()) {
            this.externalJobId = externalJobId;
        }
        this.appliedAt = LocalDateTime.now();
        this.updatedAt = this.appliedAt;
    }

    public void markFailed() {
        this.state = STATE_FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isProcessed() {
        return STATE_PROCESSED.equals(this.state);
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        if (this.updatedAt == null) this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
