package kr.co.cudo.authoring.label;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import kr.co.cudo.authoring.label.dto.YoloTrackRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YoloTrackRequest Bean Validation 검증.
 * 컨트롤러 @Valid 가 이 위반을 400 으로 변환한다 (CWE-770 / CWE-20).
 */
class YoloTrackRequestValidationTest {

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
    @DisplayName("nextSrcSns_50개초과_요청은_검증실패")
    void tooManyNextSrcSns() {
        List<Long> next = new ArrayList<>();
        for (long i = 0; i < 51; i++) {
            next.add(i);
        }
        var req = new YoloTrackRequest(1L, next);
        var violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("nextSrcSns"));
    }

    @Test
    @DisplayName("nextSrcSns_비어있으면_검증실패")
    void emptyNextSrcSns() {
        var req = new YoloTrackRequest(1L, List.of());
        var violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("nextSrcSns"));
    }

    @Test
    @DisplayName("srcSn_null이면_검증실패")
    void nullSrcSn() {
        var req = new YoloTrackRequest(null, List.of(2L));
        var violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("srcSn"));
    }

    @Test
    @DisplayName("정상_요청은_검증_통과")
    void validRequest() {
        var req = new YoloTrackRequest(1L, List.of(2L, 3L));
        var violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }
}
