package kr.co.cudo.authoring.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 해상도 변경 요청 — Phase 1 (RQ-SFR-06-03 v1.8/1.10).
 *
 * <p>{@code preset} enum 바인딩으로 표준 하위 해상도 화이트리스트(RES_1080P/RES_720P/RES_480P)를
 * 강제한다. 그 외 값/형식은 역직렬화 또는 {@code @Valid} 단계에서 400 으로 거부된다.
 */
public record ResolutionChangeRequest(
        @Schema(description = "표준 하위 해상도 프리셋", example = "RES_720P",
                allowableValues = {"RES_1080P", "RES_720P", "RES_480P"})
        @NotNull(message = "preset 은 필수입니다.")
        ResolutionPreset preset
) {
}
