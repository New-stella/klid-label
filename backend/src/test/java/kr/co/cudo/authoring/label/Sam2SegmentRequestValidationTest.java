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
}
