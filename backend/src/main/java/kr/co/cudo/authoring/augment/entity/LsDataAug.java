package kr.co.cudo.authoring.augment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Phase 9 — 데이터 증강 결과 (LS_DATA_AUG).
 *
 * <p>외부 SFR-07 시스템이 생성한 3종 증강 결과(WINTER/NIGHT/RAIN)를 적재한다.
 * 해상도 변경(RESOLUTION)은 R1 v1.8부터 외부 위탁이 아닌 저작도구 내부 수행(SFR-06-03,
 * LS_RESOLUTION_EXPORT)으로 이관 — {@link #AUG_RESOLUTION} 상수는 기존 적재 데이터
 * 호환을 위해서만 유지하며 신규 콜백/요청에서는 허용되지 않는다.
 * 검수 상태/반려 사유/정합률은 LS_DATA_AUG_RVW 에 분리 저장한다.
 */
@Entity
@Table(name = "LS_DATA_AUG")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataAug {

    public static final String STTS_PENDING  = "PENDING";
    public static final String STTS_ACCEPTED = "ACCEPTED";
    public static final String STTS_REJECTED = "REJECTED";

    public static final String AUG_WINTER     = "WINTER";
    public static final String AUG_NIGHT      = "NIGHT";
    public static final String AUG_RAIN       = "RAIN";
    public static final String AUG_RESOLUTION = "RESOLUTION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DATA_AUG_SN")
    private Long dataAugSn;

    @Column(name = "SRC_SN", nullable = false)
    private Long srcSn;

    @Column(name = "AUG_TYPE_CD", nullable = false, length = 20)
    private String augTypeCd;

    @Column(name = "AUG_PROC_STTS_CD", nullable = false, length = 20)
    private String augProcSttsCd;

    @Transient
    private BigDecimal lblIntgrtPct;

    @Transient
    private String rejectRsn;

    @Transient
    private String dcsnUserNo;

    @Transient
    private LocalDateTime dcsnDt;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "REG_USER_NO", length = 50)
    private String regUserNo;

    /**
     * Phase 4 비동기 표준 컬럼 — webhook 인계 원래 위탁 요청 식별자.
     * UNIQUE 제약 (uk_aug_idempotency_key) — 동시 인계 race 차단.
     */
    @Column(name = "IDMP_KEY", length = 64)
    private String idempotencyKey;

    /**
     * Phase 4 비동기 표준 컬럼 — 외부 시스템 작업 ID.
     * UNIQUE 제약 (uk_aug_external_job_id).
     */
    @Column(name = "OTSD_JOB_ID", length = 128)
    private String externalJobId;

    /** Phase 4 비동기 표준 컬럼 — 재시도 횟수. */
    @Column(name = "RTRY_NMTM", nullable = false)
    private int retryCount;

    /** Phase 4 비동기 표준 컬럼 — 영구 실패(dead-letter) 마킹 시점. */
    @Column(name = "DEAD_LETTER_AT")
    private LocalDateTime deadLetterAt;

    @Builder
    private LsDataAug(Long srcSn, String augTypeCd, String augProcSttsCd,
                      BigDecimal lblIntgrtPct, String rejectRsn,
                      String dcsnUserNo, LocalDateTime dcsnDt,
                      LocalDateTime regDt, String regUserNo,
                      String idempotencyKey, String externalJobId) {
        this.srcSn = srcSn;
        this.augTypeCd = augTypeCd;
        this.augProcSttsCd = augProcSttsCd;
        this.lblIntgrtPct = lblIntgrtPct;
        this.rejectRsn = rejectRsn;
        this.dcsnUserNo = dcsnUserNo;
        this.dcsnDt = dcsnDt;
        this.regDt = regDt;
        this.regUserNo = regUserNo;
        this.idempotencyKey = idempotencyKey;
        this.externalJobId = externalJobId;
        this.retryCount = 0;
    }

    /**
     * 외부 시스템에서 생성된 PENDING 상태의 증강 결과를 신규 등록한다.
     */
    public static LsDataAug createPending(Long srcSn, String augTypeCd,
                                          BigDecimal lblIntgrtPct,
                                          String regUserNo) {
        return LsDataAug.builder()
                .srcSn(srcSn)
                .augTypeCd(augTypeCd)
                .augProcSttsCd(STTS_PENDING)
                .lblIntgrtPct(lblIntgrtPct)
                .regDt(LocalDateTime.now())
                .regUserNo(regUserNo)
                .build();
    }

    /**
     * 콜백 충실 플로우 요청용 — PENDING 행을 멱등 키/외부 작업 ID 와 함께 단일 INSERT 로 적재한다
     * (DEV_FIX MEDIUM-2: 이중 save 로 인한 IDMP_KEY=null orphan aug 경계 제거).
     *
     * <p>idempotencyKey 는 UUID 기반(dataAugSn 비의존)이므로 save 이전에 미리 발급해 행에 실어
     * 한 번의 save 로 커밋한다.
     */
    public static LsDataAug createRequested(Long srcSn, String augTypeCd, String regUserNo,
                                            String idempotencyKey, String externalJobId) {
        return LsDataAug.builder()
                .srcSn(srcSn)
                .augTypeCd(augTypeCd)
                .augProcSttsCd(STTS_PENDING)
                .regDt(LocalDateTime.now())
                .regUserNo(regUserNo)
                .idempotencyKey(idempotencyKey)
                .externalJobId(externalJobId)
                .build();
    }

    /**
     * 검수 결과를 LsDataAug.augProcSttsCd 에도 동기 반영 (DB 설계서 라인 162-169 호환).
     * 상세 audit 컬럼(LBL_INTGRT_PCT/REJECT_RSN/DCSN_USER_NO/DCSN_DT)은 LS_DATA_AUG_RVW 에서 관리.
     */
    public void applyReviewStatus(String newStatus) {
        if (newStatus == null
                || (!STTS_ACCEPTED.equals(newStatus) && !STTS_REJECTED.equals(newStatus))) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "유효하지 않은 검수 상태입니다. status=" + newStatus);
        }
        if (!STTS_PENDING.equals(this.augProcSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 처리된 증강 결과입니다. status=" + this.augProcSttsCd);
        }
        this.augProcSttsCd = newStatus;
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
