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
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "LS_DATA_AUG_RVW")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataAugRvw {

    public static final String STTS_PENDING = "PENDING";
    public static final String STTS_ACCEPTED = "ACCEPTED";
    public static final String STTS_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DATA_AUG_RVW_SN")
    private Long dataAugRvwSn;

    @Column(name = "DATA_AUG_SN", nullable = false)
    private Long dataAugSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "DATA_SRC_SN", nullable = false)
    private Long dataSrcSn;

    @Column(name = "RVW_STTS_CD", nullable = false, length = 20)
    private String rvwSttsCd;

    @Column(name = "LBL_INTGRT_PCT", precision = 5, scale = 2)
    private BigDecimal lblIntgrtPct;

    @Column(name = "RJCT_RSN", length = 4000)
    private String rejectRsn;

    @Column(name = "RVW_ID", length = 30)
    private String rvwId;

    @Column(name = "RVW_DT")
    private LocalDateTime rvwDt;

    @Column(name = "REG_ID", length = 64)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 64)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    public static LsDataAugRvw pending(Long dataAugSn, Long rawSn, Long srcSn,
                                       BigDecimal integrityPct, String regId) {
        LsDataAugRvw review = new LsDataAugRvw();
        review.dataAugSn = dataAugSn;
        review.dataRawSn = rawSn == null ? 0L : rawSn;
        review.dataSrcSn = srcSn;
        review.rvwSttsCd = STTS_PENDING;
        review.lblIntgrtPct = integrityPct;
        review.regId = regId;
        review.regDt = LocalDateTime.now();
        return review;
    }

    /**
     * 승인된 검수 row 를 한 번에 생성 (PENDING row 사전 등록 없이 직접 INSERT).
     */
    public static LsDataAugRvw createAccepted(Long dataAugSn, Long dataRawSn, Long dataSrcSn,
                                              BigDecimal labelIntegrityPct, String rvwId, LocalDateTime rvwDt) {
        LsDataAugRvw review = new LsDataAugRvw();
        review.dataAugSn = dataAugSn;
        review.dataRawSn = dataRawSn == null ? 0L : dataRawSn;
        review.dataSrcSn = dataSrcSn;
        review.rvwSttsCd = STTS_ACCEPTED;
        review.lblIntgrtPct = labelIntegrityPct;
        review.rvwId = rvwId;
        review.rvwDt = rvwDt;
        review.regId = rvwId;
        review.regDt = rvwDt == null ? LocalDateTime.now() : rvwDt;
        review.mdfcnId = rvwId;
        review.mdfcnDt = rvwDt;
        return review;
    }

    /**
     * 반려된 검수 row 를 한 번에 생성. 반려 사유는 필수.
     */
    public static LsDataAugRvw createRejected(Long dataAugSn, Long dataRawSn, Long dataSrcSn,
                                              String rejectRsn, String rvwId, LocalDateTime rvwDt) {
        if (rejectRsn == null || rejectRsn.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "반려 사유는 필수입니다.");
        }
        LsDataAugRvw review = new LsDataAugRvw();
        review.dataAugSn = dataAugSn;
        review.dataRawSn = dataRawSn == null ? 0L : dataRawSn;
        review.dataSrcSn = dataSrcSn;
        review.rvwSttsCd = STTS_REJECTED;
        review.rejectRsn = rejectRsn;
        review.rvwId = rvwId;
        review.rvwDt = rvwDt;
        review.regId = rvwId;
        review.regDt = rvwDt == null ? LocalDateTime.now() : rvwDt;
        review.mdfcnId = rvwId;
        review.mdfcnDt = rvwDt;
        return review;
    }

    public void accept(String reviewerId, LocalDateTime at) {
        ensurePending();
        this.rvwSttsCd = STTS_ACCEPTED;
        this.rvwId = reviewerId;
        this.rvwDt = at;
        this.mdfcnId = reviewerId;
        this.mdfcnDt = at;
    }

    public void reject(String reason, String reviewerId, LocalDateTime at) {
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "반려 사유는 필수입니다.");
        }
        ensurePending();
        this.rvwSttsCd = STTS_REJECTED;
        this.rejectRsn = reason;
        this.rvwId = reviewerId;
        this.rvwDt = at;
        this.mdfcnId = reviewerId;
        this.mdfcnDt = at;
    }

    private void ensurePending() {
        if (!STTS_PENDING.equals(rvwSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 처리된 증강 검수입니다. status=" + rvwSttsCd);
        }
    }
}
