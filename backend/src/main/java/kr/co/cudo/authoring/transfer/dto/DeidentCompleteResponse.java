package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 비식별 완료 기록 결과(API-215).
 *
 * @param rawSn                기록된 영상의 식별번호
 * @param procLogSn            이번에 남긴 비식별 처리 이력의 식별번호
 * @param approvalHoldReleased 이 기록으로 검수 승인 보류가 풀렸는지 여부
 * @design API-215
 */
@Schema(description = "비식별 완료 기록 결과")
public record DeidentCompleteResponse(long rawSn, long procLogSn, boolean approvalHoldReleased) {
}
