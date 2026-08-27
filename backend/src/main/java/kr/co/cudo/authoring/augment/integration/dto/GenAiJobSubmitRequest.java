package kr.co.cudo.authoring.augment.integration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 「생성형 AI API 연동명세서 v1.3」 §4.1 {@code POST /api/genai/jobs} 요청 바디.
 *
 * <p>필드명은 외부 계약(snake_case)을 그대로 따른다. 미선언 필드는 보내지 않는다.
 *
 * <h3>v1.3 — 생성 조건과 자유 지시문을 최상위에서 분리한다</h3>
 * <p>구 계약(v1.1)은 생성 조건 5항목을 {@code prompt} 라는 자유 구조 dict 로 받았다. v1.3 은 그것을
 * 최상위 {@link #mtdt} 로 옮기고 {@link #prompt} 를 <b>자유 지시문 문자열</b>로 재정의했다. 구 객체
 * 형식({@code prompt.condition}/{@code prompt.text})과 최상위 {@code condition} 은 계약에서 제외됐고,
 * 명세가 <i>"prompt 값으로 객체를 전달하지 않습니다"</i> 를 명시하며 {@code prompt} 에 객체를 실으면
 * {@code 400 INVALID_PARAMETER} 다. <b>{@code prompt} 의 타입을 Map 으로 되돌리지 말 것.</b>
 *
 * <p>레코드 컴포넌트 순서가 곧 직렬화 순서다 — 명세서 샘플과 같은 순서로 읽히게 유지한다.
 *
 * @param requestId      우리가 발급한 멱등 키(1~64)
 * @param requestChannel 요청 채널 — 저작도구는 {@code AUTHORING}
 * @param requestUserId  요청자(≤64, 선택)
 * @param evntType       이벤트 유형 — {@code FLOOD}/{@code WILDFIRE}({@link GenAiContract.EventType})
 * @param evntSubtype    침수 세부 유형 — 선택. 침수가 아니면 키 자체를 보내지 않는다
 * @param operationType  작업 유형 — 증강은 {@code AUGMENT}
 * @param generationMode 생성 모드 — 이미지→이미지 증강이므로 {@code I2I}
 * @param inputFiles     입력 파일(I2I 는 1건 이상 필수, 최대 100)
 * @param mtdt           구조화 생성 조건(허용 코드로 닫힌 5항목) — 계약상 최소 1항목, 우리는 전부 채운다
 * @param prompt         자유 지시문 문자열(≤1000, 선택). <b>객체가 아니다</b>
 * @param callbackUrl    결과 회신 URL(≤500, 선택)
 * @design INT-008
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenAiJobSubmitRequest(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("request_channel") String requestChannel,
        @JsonProperty("request_user_id") String requestUserId,
        @JsonProperty("evnt_type") String evntType,
        @JsonProperty("evnt_subtype") String evntSubtype,
        @JsonProperty("operation_type") String operationType,
        @JsonProperty("generation_mode") String generationMode,
        @JsonProperty("input_files") List<GenAiInputFile> inputFiles,
        @JsonProperty("mtdt") Map<String, Object> mtdt,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("callback_url") String callbackUrl) {

    /** 요청 채널 — 저작도구 고정. */
    public static final String CHANNEL_AUTHORING = "AUTHORING";
    /** 작업 유형 — 증강 고정. */
    public static final String OPERATION_AUGMENT = "AUGMENT";
    /** 생성 모드 — 증강 AI 는 이미지-to-이미지(프레임 이미지만 변환)라 I2I 고정. */
    public static final String MODE_I2I = "I2I";
}
