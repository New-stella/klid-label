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
 * VLM describe 콜백 DTO 계약 검증 — 벤더 확정 계약(v2.0.1) 정합.
 *
 * <p>성공: {@code {request_id, status:"completed", results:[{start_sec,end_sec,description}]}}
 * <p>실패: {@code {request_id, status:"failed", error:{code,message}}}
 * <p>completed↔results / failed↔error 상호 조건은 {@code @AssertTrue} 로 강제한다 (CWE-20).
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

    @Test
    @DisplayName("completed_results_정상_통과")
    void completedWithResults_passes() {
        VlmResultRequest req = new VlmResultRequest(
                "REQ-1", "completed",
                List.of(new VlmResultRequest.Segment(0, 8, "사람이 도로를 무단횡단")),
                null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("completed인데_results_빈배열이면_검증실패")
    void completedWithEmptyResults_violates() {
        VlmResultRequest req = new VlmResultRequest("REQ-1", "completed", List.of(), null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("resultsPresentWhenCompleted"));
    }

    @Test
    @DisplayName("failed_error_정상_통과")
    void failedWithError_passes() {
        VlmResultRequest req = new VlmResultRequest(
                "REQ-F", "failed", null,
                new VlmResultRequest.VlmError("VLM_TIMEOUT", "분석 지연"));

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("failed인데_error_누락이면_검증실패")
    void failedWithoutError_violates() {
        VlmResultRequest req = new VlmResultRequest("REQ-F", "failed", null, null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("errorPresentWhenFailed"));
    }

    @Test
    @DisplayName("status_화이트리스트_밖이면_검증실패")
    void invalidStatus_violates() {
        VlmResultRequest req = new VlmResultRequest(
                "REQ-1", "SUCCESS",
                List.of(new VlmResultRequest.Segment(0, 8, "서술")),
                null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("status"));
    }

    @Test
    @DisplayName("request_id_빈값이면_검증실패")
    void blankRequestId_violates() {
        VlmResultRequest req = new VlmResultRequest(
                "  ", "completed",
                List.of(new VlmResultRequest.Segment(0, 8, "서술")),
                null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("requestId"));
    }

    @Test
    @DisplayName("Segment_description_빈값이면_검증실패")
    void blankDescription_violates() {
        VlmResultRequest req = new VlmResultRequest(
                "REQ-1", "completed",
                List.of(new VlmResultRequest.Segment(0, 8, "  ")),
                null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("description"));
    }

    @Test
    @DisplayName("Segment_start_sec_음수면_검증실패")
    void negativeStartSec_violates() {
        VlmResultRequest req = new VlmResultRequest(
                "REQ-1", "completed",
                List.of(new VlmResultRequest.Segment(-1, 8, "서술")),
                null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("startSec"));
    }

    @Test
    @DisplayName("Segment_end_sec가_start_sec보다_작으면_검증실패_역전구간")
    void endBeforeStart_violates() {
        VlmResultRequest req = new VlmResultRequest(
                "REQ-1", "completed",
                List.of(new VlmResultRequest.Segment(16, 8, "역전 구간")),
                null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("rangeOrdered"));
    }

    @Test
    @DisplayName("Segment_end_sec_상한_초과시_검증실패")
    void endSecOverMax_violates() {
        VlmResultRequest req = new VlmResultRequest(
                "REQ-1", "completed",
                List.of(new VlmResultRequest.Segment(0, (int) (VlmResultRequest.Segment.MAX_SEC + 1), "과대 구간")),
                null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("endSec"));
    }

    @Test
    @DisplayName("describe_콜백_completed_JSON_역직렬화_정상")
    void deserialize_completedSnakeCase() throws Exception {
        String json = """
                {
                  "request_id": "REQ-1",
                  "status": "completed",
                  "results": [
                    {"start_sec": 0, "end_sec": 8,  "description": "사람이 도로를 무단횡단"},
                    {"start_sec": 8, "end_sec": 16, "description": "차량이 정지선 침범"}
                  ]
                }
                """;

        VlmResultRequest req = MAPPER.readValue(json, VlmResultRequest.class);

        assertThat(req.requestId()).isEqualTo("REQ-1");
        assertThat(req.status()).isEqualTo("completed");
        assertThat(req.results()).hasSize(2);
        assertThat(req.results().get(0).startSec()).isEqualTo(0);
        assertThat(req.results().get(0).endSec()).isEqualTo(8);
        assertThat(req.results().get(0).metaKey()).isEqualTo("0-8");
        assertThat(req.results().get(1).metaKey()).isEqualTo("8-16");

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("describe_콜백_failed_JSON_역직렬화_정상")
    void deserialize_failedSnakeCase() throws Exception {
        String json = """
                {
                  "request_id": "REQ-F",
                  "status": "failed",
                  "error": {"code": "VLM_TIMEOUT", "message": "분석 서버 응답 지연"}
                }
                """;

        VlmResultRequest req = MAPPER.readValue(json, VlmResultRequest.class);

        assertThat(req.requestId()).isEqualTo("REQ-F");
        assertThat(req.status()).isEqualTo("failed");
        assertThat(req.error()).isNotNull();
        assertThat(req.error().code()).isEqualTo("VLM_TIMEOUT");

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }
}
