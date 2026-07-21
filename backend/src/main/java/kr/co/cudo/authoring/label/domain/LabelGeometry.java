package kr.co.cudo.authoring.label.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * 라벨 형태(geometry) 파생 규칙 — 라벨 마스터 {@code LBL_TYPE_CD} → 오토라벨 도형 토글.
 *
 * <p>형태는 라벨 마스터({@code LS_LABEL.LBL_TYPE_CD})가 단일 소유(SoT)한다. 프리셋·오토라벨 등
 * 소비자는 형태 값을 스냅샷으로 복제하지 않고 본 enum 으로 <b>파생(강제)</b>한다(매직값 금지).
 *
 * <ul>
 *   <li>{@code BBOX}     → bbox=true,  polygon=false</li>
 *   <li>{@code POLYGON}  → bbox=false, polygon=true</li>
 *   <li>{@code POINT}    → bbox=false, polygon=false (오토라벨 도형 미적용)</li>
 *   <li>{@code SKELETON} → bbox=false, polygon=false (COCO-17 keypoint 별도 경로)</li>
 * </ul>
 *
 * <p>Phase 2 — 프리셋 조회 응답의 형태 토글 파생에 사용한다. Phase 3(오토라벨 도형 분기)에서도
 * 동일 규칙을 재사용한다.
 */
public enum LabelGeometry {

    BBOX(true, false),
    POLYGON(false, true),
    POINT(false, false),
    SKELETON(false, false);

    private final boolean bboxEnabled;
    private final boolean polygonEnabled;

    LabelGeometry(boolean bboxEnabled, boolean polygonEnabled) {
        this.bboxEnabled = bboxEnabled;
        this.polygonEnabled = polygonEnabled;
    }

    public boolean bboxEnabled() {
        return bboxEnabled;
    }

    public boolean polygonEnabled() {
        return polygonEnabled;
    }

    /**
     * 라벨 타입 코드 문자열을 형태 규칙으로 해석한다. 대소문자/공백 무시.
     *
     * @param labelTypeCd 라벨 마스터 {@code LBL_TYPE_CD} (예: "BBOX")
     * @return 매칭되는 형태, 미지원/null/blank 이면 {@link Optional#empty()}
     */
    public static Optional<LabelGeometry> from(String labelTypeCd) {
        if (labelTypeCd == null) {
            return Optional.empty();
        }
        String normalized = labelTypeCd.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        for (LabelGeometry geometry : values()) {
            if (geometry.name().equals(normalized)) {
                return Optional.of(geometry);
            }
        }
        return Optional.empty();
    }
}
