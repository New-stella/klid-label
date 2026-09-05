package kr.co.cudo.authoring.portal.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3차 QA (CWE-770) — {@code PortalUserLabelRequest.points} 길이 상한 검증.
 *
 * <p>{@code points} 는 좌표 JSON <b>문자열</b>이라 타입 구조로 크기가 제한되지 않고, 적재 컬럼
 * ({@code LS_PORTAL_USER_LABEL.POINT_CN})도 {@code TEXT} 라 무제한이었다. 본문 크기 필터
 * ({@code PortalLabelBodySizeFilter})는 pre-parse 방어이고, 이 검증은 파싱 후 <b>필드 단위</b>
 * 상한이다(두 층은 서로 대체하지 않는다).
 *
 * <p>컨트롤러 {@code @Valid} 가 이 위반을 400 으로 변환한다.
 */
class PortalUserLabelRequestValidationTest {

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

    private static String pointsJson(int length) {
        return "[" + "1".repeat(Math.max(length - 2, 0)) + "]";
    }

    @Test
    @DisplayName("points_필드가_과도하게_크면_400_검증오류를_반환한다")
    void oversizedPointsRejected() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(
                100L, 10L, "POLYGON", "person",
                pointsJson(PortalUserLabelRequest.MAX_POINTS_LENGTH + 1), null, null);

        Set<ConstraintViolation<PortalUserLabelRequest>> violations = validator.validate(req);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("points"));
    }

    @Test
    @DisplayName("상한_이내_points_는_통과한다")
    void withinLimitPointsAccepted() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(
                100L, 10L, "POLYGON", "person",
                pointsJson(PortalUserLabelRequest.MAX_POINTS_LENGTH), null, null);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("정상_BBOX_좌표는_위반이_없다")
    void normalBboxAccepted() {
        PortalUserLabelRequest req =
                new PortalUserLabelRequest(100L, 10L, "BBOX", "person", "[[1,2],[3,4]]", null, null);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("points_가_공백이면_NotBlank_위반이다")
    void blankPointsRejected() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "BBOX", "person", "  ", null, null);

        assertThat(validator.validate(req))
                .anyMatch(v -> v.getPropertyPath().toString().equals("points"));
    }
}
