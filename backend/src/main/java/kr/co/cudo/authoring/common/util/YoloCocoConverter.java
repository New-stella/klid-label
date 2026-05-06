package kr.co.cudo.authoring.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 10 — CVAT YOLO/COCO 변환 포팅 (portable-modules/07).
 *
 * <p>입출력 단순화:
 * <ul>
 *   <li>YOLO: BBOX (xtl, ytl, xbr, ybr) → "{class} {cx} {cy} {w} {h}" (정규화 0~1)</li>
 *   <li>COCO: BBOX/POLYGON → categories/images/annotations 트리</li>
 *   <li>POLYGON → YOLO 변환 시 외접 BBOX (정보 손실 — Ultralytics segmentation 는 별도)</li>
 * </ul>
 *
 * <p>좌표 검증 (CWE-20): 음수 좌표/0 이하 폭/이미지 경계 초과는 호출자에서 사전 차단.
 * 본 변환기는 [0, 1] 클램핑은 하지 않음 (정확한 무결성 round-trip 검증 위해).
 *
 * <p>Adapted from CVAT (https://github.com/cvat-ai/cvat) dataset_manager/formats — MIT.
 */
public final class YoloCocoConverter {

    private static final int YOLO_DECIMAL_DIGITS = 6;
    private static final String YOLO_FORMAT = "%d %." + YOLO_DECIMAL_DIGITS + "f %." + YOLO_DECIMAL_DIGITS
            + "f %." + YOLO_DECIMAL_DIGITS + "f %." + YOLO_DECIMAL_DIGITS + "f";

    private YoloCocoConverter() {}

    // ============================================================
    //  YOLO export — BBOX/POLYGON → "{class_id} {cx} {cy} {w} {h}"
    // ============================================================

    /**
     * 어노테이션 리스트를 YOLO classic 한 이미지의 .txt 본문으로 직렬화.
     * 한 라벨당 1줄, 정규화 6자리 소수점.
     *
     * @param shapes  변환 대상 라벨 리스트
     * @param imgW    이미지 폭(px)
     * @param imgH    이미지 높이(px)
     */
    public static String toYolo(List<YoloShape> shapes, int imgW, int imgH) {
        if (imgW <= 0 || imgH <= 0) {
            throw new IllegalArgumentException("이미지 크기는 양수여야 합니다: w=" + imgW + ", h=" + imgH);
        }
        if (shapes == null || shapes.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < shapes.size(); i++) {
            YoloShape s = shapes.get(i);
            double[] bbox = boundingBox(s.points());
            double xtl = bbox[0];
            double ytl = bbox[1];
            double xbr = bbox[2];
            double ybr = bbox[3];
            double cx = (xtl + xbr) / (2.0 * imgW);
            double cy = (ytl + ybr) / (2.0 * imgH);
            double w = (xbr - xtl) / (double) imgW;
            double h = (ybr - ytl) / (double) imgH;
            if (i > 0) {
                out.append('\n');
            }
            out.append(String.format(Locale.ROOT, YOLO_FORMAT, s.classId(), cx, cy, w, h));
        }
        return out.toString();
    }

    /**
     * YOLO classic .txt 본문 → BBOX 리스트 (역변환).
     * Round-trip 무결성: cx/cy/w/h 정규화 인코딩 한도(6자리 소수점) 내에서 일치.
     */
    public static List<YoloShape> fromYolo(String yoloTxt, int imgW, int imgH) {
        if (imgW <= 0 || imgH <= 0) {
            throw new IllegalArgumentException("이미지 크기는 양수여야 합니다: w=" + imgW + ", h=" + imgH);
        }
        List<YoloShape> result = new ArrayList<>();
        if (yoloTxt == null || yoloTxt.isBlank()) {
            return result;
        }
        String[] lines = yoloTxt.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 5) {
                throw new IllegalArgumentException("YOLO 라인은 5개 토큰 필요: " + trimmed);
            }
            int cls;
            double cx;
            double cy;
            double w;
            double h;
            try {
                cls = Integer.parseInt(parts[0]);
                cx = Double.parseDouble(parts[1]);
                cy = Double.parseDouble(parts[2]);
                w = Double.parseDouble(parts[3]);
                h = Double.parseDouble(parts[4]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("YOLO 토큰 파싱 실패: " + trimmed, e);
            }
            double xtl = (cx - w / 2.0) * imgW;
            double ytl = (cy - h / 2.0) * imgH;
            double xbr = (cx + w / 2.0) * imgW;
            double ybr = (cy + h / 2.0) * imgH;
            result.add(new YoloShape(cls,
                    List.of(new Point(xtl, ytl), new Point(xbr, ybr))));
        }
        return result;
    }

    // ============================================================
    //  COCO export — BBOX/POLYGON → categories/images/annotations
    // ============================================================

    /**
     * 단일 이미지의 라벨을 COCO JSON 트리로 직렬화.
     * category_id 는 1-based 로 호출자가 카테고리 매핑(이름→id)을 미리 결정해 주입.
     */
    public static CocoJson toCoco(List<CocoShape> shapes, CocoImage image, List<CocoCategory> categories) {
        if (image == null) {
            throw new IllegalArgumentException("CocoImage 는 필수입니다.");
        }
        if (categories == null || categories.isEmpty()) {
            throw new IllegalArgumentException("categories 는 비어있을 수 없습니다.");
        }
        List<CocoAnnotation> anns = new ArrayList<>();
        if (shapes != null) {
            int nextId = 1;
            for (CocoShape s : shapes) {
                double[] bbox = boundingBox(s.points());
                double xtl = bbox[0];
                double ytl = bbox[1];
                double xbr = bbox[2];
                double ybr = bbox[3];
                double w = xbr - xtl;
                double h = ybr - ytl;
                double area = w * h;

                List<List<Double>> seg;
                if (s.points().size() == 2) {
                    // BBOX → 4점 폴리곤 (좌상단 → 우상단 → 우하단 → 좌하단)
                    seg = List.of(List.of(xtl, ytl, xbr, ytl, xbr, ybr, xtl, ybr));
                } else {
                    List<Double> flat = new ArrayList<>(s.points().size() * 2);
                    for (Point p : s.points()) {
                        flat.add(p.x());
                        flat.add(p.y());
                    }
                    seg = List.of(flat);
                }
                anns.add(new CocoAnnotation(
                        nextId++,
                        image.id(),
                        s.categoryId(),
                        List.of(xtl, ytl, w, h),
                        area,
                        seg,
                        0));
            }
        }
        return new CocoJson(List.of(image), anns, categories);
    }

    // ============================================================
    //  내부 — 점 리스트 → 외접 BBOX (xtl, ytl, xbr, ybr)
    // ============================================================

    private static double[] boundingBox(List<Point> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("points 가 비어있습니다.");
        }
        double xMin = Double.POSITIVE_INFINITY;
        double yMin = Double.POSITIVE_INFINITY;
        double xMax = Double.NEGATIVE_INFINITY;
        double yMax = Double.NEGATIVE_INFINITY;
        for (Point p : points) {
            if (p.x() < xMin) xMin = p.x();
            if (p.y() < yMin) yMin = p.y();
            if (p.x() > xMax) xMax = p.x();
            if (p.y() > yMax) yMax = p.y();
        }
        return new double[]{xMin, yMin, xMax, yMax};
    }

    // ============================================================
    //  값 객체
    // ============================================================

    /**
     * YOLO 입력 어노테이션. classId 0-based + 점 리스트(2점=BBOX, ≥3=Polygon 외접 BBOX 사용).
     */
    public record YoloShape(int classId, List<Point> points) {
    }

    /**
     * COCO 입력 어노테이션. categoryId 1-based + 점 리스트.
     */
    public record CocoShape(int categoryId, List<Point> points) {
    }
}
