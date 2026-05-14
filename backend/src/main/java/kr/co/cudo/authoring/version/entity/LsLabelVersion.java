package kr.co.cudo.authoring.version.entity;

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
@Table(name = "LS_LABEL_VERSION")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsLabelVersion {

    public static final String ACTIVE_YES = "Y";
    public static final String ACTIVE_NO = "N";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "LABEL_VERSION_SN")
    private Long labelVersionSn;

    @Column(name = "PJT_SN", nullable = false)
    private Long pjtSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "DATA_SRC_SN")
    private Long dataSrcSn;

    @Column(name = "GITEA_CMT_HASH", nullable = false, length = 64)
    private String giteaCmtHash;

    @Column(name = "VERSION_NO", nullable = false)
    private int versionNo;

    @Column(name = "SAVE_REASON_CD", length = 20)
    private String saveReasonCd;

    @Column(name = "ACTIVE_YN", nullable = false, length = 1)
    private String activeYn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    public static LsLabelVersion create(Long pjtSn, Long rawSn, Long srcSn, String commitHash,
                                        int versionNo, String saveReasonCd, String regId) {
        LsLabelVersion version = new LsLabelVersion();
        version.pjtSn = pjtSn == null ? 0L : pjtSn;
        version.dataRawSn = rawSn;
        version.dataSrcSn = srcSn;
        version.giteaCmtHash = commitHash;
        version.versionNo = versionNo;
        version.saveReasonCd = saveReasonCd;
        version.activeYn = ACTIVE_YES;
        version.regId = regId;
        version.regDt = LocalDateTime.now();
        return version;
    }

    public void deactivate() {
        this.activeYn = ACTIVE_NO;
    }
}
