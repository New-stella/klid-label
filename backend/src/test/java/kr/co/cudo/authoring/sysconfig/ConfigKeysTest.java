package kr.co.cudo.authoring.sysconfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1: ConfigKeys 화이트리스트 + NUMBER_RANGE 검증.
 * <p>
 * YOLO 정확도 개선을 위해 운영 UI 로 조정 가능해야 하는
 * 두 키 (YOLO_CONF_THRESHOLD / YOLO_IOU) 가 화이트리스트와 범위 매핑에 포함되는지 확인한다.
 * <p>
 * 구 키 {@code YOLO_IMGSZ} 는 폐지됐다 — ai-server 로더가 입력 크기를 640 으로 고정해
 * 조정이 무효였다. 되살리기 방지 가드는 {@link #imgszKeyIsNotConfigurable()}.
 */
class ConfigKeysTest {

    @Test
    @DisplayName("ConfigKeys_에_YOLO_CONF_IOU_두_키가_화이트리스트에_포함")
    void allowedContainsYoloKeys() {
        assertThat(ConfigKeys.ALLOWED)
                .contains(ConfigKeys.YOLO_CONF_THRESHOLD,
                          ConfigKeys.YOLO_IOU);
    }

    /**
     * ★ 되살리기 방지 가드 — 추론 입력 해상도는 운영자가 조정할 수 있으면 안 된다.
     * <p>
     * ai-server 의 YOLOX 로더가 입력 크기를 {@code (640,640)} 으로 고정해 추론하므로 요청에 실린
     * imgsz 는 로그에만 남는다. 조정 가능한 항목으로 노출하면 정밀도가 오르지 않을 때 운영자가
     * 원인을 이 값에서 찾게 된다. 되살리려면 <b>ai-server 가 요청값을 실제로 쓰도록 먼저 고칠 것.</b>
     * <p>
     * 문자열 리터럴로 단언하는 것은 <b>의도</b>다 — 상수를 지웠으므로 상수 참조로는 이 가드를 쓸 수
     * 없고, 누군가 같은 이름의 키를 다시 등록하면 그 순간 이 테스트가 깨져야 한다.
     */
    @Test
    @DisplayName("★추론_입력_해상도_키는_설정으로_열지_않는다_구_YOLO_IMGSZ_폐지")
    void imgszKeyIsNotConfigurable() {
        assertThat(ConfigKeys.ALLOWED).doesNotContain("YOLO_IMGSZ");
        assertThat(ConfigKeys.NUMBER_RANGE).doesNotContainKey("YOLO_IMGSZ");
        assertThat(ConfigKeys.NUMBER_ALLOWED_VALUES).doesNotContainKey("YOLO_IMGSZ");
        assertThat(ConfigKeys.DECIMAL_RANGE).doesNotContainKey("YOLO_IMGSZ");
    }

    @Test
    @DisplayName("ConfigKeys_NUMBER_RANGE_가_각_키별로_유효한_범위")
    void numberRangeDefinedForYoloKeys() {
        int[] conf = ConfigKeys.NUMBER_RANGE.get(ConfigKeys.YOLO_CONF_THRESHOLD);
        assertThat(conf).isNotNull();
        assertThat(conf).containsExactly(25, 80);

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

    @Test
    @DisplayName("R9_비식별_옵션_3키가_화이트리스트에_포함된다")
    void allowedContainsDeidentOptionKeys() {
        // ALLOWED 미등록이면 update 가 400 으로 거부해 화면에서 저장 자체가 안 된다.
        assertThat(ConfigKeys.ALLOWED).contains(
                ConfigKeys.KPST_DEID_MASKING_TYPE,
                ConfigKeys.KPST_DEID_MASKING_RANGE,
                ConfigKeys.KPST_DEID_DB_SAVE);
    }

    @Test
    @DisplayName("R9_마스킹방식은_범위가_아니라_허용값_0_2_3_이다_1은_벤더_미할당")
    void maskingTypeUsesAllowedValueSetNotRange() {
        assertThat(ConfigKeys.NUMBER_ALLOWED_VALUES.get(ConfigKeys.KPST_DEID_MASKING_TYPE))
                .containsExactlyInAnyOrder(0, 2, 3);
        // ★ NUMBER_RANGE 로 [0,3] 을 등록하면 벤더 미할당 값 1 이 통과한다 — 범위로 두지 않는다.
        assertThat(ConfigKeys.NUMBER_RANGE).doesNotContainKey(ConfigKeys.KPST_DEID_MASKING_TYPE);
    }

    @Test
    @DisplayName("R9_프레임저장여부는_허용값_0_1_이다")
    void dbSaveUsesAllowedValueSet() {
        assertThat(ConfigKeys.NUMBER_ALLOWED_VALUES.get(ConfigKeys.KPST_DEID_DB_SAVE))
                .containsExactlyInAnyOrder(0, 1);
        assertThat(ConfigKeys.NUMBER_RANGE).doesNotContainKey(ConfigKeys.KPST_DEID_DB_SAVE);
    }

    @Test
    @DisplayName("R9_마스킹범위는_DECIMAL_RANGE_0_5_2_0_이다")
    void maskingRangeDecimalRange() {
        double[] range = ConfigKeys.DECIMAL_RANGE.get(ConfigKeys.KPST_DEID_MASKING_RANGE);
        assertThat(range).isNotNull();
        assertThat(range).containsExactly(0.5, 2.0);
        assertThat(ConfigKeys.NUMBER_RANGE).doesNotContainKey(ConfigKeys.KPST_DEID_MASKING_RANGE);
    }

    /**
     * ★구조적 함정 고정 — 세 키가 검증 맵 어딘가에 <b>반드시</b> 등록돼 있어야 한다.
     *
     * <p>{@code SystemConfigService.validateNumberRange}/{@code validateDecimalRange} 는
     * 맵에 키가 없으면 <b>파싱만 통과하면 무제한 허용</b>한다. 즉 <b>등록 누락 = 무검증</b>이라
     * 화면·API 로 아무 값이나 저장돼 외부 위탁에 실린다. 이 테스트가 그 누락을 잡는다.
     */
    @Test
    @DisplayName("R9_세_키_모두_검증맵에_등록돼_있다_미등록이면_무검증_통과가_된다")
    void deidentOptionKeysAreActuallyValidated() {
        assertThat(ConfigKeys.NUMBER_ALLOWED_VALUES).containsKeys(
                ConfigKeys.KPST_DEID_MASKING_TYPE, ConfigKeys.KPST_DEID_DB_SAVE);
        assertThat(ConfigKeys.DECIMAL_RANGE).containsKey(ConfigKeys.KPST_DEID_MASKING_RANGE);
    }
}
