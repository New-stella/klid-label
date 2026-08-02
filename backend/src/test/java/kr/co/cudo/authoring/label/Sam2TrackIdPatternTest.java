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
 * {@code trackId} 허용 문자 검증 (CWE-117 / CWE-20).
 *
 * <p>BE 는 {@code trackId} 원문을 ai-server 로 그대로 전달하므로, 개행이 섞인 값은 ai-server 로그
 * 라인을 위조할 수 있다(가짜 {@code [AUDIT]} 라인 생성 실증). ai-server 스키마
 * ({@code TRACK_ID_PATTERN})와 <b>같은 규칙</b>을 BE 에서도 강제해 ①입력을 앞단에서 차단하고
 * ②두 계약이 갈라져 클라이언트 입력 오류가 ai-server 400 → BE 502 로 승격되는 것을 막는다.
 */
class Sam2TrackIdPatternTest {

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

    private Sam2TrackRequest req(String trackId) {
        return new Sam2TrackRequest(1L, trackId,
                List.of(List.of(1.0, 1.0), List.of(2.0, 2.0), List.of(3.0, 3.0)),
                "car", List.of(2L));
    }

    @Test
    @DisplayName("trackId에_개행이_섞이면_검증위반")
    void newlineRejected() {
        var violations = validator.validate(req("t-1\r\n[AUDIT] user=admin action=APPROVED_ALL"));

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("trackId"));
    }

    @Test
    @DisplayName("trackId에_공백_한글이_섞이면_검증위반")
    void nonAllowlistCharRejected() {
        assertThat(validator.validate(req("track 1")))
                .anyMatch(v -> v.getPropertyPath().toString().equals("trackId"));
        assertThat(validator.validate(req("트랙1")))
                .anyMatch(v -> v.getPropertyPath().toString().equals("trackId"));
    }

    @Test
    @DisplayName("실제_트랙ID_형식은_통과한다")
    void realWorldTrackIdsAccepted() {
        // 트래커 부여 정수 문자열(YoloTrackService/TrackEditService.nextTrackId) + UUID + 채번 조합.
        for (String id : List.of("7", "12", "track-1", "track_A.1:2-3",
                "550e8400-e29b-41d4-a716-446655440000")) {
            assertThat(validator.validate(req(id)))
                    .as("trackId=%s", id)
                    .noneMatch(v -> v.getPropertyPath().toString().equals("trackId"));
        }
    }
}
