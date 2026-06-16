package kr.co.cudo.authoring.dev.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AutolabelTestRequest} eventTypeCd 검증 — SoT 6 종만 허용.
 */
class AutolabelTestRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVT_FALL", "EVT_VIOLENCE", "EVT_ACCIDENT", "EVT_ABNORMAL", "EVT_FLOOD", "EVT_FIRE"})
    @DisplayName("SoT_6_종은_eventTypeCd_검증_통과")
    void allow_six(String code) {
        AutolabelTestRequest req = sample(code);
        Set<ConstraintViolation<AutolabelTestRequest>> v = validator.validate(req);
        // eventTypeCd 관련 위반이 없어야 한다 (다른 필드는 모두 정상값)
        assertThat(v).noneMatch(c -> c.getPropertyPath().toString().equals("eventTypeCd"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVT_TRASH", "EVT_OTHER", "FALL", "evt_fall", "FOO_BAR"})
    @DisplayName("정의되지_않은_코드는_eventTypeCd_검증_실패")
    void reject_others(String code) {
        AutolabelTestRequest req = sample(code);
        Set<ConstraintViolation<AutolabelTestRequest>> v = validator.validate(req);
        assertThat(v)
                .anyMatch(c -> c.getPropertyPath().toString().equals("eventTypeCd"));
    }

    @Test
    @DisplayName("빈_문자열_eventTypeCd는_NotBlank_위반")
    void reject_blank() {
        AutolabelTestRequest req = sample("");
        Set<ConstraintViolation<AutolabelTestRequest>> v = validator.validate(req);
        assertThat(v)
                .anyMatch(c -> c.getPropertyPath().toString().equals("eventTypeCd"));
    }

    @Test
    @DisplayName("정상_필드_조합은_검증_통과_위반_없음")
    void valid_request_passes() {
        AutolabelTestRequest req = sample("EVT_FALL");
        Set<ConstraintViolation<AutolabelTestRequest>> v = validator.validate(req);
        assertThat(v).isEmpty();
    }

    private AutolabelTestRequest sample(String eventTypeCd) {
        return new AutolabelTestRequest(
                "TEST-CLIP-001",
                "CCTV-001",
                eventTypeCd,
                "11680",
                AutolabelTestRequest.PrvcType.ANONY,
                Instant.parse("2026-05-01T12:00:00Z")
        );
    }
}
