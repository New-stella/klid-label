package kr.co.cudo.authoring.marking.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 마킹 프레임 단위 아이템.
 *
 * @param frameIndex 프레임 인덱스 (0-base)
 * @param timestamp  타임스탬프 문자열 ("mm:ss:ff" 등) — nullable
 */
public record MarkItem(
        @NotNull(message = "frameIndex 는 필수입니다.")
        Integer frameIndex,
        String timestamp
) {}
