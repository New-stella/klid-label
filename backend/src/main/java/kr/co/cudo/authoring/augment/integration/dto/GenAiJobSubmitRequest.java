package kr.co.cudo.authoring.augment.integration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 「생성형 AI API 연동명세서 v1.1」 §4.1 {@code POST /api/genai/jobs} 요청 바디.
 *
 * <p>필드명은 외부 계약(snake_case)을 그대로 따른다. 미선언 필드는 보내지 않는다.
 *
 * @param requestId      우리가 발급한 멱등 키(1~64)
 * @param requestChannel 요청 채널 — 저작도구는 {@code AUTHORING}
 * @param requestUserId  요청자(≤64, 선택)
 * @param evntType       이벤트 유형(1~20)
 * @param operationType  작업 유형 — 증강은 {@code AUGMENT}
 * @param generationMode 생성 모드 — 이미지→이미지 증강이므로 {@code I2I}
 * @param inputFiles     입력 파일(I2I 는 1건 이상 필수, 최대 100)
 * @param prompt         생성·변형 조건 구조화 메타(필수)
 * @param callbackUrl    결과 회신 URL(≤500, 선택)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenAiJobSubmitRequest(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("request_channel") String requestChannel,
        @JsonProperty("request_user_id") String requestUserId,
        @JsonProperty("evnt_type") String evntType,
        @JsonProperty("operation_type") String operationType,
        @JsonProperty("generation_mode") String generationMode,
        @JsonProperty("input_files") List<GenAiInputFile> inputFiles,
        @JsonProperty("prompt") Map<String, Object> prompt,
        @JsonProperty("callback_url") String callbackUrl) {

    /** 요청 채널 — 저작도구 고정. */
    public static final String CHANNEL_AUTHORING = "AUTHORING";
    /** 작업 유형 — 증강 고정. */
    public static final String OPERATION_AUGMENT = "AUGMENT";
    /** 생성 모드 — 증강 AI 는 이미지-to-이미지(프레임 이미지만 변환)라 I2I 고정. */
    public static final String MODE_I2I = "I2I";
}
