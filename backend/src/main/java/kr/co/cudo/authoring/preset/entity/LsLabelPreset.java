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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 라벨링 프리셋 Aggregate Root — <b>이벤트유형 1건에 대한 오토라벨 대상 라벨 세트</b>.
 *
 * <p>프리셋은 <b>이름과 설명을 갖지 않는다</b>(V17). 식별 축은 {@code EVNT_TYPE_CD} 하나이며 그
 * 컬럼에 UNIQUE(uk_ls_label_preset_evnt)가 걸려 있어 이벤트 1건에 프리셋 1건이 대응한다. 이름을
 * 따로 두면 그 값이 이벤트명의 중복이 되고, 설명은 읽는 화면이 없었다. 사람이 읽는 이름은 이벤트
 * 유형 마스터의 표시명(4단 폴백)이 단일 진실원으로 제공한다.
 *
 * <p>{@link LsLabelPresetCode} 는 본 Aggregate 내부 엔티티이며 외부에서 직접 변경 금지 —
 * Root 메서드(create/createWithOptions/replaceCodes)를 통해서만 접근한다.
 *
 * @design ERD-019
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

    /**
     * 매핑된 이벤트유형 코드 (예: EV01000101). <b>필수</b>이며 프리셋의 유일한 식별 축이다.
     *
     * <p>DB UNIQUE 제약(UK_LS_LABEL_PRESET_EVNT) — 이벤트 1개 = 프리셋 1개. V17 이 NOT NULL 로
     * 좁혔다: 이벤트에 걸리지 않은 프리셋은 어느 영상에도 매칭되지 않아 오토라벨에 아무 기여를
     * 하지 않는 죽은 행이다.
     */
    @Column(name = "EVNT_TYPE_CD", length = 20, nullable = false)
    private String eventTypeCd;

    @Column(name = "REG_DT", nullable = false, updatable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

    /**
     * 프리셋 라벨 코드 목록. LAZY — N+1/불필요 로딩 방지(performance 규칙).
     * <p>목록 경로는 {@link kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository#findAllWithCodes()}
     * 의 {@code LEFT JOIN FETCH} 로 초기화하고, 단건 경로(create/update/오토라벨 조회)는 모두
     * {@code @Transactional} 서비스 트랜잭션 안에서 접근하므로 지연 로딩이 안전하다.
     */
    @OneToMany(
            mappedBy = "preset",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @OrderBy("sortOrder ASC")
    private List<LsLabelPresetCode> codes = new ArrayList<>();

    private LsLabelPreset(String eventTypeCd) {
        this.eventTypeCd = normalizeEventTypeCd(eventTypeCd);
    }

    /**
     * 정적 팩토리 (레거시 코드 문자열 목록).
     *
     * @param codes       라벨 코드 문자열 목록 (null 허용, 빈 목록 처리). 각 코드는 미연결(labelId=null)
     *                    레거시 코드로 저장된다. 형태(BBOX/POLYGON)는 마스터 {@code LBL_TYPE_CD} 가
     *                    소유하므로 프리셋에는 저장하지 않는다.
     * @param eventTypeCd 매핑 이벤트유형 코드 (필수 — 응용 서비스가 빈값을 400 으로 거부한다)
     */
    public static LsLabelPreset create(List<String> codes, String eventTypeCd) {
        LsLabelPreset preset = new LsLabelPreset(eventTypeCd);
        List<LabelCodeSpec> specs = (codes == null)
                ? Collections.emptyList()
                : codes.stream()
                    .filter(c -> c != null && !c.trim().isEmpty())
                    .map(LabelCodeSpec::new)
                    .toList();
        preset.replaceCodes(specs);
        return preset;
    }

    /**
     * 정적 팩토리 (코드 스펙 목록 명시).
     *
     * @param specs       라벨 코드 스펙 목록 (null 허용, 빈 목록 처리)
     * @param eventTypeCd 매핑 이벤트유형 코드 (필수 — 응용 서비스가 빈값을 400 으로 거부한다)
     */
    public static LsLabelPreset createWithOptions(List<LabelCodeSpec> specs, String eventTypeCd) {
        LsLabelPreset preset = new LsLabelPreset(eventTypeCd);
        preset.replaceCodes(specs);
        return preset;
    }

    /**
     * 이벤트유형 매핑을 갱신한다. null/blank 입력은 null 로 정규화한다(컬럼이 NOT NULL 이라 그대로
     * 저장하면 DB 가 거부한다 — 응용 서비스가 그 이전에 400 으로 막는 것이 정상 경로다).
     * <p>실제 UNIQUE 충돌(다른 프리셋과의 이벤트 중복)은 응용 서비스의 사전 검사 + DB 제약 두 겹으로
     * 차단되며, 서비스가 DataIntegrityViolationException → CONFLICT 로 변환한다.
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
     * 코드 목록을 새 스펙 목록으로 교체한다. 스마트 diff 패턴으로 Hibernate의
     * INSERT-before-DELETE 충돌(UK_LS_LABEL_PRESET_CODE 위반)을 회피한다.
     *
     * <p>diff 식별자는 labelId 연결 행이면 labelId, 미연결 레거시 행이면 코드 문자열이다.
     *
     * <ol>
     *   <li>입력을 정규화(빈 스펙 제외, 입력 순서 보존, 중복 제거 — labelId/코드 기준)</li>
     *   <li>새 목록에 없는 기존 코드만 제거 (orphanRemoval → DELETE)</li>
     *   <li>살아남은 코드는 sortOrder 갱신</li>
     *   <li>신규 코드만 add (INSERT)</li>
     * </ol>
     */
    public void replaceCodes(List<LabelCodeSpec> newSpecs) {
        // 1. 정규화: 빈 스펙 제거, 입력 순서 보존, 중복 제거 (labelId 또는 코드 기준)
        LinkedHashMap<String, LabelCodeSpec> target = new LinkedHashMap<>();
        if (newSpecs != null) {
            for (LabelCodeSpec spec : newSpecs) {
                String key = keyOf(spec);
                if (key == null) {
                    continue;
                }
                // 중복 키는 마지막 입력이 이긴다 — Map.put 의 기본 동작.
                target.put(key, spec.normalized());
            }
        }

        // 2. 새 목록에 없는 기존 코드 제거 (orphanRemoval=true → DELETE 예약)
        this.codes.removeIf(c -> !target.containsKey(matchKey(c)));

        // 3. 살아남은 코드의 현재 위치 매핑
        Map<String, LsLabelPresetCode> existing = new HashMap<>();
        for (LsLabelPresetCode c : this.codes) {
            existing.put(matchKey(c), c);
        }

        // 4. 새 순서대로 재배치: 기존이면 sortOrder 만 갱신, 신규면 add
        int order = 0;
        List<LsLabelPresetCode> reordered = new ArrayList<>(target.size());
        for (Map.Entry<String, LabelCodeSpec> entry : target.entrySet()) {
            LabelCodeSpec spec = entry.getValue();
            LsLabelPresetCode found = existing.get(entry.getKey());
            if (found != null) {
                found.updateSortOrder(order);
                reordered.add(found);
            } else {
                reordered.add(LsLabelPresetCode.of(this, spec.labelId(), spec.code(), order));
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

    /** diff 키 — labelId 연결 행이면 {@code ID:<id>}, 미연결이면 {@code CD:<code>}. 빈 스펙은 null. */
    private static String keyOf(LabelCodeSpec spec) {
        if (spec == null) {
            return null;
        }
        if (spec.labelId() != null) {
            return "ID:" + spec.labelId();
        }
        String code = spec.code();
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        return "CD:" + code.trim();
    }

    /** 영속 코드의 diff 키 — {@link #keyOf(LabelCodeSpec)} 와 동일 규칙. */
    private static String matchKey(LsLabelPresetCode code) {
        if (code.getLabelId() != null) {
            return "ID:" + code.getLabelId();
        }
        return "CD:" + code.getCode();
    }

    /** 외부에 노출되는 코드 문자열 목록 (불변). labelId 연결 행의 코드(null)는 제외한다. */
    public List<String> codeValues() {
        List<String> result = new ArrayList<>(this.codes.size());
        for (LsLabelPresetCode code : this.codes) {
            if (code.getCode() != null) {
                result.add(code.getCode());
            }
        }
        return Collections.unmodifiableList(result);
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.regDt = now;
        this.mdfcnDt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 도메인 VO — 라벨 코드 스펙.
     *
     * <p>{@link #replaceCodes(List)} 의 명시적 입력 타입. 형태(BBOX/POLYGON)는 마스터
     * {@code LBL_TYPE_CD} 가 소유하므로 저장하지 않는다.
     *
     * <ul>
     *   <li>{@code labelId} = 마스터(LS_LABEL) PK. labelId 기반 신규 코드는 이 값을 채운다.</li>
     *   <li>{@code code} = 미연결 레거시 코드 문자열(LBL_CD). labelId 연결 스펙은 null.</li>
     * </ul>
     */
    public record LabelCodeSpec(Long labelId, String code) {

        /** 레거시 편의 생성자 — 코드 문자열만(미연결, labelId=null). */
        public LabelCodeSpec(String code) {
            this(null, code);
        }

        /** 저장 정규화 — code 는 trim, labelId 연결이면 code 를 null 로 둔다. */
        LabelCodeSpec normalized() {
            if (labelId != null) {
                return new LabelCodeSpec(labelId, null);
            }
            return new LabelCodeSpec(null, code == null ? null : code.trim());
        }
    }
}
