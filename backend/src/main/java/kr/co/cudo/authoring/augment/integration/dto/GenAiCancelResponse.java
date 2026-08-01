package kr.co.cudo.authoring.augment.integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 「생성형 AI API 연동명세서 v1.1」 §4.6 작업 취소 응답(200) — INT-031.
 *
 * <p>취소는 계약상 <b>웹훅을 발사하지 않는</b> 동기 응답이다(호출자가 결과를 이미 안다). 그래서
 * {@code status} 가 {@code CANCELED} 인지, {@code job_id} echo 가 우리가 지목한 job 인지를
 * 클라이언트가 fail-closed 로 확인한다 — 여기서 잘못 접수하면 다른 job 을 취소했다고 믿게 된다.
 *
 * @param requestId  우리가 발급한 request_id echo (필수)
 * @param jobId      취소된 job_id echo (필수)
 * @param status     항상 {@code CANCELED} (필수)
 * @param canceledAt 취소 시각(필수, 외부 표기 문자열)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenAiCancelResponse(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("job_id") String jobId,
        @JsonProperty("status") String status,
        @JsonProperty("canceled_at") String canceledAt) {
}
