package kr.co.cudo.authoring.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 배치 재처리(재기동) 응답 DTO (B3).
 *
 * @param rawSn 재처리 대상 영상 단위 식별자
 * @param stage 재기동 결과 배치 단계 (COMPLETED 성공 / FAILED 재실패)
 */
@Schema(description = "배치 재처리 응답")
public record BatchReprocessResponse(
        @Schema(description = "영상 단위 식별자", example = "1") Long rawSn,
        @Schema(description = "재기동 결과 배치 단계", example = "COMPLETED") String stage) {
}
