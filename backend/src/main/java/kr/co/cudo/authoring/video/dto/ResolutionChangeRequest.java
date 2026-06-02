package kr.co.cudo.authoring.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 해상도 변경 요청 — Phase 3 (RQ-SFR-07-02).
 *
 * <p>{@code preset} enum 바인딩으로 배율 화이트리스트(P75/P50/P25)를 강제한다.
 * 그 외 값/형식은 역직렬화 또는 {@code @Valid} 단계에서 400 으로 거부된다.
 */
public record ResolutionChangeRequest(
        @Schema(description = "해상도 변경 배율 프리셋 (P75=0.75x, P50=0.5x, P25=0.25x)", example = "P50")
        @NotNull(message = "preset 은 필수입니다.")
        ResolutionPreset preset
) {
}
