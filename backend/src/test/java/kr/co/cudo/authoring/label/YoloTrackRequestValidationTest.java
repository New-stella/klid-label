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
    @DisplayName("nextSrcSns_비어있어도_통과한다_시작_프레임만_처리하는_정상_요청이다")
    void emptyNextSrcSns() {
        // ★ 구 규칙 «비어있으면 검증실패»(@NotEmpty) 폐기 — 서버가 발행하는 이어 보내기 값이
        //   남은 프레임 하나일 때 이 목록이 비므로, 거부하면 서버가 스스로 받지 못할 값을
        //   발행하는 상태가 된다(그 요청이 400 이 되면 이미 계산한 검출이 통째로 버려진다).
        //   성질 자체는 TrackResumeRequestContractTest 가 서비스 발행값으로 고정한다.
        var req = new YoloTrackRequest(1L, List.of());
        var violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("nextSrcSns_null이면_검증실패")
    void nullNextSrcSns() {
        // 빈 목록은 허용해도 null 은 거부한다 — 서비스가 이 목록을 순회하므로 계약을 흐리지 않는다.
        var req = new YoloTrackRequest(1L, null);
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
