package kr.co.cudo.authoring.batch.entity;

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

import java.time.LocalDateTime;

/**
 * LS_DATA_META: 영상/프레임 메타 (외부 시계열 메타 검토 + VLM 객체 검증 보조).
 *  - rawSn: LS_DATA_RAW FK
 *  - metaKey + metaVal 단순 K/V (K 는 (RAW_SN, META_KEY) UK)
 *  - metaTypeCd: Phase 2 — 'RAW'(원본 기준) | 'DEID'(비식별 영상 기준).
 */
@Entity
@Table(name = "LS_DATA_META",
        uniqueConstraints = @UniqueConstraint(name = "UK_LS_DATA_META_RAW_KEY",
                columnNames = {"RAW_SN", "META_KEY"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataMeta {

    /** Phase 2: 원본 영상 기준 메타 (디폴트). */
    public static final String META_TYPE_RAW = "RAW";
    /** Phase 2: 비식별 영상 기준 메타. */
    public static final String META_TYPE_DEID = "DEID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "META_SN")
    private Long metaSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "META_KEY", nullable = false, length = 64)
    private String metaKey;

    @Column(name = "META_VAL", length = 2000)
    private String metaVal;

    @Column(name = "META_TYPE_CD", nullable = false, length = 8)
    private String metaTypeCd;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "UPD_DT")
    private LocalDateTime updDt;

    @Builder
    private LsDataMeta(Long rawSn, String metaKey, String metaVal, String metaTypeCd) {
        this.rawSn = rawSn;
        this.metaKey = metaKey;
        this.metaVal = metaVal;
        this.metaTypeCd = (metaTypeCd == null || metaTypeCd.isBlank()) ? META_TYPE_RAW : metaTypeCd;
        this.regDt = LocalDateTime.now();
    }

    /** 기본 생성: META_TYPE_CD='RAW' (Phase 2 디폴트). */
    public static LsDataMeta create(Long rawSn, String metaKey, String metaVal) {
        return createWithType(rawSn, metaKey, metaVal, META_TYPE_RAW);
    }

    /** Phase 2: META_TYPE_CD 명시 생성. */
    public static LsDataMeta createWithType(Long rawSn, String metaKey, String metaVal, String metaTypeCd) {
        return LsDataMeta.builder()
                .rawSn(rawSn)
                .metaKey(metaKey)
                .metaVal(metaVal)
                .metaTypeCd(metaTypeCd)
                .build();
    }

    public void updateValue(String newVal) {
        this.metaVal = newVal;
        this.updDt = LocalDateTime.now();
    }
}
