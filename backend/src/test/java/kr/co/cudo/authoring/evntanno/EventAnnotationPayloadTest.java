package kr.co.cudo.authoring.evntanno;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload.CaptionCandidate;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload.EvidenceCandidate;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * event_annotation payload DTO + 검토 상태 머신 단위 테스트 (Spring 컨텍스트 불필요).
 */
class EventAnnotationPayloadTest {

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    @Test
    @DisplayName("EventAnnotationPayload_필수키_event_class_없으면_검증실패")
    void eventClass_missing_fails_validation() {
        // given: event_class 가 null 인 payload
        EventAnnotationPayload payload = new EventAnnotationPayload(
                null, "무슨 일이 일어났나?", Map.of(), "답변", Map.of());

        // when
        var violations = VALIDATOR.validate(payload);

        // then: event_class(@NotBlank) 위반이 잡힌다
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("eventClass"));
    }

    @Test
    @DisplayName("EventAnnotationPayload_필수키_충족시_검증통과_옵셔널키는_느슨")
    void eventClass_present_passes_validation() {
        // given: event_class 만 있고 나머지는 비어도 통과해야 한다(포맷 미확정 — 느슨)
        EventAnnotationPayload payload = new EventAnnotationPayload(
                "LOITERING", null, null, null, null);

        // when
        var violations = VALIDATOR.validate(payload);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("EventAnnotationPayload_caption_evidence_c1cn키객체_cot객체3단계_직렬화_역직렬화_왕복_일치")
    void caption_cot_3steps_roundtrip() {
        // given: caption/evidence 후보 키 객체(c1..cn) + cot 3단계(객체 형태, 정본 샘플) + evidence 구조 포함
        LinkedHashMap<String, String> cot1 = new LinkedHashMap<>();
        cot1.put("1단계", "인물 탐지");
        cot1.put("2단계", "담장 근접 판단");
        cot1.put("3단계", "월담 행위 추론");
        LinkedHashMap<String, String> cot2 = new LinkedHashMap<>();
        cot2.put("1단계", "시간대 야간");
        cot2.put("2단계", "우회 접근");
        cot2.put("3단계", "침입 결론");
        EventAnnotationPayload original = new EventAnnotationPayload(
                "INTRUSION",
                "영상에서 어떤 이상 상황이 발생했는가?",
                Map.of(
                        "c1", new CaptionCandidate("한 사람이 담을 넘는다", cot1),
                        "c2", new CaptionCandidate("야간 침입 정황", cot2)
                ),
                "무단 침입이 발생했다.",
                Map.of(
                        "c1", new EvidenceCandidate(
                                "프레임 10~12 에서 월담",
                                List.of(10, 11, 12),
                                List.of("obj-1"),
                                List.of(List.of(0.1, 0.2, 0.5, 0.8)),
                                List.of("person"))
                ));

        // when: JSON 왕복
        String json = original.toJson();
        EventAnnotationPayload restored = EventAnnotationPayload.fromJson(json);

        // then: 문서 키(caption/evidence/c1..cn) + snake_case + 중첩 cot 3단계(객체)/evidence 무손실
        assertThat(json).contains("event_class").contains("\"caption\"").contains("\"evidence\"")
                .contains("\"c1\"").contains("caption_text").contains("obj_bbox").contains("cot");
        // cot 이 배열이 아니라 객체로 직렬화된다(정본 샘플 {"1단계":..}).
        assertThat(json).contains("\"cot\":{").contains("\"1단계\"").contains("\"3단계\"");
        assertThat(restored.eventClass()).isEqualTo("INTRUSION");
        assertThat(restored.caption()).hasSize(2);
        assertThat(restored.caption().get("c1").cot()).hasSize(3);
        assertThat(restored.caption().get("c1").cot().get("3단계")).isEqualTo("월담 행위 추론");
        assertThat(restored.evidence().get("c1").frameId()).containsExactly(10, 11, 12);
        assertThat(restored.evidence().get("c1").objId()).containsExactly("obj-1");
        assertThat(restored.evidence().get("c1").objBbox().get(0)).containsExactly(0.1, 0.2, 0.5, 0.8);
        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("EventAnnotationPayload_하위호환_과거_배열cot_동결본_역직렬화시_예외없이_객체로_흡수")
    void legacy_array_cot_deserializes_without_exception() {
        // given: 과거 동결본 — cot 이 배열(List<String>) 형태
        String legacyJson = "{\"event_class\":\"assault\","
                + "\"caption\":{\"c1\":{\"caption_text\":\"두 사람이 다툰다\",\"cot\":[\"발단\",\"충돌\",\"제압\"]}}}";

        // when: 신규 Map 필드로 역직렬화(500 방지 — 관용 흡수)
        EventAnnotationPayload restored = EventAnnotationPayload.fromJson(legacyJson);

        // then: 순번 기반 n단계 키로 흡수되어 값이 보존된다
        assertThat(restored.caption().get("c1").cot()).hasSize(3);
        assertThat(restored.caption().get("c1").cot().get("1단계")).isEqualTo("발단");
        assertThat(restored.caption().get("c1").cot().get("3단계")).isEqualTo("제압");
        // 재직렬화 시 객체 형태로 정규화된다
        assertThat(restored.toJson()).contains("\"cot\":{").contains("\"1단계\"");
    }

    @Test
    @DisplayName("EventAnnotationPayload_알수없는_키는_무시하고_역직렬화_성공")
    void unknown_keys_ignored() {
        // given: 미래에 추가될 수 있는 미지의 키 포함(PR#43 키 변경 가능)
        String json = "{\"event_class\":\"FALL\",\"future_new_key\":\"whatever\",\"question\":\"q\"}";

        // when / then: 키 변경에도 깨지지 않는다
        EventAnnotationPayload restored = EventAnnotationPayload.fromJson(json);
        assertThat(restored.eventClass()).isEqualTo("FALL");
        assertThat(restored.question()).isEqualTo("q");
    }

    @Test
    @DisplayName("LsEvntAnnoReview_승인은_PENDING에서만_가능_완료상태면_CONFLICT")
    void review_approve_only_from_pending() {
        // given: PENDING 검토 row
        LsEvntAnnoReview review = LsEvntAnnoReview.createAuto(
                1L, LsEvntAnnoReview.META_TYPE_VLM, LsEvntAnnoReview.STTS_PENDING, "system");

        // when: 승인
        review.approve("reviewer1");

        // then: APPROVED 전이
        assertThat(review.getRvwSttsCd()).isEqualTo(LsEvntAnnoReview.STTS_APPROVED);
        assertThat(review.getRvwId()).isEqualTo("reviewer1");

        // and: 이미 완료된 상태에서 재승인/반려 시 CONFLICT
        assertThatThrownBy(() -> review.approve("reviewer2"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONFLICT);
        assertThatThrownBy(() -> review.reject("reviewer2", "사유"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("LsEvntAnnoReview_반려는_사유필수_AUTO_GENERATED에서_전이")
    void review_reject_requires_reason() {
        // given: AUTO_GENERATED 검토 row
        LsEvntAnnoReview review = LsEvntAnnoReview.createAuto(
                1L, LsEvntAnnoReview.META_TYPE_VLM, null, "system");
        assertThat(review.getRvwSttsCd()).isEqualTo(LsEvntAnnoReview.STTS_AUTO_GENERATED);

        // when / then: 사유 없으면 INVALID_INPUT
        assertThatThrownBy(() -> review.reject("reviewer1", " "))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        // and: 사유 있으면 REJECTED 전이
        review.reject("reviewer1", "근거 부족");
        assertThat(review.getRvwSttsCd()).isEqualTo(LsEvntAnnoReview.STTS_REJECTED);
        assertThat(review.getRjctRsn()).isEqualTo("근거 부족");
    }
}
