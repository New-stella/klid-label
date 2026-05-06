package kr.co.cudo.authoring.common.util;

/**
 * Phase 10 — 단일 충돌 결과 (CVAT portable-modules/09 포팅).
 *
 * <p>{@code labelIdA}: GT 측 라벨 ID (없으면 null — EXTRA 의 경우)
 * <p>{@code labelIdB}: DS 측 라벨 ID (없으면 null — MISSING 의 경우)
 * <p>{@code iou}: 매칭된 경우 0~1, 매칭 실패는 null
 */
public record AnnotationConflict(
        ConflictType type,
        Long labelIdA,
        Long labelIdB,
        Double iou
) {
}
