package kr.co.cudo.authoring.meta.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "LS_DATA_META_REVIEW")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataMetaReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DATA_META_REVIEW_SN")
    private Long dataMetaReviewSn;

    @Column(name = "DATA_META_SN", nullable = false)
    private Long dataMetaSn;

    @Column(name = "PJT_SN", nullable = false)
    private Long pjtSn;

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

    @Column(name = "REJECT_REASON", length = 1000)
    private String rejectReason;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;
}
