package kr.co.cudo.authoring.augment.entity;

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
@Table(name = "LS_DATA_AUG_LBL_MAP")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataAugLblMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DATA_AUG_LBL_MAP_SN")
    private Long dataAugLblMapSn;

    @Column(name = "DATA_AUG_SN", nullable = false)
    private Long dataAugSn;

    @Column(name = "ORGN_DATA_LBL_SN")
    private Long orgnDataLblSn;

    @Column(name = "DATA_LBL_SN", nullable = false)
    private Long dataLblSn;

    @Column(name = "COORD_RECALC_YN", nullable = false, length = 1)
    private String coordRecalcYn;

    @Column(name = "SCALE_X", precision = 10, scale = 6)
    private BigDecimal scaleX;

    @Column(name = "SCALE_Y", precision = 10, scale = 6)
    private BigDecimal scaleY;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    public static LsDataAugLblMap create(Long dataAugSn, Long orgnDataLblSn, Long dataLblSn,
                                         boolean coordRecalculated, BigDecimal scaleX,
                                         BigDecimal scaleY, String regId) {
        LsDataAugLblMap map = new LsDataAugLblMap();
        map.dataAugSn = dataAugSn;
        map.orgnDataLblSn = orgnDataLblSn;
        map.dataLblSn = dataLblSn;
        map.coordRecalcYn = coordRecalculated ? "Y" : "N";
        map.scaleX = scaleX;
        map.scaleY = scaleY;
        map.regId = regId;
        map.regDt = LocalDateTime.now();
        return map;
    }
}
