package kr.co.cudo.authoring.meta.entity;

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

import java.time.LocalDateTime;

/**
 * LS_DATA_META_REVIEW: 외부/자동 생성 메타데이터 검토 상태.
 *  - LS_DATA_META 는 값 (K/V) 만 저장하고, 본 테이블에서 외부 생성 여부 + 검토 상태 (PENDING/APPROVED/REJECTED) 를 관리한다.
 *  - META_TYPE_CD: 메타 유형 (VLM / EXTERNAL).
 *  - SRC_SYS_CD: 생성 시스템 (AI_SERVER / CONTROL_SERVER / PORTAL).
 *  - RVW_STTS_CD: 검토 상태 (AUTO_GENERATED / PENDING / APPROVED / REJECTED).
 */
@Entity
@Table(name = "LS_DATA_META_REVIEW")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataMetaReview {

    public static final String STTS_AUTO_GENERATED = "AUTO_GENERATED";
    public static final String STTS_PENDING = "PENDING";
    public static final String STTS_APPROVED = "APPROVED";
    public static final String STTS_REJECTED = "REJECTED";

    public static final String META_TYPE_VLM = "VLM";
    public static final String META_TYPE_EXTERNAL = "EXTERNAL";

    public static final String SRC_AI_SERVER = "AI_SERVER";
    public static final String SRC_CONTROL_SERVER = "CONTROL_SERVER";
    public static final String SRC_PORTAL = "PORTAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DATA_META_REVIEW_SN")
    private Long dataMetaReviewSn;

    @Column(name = "DATA_META_SN", nullable = false)
    private Long dataMetaSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "DATA_SRC_SN")
    private Long dataSrcSn;

    @Column(name = "META_TYPE_CD", nullable = false, length = 20)
    private String metaTypeCd;

    @Column(name = "SRC_SYS_CD", length = 20)
    private String srcSysCd;

    @Column(name = "RVW_STTS_CD", nullable = false, length = 20)
    private String rvwSttsCd;

    @Column(name = "RVW_ID", length = 30)
    private String rvwId;

    @Column(name = "RVW_DT")
    private LocalDateTime rvwDt;

    @Column(name = "RJCT_RSN", length = 4000)
    private String rejectRsn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    /**
     * 자동 생성/외부 송신 메타에 대한 검토 row 생성.
     * - VLM 자동 생성 시 RVW_STTS_CD='AUTO_GENERATED' 또는 'PENDING'.
     * - 외부 시스템 송신 메타는 'PENDING'.
     */
    public static LsDataMetaReview createAuto(Long dataMetaSn, Long dataRawSn, Long dataSrcSn,
                                              String metaTypeCd, String srcSysCd, String rvwSttsCd) {
        LsDataMetaReview review = new LsDataMetaReview();
        review.dataMetaSn = dataMetaSn;
        review.dataRawSn = dataRawSn == null ? 0L : dataRawSn;
        review.dataSrcSn = dataSrcSn;
        review.metaTypeCd = metaTypeCd;
        review.srcSysCd = srcSysCd;
        review.rvwSttsCd = rvwSttsCd == null ? STTS_AUTO_GENERATED : rvwSttsCd;
        review.regDt = LocalDateTime.now();
        return review;
    }

    /** REVIEWER 가 메타를 승인. PENDING/AUTO_GENERATED 상태에서만 가능. */
    public void approve(String reviewerId, LocalDateTime at) {
        ensureReviewable();
        this.rvwSttsCd = STTS_APPROVED;
        this.rvwId = reviewerId;
        this.rvwDt = at;
        this.mdfcnId = reviewerId;
        this.mdfcnDt = at;
    }

    /** REVIEWER 가 메타를 반려. 사유 필수. */
    public void reject(String reason, String reviewerId, LocalDateTime at) {
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "반려 사유는 필수입니다.");
        }
        ensureReviewable();
        this.rvwSttsCd = STTS_REJECTED;
        this.rejectRsn = sanitize(reason);
        this.rvwId = reviewerId;
        this.rvwDt = at;
        this.mdfcnId = reviewerId;
        this.mdfcnDt = at;
    }

    private void ensureReviewable() {
        if (STTS_APPROVED.equals(rvwSttsCd) || STTS_REJECTED.equals(rvwSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 검토 완료된 메타입니다. status=" + rvwSttsCd);
        }
    }

    /** Log Injection / 제어문자 sanitize — 개행/탭/캐리지리턴 제거. */
    private static String sanitize(String raw) {
        return raw.replaceAll("[\\r\\n\\t]", " ");
    }
}
