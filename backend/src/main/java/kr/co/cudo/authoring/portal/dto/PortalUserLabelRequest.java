package kr.co.cudo.authoring.portal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * V2.0 포털 사용자 라벨 저장 요청. 원본 미수정 — LS_PORTAL_USER_LABEL 별도 적재.
 */
public record PortalUserLabelRequest(
        @NotNull(message = "sourceRawSn 은 필수입니다.") Long sourceRawSn,
        @NotNull(message = "sourceSrcSn 은 필수입니다.") Long sourceSrcSn,
        @NotBlank(message = "lblTypeCd 는 필수입니다.") @Size(max = 16) String lblTypeCd,
        @Size(max = 255) String label,
        String points
) {}
