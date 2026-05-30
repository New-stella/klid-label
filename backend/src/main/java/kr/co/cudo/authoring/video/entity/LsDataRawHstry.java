package kr.co.cudo.authoring.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 영상 변경 이력. INGEST(신규/갱신)·STATUS_CHANGE 등 변경 사유 추적. */
@Entity
@Table(name = "LS_DATA_RAW_HSTRY")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataRawHstry {

    public static final String CHG_INGEST_NEW = "INGEST_NEW";
    public static final String CHG_INGEST_UPD = "INGEST_UPD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "HSTRY_SEQ")
    private Long hstrySeq;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "CHG_TYPE_CD", nullable = false, length = 16)
    private String chgTypeCd;

    @Column(name = "PREV_STTS_CD", length = 32)
    private String prevSttsCd;

    @Column(name = "NEW_STTS_CD", length = 32)
    private String newSttsCd;

    @Column(name = "CHG_USER_NO")
    private Long chgUserNo;

    @Column(name = "CHG_DT", nullable = false)
    private LocalDateTime chgDt;

    @Builder
    private LsDataRawHstry(Long rawSn, String chgTypeCd, String prevSttsCd, String newSttsCd, Long chgUserNo) {
        this.rawSn = rawSn;
        this.chgTypeCd = chgTypeCd;
        this.prevSttsCd = prevSttsCd;
        this.newSttsCd = newSttsCd;
        this.chgUserNo = chgUserNo;
        this.chgDt = LocalDateTime.now();
    }

    public static LsDataRawHstry recordIngest(Long rawSn, boolean isNew, String currentSttsCd) {
        return LsDataRawHstry.builder()
                .rawSn(rawSn)
                .chgTypeCd(isNew ? CHG_INGEST_NEW : CHG_INGEST_UPD)
                .newSttsCd(currentSttsCd)
                .build();
    }
}
