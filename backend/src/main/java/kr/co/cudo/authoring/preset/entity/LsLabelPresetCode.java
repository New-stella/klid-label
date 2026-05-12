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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 프리셋 라벨 코드 (Aggregate 내부 엔티티).
 *
 * <p>외부에서 직접 생성/수정 금지. {@link LsLabelPreset#replaceCodes(java.util.List)} 통해서만 변경한다.
 */
@Entity
@Table(name = "LS_LABEL_PRESET_CODE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsLabelPresetCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "CODE_SN")
    private Long codeSn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "PRESET_ID", nullable = false)
    private LsLabelPreset preset;

    @Column(name = "CODE", nullable = false, length = 32)
    private String code;

    @Column(name = "SORT_ORDER", nullable = false)
    private int sortOrder;

    private LsLabelPresetCode(LsLabelPreset preset, String code, int sortOrder) {
        this.preset = preset;
        this.code = code;
        this.sortOrder = sortOrder;
    }

    /** 내부 팩토리 — 패키지 가시성으로 Aggregate Root 만 호출 가능. */
    static LsLabelPresetCode of(LsLabelPreset preset, String code, int sortOrder) {
        return new LsLabelPresetCode(preset, code, sortOrder);
    }

    /** sortOrder 갱신 — Aggregate Root의 replaceCodes에서만 호출. */
    public void updateSortOrder(int order) {
        this.sortOrder = order;
    }
}
