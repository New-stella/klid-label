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
 * V2.0 포털 전용 업로드 원본 (LS_PORTAL_ULD).
 * <p>
 * 포털 사용자가 직접 올린 이미지 1장 또는 영상 1건을 표현한다. 데이터마트/원본
 * (LS_DATA_RAW)과 무관한 포털 작업본이다.
 * <p>
 * 상태 전이는 setter 없이 의미 있는 도메인 메서드로만 수행한다:
 * UPLOADED → {@link #markProcessing()} → {@link #markReady(Double, Double, Integer)}
 * 또는 {@link #markFailed(String)}.
 */
@Entity
@Table(name = "LS_PORTAL_ULD")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPortalUld {

    /** 업로드 유형 코드. */
    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_VIDEO = "VIDEO";

    /** 업로드 상태 코드. */
    public static final String STATUS_UPLOADED   = "UPLOADED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY      = "READY";
    public static final String STATUS_FAILED     = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ULD_SN")
    private Long uldSn;

    @Column(name = "PORTAL_USER_NO", nullable = false, length = 100)
    private String portalUserNo;

    @Column(name = "ULD_TYPE_CD", nullable = false, length = 16)
    private String uldTypeCd;

    @Column(name = "ORGNL_FILE_NM", length = 255)
    private String orgnlFileNm;

    @Column(name = "FILE_PATH_NM", length = 500)
    private String filePathNm;

    @Column(name = "FILE_SZ")
    private Long fileSz;

    @Column(name = "MIME_TYPE_NM", length = 100)
    private String mimeTypeNm;

    @Column(name = "ULD_STTS_CD", nullable = false, length = 16)
    private String uldSttsCd;

    @Column(name = "VDO_LEN_SEC")
    private Double vdoLenSec;

    @Column(name = "FPS")
    private Double fps;

    @Column(name = "FRME_CNT")
    private Integer frmeCnt;

    @Column(name = "FAIL_RSN_CN", columnDefinition = "TEXT")
    private String failRsnCn;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

    private LsPortalUld(String portalUserNo, String uldTypeCd, String orgnlFileNm,
                        String filePathNm, Long fileSz, String mimeTypeNm) {
        this.portalUserNo = portalUserNo;
        this.uldTypeCd = uldTypeCd;
        this.orgnlFileNm = orgnlFileNm;
        this.filePathNm = filePathNm;
        this.fileSz = fileSz;
        this.mimeTypeNm = mimeTypeNm;
        this.uldSttsCd = STATUS_UPLOADED;
        LocalDateTime now = LocalDateTime.now();
        this.regDt = now;
        this.mdfcnDt = now;
    }

    /** 이미지 업로드 생성 (상태 UPLOADED). */
    public static LsPortalUld createImage(String portalUserNo, String orgnlFileNm,
                                          String filePathNm, Long fileSz, String mimeTypeNm) {
        return new LsPortalUld(portalUserNo, TYPE_IMAGE, orgnlFileNm, filePathNm, fileSz, mimeTypeNm);
    }

    /** 영상 업로드 생성 (상태 UPLOADED). */
    public static LsPortalUld createVideo(String portalUserNo, String orgnlFileNm,
                                          String filePathNm, Long fileSz, String mimeTypeNm) {
        return new LsPortalUld(portalUserNo, TYPE_VIDEO, orgnlFileNm, filePathNm, fileSz, mimeTypeNm);
    }

    /** 프레임 추출/처리 시작. */
    public void markProcessing() {
        this.uldSttsCd = STATUS_PROCESSING;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 처리 완료(라벨링 준비). 영상은 길이/FPS/프레임 수를 함께 기록하고,
     * 이미지는 세 값 모두 null 로 호출한다.
     */
    public void markReady(Double vdoLenSec, Double fps, Integer frmeCnt) {
        this.uldSttsCd = STATUS_READY;
        this.vdoLenSec = vdoLenSec;
        this.fps = fps;
        this.frmeCnt = frmeCnt;
        this.failRsnCn = null;
        this.mdfcnDt = LocalDateTime.now();
    }

    /** 처리 실패(사유 기록). */
    public void markFailed(String failRsnCn) {
        this.uldSttsCd = STATUS_FAILED;
        this.failRsnCn = failRsnCn;
        this.mdfcnDt = LocalDateTime.now();
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.regDt == null) this.regDt = now;
        if (this.mdfcnDt == null) this.mdfcnDt = now;
        if (this.uldSttsCd == null) this.uldSttsCd = STATUS_UPLOADED;
    }

    @PreUpdate
    void preUpdate() {
        this.mdfcnDt = LocalDateTime.now();
    }
}
