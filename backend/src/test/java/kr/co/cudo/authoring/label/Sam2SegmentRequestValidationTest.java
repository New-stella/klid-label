package kr.co.cudo.authoring.label;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 — Sam2SegmentRequest Bean Validation 검증 (points/box 배타, CWE-20).
 * 컨트롤러 @Valid 가 이 위반을 400 으로 변환한다.
 */
class Sam2SegmentRequestValidationTest {

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
    @DisplayName("포인트와_박스_모두_없으면_400")
    void neitherPointsNorBox() {
        var req = new Sam2SegmentRequest(1L, null, null);
        var violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("exactlyOnePrompt"));
    }

    @Test
    @DisplayName("동시_전달시_400")
    void bothPointsAndBox() {
        var req = new Sam2SegmentRequest(1L,
                List.of(List.of(10.0, 10.0)),
                List.of(5.0, 5.0, 40.0, 40.0));
        var violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("exactlyOnePrompt"));
    }

    @Test
    @DisplayName("포인트만_있으면_검증_통과")
    void onlyPointsValid() {
        var req = new Sam2SegmentRequest(1L, List.of(List.of(10.0, 10.0)), null);
        var violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("박스만_있으면_검증_통과")
    void onlyBoxValid() {
        var req = new Sam2SegmentRequest(1L, null, List.of(5.0, 5.0, 40.0, 40.0));
        var violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    // ── FEAT-007: 경계 세밀함(simplifyTolerance) 범위 검증 0.0~50.0 (CWE-20) ────────

    @Test
    @DisplayName("simplifyTolerance_범위밖60_요청시_400")
    void simplifyToleranceAboveMaxRejected() {
        var req = new Sam2SegmentRequest(1L, List.of(List.of(10.0, 10.0)), null, 60.0);
        var violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("simplifyTolerance"));
    }

    @Test
    @DisplayName("simplifyTolerance_범위내10_검증_통과")
    void simplifyToleranceWithinRangeValid() {
        var req = new Sam2SegmentRequest(1L, List.of(List.of(10.0, 10.0)), null, 10.0);
        var violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("simplifyTolerance_null이면_검증_통과_하위호환")
    void simplifyToleranceNullValid() {
        var req = new Sam2SegmentRequest(1L, List.of(List.of(10.0, 10.0)), null, null);
        var violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("simplifyTolerance_경계값_0.0과_50.0은_검증통과")
    void simplifyToleranceBoundariesValid() {
        // @DecimalMin(0.0)/@DecimalMax(50.0) 는 경계 포함 — 하한/상한 정확히 유효.
        var lo = new Sam2SegmentRequest(1L, List.of(List.of(10.0, 10.0)), null, 0.0);
        var hi = new Sam2SegmentRequest(1L, List.of(List.of(10.0, 10.0)), null, 50.0);
        assertThat(validator.validate(lo)).isEmpty();
        assertThat(validator.validate(hi)).isEmpty();
    }

    @Test
    @DisplayName("simplifyTolerance_음수요청시_검증위반")
    void simplifyToleranceNegativeRejected() {
        // 하한 위반 — @DecimalMin(0.0).
        var req = new Sam2SegmentRequest(1L, List.of(List.of(10.0, 10.0)), null, -1.0);
        var violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("simplifyTolerance"));
    }
}
