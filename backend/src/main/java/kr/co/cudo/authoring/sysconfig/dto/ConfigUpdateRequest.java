package kr.co.cudo.authoring.sysconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 시스템 설정 갱신 요청.
 * <p>
 * 값 형식 / 범위 검증은 SystemConfigService 에서 CONFIG_TYPE 별로 수행한다.
 * (DTO 단계에서는 not-blank + 길이 제한만 — Mass Assignment 방어).
 */
public record ConfigUpdateRequest(
        @NotBlank(message = "값은 비어있을 수 없습니다.")
        @Size(max = 500, message = "값은 500자를 초과할 수 없습니다.")
        String value
) {
}
