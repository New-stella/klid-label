package kr.co.cudo.authoring.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;

/** CCTV 관리 마스터. VMS_CCTV_ID 매핑 검증/명칭 조회용. */
@Entity
@Table(name = "MNG_RESOURCE_CCTV")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MngResourceCctv {

    @Id
    @Column(name = "VMS_CCTV_ID", length = 64)
    private String vmsCctvId;

    @Column(name = "CCTV_NM", length = 255)
    private String cctvNm;

    @Column(name = "SHT_ADDR", length = 500)
    private String shtAddr;

    @Column(name = "OG_NM", length = 255)
    private String ogNm;

    @Column(name = "WGS84_LAT")
    private BigDecimal wgs84Lat;

    @Column(name = "WGS84_LOT")
    private BigDecimal wgs84Lot;

    @Column(name = "RESOLUTION", length = 32)
    private String resolution;

    @Column(name = "USE_YN", nullable = false, length = 1)
    private String useYn;
}
