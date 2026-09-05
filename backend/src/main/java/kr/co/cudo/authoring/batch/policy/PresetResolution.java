package kr.co.cudo.authoring.batch.policy;

import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 이벤트 유형 → 오토라벨 프리셋 해석 <b>결과</b>. [@design ADR-054] [@design ADR-019]
 *
 * <p>구 반환형({@code Optional<Map<String, AnnotationToggle>>})은 "왜 비었는가" 를 담지 못해 호출자가
 * 빈 값을 전량 저장으로 폴백했다. 이 레코드는 사유({@link PresetResolutionStatus})를 함께 실어
 * <b>보류 / 오토라벨 제외 / 수행</b> 세 갈래를 호출자가 구분할 수 있게 한다.
 *
 * <p>토글 맵은 {@link PresetResolutionStatus#RESOLVED} 일 때만 비어 있지 않다. 그 밖의 상태는 항상
 * 빈 맵이며, <b>빈 맵을 「전 라벨 허용」으로 읽지 말 것</b> — 그것이 이 변경이 없앤 fail-open 이다.
 *
 * @param status  해석 사유
 * @param toggles 검출 클래스(정규화된 {@code DTCT_TYPE_CD}) → 저장 형태 토글. 삽입 순서 보존, 불변
 */
public record PresetResolution(PresetResolutionStatus status, Map<String, AnnotationToggle> toggles) {

    public PresetResolution {
        Objects.requireNonNull(status, "status");
        // 프리셋 코드 순서(sortOrder ASC)가 판독에 의미가 있으므로 삽입 순서를 보존한다(Map.copyOf 는 잃는다).
        toggles = toggles == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(toggles));
    }

    /** 실효 결과 — 토글 맵이 비어 있으면 계약 위반이다(그 경우는 {@link PresetResolutionStatus#PRESET_UNMAPPED}). */
    public static PresetResolution resolved(Map<String, AnnotationToggle> toggles) {
        if (toggles == null || toggles.isEmpty()) {
            throw new IllegalArgumentException("resolved preset must carry at least one toggle");
        }
        return new PresetResolution(PresetResolutionStatus.RESOLVED, toggles);
    }

    /** 실효하지 않은 결과 — 토글 맵은 비어 있다. */
    public static PresetResolution of(PresetResolutionStatus status) {
        if (status == PresetResolutionStatus.RESOLVED) {
            throw new IllegalArgumentException("RESOLVED must be created via resolved(toggles)");
        }
        return new PresetResolution(status, Map.of());
    }

    /** 오토라벨을 수행해도 되는가 — 저장 기준(토글 맵)이 실재한다. */
    public boolean isResolved() {
        return status == PresetResolutionStatus.RESOLVED;
    }

    /**
     * 오토라벨 <b>제외 선언</b> 인가 — 라벨을 하나도 담지 않은 프리셋. [@design AC-119]
     *
     * <p>보류가 아니다. 오토라벨 묶음만 건너뛰고 배치는 완료로 마감하며 재개 대상도 아니다.
     */
    public boolean isAutolabelExcluded() {
        return status == PresetResolutionStatus.PRESET_EMPTY;
    }

    /**
     * 보류 대상인가 — 실효도 아니고 제외 선언도 아닌 나머지 전부. [@design AC-113] [@design AC-114]
     *
     * <p>여기에 해당하면 오토라벨 묶음을 <b>시작하지 않고</b> 배치를 완료로 마감하지 않는다.
     * 사람이 프리셋을 채우면 재개된다.
     */
    public boolean isWithheld() {
        return !isResolved() && !isAutolabelExcluded();
    }
}
