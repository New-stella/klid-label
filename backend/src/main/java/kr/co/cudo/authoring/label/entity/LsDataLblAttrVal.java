package kr.co.cudo.authoring.label.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 객체별 속성값 (CVAT-Like 라벨 풀 포팅 Phase 3).
 *
 * <p>라벨링된 객체({@code LS_DATA_LBL.LBL_SN}) 단위로 {@link LsLabelAttr} 의 값을 저장한다.
 * (LBL_SN, ATTR_ID) UNIQUE — upsert 키.
 *
 * <p>비즈니스 규칙:
 * <ul>
 *   <li>VALUE 는 1000자 이내 (옵션형 JSON 직렬화도 가능).</li>
 *   <li>LBL_SN 의 LABEL_ID 와 ATTR_ID 의 LABEL_ID 가 일치해야 함 (Service 검증).</li>
 *   <li>옵션형(SELECT/CHECKBOX/RADIO) value 의 옵션 일치 강제는 MVP 범위 외 — FE 에서 1차 방어.</li>
 * </ul>
 */
@Entity
@Table(name = "LS_DATA_LBL_ATTR_VAL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataLblAttrVal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ATTR_VAL_ID")
    private Long attrValId;

    @Column(name = "LBL_SN", nullable = false)
    private Long lblSn;

    @Column(name = "ATTR_ID", nullable = false)
    private Long attrId;

    /** H2 의 VALUE reserved keyword 회피 — 컬럼명은 ATTR_VAL, Java 필드는 value 유지. */
    @Column(name = "ATTR_VAL", length = 1000)
    private String value;

    @Column(name = "REG_DT", nullable = false, updatable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    private LsDataLblAttrVal(Long lblSn, Long attrId, String value) {
        this.lblSn = lblSn;
        this.attrId = attrId;
        this.value = value;
    }

    /** 정적 팩토리 — 신규 속성값 INSERT. */
    public static LsDataLblAttrVal create(Long lblSn, Long attrId, String value) {
        return new LsDataLblAttrVal(lblSn, attrId, value);
    }

    /** 기존 속성값 UPDATE — value 만 갱신. */
    public void updateValue(String value) {
        this.value = value;
    }

    @PrePersist
    void onCreate() {
        if (this.regDt == null) {
            this.regDt = LocalDateTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        this.mdfcnDt = LocalDateTime.now();
    }
}
