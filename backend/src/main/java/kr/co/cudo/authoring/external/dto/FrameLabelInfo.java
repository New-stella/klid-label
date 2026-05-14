package kr.co.cudo.authoring.external.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Phase 4 — 외부 학습데이터 API 프레임 단위 응답.
 *
 * <p>RAW 프레임 기준 1벌만 포함한다 (DEID 프레임 row 는 외부 응답에서 제외).
 */
@Schema(description = "프레임 단위 라벨 묶음 (RAW 프레임 기준)")
public record FrameLabelInfo(
        @Schema(description = "프레임 번호 (0-base)") Integer frameNo,
        @Schema(description = "원본 프레임 파일 경로") String filePath,
        @Schema(description = "해당 프레임의 라벨 목록") List<LabelInfo> labels
) {
}
