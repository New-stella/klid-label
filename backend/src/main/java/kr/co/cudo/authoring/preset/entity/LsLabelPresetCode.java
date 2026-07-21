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
 *
 * <p>V117/V118 — 라벨 마스터(LS_LABEL) 연결 전환:
 * <ul>
 *   <li>{@code labelId}(LBL_ID) = 마스터 PK FK. null = 미연결(이름 매칭 실패한 레거시 행).</li>
 *   <li>BBOX/POLYGON 형태 스냅샷 컬럼 제거 — 형태는 마스터 {@code LBL_TYPE_CD} 가 단일 소유.</li>
 *   <li>{@code code}(LBL_CD) = <b>미연결 레거시 행의 표시용 코드 문자열</b>(V118 로 nullable).
 *       labelId 기반 신규 행은 LBL_CD 를 보유하지 않으며(null) 라벨명은 마스터에서 실시간 조회한다.</li>
 * </ul>
 *
 * <p>조회 시 라벨명/형태는 {@code labelId} 로 마스터를 실시간 join 하여 파생한다(스냅샷 금지).
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

    /** 라벨 마스터(LS_LABEL) PK FK. null = 미연결(backfill 이름 매칭 실패한 레거시 행). */
    @Column(name = "LBL_ID")
    private Long labelId;

    /**
     * 미연결 레거시 행의 표시용 코드 문자열. V118 로 nullable — labelId 기반 신규 행은 null.
     * 연결 행의 라벨명은 저장하지 않고 {@code labelId} 로 마스터에서 실시간 조회한다.
     */
    @Column(name = "LBL_CD", length = 32)
    private String code;

    @Column(name = "SORT_SEQ", nullable = false)
    private int sortOrder;

    private LsLabelPresetCode(LsLabelPreset preset, Long labelId, String code, int sortOrder) {
        this.preset = preset;
        this.labelId = labelId;
        this.code = code;
        this.sortOrder = sortOrder;
    }

    /**
     * 내부 팩토리(레거시/미연결) — 패키지 가시성으로 Aggregate Root 만 호출 가능.
     *
     * <p>{@code labelId} 는 null(미연결)로 시작하고 {@code code}(LBL_CD)에 코드 문자열을 보관한다.
     */
    static LsLabelPresetCode of(LsLabelPreset preset, String code, int sortOrder) {
        return new LsLabelPresetCode(preset, null, code, sortOrder);
    }

    /**
     * 내부 팩토리(labelId 연결) — 패키지 가시성으로 Aggregate Root 만 호출 가능.
     *
     * <p>{@code labelId} 로 마스터를 연결한다. {@code code}(LBL_CD)는 null 이며 라벨명은 마스터에서
     * 실시간 조회한다.
     */
    static LsLabelPresetCode of(LsLabelPreset preset, Long labelId, String code, int sortOrder) {
        return new LsLabelPresetCode(preset, labelId, code, sortOrder);
    }

    /** sortOrder 갱신 — Aggregate Root의 replaceCodes에서만 호출. */
    public void updateSortOrder(int order) {
        this.sortOrder = order;
    }
}
