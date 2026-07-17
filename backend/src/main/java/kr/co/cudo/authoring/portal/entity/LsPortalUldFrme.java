package kr.co.cudo.authoring.portal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * V107 포털 업로드 파생 프레임 (LS_PORTAL_ULD_FRME).
 * <p>
 * 이미지 업로드는 프레임 1행, 영상 업로드는 추출된 프레임 N행. 업로드(ULD) 삭제 시
 * DB ON DELETE CASCADE 로 연쇄 삭제된다(Aggregate 간 ID 참조 — 객체 참조 미사용).
 */
@Entity
@Table(name = "LS_PORTAL_ULD_FRME")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPortalUldFrme {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "lsPortalUldFrmeSeq")
    @SequenceGenerator(name = "lsPortalUldFrmeSeq",
            sequenceName = "LS_PORTAL_ULD_FRME_SEQ", allocationSize = 50)
    @Column(name = "ULD_FRME_SN")
    private Long uldFrmeSn;

    @Column(name = "ULD_SN", nullable = false)
    private Long uldSn;

    @Column(name = "FRME_NO", nullable = false)
    private Integer frmeNo;

    @Column(name = "FILE_PATH_NM", nullable = false, length = 500)
    private String filePathNm;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    public static LsPortalUldFrme create(Long uldSn, Integer frmeNo, String filePathNm) {
        LsPortalUldFrme entity = new LsPortalUldFrme();
        entity.uldSn = uldSn;
        entity.frmeNo = frmeNo;
        entity.filePathNm = filePathNm;
        entity.regDt = LocalDateTime.now();
        return entity;
    }

    @PrePersist
    void prePersist() {
        if (this.regDt == null) this.regDt = LocalDateTime.now();
    }
}
