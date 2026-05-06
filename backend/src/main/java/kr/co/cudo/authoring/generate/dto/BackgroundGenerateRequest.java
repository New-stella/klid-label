package kr.co.cudo.authoring.generate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Phase 9 — 외부 SFR-06/11 생성 시스템에 배경영상 생성을 요청하는 DTO.
 *
 * <p>genType 화이트리스트: WILDFIRE / FLOOD (V1.5 외부 시스템 책임 — 본체는 인터페이스만).
 */
public record BackgroundGenerateRequest(
        @NotBlank(message = "genType 은 필수입니다.")
        @Pattern(regexp = "WILDFIRE|FLOOD",
                message = "genType 은 WILDFIRE / FLOOD 중 하나여야 합니다.")
        String genType,

        @Size(max = 500, message = "프롬프트는 최대 500자까지 입력 가능합니다.")
        String prompt
) {
}
