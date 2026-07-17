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
 * 포털 업로드 파생 프레임 (LS_PORTAL_ULD_FRME).
 * <p>
 * 이미지 업로드는 프레임 1행(FRME_NO=0), 영상 업로드는 추출된 프레임 N행으로 매핑된다.
 * 업로드(ULD) 삭제 시 DB ON DELETE CASCADE 로 연쇄 삭제되며, Aggregate 간 참조는 ID로만 보유한다.
 * <p>
 * PK 는 SEQUENCE(LS_PORTAL_ULD_FRME_SEQ, allocationSize=50)로 채번한다 — 영상당 최대 N행
 * 일괄 INSERT 시 JDBC batching 활성화(IDENTITY 는 batching 무효화되므로 미사용). V110 델타에서
 * V109 의 IDENTITY 컬럼을 SEQUENCE 로 전환한다.
 */
@Entity
@Table(name = "LS_PORTAL_ULD_FRME")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPortalUldFrme {

    /** 이미지 업로드의 단일 프레임 순번. */
    public static final int IMAGE_FRME_NO = 0;

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

    private LsPortalUldFrme(Long uldSn, Integer frmeNo, String filePathNm) {
        this.uldSn = uldSn;
        this.frmeNo = frmeNo;
        this.filePathNm = filePathNm;
        this.regDt = LocalDateTime.now();
    }

    public static LsPortalUldFrme create(Long uldSn, Integer frmeNo, String filePathNm) {
        return new LsPortalUldFrme(uldSn, frmeNo, filePathNm);
    }

    /** 이미지 업로드의 단일 프레임(FRME_NO=0) 생성. */
    public static LsPortalUldFrme createImageFrame(Long uldSn, String filePathNm) {
        return new LsPortalUldFrme(uldSn, IMAGE_FRME_NO, filePathNm);
    }

    @PrePersist
    void prePersist() {
        if (this.regDt == null) this.regDt = LocalDateTime.now();
    }
}
