package kr.co.cudo.authoring.label.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 라벨 마스터 (CVAT-Like 라벨 풀 포팅 Phase 1).
 *
 * <p>저작도구 전역 단일 라벨 풀에서 작업자가 라벨링 시점에 선택한다.
 * soft delete(USE_YN='N') 만 지원하며, hard delete 는 라벨 히스토리·태그 무결성 보호를
 * 위해 금지된다.
 *
 * <p>비즈니스 규칙:
 * <ul>
 *   <li>NAME UNIQUE — DB 레벨 + Service 레벨 이중 가드. (V34: PJT_ID 제거됨)</li>
 *   <li>COLOR 는 대문자 {@code #RRGGBB} hex (소문자 거부 — DTO 검증).</li>
 *   <li>TYPE 은 BBOX / POLYGON / POINT 중 하나 (DTO 검증).</li>
 *   <li>SORT_NO 는 목록 정렬용 (ASC).</li>
 * </ul>
 */
@Entity
@Table(name = "LS_LABEL", uniqueConstraints = {
        @UniqueConstraint(name = "UK_LS_LABEL_NAME", columnNames = {"NAME"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsLabel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "LABEL_ID")
    private Long labelId;

    @Column(name = "NAME", nullable = false, length = 64)
    private String name;

    @Column(name = "COLOR", nullable = false, length = 7)
    private String color;

    @Column(name = "TYPE", nullable = false, length = 16)
    private String type;

    @Column(name = "SORT_NO", nullable = false)
    private Integer sortNo;

    @Column(name = "USE_YN", nullable = false, length = 1)
    private String useYn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false, updatable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    private LsLabel(String name, String color, String type, Integer sortNo, String regId) {
        this.name = name;
        this.color = color;
        this.type = type;
        this.sortNo = sortNo == null ? 0 : sortNo;
        this.useYn = "Y";
        this.regId = regId;
    }

    /**
     * 정적 팩토리 — 새 라벨 생성.
     *
     * @param name    라벨 이름 (1~64자, UNIQUE)
     * @param color   색상 (대문자 #RRGGBB)
     * @param type    BBOX / POLYGON / POINT
     * @param sortNo  정렬 순서 (null 허용 — 0 으로 정규화)
     * @param regId   등록자 ID (선택)
     */
    public static LsLabel create(String name, String color, String type,
                                 Integer sortNo, String regId) {
        return new LsLabel(name, color, type, sortNo, regId);
    }

    /** 비즈니스 메서드 — 라벨 정보 수정. Setter 대신 의미 있는 메서드명 사용. */
    public void update(String name, String color, String type, Integer sortNo, String mdfcnId) {
        this.name = name;
        this.color = color;
        this.type = type;
        this.sortNo = sortNo == null ? 0 : sortNo;
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
