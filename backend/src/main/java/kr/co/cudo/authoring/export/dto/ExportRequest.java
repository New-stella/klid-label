package kr.co.cudo.authoring.export.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Phase 10 — 학습데이터셋 내보내기 요청.
 * Mass Assignment(CWE-915) 방어 — record + @Valid + Pattern.
 *
 * <p>FE alias 호환: {@code datasetId} 는 {@code pjtId} 와 동일하게 매핑된다.
 * 미사용 필드(videoIds/nasPath)는 mock UI 정합을 위해 수신만 허용 — 현재 BE 로직은 미사용.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExportRequest(
        @NotNull @Positive @JsonAlias({"datasetId"}) Long pjtId,
        @NotBlank @Pattern(regexp = "YOLO|COCO",
                message = "format 은 YOLO/COCO 만 지원합니다.") String format,
        /** mock 정합용 — 현재 BE 미사용. 향후 영상 단위 필터 확장 대비. */
        List<Long> videoIds,
        /** mock 정합용 — 현재 BE 미사용 (NAS 경로는 서비스에서 결정). */
        String nasPath
) {
}
