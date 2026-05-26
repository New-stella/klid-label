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

    @Column(name = "SOURCE_RAW_SN", nullable = false)
    private Long sourceRawSn;

    @Column(name = "SOURCE_SRC_SN", nullable = false)
    private Long sourceSrcSn;

    @Column(name = "LBL_TYPE_CD", nullable = false, length = 16)
    private String lblTypeCd;

    @Column(name = "LABEL", length = 255)
    private String label;

    @Column(name = "POINTS", columnDefinition = "TEXT")
    private String points;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    public static LsPortalUserLabel create(String portalUserNo, Long sourceRawSn, Long sourceSrcSn,
                                           String lblTypeCd, String label, String points) {
        LsPortalUserLabel entity = new LsPortalUserLabel();
        entity.portalUserNo = portalUserNo;
        entity.sourceRawSn = sourceRawSn;
        entity.sourceSrcSn = sourceSrcSn;
        entity.lblTypeCd = lblTypeCd;
        entity.label = label;
        entity.points = points;
        LocalDateTime now = LocalDateTime.now();
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        if (this.updatedAt == null) this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
