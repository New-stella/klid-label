package kr.co.cudo.authoring.label.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 라벨 속성 정의 생성/수정 요청 DTO (CVAT-Like 라벨 풀 포팅 Phase 3).
 *
 * <p>Mass Assignment 방어를 위해 Entity 직접 바인딩 금지 — 허용 필드만 명시.
 *
 * <p>검증:
 * <ul>
 *   <li>{@code name}: 1~64자.</li>
 *   <li>{@code inputType}: SELECT / CHECKBOX / RADIO / NUMBER / TEXT.</li>
 *   <li>{@code valuesJson}: 1000자 이하 (SELECT/CHECKBOX/RADIO 일 때 Service 에서 필수 검증).</li>
 *   <li>{@code defaultVal}: 255자 이하.</li>
 *   <li>{@code mutable}: 'Y' | 'N' (null/blank 면 Service 에서 'Y' 로 정규화).</li>
 *   <li>{@code sortNo}: 0 이상.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LabelAttrRequest(
        @NotBlank(message = "name 은 필수입니다.")
        @Size(max = 64, message = "name 은 64자 이하여야 합니다.")
        String name,

        @NotBlank(message = "inputType 은 필수입니다.")
        @Pattern(regexp = "^(SELECT|CHECKBOX|RADIO|NUMBER|TEXT)$",
                 message = "inputType 은 SELECT/CHECKBOX/RADIO/NUMBER/TEXT 중 하나여야 합니다.")
        String inputType,

        @Size(max = 1000, message = "valuesJson 은 1000자 이하여야 합니다.")
        String valuesJson,

        @Size(max = 255, message = "defaultVal 은 255자 이하여야 합니다.")
        String defaultVal,

        @Pattern(regexp = "^[YN]$", message = "mutable 은 Y 또는 N 이어야 합니다.")
        String mutable,

        @Min(value = 0, message = "sortNo 는 0 이상이어야 합니다.")
        Integer sortNo
) {
}
