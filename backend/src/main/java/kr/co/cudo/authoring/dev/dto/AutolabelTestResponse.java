package kr.co.cudo.authoring.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * [개발/검수 전용] 수동 업로드 + 오토라벨 트리거 응답.
 *
 * <p>실제 파이프라인은 백그라운드로 실행되며, 본 응답은 즉시(200) 반환된다.
 * 응답에는 저장소 기준 상대 경로만 노출 — 절대 경로 (CWE-209 Information Leak) 미노출.
 */
@Schema(description = "[개발/검수 전용] 업로드된 영상 식별자와 백그라운드 파이프라인 상태")
public record AutolabelTestResponse(
        @Schema(description = "LS_DATA_RAW.RAW_SN — 생성된 영상 식별자", example = "12345")
        Long rawSn,

        @Schema(description = "저장된 파일의 storage 기준 상대 경로 (절대 경로 미노출)",
                example = "dev-upload/abc-123.mp4")
        String savedFilePath,

        @Schema(description = "파이프라인 상태 — PROCESSING (비동기 시작) 또는 ACCEPTED",
                example = "PROCESSING")
        String pipelineStatus,

        @Schema(description = "트리거 시각 epoch millis", example = "1715520000000")
        Long startedAt
) {
}
