package kr.co.cudo.authoring.common.util;

/**
 * Phase 10 — 어노테이션 품질 충돌 유형 (CVAT portable-modules/09).
 *
 * <p>전체 8종 중 V1 범위 4종만 채택:
 * <ul>
 *   <li>{@link #MISSING}            — GT 에 있는데 작업자가 누락</li>
 *   <li>{@link #EXTRA}              — 작업자가 추가했는데 GT 에 없음</li>
 *   <li>{@link #MISMATCHING_LABEL}  — 매칭은 됐지만 라벨명 다름</li>
 *   <li>{@link #LOW_OVERLAP}        — IoU < low_overlap_threshold (warning)</li>
 * </ul>
 *
 * <p>Adapted from CVAT (https://github.com/cvat-ai/cvat) apps/quality_control — MIT.
 */
public enum ConflictType {
    MISSING,
    EXTRA,
    MISMATCHING_LABEL,
    LOW_OVERLAP
}
