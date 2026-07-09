package kr.co.cudo.authoring.preset.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.AssertTrue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 프리셋 라벨 코드 (Aggregate 내부 엔티티).
 *
 * <p>외부에서 직접 생성/수정 금지. {@link LsLabelPreset#replaceCodes(java.util.List)} 통해서만 변경한다.
 *
 * <p>Phase 1 — 라벨별 BBOX/POLYGON 토글 컬럼 추가 (V16 migration).
 */
@Entity
@Table(name = "LS_LABEL_PRESET_CODE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsLabelPresetCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CD_SN")
    private Long codeSn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "PRESET_ID", nullable = false)
    private LsLabelPreset preset;

    @Column(name = "LBL_CD", nullable = false, length = 32)
    private String code;

    @Column(name = "SORT_SEQ", nullable = false)
    private int sortOrder;

    /** BBOX 어노테이션 활성 여부 (V16). */
    @Column(name = "BBOX_ENABLED", nullable = false)
    private boolean bboxEnabled;

    /** POLYGON 어노테이션 활성 여부 (V16). */
    @Column(name = "POLYGON_ENABLED", nullable = false)
    private boolean polygonEnabled;

    private LsLabelPresetCode(LsLabelPreset preset, String code, int sortOrder,
                              boolean bboxEnabled, boolean polygonEnabled) {
        this.preset = preset;
        this.code = code;
        this.sortOrder = sortOrder;
        this.bboxEnabled = bboxEnabled;
        this.polygonEnabled = polygonEnabled;
    }

    /**
     * 내부 팩토리 — 패키지 가시성으로 Aggregate Root 만 호출 가능.
     * 두 옵션이 모두 false 인 조합은 호출자({@link LsLabelPreset#replaceCodes}) 또는
     * 상위 검증 단계({@code LabelCodeOptionDto.@AssertTrue})에서 거부되어야 한다.
     */
    static LsLabelPresetCode of(LsLabelPreset preset, String code, int sortOrder,
                                boolean bboxEnabled, boolean polygonEnabled) {
        return new LsLabelPresetCode(preset, code, sortOrder, bboxEnabled, polygonEnabled);
    }

    /** sortOrder 갱신 — Aggregate Root의 replaceCodes에서만 호출. */
    public void updateSortOrder(int order) {
        this.sortOrder = order;
    }

    /** 토글 갱신 — Aggregate Root의 replaceCodes에서만 호출 (이미 살아남은 row 의 옵션 변경). */
    void updateToggles(boolean bboxEnabled, boolean polygonEnabled) {
        this.bboxEnabled = bboxEnabled;
        this.polygonEnabled = polygonEnabled;
    }

    /**
     * Bean Validation 다중 가드 — DTO·DB CHECK 외에 엔티티 레벨에서도 보호.
     *
     * <p>{@link AssertTrue} 는 메서드 호출시점이 아닌 {@code @Valid} 검증 시점에 평가되므로
     * 단위 테스트에서는 {@link #isAtLeastOneEnabled()} 를 직접 호출해 boolean 결과를 확인한다.
     */
    @AssertTrue(message = "최소 하나의 어노테이션 유형은 활성화되어야 합니다.")
    public boolean isAtLeastOneEnabled() {
        return bboxEnabled || polygonEnabled;
    }
}
