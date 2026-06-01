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

    public static final String RECALC_Y = "Y";
    public static final String RECALC_N = "N";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DATA_AUG_LBL_MAP_SN")
    private Long dataAugLblMapSn;

    @Column(name = "DATA_AUG_SN", nullable = false)
    private Long dataAugSn;

    @Column(name = "ORGNL_DATA_LBL_SN")
    private Long orgnlDataLblSn;

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

    public static LsDataAugLblMap create(Long dataAugSn, Long orgnlDataLblSn, Long dataLblSn,
                                         boolean coordRecalculated, BigDecimal scaleX,
                                         BigDecimal scaleY, String regId) {
        return create(dataAugSn, orgnlDataLblSn, dataLblSn,
                coordRecalculated ? RECALC_Y : RECALC_N, scaleX, scaleY, regId);
    }

    /**
     * 명시적 COORD_RECALC_YN ('Y'/'N') 으로 생성. 사양 호환용.
     */
    public static LsDataAugLblMap create(Long dataAugSn, Long orgnlDataLblSn, Long dataLblSn,
                                         String coordRecalcYn, BigDecimal scaleX,
                                         BigDecimal scaleY, String regId) {
        if (!RECALC_Y.equals(coordRecalcYn) && !RECALC_N.equals(coordRecalcYn)) {
            throw new IllegalArgumentException(
                    "coordRecalcYn 은 'Y' 또는 'N' 이어야 합니다. value=" + coordRecalcYn);
        }
        LsDataAugLblMap map = new LsDataAugLblMap();
        map.dataAugSn = dataAugSn;
        map.orgnlDataLblSn = orgnlDataLblSn;
        map.dataLblSn = dataLblSn;
        map.coordRecalcYn = coordRecalcYn;
        map.scaleX = scaleX;
        map.scaleY = scaleY;
        map.regId = regId;
        map.regDt = LocalDateTime.now();
        return map;
    }
}
