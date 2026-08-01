package kr.co.cudo.authoring.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관제 인입 <b>일괄</b> 재큐 응답 (설계 §6-0-1-b).
 *
 * @param requeued        이번 호출로 실제 재큐된 행 수
 * @param limit           이번 호출에 적용된 상한
 * @param remainingFailed 재큐 후 남아 있는 종결(FAILED) 행 수 — 0 이 아니면 재호출로 이어서 회수한다
 */
@Schema(description = "관제 인입 일괄 재큐 응답")
public record ControlIngestRequeueBulkResponse(
        @Schema(description = "재큐된 행 수", example = "100") int requeued,
        @Schema(description = "적용된 상한", example = "100") int limit,
        @Schema(description = "재큐 후 남은 FAILED 인입 행 수(0 이면 회수 완료)", example = "0")
        long remainingFailed) {
}
