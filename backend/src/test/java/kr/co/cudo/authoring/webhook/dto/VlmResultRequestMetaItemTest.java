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

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VLM <b>verify</b> 콜백 DTO 계약 검증 — 벤더 확정 계약(IntelliVIX Video VLM API v2.0.1) 정합.
 *
 * <p>성공: {@code {request_id, status:"completed", results:{accuracy, description}}} — <b>단일 객체</b>.
 * <p>실패: {@code {request_id, status:"failed", error:{code,message}}} (무변경).
 *
 * <p>구 describe 규격의 {@code results:[{start_sec,end_sec,description}]} <b>배열</b>은 폐기됐다.
 * 검수큐를 거치지 않는 {@code accuracy} 는 <b>서버 검증이 유일한 방어선</b>이므로 범위(0~1 inclusive)·
 * 자릿수 상한을 여기서 강제한다(CWE-20).
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

    private static VlmResultRequest completed(VlmResultRequest.Results results) {
        return new VlmResultRequest("REQ-1", "completed", results, null);
    }

    @Test
    @DisplayName("verify_completed_results_객체_정상_통과")
    void completedWithResults_passes() {
        VlmResultRequest req = completed(new VlmResultRequest.Results(
                new BigDecimal("0.8"), "한 남성이 전봇대 옆에서 쓰러진 상태로 확인됩니다."));

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("completed인데_results가_없으면_400")
    void completedWithoutResults_violates() {
        VlmResultRequest req = completed(null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("resultsPresentWhenCompleted"));
    }

    @Test
    @DisplayName("accuracy가_없으면_통과한다_optional")
    void accuracyOmitted_passes() {
        VlmResultRequest req = completed(new VlmResultRequest.Results(null, "서술"));

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("accuracy가_0이나_1이면_정상_통과한다_경계_inclusive")
    void accuracyBoundaryValues_pass() {
        assertThat(validator.validate(completed(
                new VlmResultRequest.Results(BigDecimal.ZERO, "서술")))).isEmpty();
        assertThat(validator.validate(completed(
                new VlmResultRequest.Results(BigDecimal.ONE, "서술")))).isEmpty();
    }

    @Test
    @DisplayName("accuracy가_범위를_벗어나면_400")
    void accuracyOutOfRange_violates() {
        Set<ConstraintViolation<VlmResultRequest>> negative = validator.validate(completed(
                new VlmResultRequest.Results(new BigDecimal("-0.1"), "서술")));
        Set<ConstraintViolation<VlmResultRequest>> over = validator.validate(completed(
                new VlmResultRequest.Results(new BigDecimal("1.5"), "서술")));

        assertThat(negative)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("accuracy"));
        assertThat(over)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("accuracy"));
    }

    @Test
    @DisplayName("accuracy_자릿수가_과대하면_400_META_VL_길이_방어")
    void accuracyTooManyDigits_violates() {
        BigDecimal absurd = new BigDecimal("0." + "1".repeat(2100));

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(completed(
                new VlmResultRequest.Results(absurd, "서술")));

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("accuracy"));
    }

    @Test
    @DisplayName("description이_공백이면_400")
    void blankDescription_violates() {
        VlmResultRequest req = completed(new VlmResultRequest.Results(new BigDecimal("0.8"), "   "));

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("description"));
    }

    @Test
    @DisplayName("description이_2000자를_초과하면_400_자동_절단_없음")
    void descriptionOverMaxLength_violates() {
        VlmResultRequest req = completed(new VlmResultRequest.Results(null, "가".repeat(2001)));

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("description"));
    }

    @Test
    @DisplayName("failed_error_정상_통과")
    void failedWithError_passes() {
        VlmResultRequest req = new VlmResultRequest(
                "REQ-F", "failed", null,
                new VlmResultRequest.VlmError("INFERENCE_ERROR", "Video VLM inference failed"));

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
                new VlmResultRequest.Results(new BigDecimal("0.8"), "서술"), null);

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
                new VlmResultRequest.Results(new BigDecimal("0.8"), "서술"), null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("requestId"));
    }

    @Test
    @DisplayName("verify_콜백_completed_JSON_역직렬화_정상")
    void deserialize_completedSnakeCase() throws Exception {
        String json = """
                {
                  "request_id": "00000001",
                  "status": "completed",
                  "results": {
                    "accuracy": 0.8,
                    "description": "한 남성이 전봇대 옆에서 쓰러진 상태로 확인됩니다."
                  }
                }
                """;

        VlmResultRequest req = MAPPER.readValue(json, VlmResultRequest.class);

        assertThat(req.requestId()).isEqualTo("00000001");
        assertThat(req.status()).isEqualTo("completed");
        assertThat(req.results().accuracy()).isEqualByComparingTo("0.8");
        assertThat(req.results().description())
                .isEqualTo("한 남성이 전봇대 옆에서 쓰러진 상태로 확인됩니다.");
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("구_describe_배열_results는_역직렬화_자체가_실패한다_관대한_파싱_없음")
    void deserialize_legacyArrayResults_fails() {
        String json = """
                {
                  "request_id": "00000001",
                  "status": "completed",
                  "results": [
                    {"start_sec": 0, "end_sec": 8, "description": "사람이 도로를 무단횡단"}
                  ]
                }
                """;

        // 확정 계약이므로 신·구 양쪽을 받아주지 않는다 — 관대한 파싱은 벤더 버그를 숨긴다.
        assertThatThrownBy(() -> MAPPER.readValue(json, VlmResultRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
    }

    @Test
    @DisplayName("verify_콜백_failed_JSON_역직렬화_정상")
    void deserialize_failedSnakeCase() throws Exception {
        String json = """
                {
                  "request_id": "00000001",
                  "status": "failed",
                  "error": {"code": "INFERENCE_ERROR", "message": "Video VLM inference failed"}
                }
                """;

        VlmResultRequest req = MAPPER.readValue(json, VlmResultRequest.class);

        assertThat(req.requestId()).isEqualTo("00000001");
        assertThat(req.status()).isEqualTo("failed");
        assertThat(req.error()).isNotNull();
        assertThat(req.error().code()).isEqualTo("INFERENCE_ERROR");
        assertThat(validator.validate(req)).isEmpty();
    }
}
