package kr.co.cudo.authoring.preset.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 라벨링 프리셋 Aggregate Root.
 *
 * <p>SCR-LBL-PRESET-001. {@link LsLabelPresetCode} 는 본 Aggregate 내부 엔티티이며
 * 외부에서 직접 변경 금지 — Root 메서드(create/replaceCodes)를 통해서만 접근한다.
 */
@Entity
@Table(name = "LS_LABEL_PRESET")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsLabelPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PRESET_ID")
    private Long presetId;

    @Column(name = "NAME", nullable = false, length = 64)
    private String name;

    @Column(name = "DESCRIPTION", length = 500)
    private String description;

    /**
     * 매핑된 이벤트 타입 코드 (예: EVT_FALL). null = 미매핑.
     * <p>DB UNIQUE 제약(UK_LS_LABEL_PRESET_EVNT) — 이벤트 1개 = 프리셋 1개.
     */
    @Column(name = "EVNT_TYPE_CD", length = 32)
    private String eventTypeCd;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(
            mappedBy = "preset",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER
    )
    @OrderBy("sortOrder ASC")
    private List<LsLabelPresetCode> codes = new ArrayList<>();

    private LsLabelPreset(String name, String description, String eventTypeCd) {
        this.name = name;
        this.description = description;
        this.eventTypeCd = normalizeEventTypeCd(eventTypeCd);
    }

    /**
     * 정적 팩토리. 이름/설명/코드 목록으로 프리셋을 생성한다 (이벤트 매핑 없음).
     *
     * @param name        프리셋 이름 (1~64자)
     * @param description 설명 (선택)
     * @param codes       라벨 코드 목록 (null 허용, 빈 목록 처리)
     */
    public static LsLabelPreset create(String name, String description, List<String> codes) {
        return create(name, description, codes, null);
    }

    /**
     * 정적 팩토리. 이름/설명/코드 목록/이벤트 매핑으로 프리셋을 생성한다.
     *
     * @param name        프리셋 이름 (1~64자)
     * @param description 설명 (선택)
     * @param codes       라벨 코드 목록 (null 허용, 빈 목록 처리)
     * @param eventTypeCd 매핑 이벤트 타입 코드 (선택, null/blank → 미매핑)
     */
    public static LsLabelPreset create(String name, String description, List<String> codes, String eventTypeCd) {
        LsLabelPreset preset = new LsLabelPreset(name, description, eventTypeCd);
        preset.replaceCodes(codes);
        return preset;
    }

    /** 기본 정보(이름/설명) 갱신. */
    public void updateBasics(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /**
     * 이벤트 타입 매핑을 갱신한다. null/blank 입력은 미매핑(null)으로 저장한다.
     * <p>실제 UNIQUE 충돌(다른 프리셋과의 이벤트 중복)은 DB 레벨에서 차단되며,
     * 응용 서비스가 DataIntegrityViolationException → CONFLICT 로 변환한다.
     */
    public void assignToEvent(String eventTypeCd) {
        this.eventTypeCd = normalizeEventTypeCd(eventTypeCd);
    }

    private static String normalizeEventTypeCd(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 코드 목록을 새 목록으로 교체한다. 스마트 diff 패턴으로 Hibernate의
     * INSERT-before-DELETE 충돌(UK_LS_LABEL_PRESET_CODE 위반)을 회피한다.
     *
     * <ol>
     *   <li>입력을 정규화(null/공백 제외, 입력 순서 보존, 중복 제거)</li>
     *   <li>새 목록에 없는 기존 코드만 제거 (orphanRemoval → DELETE)</li>
     *   <li>살아남은 코드는 sortOrder만 갱신</li>
     *   <li>신규 코드만 add (INSERT)</li>
     * </ol>
     */
    public void replaceCodes(List<String> newCodes) {
        // 1. 정규화: null/공백 제거, 입력 순서 보존, 중복 제거
        LinkedHashSet<String> targetCodes = new LinkedHashSet<>();
        if (newCodes != null) {
            for (String code : newCodes) {
                if (code == null) {
                    continue;
                }
                String trimmed = code.trim();
                if (!trimmed.isEmpty()) {
                    targetCodes.add(trimmed);
                }
            }
        }

        // 2. 새 목록에 없는 기존 코드 제거 (orphanRemoval=true → DELETE 예약)
        this.codes.removeIf(c -> !targetCodes.contains(c.getCode()));

        // 3. 살아남은 코드의 현재 위치 매핑
        Map<String, LsLabelPresetCode> existing = new HashMap<>();
        for (LsLabelPresetCode c : this.codes) {
            existing.put(c.getCode(), c);
        }

        // 4. 새 순서대로 재배치: 기존이면 sortOrder만 갱신, 신규면 add
        int order = 0;
        List<LsLabelPresetCode> reordered = new ArrayList<>(targetCodes.size());
        for (String code : targetCodes) {
            LsLabelPresetCode found = existing.get(code);
            if (found != null) {
                found.updateSortOrder(order);
                reordered.add(found);
            } else {
                reordered.add(LsLabelPresetCode.of(this, code, order));
            }
            order++;
        }

        // 5. 컬렉션 교체 (clear() 없이 retainAll + addAll로 충돌 방지)
        this.codes.retainAll(reordered);
        for (LsLabelPresetCode c : reordered) {
            if (!this.codes.contains(c)) {
                this.codes.add(c);
            }
        }
    }

    /** 외부에 노출되는 코드 문자열 목록 (불변). */
    public List<String> codeValues() {
        List<String> result = new ArrayList<>(this.codes.size());
        for (LsLabelPresetCode code : this.codes) {
            result.add(code.getCode());
        }
        return Collections.unmodifiableList(result);
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
