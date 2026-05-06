package kr.co.cudo.authoring.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10 — CVAT 품질 충돌 감지 포팅 테스트 (portable-modules/09).
 */
class QualityConflictDetectorTest {

    @Test
    @DisplayName("품질_충돌_감지_MISSING_EXTRA_LOW_OVERLAP_분류")
    void detectsAllConflictTypes() {
        // GT: car (정확), person (정확), bicycle (작업자 누락)
        List<QualityConflictDetector.LabelShape> gt = List.of(
                new QualityConflictDetector.LabelShape(1L, "car",
                        List.of(new Point(100.0, 100.0), new Point(200.0, 200.0))),
                new QualityConflictDetector.LabelShape(2L, "person",
                        List.of(new Point(300.0, 300.0), new Point(350.0, 400.0))),
                new QualityConflictDetector.LabelShape(3L, "bicycle",
                        List.of(new Point(800.0, 800.0), new Point(900.0, 900.0)))
        );
        // DS: car (IoU ~0.85, valid), person 위치는 비슷하지만 라벨이 dog (mismatching),
        //     LOW_OVERLAP car 와 거리만 다른 box, GT 에 없는 truck (extra)
        List<QualityConflictDetector.LabelShape> ds = List.of(
                // car valid (IoU 충분)
                new QualityConflictDetector.LabelShape(10L, "car",
                        List.of(new Point(105.0, 105.0), new Point(205.0, 205.0))),
                // person ↔ dog (mismatching label)
                new QualityConflictDetector.LabelShape(11L, "dog",
                        List.of(new Point(295.0, 295.0), new Point(355.0, 405.0))),
                // GT 에 없는 truck (extra)
                new QualityConflictDetector.LabelShape(12L, "truck",
                        List.of(new Point(500.0, 500.0), new Point(600.0, 600.0)))
        );

        List<AnnotationConflict> conflicts = QualityConflictDetector.detect(gt, ds);

        // 분류:
        //  - car (gt 1) ↔ car (ds 10) IoU=0.85 → LOW_OVERLAP (low_overlap_threshold=0.8 미달)? 0.85 > 0.8 이라 통과 → 충돌 없음
        //  - person (gt 2) ↔ dog (ds 11) → MISMATCHING_LABEL
        //  - bicycle (gt 3) → MISSING (DS 매칭 없음)
        //  - truck (ds 12) → EXTRA (GT 매칭 없음)
        assertThat(conflicts).hasSize(3);

        assertThat(conflicts).anySatisfy(c -> {
            assertThat(c.type()).isEqualTo(ConflictType.MISMATCHING_LABEL);
            assertThat(c.labelIdA()).isEqualTo(2L);
            assertThat(c.labelIdB()).isEqualTo(11L);
        });
        assertThat(conflicts).anySatisfy(c -> {
            assertThat(c.type()).isEqualTo(ConflictType.MISSING);
            assertThat(c.labelIdA()).isEqualTo(3L);
            assertThat(c.labelIdB()).isNull();
        });
        assertThat(conflicts).anySatisfy(c -> {
            assertThat(c.type()).isEqualTo(ConflictType.EXTRA);
            assertThat(c.labelIdA()).isNull();
            assertThat(c.labelIdB()).isEqualTo(12L);
        });
    }

    @Test
    @DisplayName("완전_동일_라벨_충돌_없음")
    void noConflictWhenIdentical() {
        List<QualityConflictDetector.LabelShape> gt = List.of(
                new QualityConflictDetector.LabelShape(1L, "car",
                        List.of(new Point(100.0, 100.0), new Point(200.0, 200.0))),
                new QualityConflictDetector.LabelShape(2L, "person",
                        List.of(new Point(300.0, 300.0), new Point(350.0, 400.0)))
        );
        List<QualityConflictDetector.LabelShape> ds = List.of(
                new QualityConflictDetector.LabelShape(10L, "car",
                        List.of(new Point(100.0, 100.0), new Point(200.0, 200.0))),
                new QualityConflictDetector.LabelShape(11L, "person",
                        List.of(new Point(300.0, 300.0), new Point(350.0, 400.0)))
        );

        List<AnnotationConflict> conflicts = QualityConflictDetector.detect(gt, ds);

        assertThat(conflicts).isEmpty();
    }

    @Test
    @DisplayName("LOW_OVERLAP_warning_low_overlap_threshold_미달시_분류")
    void lowOverlapDetected() {
        // GT 와 DS 동일 라벨, 위치 어긋나서 IoU 가 0.4 ~ 0.8 사이 (warning)
        List<QualityConflictDetector.LabelShape> gt = List.of(
                new QualityConflictDetector.LabelShape(1L, "car",
                        List.of(new Point(0.0, 0.0), new Point(100.0, 100.0)))
        );
        // (50,0)-(150,100) → 교집합 50x100=5000, 합집합 100x100+100x100-5000=15000 → IoU=0.333
        // ↑ 0.4 미만 → 매칭 안됨 → MISSING+EXTRA
        // 더 가까이: (30,0)-(130,100) → 교집합 70x100=7000, 합집합 100x100*2-7000=13000 → IoU=0.538
        List<QualityConflictDetector.LabelShape> ds = List.of(
                new QualityConflictDetector.LabelShape(10L, "car",
                        List.of(new Point(30.0, 0.0), new Point(130.0, 100.0)))
        );

        List<AnnotationConflict> conflicts = QualityConflictDetector.detect(gt, ds);

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).type()).isEqualTo(ConflictType.LOW_OVERLAP);
        assertThat(conflicts.get(0).iou()).isBetween(0.4, 0.8);
    }
}
