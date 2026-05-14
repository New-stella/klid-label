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
 * LS_DATA_META: 영상/프레임 메타 값.
 *  - rawSn: LS_DATA_RAW FK
 *  - metaKey + metaVal 단순 K/V (K 는 (RAW_SN, META_KEY) UK)
 *
 * 외부 생성 여부, 메타 유형, 검토 상태는 LS_DATA_META_REVIEW 에 분리 저장한다.
 */
@Entity
@Table(name = "LS_DATA_META",
        uniqueConstraints = @UniqueConstraint(name = "UK_LS_DATA_META_RAW_KEY",
                columnNames = {"RAW_SN", "META_KEY"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataMeta {

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

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "UPD_DT")
    private LocalDateTime updDt;

    @Builder
    private LsDataMeta(Long rawSn, String metaKey, String metaVal) {
        this.rawSn = rawSn;
        this.metaKey = metaKey;
        this.metaVal = metaVal;
        this.regDt = LocalDateTime.now();
    }

    public static LsDataMeta create(Long rawSn, String metaKey, String metaVal) {
        return LsDataMeta.builder()
                .rawSn(rawSn)
                .metaKey(metaKey)
                .metaVal(metaVal)
                .build();
    }

    public void updateValue(String newVal) {
        this.metaVal = newVal;
        this.updDt = LocalDateTime.now();
    }
}
