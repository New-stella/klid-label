package kr.co.cudo.authoring.webhook.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 보강 (DEV_FIX M-3) — Webhook 요청 DTO 의 컬렉션 상한 검증.
 *
 * <p>CWE-770 Allocation of Resources Without Limits 차단 — 외부 시스템이 비정상적으로 큰 배열을
 * 보내 메모리/DB 자원을 소진시키는 공격을 방어한다.
 */
class WebhookRequestSizeLimitTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void teardown() {
        if (factory != null) factory.close();
    }

    @Test
    @DisplayName("VlmResultRequest_vlmMetaItems_500_초과_시_검증_실패")
    void vlmMetaItemsOver500_violates() {
        List<VlmResultRequest.MetaItem> items = new ArrayList<>(501);
        for (int i = 0; i < 501; i++) {
            items.add(new VlmResultRequest.MetaItem("k" + i, "v"));
        }
        VlmResultRequest req = new VlmResultRequest(
                "K1", "EXT1", "SUCCESS", 1L, items, null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("vlmMetaItems");
    }

    @Test
    @DisplayName("AugmentResultRequest_labelMapping_1000_초과_시_검증_실패")
    void labelMappingOver1000_violates() {
        List<AugmentResultRequest.LabelMap> maps = new ArrayList<>(1001);
        for (int i = 0; i < 1001; i++) {
            maps.add(new AugmentResultRequest.LabelMap((long) i, (long) i, "AUTO"));
        }
        AugmentResultRequest req = new AugmentResultRequest(
                "K1", "EXT1", "SUCCESS", 1L, "WINTER", null, maps);

        Set<ConstraintViolation<AugmentResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("labelMapping");
    }

    @Test
    @DisplayName("DeidentifyResultRequest_processedRegions_1000_초과_시_검증_실패")
    void processedRegionsOver1000_violates() {
        List<DeidentifyResultRequest.ProcessedRegion> regions = new ArrayList<>(1001);
        for (int i = 0; i < 1001; i++) {
            regions.add(new DeidentifyResultRequest.ProcessedRegion("F" + i, "BOX", 0, 0, 10, 10));
        }
        DeidentifyResultRequest req = new DeidentifyResultRequest(
                "K1", "EXT1", "SUCCESS", 1L, null, regions);

        Set<ConstraintViolation<DeidentifyResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("processedRegions");
    }
}
