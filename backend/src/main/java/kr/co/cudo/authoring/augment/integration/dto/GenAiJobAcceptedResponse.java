package kr.co.cudo.authoring.augment.integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 「생성형 AI API 연동명세서 v1.3」 §4.1 응답(202).
 *
 * <p>외부는 신뢰 영역 밖이므로 {@code request_id} echo 일치 · {@code status=RECEIVED} ·
 * {@code job_id} 존재를 호출부에서 검증한다.
 *
 * @param requestId  우리가 보낸 request_id echo
 * @param jobId      <b>외부가 발급한</b> 작업 식별자
 * @param status     접수 상태(RECEIVED)
 * @param receivedAt 접수 시각(외부 표기 문자열)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenAiJobAcceptedResponse(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("job_id") String jobId,
        @JsonProperty("status") String status,
        @JsonProperty("received_at") String receivedAt) {
}
