package kr.co.cudo.authoring.webhook.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * VLM 시계열 결과 콜백 계약 명확화 — MetaItem 은 "마킹별 자연어 서술".
 *
 * <p>각 항목은 metaKey(=시계열 정렬 키, frameIndex 권장) → metaVal(=해당 시점 자연어 서술)이다.
 * 자연어 서술이 핵심이므로 빈 metaVal 은 무의미 → {@code @NotBlank} 로 거부한다 (CWE-20 입력 검증 강화).
 */
class VlmResultRequestMetaItemTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void teardown() {
        if (factory != null) factory.close();
    }

    private VlmResultRequest reqWith(List<VlmResultRequest.MetaItem> items) {
        return new VlmResultRequest("K1", "EXT1", "SUCCESS", 1L, items, null);
    }

    @Test
    @DisplayName("MetaItem_metaVal_빈값이면_검증실패")
    void metaValBlank_violates() {
        VlmResultRequest req = reqWith(List.of(new VlmResultRequest.MetaItem("0", "  ")));

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("metaVal"));
    }

    @Test
    @DisplayName("MetaItem_metaVal_자연어_정상통과")
    void metaValNaturalLanguage_passes() {
        VlmResultRequest req = reqWith(List.of(
                new VlmResultRequest.MetaItem("0", "사람이 도로를 무단횡단")));

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("MetaItem_metaKey_빈값이면_검증실패")
    void metaKeyBlank_violates() {
        VlmResultRequest req = reqWith(List.of(new VlmResultRequest.MetaItem("", "사람이 도로를 무단횡단")));

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("metaKey"));
    }

    @Test
    @DisplayName("VlmResultRequest_마킹별_자연어_항목_역직렬화_정상")
    void deserialize_markBasedNaturalLanguageItems() throws Exception {
        String json = """
                {
                  "idempotencyKey": "K1",
                  "externalJobId": "EXT1",
                  "status": "SUCCESS",
                  "rawSn": 1,
                  "vlmMetaItems": [
                    {"metaKey": "0",  "metaVal": "사람이 도로를 무단횡단"},
                    {"metaKey": "90", "metaVal": "차량이 정지선 침범"}
                  ]
                }
                """;

        VlmResultRequest req = MAPPER.readValue(json, VlmResultRequest.class);

        assertThat(req.vlmMetaItems()).hasSize(2);
        assertThat(req.vlmMetaItems().get(0).metaKey()).isEqualTo("0");
        assertThat(req.vlmMetaItems().get(0).metaVal()).isEqualTo("사람이 도로를 무단횡단");
        assertThat(req.vlmMetaItems().get(1).metaKey()).isEqualTo("90");
        assertThat(req.vlmMetaItems().get(1).metaVal()).isEqualTo("차량이 정지선 침범");

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("MetaItem_metaVal_2000자_경계_정상통과")
    void metaValMaxSize_passes() {
        String maxVal = "가".repeat(2000);
        VlmResultRequest req = reqWith(List.of(new VlmResultRequest.MetaItem("0", maxVal)));

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("MetaItem_metaVal_2000자_초과_시_검증실패")
    void metaValOverMaxSize_violates() {
        String overVal = "가".repeat(2001);
        VlmResultRequest req = reqWith(List.of(new VlmResultRequest.MetaItem("0", overVal)));

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("metaVal"));
    }
}
