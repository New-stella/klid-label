package kr.co.cudo.authoring.label.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 라벨 마스터 생성/수정 요청 DTO.
 *
 * <p>Mass Assignment 방어를 위해 Entity 직접 바인딩 금지 — 허용 필드만 명시.
 *
 * <p>검증:
 * <ul>
 *   <li>{@code name}: 1~64자.</li>
 *   <li>{@code color}: 대문자 hex {@code #RRGGBB} 만 (소문자 입력 시 400).</li>
 *   <li>{@code type}: BBOX / POLYGON / POINT.</li>
 *   <li>{@code sortNo}: 0 이상.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LabelMasterRequest(
        @NotBlank(message = "name 은 필수입니다.")
        @Size(max = 64, message = "name 은 64자 이하여야 합니다.")
        String name,

        @NotBlank(message = "color 는 필수입니다.")
        @Pattern(regexp = "^#[0-9A-F]{6}$", message = "color 는 대문자 hex (#RRGGBB) 형식이어야 합니다.")
        String color,

        @NotBlank(message = "type 은 필수입니다.")
        @Pattern(regexp = "^(BBOX|POLYGON|POINT)$", message = "type 은 BBOX/POLYGON/POINT 중 하나여야 합니다.")
        String type,

        @Min(value = 0, message = "sortNo 는 0 이상이어야 합니다.")
        Integer sortNo
) {
}
