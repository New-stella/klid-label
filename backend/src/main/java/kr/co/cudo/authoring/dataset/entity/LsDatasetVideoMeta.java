package kr.co.cudo.authoring.dataset.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 포털향 통합 메타 스냅샷 (LS_DATASET_VIDEO_META).
 *
 * <p>검수 승인(APPROVED) 시점에 영상 메타(NIA {@code video.*} 블록)를 1영상=1행 컬럼형으로
 * 동결(materialize)한 스냅샷. control DB(klid_at) 단일 진실원(SoT)의 원본이며, 포털 DB 로는
 * outbox 단방향 복제로 반영된다.
 *
 * <p>멱등: 동일 페이로드 재승인 시 동일 {@code SNPSHT_HASH} 로 UK(RAW_SN, SNPSHT_HASH) 충돌 →
 * 리포지토리의 {@code ON CONFLICT DO NOTHING} upsert 가 중복 insert 를 원자적으로 차단한다.
 * 재검수·수정 후 재승인 시 신규 행 append + 이전 행 {@code ACTIVE_YN='N'}(append-only 이력).
 *
 * <p>YN 컬럼은 V85 프로젝트 표준(여부 도메인 CHAR(1))에 맞춰 {@link SqlTypes#CHAR} 로 매핑한다.
 */
@Entity
@Table(name = "LS_DATASET_VIDEO_META",
        uniqueConstraints = @UniqueConstraint(name = "UK_LS_DATASET_VIDEO_META",
                columnNames = {"RAW_SN", "SNPSHT_HASH"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDatasetVideoMeta {

    public static final String ACTIVE_YES = "Y";
    public static final String ACTIVE_NO = "N";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "META_SNPSHT_SN")
    private Long metaSnpshtSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "SNPSHT_HASH", nullable = false, length = 64)
    private String snpshtHash;

    @Column(name = "ACTIVE_YN", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String activeYn;

    // ---- 원시(LS_DATA_RAW 유래) ----
    @Column(name = "ORGNL_RAW_SN")
    private Long orgnlRawSn;

    @Column(name = "VMS_CLIP_ID", length = 128)
    private String vmsClipId;

    @Column(name = "VMS_CCTV_ID", length = 64)
    private String vmsCctvId;

    @Column(name = "RAW_FILE_PATH_NM", length = 500)
    private String rawFilePathNm;

    @Column(name = "SHT_DT")
    private LocalDateTime shtDt;

    @Column(name = "VDO_LEN_SEC")
    private Integer vdoLenSec;

    @Column(name = "LCLGV_CD", length = 20)
    private String lclgvCd;

    @Column(name = "PRVC_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String prvcYn;

    @Column(name = "PRVC_TYPE_CD", length = 16)
    private String prvcTypeCd;

    @Column(name = "DE_IDENT_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String deIdentYn;

    @Column(name = "AI_CRT_YN", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String aiCrtYn;

    @Column(name = "EVNT_TYPE_CD", length = 20)
    private String evntTypeCd;

    // ---- MNG 동결 ----
    @Column(name = "CCTV_NM", length = 255)
    private String cctvNm;

    @Column(name = "WGS84_LAT")
    private BigDecimal wgs84Lat;

    @Column(name = "WGS84_LOT")
    private BigDecimal wgs84Lot;

    @Column(name = "SIDO_NM", length = 100)
    private String sidoNm;

    @Column(name = "SGG_NM", length = 100)
    private String sggNm;

    @Column(name = "FILE_FMT", length = 32)
    private String fileFmt;

    @Column(name = "EVNT_NM", length = 255)
    private String evntNm;

    // ---- ffprobe 기술 메타 ----
    @Column(name = "VDO_CDC", length = 20)
    private String vdoCdc;

    @Column(name = "FPS")
    private BigDecimal fps;

    @Column(name = "BIT_RT")
    private Long bitRt;

    @Column(name = "ASPRT_RT")
    private BigDecimal asprtRt;

    @Column(name = "RESL", length = 32)
    private String resl;

    @Column(name = "VDO_WDTH")
    private Integer vdoWdth;

    @Column(name = "VDO_HGT")
    private Integer vdoHgt;

    @Column(name = "FILE_SZ")
    private Long fileSz;

    // ---- 파생/수기 ----
    @Column(name = "DAY_NGT_CD", length = 8)
    private String dayNgtCd;

    @Column(name = "SESN_CD", length = 20)
    private String sesnCd;

    @Column(name = "WTHR_NM", length = 32)
    private String wthrNm;

    // ---- event_annotation 동결(C2) ----
    /**
     * 동결된 event_annotation payload 원문(jsonb, nullable). 검수 승인 시점의 APPROVED
     * event_annotation(LS_EVNT_ANNO.ANNO_CN) 스냅샷. 승인 안 됐거나 event_annotation 부재 시 null.
     * export 는 이 동결본만 사용해 승인 후 편집분에 오염되지 않고 재export 가 멱등이다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "EVNT_ANNO_CN", columnDefinition = "jsonb")
    private String evntAnnoCn;

    // ---- 관리 ----
    @Column(name = "RVW_CMPL_DT")
    private LocalDateTime rvwCmplDt;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Builder
    private LsDatasetVideoMeta(Long rawSn, String snpshtHash, String activeYn,
                               Long orgnlRawSn, String vmsClipId, String vmsCctvId, String rawFilePathNm,
                               LocalDateTime shtDt, Integer vdoLenSec, String lclgvCd, String prvcYn,
                               String prvcTypeCd, String deIdentYn, String aiCrtYn, String evntTypeCd,
                               String cctvNm, BigDecimal wgs84Lat, BigDecimal wgs84Lot, String sidoNm,
                               String sggNm, String fileFmt, String evntNm,
                               String vdoCdc, BigDecimal fps, Long bitRt, BigDecimal asprtRt, String resl,
                               Integer vdoWdth, Integer vdoHgt, Long fileSz,
                               String dayNgtCd, String sesnCd, String wthrNm, String evntAnnoCn,
                               LocalDateTime rvwCmplDt, LocalDateTime regDt, String regId) {
        this.rawSn = rawSn;
        this.snpshtHash = snpshtHash;
        this.activeYn = (activeYn == null) ? ACTIVE_YES : activeYn;
        this.orgnlRawSn = orgnlRawSn;
        this.vmsClipId = vmsClipId;
        this.vmsCctvId = vmsCctvId;
        this.rawFilePathNm = rawFilePathNm;
        this.shtDt = shtDt;
        this.vdoLenSec = vdoLenSec;
        this.lclgvCd = lclgvCd;
        this.prvcYn = prvcYn;
        this.prvcTypeCd = prvcTypeCd;
        this.deIdentYn = deIdentYn;
        this.aiCrtYn = aiCrtYn;
        this.evntTypeCd = evntTypeCd;
        this.cctvNm = cctvNm;
        this.wgs84Lat = wgs84Lat;
        this.wgs84Lot = wgs84Lot;
        this.sidoNm = sidoNm;
        this.sggNm = sggNm;
        this.fileFmt = fileFmt;
        this.evntNm = evntNm;
        this.vdoCdc = vdoCdc;
        this.fps = fps;
        this.bitRt = bitRt;
        this.asprtRt = asprtRt;
        this.resl = resl;
        this.vdoWdth = vdoWdth;
        this.vdoHgt = vdoHgt;
        this.fileSz = fileSz;
        this.dayNgtCd = dayNgtCd;
        this.sesnCd = sesnCd;
        this.wthrNm = wthrNm;
        this.evntAnnoCn = evntAnnoCn;
        this.rvwCmplDt = rvwCmplDt;
        this.regDt = (regDt == null) ? LocalDateTime.now() : regDt;
        this.regId = regId;
    }
}
