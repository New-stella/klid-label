package kr.co.cudo.authoring.export.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Phase 10 — 학습데이터셋 내보내기 요청.
 * Mass Assignment(CWE-915) 방어 — record + @Valid + Pattern.
 */
public record ExportRequest(
        @NotNull @Positive Long pjtId,
        @NotBlank @Pattern(regexp = "YOLO|COCO",
                message = "format 은 YOLO/COCO 만 지원합니다.") String format
) {
}
