package kr.co.cudo.authoring.sysconfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1: ConfigKeys 화이트리스트 + NUMBER_RANGE 검증.
 * <p>
 * YOLO 정확도 개선을 위해 운영 UI 로 조정 가능해야 하는
 * 3개 신규 키 (YOLO_CONF_THRESHOLD / YOLO_IMGSZ / YOLO_IOU) 가
 * 화이트리스트와 범위 매핑에 포함되는지 확인한다.
 */
class ConfigKeysTest {

    @Test
    @DisplayName("ConfigKeys_에_YOLO_CONF_IMGSZ_IOU_세_키가_화이트리스트에_포함")
    void allowedContainsYoloKeys() {
        assertThat(ConfigKeys.ALLOWED)
                .contains(ConfigKeys.YOLO_CONF_THRESHOLD,
                          ConfigKeys.YOLO_IMGSZ,
                          ConfigKeys.YOLO_IOU);
    }

    @Test
    @DisplayName("ConfigKeys_NUMBER_RANGE_가_각_키별로_유효한_범위")
    void numberRangeDefinedForYoloKeys() {
        int[] conf = ConfigKeys.NUMBER_RANGE.get(ConfigKeys.YOLO_CONF_THRESHOLD);
        assertThat(conf).isNotNull();
        assertThat(conf).containsExactly(25, 80);

        int[] imgsz = ConfigKeys.NUMBER_RANGE.get(ConfigKeys.YOLO_IMGSZ);
        assertThat(imgsz).isNotNull();
        assertThat(imgsz).containsExactly(320, 1920);

        int[] iou = ConfigKeys.NUMBER_RANGE.get(ConfigKeys.YOLO_IOU);
        assertThat(iou).isNotNull();
        assertThat(iou).containsExactly(30, 80);
    }

    @Test
    @DisplayName("기존_Batch_키는_그대로_유지")
    void legacyKeysPreserved() {
        assertThat(ConfigKeys.ALLOWED).contains(
                ConfigKeys.BATCH_INTERVAL_SEC,
                ConfigKeys.BATCH_CONCURRENCY);
        assertThat(ConfigKeys.NUMBER_RANGE).containsKeys(
                ConfigKeys.BATCH_INTERVAL_SEC,
                ConfigKeys.BATCH_CONCURRENCY);
    }

    @Test
    @DisplayName("EVENT_EXCLUDED_CLASS_CODES_가_화이트리스트에_포함되고_숫자범위는_없다")
    void allowedContainsEventExcludedClassCodes() {
        assertThat(ConfigKeys.ALLOWED).contains(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES);
        // JSON 타입 키이므로 NUMBER/DECIMAL 범위 매핑 대상이 아니다.
        assertThat(ConfigKeys.NUMBER_RANGE).doesNotContainKey(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES);
        assertThat(ConfigKeys.DECIMAL_RANGE).doesNotContainKey(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES);
    }

    @Test
    @DisplayName("FEAT007_POLYGON_SIMPLIFY_TOLERANCE_가_화이트리스트에_포함")
    void allowedContainsPolygonSimplifyKey() {
        assertThat(ConfigKeys.ALLOWED).contains(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE);
    }

    @Test
    @DisplayName("FEAT007_POLYGON_SIMPLIFY_TOLERANCE_는_DECIMAL_RANGE_0_50")
    void polygonSimplifyDecimalRange() {
        double[] range = ConfigKeys.DECIMAL_RANGE.get(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE);
        assertThat(range).isNotNull();
        assertThat(range).containsExactly(0.0, 50.0);
        // 정수 범위(NUMBER_RANGE)에는 포함되지 않아야 한다 — DECIMAL 키이므로.
        assertThat(ConfigKeys.NUMBER_RANGE).doesNotContainKey(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE);
    }
}
