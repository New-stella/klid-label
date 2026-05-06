package kr.co.cudo.authoring.augment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 * <p>외부 SFR-07 시스템이 생성한 4종 증강 결과(WINTER/NIGHT/RAIN/RESOLUTION)를
 * 본 저작도구의 REVIEWER 가 검수(ACCEPT/REJECT)한다.
 *
 * <p>상태 전이:
 * <ul>
 *   <li>PENDING  → ACCEPTED  (markAccepted)</li>
 *   <li>PENDING  → REJECTED  (markRejected)</li>
 *   <li>ACCEPTED/REJECTED → 재처리 불가 (CONFLICT)</li>
 * </ul>
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

    @Column(name = "LBL_INTGRT_PCT", precision = 5, scale = 2)
    private BigDecimal lblIntgrtPct;

    @Column(name = "REJECT_REASON", length = 500)
    private String rejectReason;

    @Column(name = "DECISION_USER_NO", length = 50)
    private String decisionUserNo;

    @Column(name = "DECISION_AT")
    private LocalDateTime decisionAt;

    @Column(name = "REGISTERED_AT", nullable = false)
    private LocalDateTime registeredAt;

    @Column(name = "REGISTERED_USER_NO", length = 50)
    private String registeredUserNo;

    @Builder
    private LsDataAug(Long srcSn, String augTypeCd, String augProcSttsCd,
                      BigDecimal lblIntgrtPct, String rejectReason,
                      String decisionUserNo, LocalDateTime decisionAt,
                      LocalDateTime registeredAt, String registeredUserNo) {
        this.srcSn = srcSn;
        this.augTypeCd = augTypeCd;
        this.augProcSttsCd = augProcSttsCd;
        this.lblIntgrtPct = lblIntgrtPct;
        this.rejectReason = rejectReason;
        this.decisionUserNo = decisionUserNo;
        this.decisionAt = decisionAt;
        this.registeredAt = registeredAt;
        this.registeredUserNo = registeredUserNo;
    }

    /**
     * 외부 시스템에서 생성된 PENDING 상태의 증강 결과를 신규 등록한다.
     */
    public static LsDataAug createPending(Long srcSn, String augTypeCd,
                                          BigDecimal lblIntgrtPct,
                                          String registeredUserNo) {
        return LsDataAug.builder()
                .srcSn(srcSn)
                .augTypeCd(augTypeCd)
                .augProcSttsCd(STTS_PENDING)
                .lblIntgrtPct(lblIntgrtPct)
                .registeredAt(LocalDateTime.now())
                .registeredUserNo(registeredUserNo)
                .build();
    }

    /**
     * REVIEWER 가 증강 결과를 승인. PENDING 일 때만 가능.
     */
    public void markAccepted(String decisionUserNo, LocalDateTime decisionAt) {
        if (!STTS_PENDING.equals(this.augProcSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 처리된 증강 결과입니다. status=" + this.augProcSttsCd);
        }
        this.augProcSttsCd = STTS_ACCEPTED;
        this.decisionUserNo = decisionUserNo;
        this.decisionAt = decisionAt;
    }

    /**
     * REVIEWER 가 증강 결과를 반려. 사유 필수, PENDING 일 때만 가능.
     */
    public void markRejected(String reason, String decisionUserNo, LocalDateTime decisionAt) {
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "반려 사유는 필수입니다.");
        }
        if (!STTS_PENDING.equals(this.augProcSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 처리된 증강 결과입니다. status=" + this.augProcSttsCd);
        }
        this.augProcSttsCd = STTS_REJECTED;
        this.rejectReason = reason;
        this.decisionUserNo = decisionUserNo;
        this.decisionAt = decisionAt;
    }
}
