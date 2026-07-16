package kr.co.cudo.authoring.label.entity;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
 * 라벨별 속성 정의 (CVAT-Like 라벨 풀 포팅 Phase 3).
 *
 * <p>한 {@link LsLabel} 에 대해 사용자가 라벨링 시 부여할 수 있는 속성을 정의한다.
 * 예: 라벨 "person" 에 속성 "occluded(SELECT[yes,no])", "direction(RADIO[N,S,E,W])".
 *
 * <p>비즈니스 규칙:
 * <ul>
 *   <li>(LBL_ID, ATRB_NM) UNIQUE — DB 레벨 + Service 레벨 이중 가드.</li>
 *   <li>INPUT_TYPE_CD: SELECT / CHECKBOX / RADIO / NUMBER / TEXT.</li>
 *   <li>INPUT_TYPE_CD 가 SELECT/CHECKBOX/RADIO 면 VALUES_CN 필수 (Service 검증).</li>
 *   <li>MUTABLE_YN='Y' 면 프레임마다 다른 값 허용. 'N' 이면 트랙 단위 고정 (강제는 향후 Phase).</li>
 *   <li>SORT_SEQ 는 목록 정렬용 (ASC).</li>
 *   <li>soft delete (USE_YN='N') — hard delete 금지.</li>
 * </ul>
 */
@Entity
@Table(name = "LS_LABEL_ATTR")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsLabelAttr {

    public static final String INPUT_SELECT = "SELECT";
    public static final String INPUT_CHECKBOX = "CHECKBOX";
    public static final String INPUT_RADIO = "RADIO";
    public static final String INPUT_NUMBER = "NUMBER";
    public static final String INPUT_TEXT = "TEXT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ATRB_ID")
    private Long attrId;

    @Column(name = "LBL_ID", nullable = false)
    private Long labelId;

    @Column(name = "ATRB_NM", nullable = false, length = 100)
    private String attrNm;

    @Column(name = "INPUT_TYPE_CD", nullable = false, length = 16)
    private String inputTypeCd;

    @Column(name = "VALUES_CN", length = 1000)
    private String valuesCn;

    @Column(name = "DFLT_VL", length = 255)
    private String dfltVl;

    @Column(name = "MUTABLE_YN", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String mutableYn;

    @Column(name = "SORT_SEQ", nullable = false)
    private Integer sortSeq;

    @Column(name = "USE_YN", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String useYn;

    @Column(name = "REG_ID", length = 64)
    private String regId;

    @Column(name = "REG_DT", nullable = false, updatable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 64)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    private LsLabelAttr(Long labelId, String attrNm, String inputTypeCd, String valuesCn,
                        String dfltVl, String mutableYn, Integer sortSeq, String regId) {
        this.labelId = labelId;
        this.attrNm = attrNm;
        this.inputTypeCd = inputTypeCd;
        this.valuesCn = valuesCn;
        this.dfltVl = dfltVl;
        this.mutableYn = (mutableYn == null || mutableYn.isBlank()) ? "Y" : mutableYn;
        this.sortSeq = sortSeq == null ? 0 : sortSeq;
        this.useYn = "Y";
        this.regId = regId;
    }

    /**
     * 정적 팩토리 — 새 속성 정의 생성.
     *
     * @param labelId     LS_LABEL FK
     * @param attrNm      속성 이름 (1~64자, 라벨 내 UNIQUE)
     * @param inputTypeCd SELECT / CHECKBOX / RADIO / NUMBER / TEXT
     * @param valuesCn    옵션 목록 JSON (SELECT/CHECKBOX/RADIO 일 때 필수)
     * @param dfltVl      기본값 (선택)
     * @param mutableYn   'Y'(기본) | 'N' — 프레임마다 변경 허용 여부
     * @param sortSeq     정렬 순서 (null 허용 — 0 으로 정규화)
     * @param regId       등록자 ID (선택)
     */
    public static LsLabelAttr create(Long labelId, String attrNm, String inputTypeCd, String valuesCn,
                                     String dfltVl, String mutableYn, Integer sortSeq, String regId) {
        return new LsLabelAttr(labelId, attrNm, inputTypeCd, valuesCn, dfltVl, mutableYn, sortSeq, regId);
    }

    /** 비즈니스 메서드 — 속성 정의 수정. Setter 대신 의미 있는 메서드명 사용. */
    public void update(String attrNm, String inputTypeCd, String valuesCn, String dfltVl,
                       String mutableYn, Integer sortSeq, String mdfcnId) {
        this.attrNm = attrNm;
        this.inputTypeCd = inputTypeCd;
        this.valuesCn = valuesCn;
        this.dfltVl = dfltVl;
        this.mutableYn = (mutableYn == null || mutableYn.isBlank()) ? "Y" : mutableYn;
        this.sortSeq = sortSeq == null ? 0 : sortSeq;
        this.mdfcnId = mdfcnId;
    }

    /** Soft delete — USE_YN='N'. hard delete 는 호출 금지. */
    public void softDelete(String mdfcnId) {
        this.useYn = "N";
        this.mdfcnId = mdfcnId;
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
