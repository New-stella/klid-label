package kr.co.cudo.authoring.common.util;

/**
 * COCO images[] 항목.
 *
 * <p>CVAT YOLO/COCO 변환 포팅(portable-modules/07).
 */
public record CocoImage(int id, String fileName, int width, int height, int license) {

    public static CocoImage of(int id, String fileName, int width, int height) {
        return new CocoImage(id, fileName, width, height, 0);
    }
}
