package kr.co.cudo.authoring.batch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LS_DATA_META: 영상/프레임 메타 값.
 *  - rawSn: LS_DATA_RAW FK
 *  - metaKey + metaVl(META_VL) 단순 K/V (K 는 (RAW_SN, META_KEY) UK)
 *
 * 외부 생성 여부, 메타 유형, 검토 상태는 LS_DATA_META_REVIEW 에 분리 저장한다.
 */
@Entity
@Table(name = "LS_DATA_META",
        uniqueConstraints = @UniqueConstraint(name = "UK_LS_DATA_META_RAW_KEY",
                columnNames = {"RAW_SN", "META_KEY"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataMeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "META_SN")
    private Long metaSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "META_KEY", nullable = false, length = 64)
    private String metaKey;

    @Column(name = "META_VL", length = 2000)
    private String metaVl;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    /**
     * Phase 4 비동기 표준 컬럼 — webhook 인계 원래 위탁 요청 식별자.
     * UNIQUE 제약 (uk_meta_idempotency_key) — 동시 인계 race 차단.
     */
    @Column(name = "IDEMPOTENCY_KEY", length = 64)
    private String idempotencyKey;

    /**
     * Phase 4 비동기 표준 컬럼 — 외부 시스템 작업 ID.
     * UNIQUE 제약 (uk_meta_external_job_id).
     */
    @Column(name = "EXTERNAL_JOB_ID", length = 128)
    private String externalJobId;

    /** Phase 4 비동기 표준 컬럼 — 재시도 횟수. */
    @Column(name = "RETRY_COUNT", nullable = false)
    private int retryCount;

    /** Phase 4 비동기 표준 컬럼 — 영구 실패(dead-letter) 마킹 시점. */
    @Column(name = "DEAD_LETTER_AT")
    private LocalDateTime deadLetterAt;

    @Builder
    private LsDataMeta(Long rawSn, String metaKey, String metaVl) {
        this.rawSn = rawSn;
        this.metaKey = metaKey;
        this.metaVl = metaVl;
        this.regDt = LocalDateTime.now();
        this.retryCount = 0;
    }

    public static LsDataMeta create(Long rawSn, String metaKey, String metaVl) {
        return LsDataMeta.builder()
                .rawSn(rawSn)
                .metaKey(metaKey)
                .metaVl(metaVl)
                .build();
    }

    public void updateValue(String newVal) {
        this.metaVl = newVal;
        this.mdfcnDt = LocalDateTime.now();
    }

    // ============================================================
    // Phase 4 — 비동기 표준 컬럼 비즈니스 메서드 (setter 금지 패턴)
    // ============================================================

    /** 멱등 키 할당. 신규 webhook 인계 시점에 1회 호출. */
    public void assignIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    /** 외부 작업 ID 할당. */
    public void assignExternalJobId(String externalJobId) {
        this.externalJobId = externalJobId;
    }

    /** 재시도 횟수 1 증가. */
    public void incrementRetryCount() {
        this.retryCount++;
    }

    /** 영구 실패 마킹 — dead-letter 큐 진입. */
    public void markDeadLetter() {
        this.deadLetterAt = LocalDateTime.now();
    }
}
