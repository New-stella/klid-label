package kr.co.cudo.authoring.common.util;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10 — CVAT YOLO/COCO 변환 포팅 테스트 (portable-modules/07).
 */
class YoloCocoConverterTest {

    private static final Offset<Double> EPS_PIXEL = Offset.offset(0.5);

    @Test
    @DisplayName("YOLO_좌표_변환_정확도_픽셀_정규화")
    void yoloCoordinateNormalizationCorrect() {
        // 1920x1080 이미지의 BBOX (100, 200) - (300, 400)
        // cx=(100+300)/(2*1920)=0.104167, cy=(200+400)/(2*1080)=0.277778
        // w=200/1920=0.104167, h=200/1080=0.185185
        List<YoloCocoConverter.YoloShape> shapes = List.of(
                new YoloCocoConverter.YoloShape(0,
                        List.of(new Point(100.0, 200.0), new Point(300.0, 400.0)))
        );
        String txt = YoloCocoConverter.toYolo(shapes, 1920, 1080);
        // 한 줄, 5개 토큰
        String[] tokens = txt.split("\\s+");
        assertThat(tokens).hasSize(5);
        assertThat(Integer.parseInt(tokens[0])).isEqualTo(0);
        assertThat(Double.parseDouble(tokens[1])).isCloseTo(0.104167, Offset.offset(1e-6));
        assertThat(Double.parseDouble(tokens[2])).isCloseTo(0.277778, Offset.offset(1e-6));
        assertThat(Double.parseDouble(tokens[3])).isCloseTo(0.104167, Offset.offset(1e-6));
        assertThat(Double.parseDouble(tokens[4])).isCloseTo(0.185185, Offset.offset(1e-6));
    }

    @Test
    @DisplayName("COCO_JSON_스키마_검증_통과_categories_images_annotations_필드_존재")
    void cocoJsonSchemaValid() {
        CocoImage image = CocoImage.of(1, "frame_000001.jpg", 1920, 1080);
        List<CocoCategory> categories = List.of(
                new CocoCategory(1, "person", ""),
                new CocoCategory(2, "car", "")
        );
        List<YoloCocoConverter.CocoShape> shapes = List.of(
                new YoloCocoConverter.CocoShape(1,
                        List.of(new Point(100.0, 200.0), new Point(300.0, 400.0))),
                new YoloCocoConverter.CocoShape(2,
                        List.of(new Point(500.0, 500.0), new Point(700.0, 500.0), new Point(600.0, 700.0)))
        );

        CocoJson coco = YoloCocoConverter.toCoco(shapes, image, categories);

        assertThat(coco.images()).hasSize(1);
        assertThat(coco.images().get(0).fileName()).isEqualTo("frame_000001.jpg");
        assertThat(coco.images().get(0).width()).isEqualTo(1920);

        assertThat(coco.categories()).hasSize(2);
        assertThat(coco.categories().get(0).id()).isEqualTo(1);

        assertThat(coco.annotations()).hasSize(2);
        // BBOX [xtl, ytl, w, h] = [100, 200, 200, 200]
        assertThat(coco.annotations().get(0).bbox()).containsExactly(100.0, 200.0, 200.0, 200.0);
        assertThat(coco.annotations().get(0).area()).isEqualTo(40000.0);
        // BBOX 의 segmentation 은 4점 폴리곤 (시계방향)
        assertThat(coco.annotations().get(0).segmentation().get(0))
                .containsExactly(100.0, 200.0, 300.0, 200.0, 300.0, 400.0, 100.0, 400.0);
        assertThat(coco.annotations().get(0).iscrowd()).isEqualTo(0);
        // POLYGON: bbox 외접, segmentation 은 원본 점 그대로
        assertThat(coco.annotations().get(1).bbox()).containsExactly(500.0, 500.0, 200.0, 200.0);
        assertThat(coco.annotations().get(1).segmentation().get(0))
                .containsExactly(500.0, 500.0, 700.0, 500.0, 600.0, 700.0);
    }

    @Test
    @DisplayName("YOLO_양방향_변환_무결성_round_trip_100회_random")
    void yoloRoundTripIntegrity() {
        Random rng = new Random(42);
        int imgW = 1920;
        int imgH = 1080;
        int iterations = 100;

        for (int n = 0; n < iterations; n++) {
            List<YoloCocoConverter.YoloShape> original = new ArrayList<>();
            int boxCount = 1 + rng.nextInt(5); // 한 이미지에 1~5개
            for (int i = 0; i < boxCount; i++) {
                double xtl = rng.nextDouble() * (imgW - 100);
                double ytl = rng.nextDouble() * (imgH - 100);
                double w = 50 + rng.nextDouble() * (imgW - xtl - 50);
                double h = 50 + rng.nextDouble() * (imgH - ytl - 50);
                int cls = rng.nextInt(10);
                original.add(new YoloCocoConverter.YoloShape(cls,
                        List.of(new Point(xtl, ytl), new Point(xtl + w, ytl + h))));
            }
            String yolo = YoloCocoConverter.toYolo(original, imgW, imgH);
            List<YoloCocoConverter.YoloShape> back = YoloCocoConverter.fromYolo(yolo, imgW, imgH);
            assertThat(back).hasSize(original.size());
            for (int i = 0; i < original.size(); i++) {
                YoloCocoConverter.YoloShape o = original.get(i);
                YoloCocoConverter.YoloShape r = back.get(i);
                assertThat(r.classId()).isEqualTo(o.classId());
                // 6자리 정규화 인코딩 round-off → 0.5px 오차 이내
                assertThat(r.points().get(0).x()).isCloseTo(o.points().get(0).x(), EPS_PIXEL);
                assertThat(r.points().get(0).y()).isCloseTo(o.points().get(0).y(), EPS_PIXEL);
                assertThat(r.points().get(1).x()).isCloseTo(o.points().get(1).x(), EPS_PIXEL);
                assertThat(r.points().get(1).y()).isCloseTo(o.points().get(1).y(), EPS_PIXEL);
            }
        }
    }
}
