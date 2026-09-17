package kr.co.cudo.authoring.webhook.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import kr.co.cudo.authoring.support.VlmKlidLiveFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시계열 분석 결과 콜백 DTO 계약 검증 — 확정 계약(KLID 연동 API v1.1.0) 정합.
 *
 * <p>성공: {@code {request_id, status:"completed", results:{description}}} — <b>단일 객체</b>.
 * <p>실패: {@code {request_id, status:"failed", error:"..."}} — error 는 <b>문자열</b>이다.
 *
 * <p>구 규격의 구간 배열({@code results:[{start_sec,end_sec,description}]})과 판정 항목
 * ({@code detected}/{@code accuracy})은 모두 폐기됐다 — 판정 항목은 우리가 연동하지 않는
 * 판정 창구 전용이라 애초에 오지 않는다(§2.8).
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
        VlmResultRequest req = completed(new VlmResultRequest.Results("한 남성이 전봇대 옆에서 쓰러진 상태로 확인됩니다."));

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
    @DisplayName("★판정항목이_섞여와도_수신을_거부하지_않는다_구_accuracy_검증_폐기")
    void unknownResultFieldsAreIgnored() throws Exception {
        // given — 구 계약은 accuracy 의 범위·자릿수를 여기서 강제했다(그 값이 검수큐를 거치지 않아
        //   서버 검증이 유일한 방어선이었기 때문). 이제 그 값은 우리 연동에 오지 않으므로 규율 대상이
        //   사라졌고, 남은 질문은 "섞여 오면 어떻게 하는가" 하나다.
        //
        //   답은 <b>무시</b>다. 여기서 400 을 내면 그 결과는 영영 유실된다 — 재전송은 3회로 끝나고
        //   결과를 다시 받을 수 있는 조회 API 가 없다(§5.1). 되받을 수 없는 입구에서의 엄격함은
        //   곧 손실이다.
        String json = """
                {
                  "request_id": "00000001",
                  "status": "completed",
                  "results": {"detected": true, "accuracy": 0.8, "description": "서술"}
                }
                """;

        // when
        VlmResultRequest req = MAPPER.readValue(json, VlmResultRequest.class);

        // then
        assertThat(req.results().description()).isEqualTo("서술");
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("description이_공백이면_400")
    void blankDescription_violates() {
        VlmResultRequest req = completed(new VlmResultRequest.Results("   "));

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("description"));
    }

    @Test
    @DisplayName("description이_2000자를_초과하면_400_자동_절단_없음")
    void descriptionOverMaxLength_violates() {
        VlmResultRequest req = completed(new VlmResultRequest.Results("가".repeat(2001)));

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
                ("Video VLM inference failed"));

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
                new VlmResultRequest.Results("서술"), null);

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
                new VlmResultRequest.Results("서술"), null);

        Set<ConstraintViolation<VlmResultRequest>> violations = validator.validate(req);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.contains("requestId"));
    }

    @Test
    @DisplayName("★콜백_completed_역직렬화_정상이고_판정항목은_무시된다")
    void deserialize_completedSnakeCase() throws Exception {
        // 규격 §2.8 상 판정 항목(detected/accuracy)은 판정 창구 전용이라 우리 두 창구에는 오지
        // 않는다. 그럼에도 섞여 오면 <b>조용히 무시</b>되어야 한다 — 알 수 없는 키로 400 을 내면
        // 벤더가 필드를 하나 더 붙이는 것만으로 결과 수신이 통째로 끊긴다.
        String json = """
                {
                  "request_id": "00000001",
                  "status": "completed",
                  "results": {
                    "detected": true,
                    "accuracy": 0.8,
                    "description": "한 남성이 전봇대 옆에서 쓰러진 상태로 확인됩니다."
                  }
                }
                """;

        VlmResultRequest req = MAPPER.readValue(json, VlmResultRequest.class);

        assertThat(req.requestId()).isEqualTo("00000001");
        assertThat(req.status()).isEqualTo("completed");
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

    /**
     * 사업자 실서버가 돌려준 콜백 본문 원문 4건(2026-09-14)을 그대로 역직렬화·검증한다.
     * 출처·보존 규칙은 {@code fixtures/vlm-klid-live-20260914/README.md}. [design: INTSPEC-002]
     *
     * <p>사업자가 필드 이름·상태값·서술 길이를 바꾸면 여기서 먼저 깨진다. 서술은 역직렬화를 거쳐도
     * 원문 JSON 트리의 문자열과 <b>정확히 같아야</b> 한다(마크다운 기호·줄끝 공백 포함).
     */
    @Test
    @DisplayName("실응답_콜백_본문_4건은_역직렬화와_검증을_통과하고_서술이_원문과_같다")
    void liveCallbackBodiesAreAccepted() throws Exception {
        for (String file : VlmKlidLiveFixtures.ALL) {
            VlmResultRequest req = MAPPER.readValue(VlmKlidLiveFixtures.body(file), VlmResultRequest.class);

            assertThat(req.status()).as(file).isEqualTo("completed");
            assertThat(req.requestId()).as(file).startsWith("LIVE-");
            assertThat(req.error()).as(file).isNull();
            assertThat(req.results().description()).as(file)
                    .isEqualTo(VlmKlidLiveFixtures.description(file))
                    .hasSizeLessThanOrEqualTo(VlmResultRequest.Results.MAX_DESCRIPTION_LENGTH);
            assertThat(validator.validate(req)).as(file).isEmpty();
        }
    }

    @Test
    @DisplayName("★실패_콜백의_error는_문자열이다_구_객체형태_폐기")
    void deserialize_failedSnakeCase() throws Exception {
        // 규격 §2.7 — error 는 객체가 아니라 문자열이다. 구 규격의 {code, message} 객체를
        // 되살리면 실패 콜백이 전량 400 이 되어 실패 사실 자체를 받지 못한다.
        String json = """
                {
                  "request_id": "00000001",
                  "status": "failed",
                  "error": "추론 실패: Video VLM inference failed"
                }
                """;

        VlmResultRequest req = MAPPER.readValue(json, VlmResultRequest.class);

        assertThat(req.requestId()).isEqualTo("00000001");
        assertThat(req.status()).isEqualTo("failed");
        assertThat(req.error()).isEqualTo("추론 실패: Video VLM inference failed");
        assertThat(validator.validate(req)).isEmpty();
    }
}
