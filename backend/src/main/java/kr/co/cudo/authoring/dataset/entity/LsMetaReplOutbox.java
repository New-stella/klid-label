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
 * 처리한다. 실패는 재시도(RTRY_NMTM) 후 초과 시 {@code DEAD}(dead-letter).
 *
 * <p>멱등키 (RAW_SN, SNPSHT_HASH) 로 포털 upsert 가 중복 복제에 무해(재시도 안전)하다.
 *
 * <p><b>표준용어 개명(V5)</b>: 컬럼 4종({@code PAYLOAD}·{@code STATUS}·{@code RETRY_CNT}·
 * {@code PROC_DT})을 표준 조합으로 바꿨고, 자바 필드명은 물리명의 camelCase 미러라는 이 저장소
 * 관례에 따라 함께 개명했다. 행은 RENAME 으로 그대로 보존된다(미완 복제 이벤트가 사라지면 포털
 * 메타가 영구 stale 이 된다). {@code OUTBOX_SN}·{@code SNPSHT_HASH} 는 <b>의도적으로 제외</b>했다 —
 * 앞은 테이블명 축과 함께 가야 하고, 뒤는 포털 DB 복제본이 같은 이름을 쓰므로 한쪽만 바꾸면
 * 복제가 깨진다. 근거·롤백 절차는 마이그레이션 헤더에 있다.
 *
 * <p>{@code PAYLOAD_CN} 은 <b>사업표준용어(페이로드내용)에 등록된 이름</b>이자 직계 형제
 * {@code LS_CONTROL_NOTIFY_FALLBACK.PAYLOAD_CN} 과 같은 형태다. 낱말을 재조합한
 * {@code PYLD_CN} 으로 바꾸지 말 것 — 이 스키마의 payload 계열 4개가 전부 {@code PAYLOAD} 형태라
 * 유일한 예외가 되고, 같은 개념에 이름이 둘이 된다. 타입도 형제와 같이 {@code text} 를 유지한다
 * (등록 도메인은 V/4000 이지만 이 값은 영상 메타 스냅샷 전문이라 그 폭을 넘을 수 있다).
 *
 * @req R2
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

    @Column(name = "PAYLOAD_CN")
    private String payloadCn;

    @Column(name = "STTS_CD", nullable = false, length = 16)
    private String sttsCd;

    @Column(name = "RTRY_NMTM", nullable = false)
    private int rtryNmtm;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "PRCS_DT")
    private LocalDateTime prcsDt;

    @Builder
    private LsMetaReplOutbox(Long rawSn, String snpshtHash, String payloadCn) {
        this.rawSn = rawSn;
        this.snpshtHash = snpshtHash;
        this.payloadCn = payloadCn;
        this.sttsCd = STATUS_PENDING;
        this.rtryNmtm = 0;
        this.regDt = LocalDateTime.now();
    }

    public static LsMetaReplOutbox create(Long rawSn, String snpshtHash, String payloadCn) {
        return LsMetaReplOutbox.builder()
                .rawSn(rawSn)
                .snpshtHash(snpshtHash)
                .payloadCn(payloadCn)
                .build();
    }

    /** 포털 복제 성공 — DONE 전이 + 처리 시각 기록. */
    public void markDone() {
        this.sttsCd = STATUS_DONE;
        this.prcsDt = LocalDateTime.now();
    }

    /** 복제 실패 — 재시도 횟수 1 증가(다음 주기 재시도). */
    public void incrementRetry() {
        this.rtryNmtm++;
    }

    /** 재시도 초과 — dead-letter(DEAD) 전이 + 처리 시각 기록. */
    public void markDead() {
        this.sttsCd = STATUS_DEAD;
        this.prcsDt = LocalDateTime.now();
    }
}
