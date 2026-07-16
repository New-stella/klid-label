package kr.co.cudo.authoring.dataset.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 통합 메타 복제 outbox (LS_META_REPL_OUTBOX).
 *
 * <p>control DB(SoT)에 동결된 {@link LsDatasetVideoMeta} 스냅샷을 포털 DB(물리 분리)로
 * at-least-once 단방향 복제하기 위한 outbox 이벤트. APPROVED 트랜잭션에서 스냅샷과 함께
 * 커밋되고(원자), 별도 워커(Phase 3)가 {@code PENDING} 을 폴링해 포털로 push 한 뒤 {@code DONE}
 * 처리한다. 실패는 재시도(RETRY_CNT) 후 초과 시 {@code DEAD}(dead-letter).
 *
 * <p>멱등키 (RAW_SN, SNPSHT_HASH) 로 포털 upsert 가 중복 복제에 무해(재시도 안전)하다.
 */
@Entity
@Table(name = "LS_META_REPL_OUTBOX")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsMetaReplOutbox {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_DEAD = "DEAD";
    /**
     * 같은 RAW_SN 의 더 최신 outbox 가 발행돼 무효화된 미완(PENDING) outbox — 워커 폴링 대상에서 제외된다.
     * 옛 스냅샷 재전달로 포털이 stale 해시로 되살아나는 것을 방지하는 rawSn coalescing 의 결과 상태.
     */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "OUTBOX_SN")
    private Long outboxSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "SNPSHT_HASH", nullable = false, length = 64)
    private String snpshtHash;

    @Column(name = "PAYLOAD")
    private String payload;

    @Column(name = "STATUS", nullable = false, length = 20)
    private String status;

    @Column(name = "RETRY_CNT", nullable = false)
    private int retryCnt;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "PROC_DT")
    private LocalDateTime procDt;

    @Builder
    private LsMetaReplOutbox(Long rawSn, String snpshtHash, String payload) {
        this.rawSn = rawSn;
        this.snpshtHash = snpshtHash;
        this.payload = payload;
        this.status = STATUS_PENDING;
        this.retryCnt = 0;
        this.regDt = LocalDateTime.now();
    }

    public static LsMetaReplOutbox create(Long rawSn, String snpshtHash, String payload) {
        return LsMetaReplOutbox.builder()
                .rawSn(rawSn)
                .snpshtHash(snpshtHash)
                .payload(payload)
                .build();
    }

    /** 포털 복제 성공 — DONE 전이 + 처리 시각 기록. */
    public void markDone() {
        this.status = STATUS_DONE;
        this.procDt = LocalDateTime.now();
    }

    /** 복제 실패 — 재시도 횟수 1 증가(다음 주기 재시도). */
    public void incrementRetry() {
        this.retryCnt++;
    }

    /** 재시도 초과 — dead-letter(DEAD) 전이 + 처리 시각 기록. */
    public void markDead() {
        this.status = STATUS_DEAD;
        this.procDt = LocalDateTime.now();
    }
}
