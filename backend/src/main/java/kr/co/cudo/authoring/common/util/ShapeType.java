package kr.co.cudo.authoring.common.util;

/**
 * CVAT 포팅 — shape 타입 enum.
 * 본 Phase 에서는 RECTANGLE/ELLIPSE/CUBOID 단순 선형 보간만 지원.
 * POLYGON/MASK/SKELETON 은 후속 Phase 대상 (TODO).
 */
public enum ShapeType {
    RECTANGLE,
    POLYGON,
    ELLIPSE,
    CUBOID,
    MASK,
    SKELETON
}
