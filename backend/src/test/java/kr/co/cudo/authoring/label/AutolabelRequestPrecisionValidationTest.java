package kr.co.cudo.authoring.label;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import kr.co.cudo.authoring.label.dto.AutolabelRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-007 — AutolabelRequest 정밀도 override(confThreshold/simplifyTolerance) 범위 검증 (CWE-20).
 *
 * <p>인식 민감도(conf) 0.25~0.80, 경계 세밀함(simplify) 0.0~50.0 을 벗어나면 컨트롤러 {@code @Valid}
 * 단계에서 400(MethodArgumentNotValid). 미지정(null)은 통과(시스템설정 폴백 신호).
 */
class AutolabelRequestPrecisionValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    @DisplayName("confThreshold_범위밖0.9_요청시_400")
    void confThresholdAboveMaxRejected() {
        var req = new AutolabelRequest(null, null, 0.9, null);
        var violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("confThreshold"));
    }

    @Test
    @DisplayName("confThreshold_범위밖0.1_요청시_400")
    void confThresholdBelowMinRejected() {
        var req = new AutolabelRequest(null, null, 0.1, null);
        var violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("confThreshold"));
    }

    @Test
    @DisplayName("simplifyTolerance_범위밖60_요청시_400")
    void simplifyToleranceAboveMaxRejected() {
        var req = new AutolabelRequest(null, null, null, 60.0);
        var violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("simplifyTolerance"));
    }

    @Test
    @DisplayName("정밀도_범위내값은_검증_통과")
    void withinRangeValid() {
        var req = new AutolabelRequest(null, null, 0.5, 10.0);
        var violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("정밀도_미지정_null이면_검증_통과_하위호환")
    void nullPrecisionValid() {
        var req = new AutolabelRequest(null, null, null, null);
        var violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("confThreshold_경계값_0.25와_0.80은_검증통과")
    void confThresholdBoundariesValid() {
        // @DecimalMin/@DecimalMax 는 경계 포함(inclusive) — 하한/상한 정확히 유효.
        assertThat(validator.validate(new AutolabelRequest(null, null, 0.25, null))).isEmpty();
        assertThat(validator.validate(new AutolabelRequest(null, null, 0.80, null))).isEmpty();
    }

    @Test
    @DisplayName("simplifyTolerance_경계값_0.0과_50.0은_검증통과")
    void simplifyToleranceBoundariesValid() {
        assertThat(validator.validate(new AutolabelRequest(null, null, null, 0.0))).isEmpty();
        assertThat(validator.validate(new AutolabelRequest(null, null, null, 50.0))).isEmpty();
    }

    @Test
    @DisplayName("simplifyTolerance_음수요청시_검증위반")
    void simplifyToleranceNegativeRejected() {
        // 하한 위반 — @DecimalMin(0.0).
        var violations = validator.validate(new AutolabelRequest(null, null, null, -1.0));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("simplifyTolerance"));
    }
}
