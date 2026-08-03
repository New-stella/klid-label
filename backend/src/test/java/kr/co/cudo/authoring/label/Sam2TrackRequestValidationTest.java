package kr.co.cudo.authoring.label;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code Sam2TrackRequest} Bean Validation — <b>요청 축</b> 회귀 가드 (CWE-20).
 *
 * <p>요청 {@code prevPolygon} 의 최소 정점 수는 DTO {@code @Size(min = 3)} 이 담당하며 컨트롤러
 * {@code @Valid} 가 이를 <b>400</b> 으로 변환한다. 반면 <b>응답</b> 폴리곤의 최소 정점 수 위반은
 * {@code Sam2CoordinateValidator.validateResponseMinPoints} 가 <b>502</b>(외부 시스템이 잘못 준 것)
 * 로 낸다 — 두 축이 섞이지 않음을 고정한다.
 */
class Sam2TrackRequestValidationTest {

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

    private Sam2TrackRequest req(List<List<Double>> prevPolygon) {
        return new Sam2TrackRequest(1L, "track-1", prevPolygon, "car", List.of(2L));
    }

    @Test
    @DisplayName("SAM2_track_요청_prevPolygon_2점이면_400")
    void prevPolygonTooFewPointsRejected() {
        // given / when — 정점 2개(폐곡선 불성립).
        var violations = validator.validate(req(List.of(List.of(1.0, 1.0), List.of(2.0, 2.0))));

        // then — 요청 축은 Bean Validation → 400 (502 아님).
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("prevPolygon"));
    }

    @Test
    @DisplayName("SAM2_track_요청_prevPolygon_3점이면_통과")
    void prevPolygonThreePointsAccepted() {
        var violations = validator.validate(
                req(List.of(List.of(1.0, 1.0), List.of(2.0, 2.0), List.of(3.0, 3.0))));

        assertThat(violations).noneMatch(v -> v.getPropertyPath().toString().equals("prevPolygon"));
    }
}
