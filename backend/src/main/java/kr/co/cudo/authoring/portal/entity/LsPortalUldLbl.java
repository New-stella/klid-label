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
 * V2.0 포털 업로드 프레임 위 수동 라벨 (LS_PORTAL_ULD_LBL).
 * <p>
 * {@link LsPortalUserLabel}(데이터마트 영상 위 라벨)과 별개로, 포털 사용자가 직접
 * 업로드한 프레임에 그린 BBOX/POLYGON 을 적재한다. FK(ULD_FRME_SN)는 DB
 * ON DELETE CASCADE 로 정리된다.
 */
@Entity
@Table(name = "LS_PORTAL_ULD_LBL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPortalUldLbl {

    /** 라벨 유형 코드. */
    public static final String TYPE_BBOX    = "BBOX";
    public static final String TYPE_POLYGON = "POLYGON";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ULD_LBL_SN")
    private Long uldLblSn;

    @Column(name = "PORTAL_USER_NO", nullable = false, length = 100)
    private String portalUserNo;

    @Column(name = "ULD_SN", nullable = false)
    private Long uldSn;

    @Column(name = "ULD_FRME_SN", nullable = false)
    private Long uldFrmeSn;

    @Column(name = "LBL_TYPE_CD", nullable = false, length = 16)
    private String lblTypeCd;

    @Column(name = "LBL_NM", length = 80)
    private String lblNm;

    @Column(name = "POINT_CN", columnDefinition = "TEXT")
    private String pointCn;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

    private LsPortalUldLbl(String portalUserNo, Long uldSn, Long uldFrmeSn,
                          String lblTypeCd, String lblNm, String pointCn) {
        this.portalUserNo = portalUserNo;
        this.uldSn = uldSn;
        this.uldFrmeSn = uldFrmeSn;
        this.lblTypeCd = lblTypeCd;
        this.lblNm = lblNm;
        this.pointCn = pointCn;
        LocalDateTime now = LocalDateTime.now();
        this.regDt = now;
        this.mdfcnDt = now;
    }

    public static LsPortalUldLbl create(String portalUserNo, Long uldSn, Long uldFrmeSn,
                                        String lblTypeCd, String lblNm, String pointCn) {
        return new LsPortalUldLbl(portalUserNo, uldSn, uldFrmeSn, lblTypeCd, lblNm, pointCn);
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
