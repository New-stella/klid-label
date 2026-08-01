package kr.co.cudo.authoring.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관제 인입 <b>단건</b> 재큐 응답 (설계 §6-0-1-b).
 *
 * @param rcptnSn        재큐한 인입 행 식별자
 * @param requeued       재큐된 행 수(성공 시 항상 1 — 조건부 UPDATE 결과를 그대로 노출한다)
 * @param remainingFailed 재큐 후 남아 있는 종결(FAILED) 인입 행 수 — 일괄 재큐가 더 필요한지 판단용
 */
@Schema(description = "관제 인입 단건 재큐 응답")
public record ControlIngestRequeueResponse(
        @Schema(description = "인입 행 식별자(수신일련번호)", example = "1024") Long rcptnSn,
        @Schema(description = "재큐된 행 수", example = "1") int requeued,
        @Schema(description = "재큐 후 남은 FAILED 인입 행 수", example = "37") long remainingFailed) {
}
