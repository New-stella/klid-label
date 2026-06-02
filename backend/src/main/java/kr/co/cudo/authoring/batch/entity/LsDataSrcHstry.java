package kr.co.cudo.authoring.batch.entity;

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

@Entity
@Table(name = "LS_DATA_SRC_HSTRY")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataSrcHstry {

    public static final String CHG_TYPE_CREATED = "CREATED";
    public static final String CHG_TYPE_DEID_ATTACHED = "DEID_ATTACHED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "HSTRY_SEQ")
    private Long hstrySeq;

    @Column(name = "SRC_SN", nullable = false)
    private Long srcSn;

    @Column(name = "CHG_TYPE_CD", nullable = false, length = 16)
    private String chgTypeCd;

    @Column(name = "CHG_USER_NO")
    private Long chgUserNo;

    @Column(name = "CHG_DT", nullable = false)
    private LocalDateTime chgDt;

    @Builder
    private LsDataSrcHstry(Long srcSn, String chgTypeCd, Long chgUserNo) {
        this.srcSn = srcSn;
        this.chgTypeCd = chgTypeCd;
        this.chgUserNo = chgUserNo;
        this.chgDt = LocalDateTime.now();
    }

    public static LsDataSrcHstry recordCreated(Long srcSn) {
        return LsDataSrcHstry.builder().srcSn(srcSn).chgTypeCd(CHG_TYPE_CREATED).build();
    }

    public static LsDataSrcHstry recordDeidAttached(Long srcSn) {
        return LsDataSrcHstry.builder().srcSn(srcSn).chgTypeCd(CHG_TYPE_DEID_ATTACHED).build();
    }
}
