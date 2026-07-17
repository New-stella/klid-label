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
 * V107 포털 전용 업로드 마스터 (LS_PORTAL_ULD).
 * <p>
 * 포털 사용자가 직접 올린 이미지 1건 또는 영상 1건. 관제 학습용 적재 파이프라인과 분리된
 * 포털 전용 경로다. 상태 전이는 {@code @Setter} 대신 의미 있는 비즈니스 메서드로만 수행한다.
 * <ul>
 *   <li>UPLOADED : 업로드 완료(처리 대기)</li>
 *   <li>PROCESSING : 프레임 추출 등 후처리 중</li>
 *   <li>READY : 라벨링 가능 상태</li>
 *   <li>FAILED : 처리 실패({@code failRsnCn} 사유 보유)</li>
 * </ul>
 */
@Entity
@Table(name = "LS_PORTAL_ULD")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPortalUld {

    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_VIDEO = "VIDEO";

    public static final String STTS_UPLOADED   = "UPLOADED";
    public static final String STTS_PROCESSING = "PROCESSING";
    public static final String STTS_READY      = "READY";
    public static final String STTS_FAILED     = "FAILED";

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

    /** 이미지 업로드 생성 팩토리 — 초기 상태 UPLOADED. */
    public static LsPortalUld createImage(String portalUserNo, String orgnlFileNm,
                                          String filePathNm, Long fileSz, String mimeTypeNm) {
        LsPortalUld entity = baseCreate(portalUserNo, TYPE_IMAGE, orgnlFileNm, filePathNm, fileSz, mimeTypeNm);
        return entity;
    }

    /** 영상 업로드 생성 팩토리 — 초기 상태 UPLOADED. 프레임 추출 메타는 이후 markReady 로 채운다. */
    public static LsPortalUld createVideo(String portalUserNo, String orgnlFileNm,
                                          String filePathNm, Long fileSz, String mimeTypeNm) {
        LsPortalUld entity = baseCreate(portalUserNo, TYPE_VIDEO, orgnlFileNm, filePathNm, fileSz, mimeTypeNm);
        return entity;
    }

    private static LsPortalUld baseCreate(String portalUserNo, String uldTypeCd, String orgnlFileNm,
                                          String filePathNm, Long fileSz, String mimeTypeNm) {
        LsPortalUld entity = new LsPortalUld();
        entity.portalUserNo = portalUserNo;
        entity.uldTypeCd = uldTypeCd;
        entity.orgnlFileNm = orgnlFileNm;
        entity.filePathNm = filePathNm;
        entity.fileSz = fileSz;
        entity.mimeTypeNm = mimeTypeNm;
        entity.uldSttsCd = STTS_UPLOADED;
        LocalDateTime now = LocalDateTime.now();
        entity.regDt = now;
        entity.mdfcnDt = now;
        return entity;
    }

    /** 후처리 시작 — UPLOADED → PROCESSING. */
    public void markProcessing() {
        this.uldSttsCd = STTS_PROCESSING;
    }

    /**
     * 처리 완료 — → READY. 영상 프레임 추출 메타(길이/FPS/프레임수)를 함께 확정한다.
     * 이미지는 메타가 null 일 수 있다.
     */
    public void markReady(Double vdoLenSec, Double fps, Integer frmeCnt) {
        this.vdoLenSec = vdoLenSec;
        this.fps = fps;
        this.frmeCnt = frmeCnt;
        this.failRsnCn = null;
        this.uldSttsCd = STTS_READY;
    }

    /** 처리 실패 — → FAILED. 사유를 기록한다. */
    public void markFailed(String failRsnCn) {
        this.failRsnCn = failRsnCn;
        this.uldSttsCd = STTS_FAILED;
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
