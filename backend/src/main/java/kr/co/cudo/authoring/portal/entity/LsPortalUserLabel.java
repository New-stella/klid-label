package kr.co.cudo.authoring.portal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * V2.0 포털 사용자 작업 라벨 (LS_PORTAL_USER_LABEL).
 * 원본(LS_DATA_LBL) 미수정 정책 — 사용자 수정분은 본 테이블에 별도 적재.
 */
@Entity
@Table(name = "LS_PORTAL_USER_LABEL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPortalUserLabel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "USER_LBL_SN")
    private Long userLblSn;

    @Column(name = "PORTAL_USER_NO", nullable = false, length = 100)
    private String portalUserNo;

    @Column(name = "SRC_RAW_SN", nullable = false)
    private Long srcRawSn;

    @Column(name = "SRC_DATA_SRC_SN", nullable = false)
    private Long srcDataSrcSn;

    @Column(name = "LBL_TYPE_CD", nullable = false, length = 16)
    private String lblTypeCd;

    @Column(name = "LABEL_NM", length = 255)
    private String labelNm;

    @Column(name = "POINT_CN", columnDefinition = "TEXT")
    private String pointCn;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

    public static LsPortalUserLabel create(String portalUserNo, Long srcRawSn, Long srcDataSrcSn,
                                           String lblTypeCd, String labelNm, String pointCn) {
        LsPortalUserLabel entity = new LsPortalUserLabel();
        entity.portalUserNo = portalUserNo;
        entity.srcRawSn = srcRawSn;
        entity.srcDataSrcSn = srcDataSrcSn;
        entity.lblTypeCd = lblTypeCd;
        entity.labelNm = labelNm;
        entity.pointCn = pointCn;
        LocalDateTime now = LocalDateTime.now();
        entity.regDt = now;
        entity.mdfcnDt = now;
        return entity;
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
