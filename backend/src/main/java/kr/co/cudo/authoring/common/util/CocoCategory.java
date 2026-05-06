package kr.co.cudo.authoring.common.util;

/**
 * COCO categories[] 항목 (1-based id).
 *
 * <p>CVAT YOLO/COCO 변환 포팅(portable-modules/07).
 * Adapted from CVAT (https://github.com/cvat-ai/cvat) dataset_manager/formats — MIT.
 */
public record CocoCategory(int id, String name, String supercategory) {
}
