package kr.co.cudo.authoring.common.util;

import java.util.List;

/**
 * COCO annotations[] 항목.
 *
 * <p>bbox = [x, y, w, h] (xywh, 픽셀 절대값). category_id 는 1-based.
 * segmentation 은 polygon 만 지원 (RLE 미지원 — 본 포팅 범위 외).
 *
 * <p>CVAT YOLO/COCO 변환 포팅(portable-modules/07).
 */
public record CocoAnnotation(
        int id,
        int imageId,
        int categoryId,
        List<Double> bbox,
        double area,
        List<List<Double>> segmentation,
        int iscrowd
) {
}
