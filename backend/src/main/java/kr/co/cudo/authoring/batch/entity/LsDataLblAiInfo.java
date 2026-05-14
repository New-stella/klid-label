package kr.co.cudo.authoring.batch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "LS_DATA_LBL_AI_INFO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataLblAiInfo {

    public static final String SRC_YOLO = "YOLO";
    public static final String SRC_SAM2 = "SAM2";
    public static final String SRC_INTERPOLATE = "INTERPOLATE";
    public static final String SRC_VLM = "VLM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DATA_LBL_AI_INFO_SN")
    private Long dataLblAiInfoSn;

    @Column(name = "DATA_LBL_SN", nullable = false)
    private Long dataLblSn;

    @Column(name = "PJT_SN", nullable = false)
    private Long pjtSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "DATA_SRC_SN", nullable = false)
    private Long dataSrcSn;

    @Column(name = "LBL_SRC_CD", nullable = false, length = 20)
    private String lblSrcCd;

    @Column(name = "MODEL_NM", length = 100)
    private String modelNm;

    @Column(name = "MODEL_VER", length = 50)
    private String modelVer;

    @Column(name = "CONF_SCORE", precision = 6, scale = 5)
    private BigDecimal confScore;

    @Column(name = "AUTO_LBL_YN", nullable = false, length = 1)
    private String autoLblYn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    public static LsDataLblAiInfo create(Long dataLblSn, Long pjtSn, Long rawSn, Long srcSn,
                                         String lblSrcCd, BigDecimal confScore, String regId) {
        LsDataLblAiInfo info = new LsDataLblAiInfo();
        info.dataLblSn = dataLblSn;
        info.pjtSn = pjtSn == null ? 0L : pjtSn;
        info.dataRawSn = rawSn;
        info.dataSrcSn = srcSn;
        info.lblSrcCd = lblSrcCd;
        info.confScore = confScore;
        info.autoLblYn = LsDataLbl.AUTO_YES;
        info.regId = regId;
        info.regDt = LocalDateTime.now();
        return info;
    }

    public void updateConfidence(BigDecimal newScore, String actorId) {
        this.confScore = newScore;
        this.mdfcnId = actorId;
        this.mdfcnDt = LocalDateTime.now();
    }
}
