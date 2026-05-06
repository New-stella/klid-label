package kr.co.cudo.authoring.common.util;

import java.util.List;

/**
 * COCO instances JSON 최상위 스키마.
 *
 * <p>CVAT YOLO/COCO 변환 포팅(portable-modules/07).
 *
 * <p>info / licenses 는 단순 placeholder. categories[].id 는 1-based.
 */
public record CocoJson(
        List<CocoImage> images,
        List<CocoAnnotation> annotations,
        List<CocoCategory> categories
) {
}
