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
 * {@link AutolabelTestRequest} eventTypeCd 검증 — 관제 EV-코드 형식(EV + 숫자 8자리)만 허용.
 *
 * <p>Phase 5: dev 업로드 도구는 관제 마스터 상세 EV-코드를 받아 오토라벨 프리셋 매칭 흐름을
 * 시험한다. 따라서 @Pattern 은 형식만 1차 가드(EV[0-9]{8})하고, 실제 관제 등록 여부는
 * 서비스(EventTypeService.categoryKeyOf)에서 검증한다. 구 EVT_* 코드는 더 이상 허용하지 않는다.
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
    @ValueSource(strings = {"EV02000201", "EV05000101", "EV03000101", "EV05000701", "EV01000101", "EV02000101"})
    @DisplayName("관제_EV코드_형식은_eventTypeCd_검증_통과")
    void allow_ev_codes(String code) {
        AutolabelTestRequest req = sample(code);
        Set<ConstraintViolation<AutolabelTestRequest>> v = validator.validate(req);
        // eventTypeCd 관련 위반이 없어야 한다 (다른 필드는 모두 정상값)
        assertThat(v).noneMatch(c -> c.getPropertyPath().toString().equals("eventTypeCd"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVT_FALL", "EVT_ABNORMAL", "EV0200020", "EV020002012", "EV0200020A", "ev02000201", "FOO_BAR"})
    @DisplayName("구_EVT_코드와_형식위반은_eventTypeCd_검증_실패")
    void reject_invalid_format(String code) {
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
        AutolabelTestRequest req = sample("EV02000201");
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
