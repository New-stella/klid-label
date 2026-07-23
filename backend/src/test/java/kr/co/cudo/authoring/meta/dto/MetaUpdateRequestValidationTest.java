package kr.co.cudo.authoring.meta.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MetaUpdateRequest 빈 검증 — META_VL(길이 2000) 정합.
 * BE @Size(max=2000) 로 초과 입력을 400 으로 거부(절단 아님).
 */
class MetaUpdateRequestValidationTest {

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
    @DisplayName("META_VL_2000초과_입력은_거부")
    void metaVal_2000_초과_거부() {
        // given — 2001 자
        String tooLong = "a".repeat(2001);
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item("manual-timeseries", tooLong)));

        // when
        Set<ConstraintViolation<MetaUpdateRequest>> violations = validator.validate(req);

        // then — metaVal 길이 위반 검출
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("metaVal"));
    }

    @Test
    @DisplayName("META_VL_2000_이하는_통과")
    void metaVal_2000_이하_통과() {
        // given — 정확히 2000 자
        String maxLen = "a".repeat(2000);
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item("manual-timeseries", maxLen)));

        // when
        Set<ConstraintViolation<MetaUpdateRequest>> violations = validator.validate(req);

        // then — metaVal 관련 위반 없음
        assertThat(violations).noneMatch(v -> v.getPropertyPath().toString().contains("metaVal"));
    }
}
