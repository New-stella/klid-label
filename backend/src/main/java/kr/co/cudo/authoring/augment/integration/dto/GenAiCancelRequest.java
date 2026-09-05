package kr.co.cudo.authoring.augment.integration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 「생성형 AI API 연동명세서 v1.3」 §4.4 작업 취소 요청 본문 — INT-031.
 *
 * <p>{@code POST /api/genai/jobs/{job_id}/cancel}. {@code RECEIVED}·{@code RUNNING} 에서만 성립하며
 * 종결 상태에서는 409 {@code STATE_CONFLICT} 다.
 *
 * <p>{@code requested_by} 는 <b>인증 주체(JWT sub)</b>로만 채운다. 호출부가 자유 문자열을 넣지 못하도록
 * {@code AugmentCancelCommand} 가 {@code TokenClaims} 를 받아 이 값을 파생시킨다 — 취소는 감사 대상
 * 행위라 "누가 눌렀는지" 를 클라이언트가 지어내면 안 된다.
 *
 * @param reason      취소 사유(선택, ≤500). 빈 값이면 필드를 아예 보내지 않는다.
 * @param requestedBy 요청자 식별자(필수, ≤64) — 토큰 sub
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenAiCancelRequest(
        @JsonProperty("reason") String reason,
        @JsonProperty("requested_by") String requestedBy) {
}
