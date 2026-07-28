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
    @DisplayName("VlmResultRequest_results_500_초과_시_검증_실패")
    void vlmResultsOver500_violates() {
        List<VlmResultRequest.Segment> items = new ArrayList<>(501);
        for (int i = 0; i < 501; i++) {
            items.add(new VlmResultRequest.Segment(i, i + 1, "v"));
        }
        VlmResultRequest req = new VlmResultRequest("REQ-1", "completed", items, null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("results");
    }

    @Test
    @DisplayName("GenAiCallbackRequest_results_100_초과_시_검증_실패")
    void genAiResultsOver100_violates() {
        List<GenAiCallbackRequest.ResultItem> items = new ArrayList<>(101);
        for (int i = 0; i < 101; i++) {
            items.add(new GenAiCallbackRequest.ResultItem(
                    "g" + i, "IMAGE", "/nas-storage/genai/j/" + i + ".jpg", null));
        }
        GenAiCallbackRequest req = new GenAiCallbackRequest(
                "AUG-1", "job-1", "SUCCEEDED", 100, "COMPLETED", "t", items, null, null);

        Set<ConstraintViolation<GenAiCallbackRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("results");
    }

    @Test
    @DisplayName("GenAiCallbackRequest_status_화이트리스트_밖은_검증_거부")
    void genAiUnknownStatus_violates() {
        GenAiCallbackRequest req = new GenAiCallbackRequest(
                "AUG-1", "job-1", "CANCELED", null, null, "t", null, null, null);

        Set<ConstraintViolation<GenAiCallbackRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("status");
    }

    @Test
    @DisplayName("GenAiCallbackRequest_output_file_path_500_초과_시_검증_실패")
    void genAiOutputPathTooLong_violates() {
        String longPath = "/nas-storage/genai/" + "a".repeat(500);
        GenAiCallbackRequest req = new GenAiCallbackRequest(
                "AUG-1", "job-1", "SUCCEEDED", 100, "COMPLETED", "t",
                List.of(new GenAiCallbackRequest.ResultItem("g1", "IMAGE", longPath, null)),
                null, null);

        Set<ConstraintViolation<GenAiCallbackRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(path -> path.endsWith("outputFilePath"));
    }

    // (UC018 — 비식별은 KPST 폴링으로 단일화되어 DeidentifyResultRequest 콜백 DTO 가 제거됨.
    //  관련 processedRegions 상한 검증 케이스도 함께 제거. VLM·증강 상한 검증은 위에서 유지.)
}
