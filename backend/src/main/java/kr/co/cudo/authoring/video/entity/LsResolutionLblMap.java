package kr.co.cudo.authoring.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 해상도 파생영상 라벨 매핑(LS_RESOLUTION_LBL_MAP) — Phase 2 (RQ-SFR-06-03 파생영상).
 *
 * <p>원본 라벨을 목표 해상도 파생영상으로 좌표 리스케일 복사할 때, 원본↔파생 라벨 매핑과
 * 배율(scaleX/scaleY)을 추적한다. 해상도 변경이므로 좌표 재계산이 항상 일어나 COORD_RECALC_YN='Y' 다.
 *
 * <p>{@code LS_DATA_AUG_LBL_MAP} 은 {@code DATA_AUG_SN NOT NULL} 이라 재사용하지 않는다(증강 이력/통계/
 * FE 오염 방지). RESL_EXPORT_SN 으로 {@code LS_RESOLUTION_EXPORT} 산출 추적 행과 연결한다.
 */
@Entity
@Table(name = "LS_RESOLUTION_LBL_MAP")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsResolutionLblMap {

    public static final String RECALC_Y = "Y";
    public static final String RECALC_N = "N";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RESL_LBL_MAP_SN")
    private Long resLblMapSn;

    @Column(name = "RESL_EXPORT_SN", nullable = false)
    private Long resExportSn;

    @Column(name = "ORGNL_DATA_LBL_SN")
    private Long orgnlDataLblSn;

    @Column(name = "DATA_LBL_SN", nullable = false)
    private Long dataLblSn;

    @Column(name = "COORD_RECALC_YN", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String coordRecalcYn;

    @Column(name = "SCALE_X", precision = 10, scale = 6)
    private BigDecimal scaleX;

    @Column(name = "SCALE_Y", precision = 10, scale = 6)
    private BigDecimal scaleY;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    /**
     * 좌표 리스케일 복사 매핑 생성.
     *
     * @param coordRecalculated 좌표 재계산 여부 (해상도 변경이면 true → 'Y')
     * @param scaleX            x축 배율 (양수, null 허용)
     * @param scaleY            y축 배율 (양수, null 허용)
     */
    public static LsResolutionLblMap create(Long resExportSn, Long orgnlDataLblSn, Long dataLblSn,
                                            boolean coordRecalculated, BigDecimal scaleX,
                                            BigDecimal scaleY, String regId) {
        LsResolutionLblMap map = new LsResolutionLblMap();
        map.resExportSn = resExportSn;
        map.orgnlDataLblSn = orgnlDataLblSn;
        map.dataLblSn = dataLblSn;
        map.coordRecalcYn = coordRecalculated ? RECALC_Y : RECALC_N;
        map.scaleX = scaleX;
        map.scaleY = scaleY;
        map.regId = regId;
        map.regDt = LocalDateTime.now();
        return map;
    }
}
