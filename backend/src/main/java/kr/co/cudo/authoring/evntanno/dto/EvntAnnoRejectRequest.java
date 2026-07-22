package kr.co.cudo.authoring.evntanno.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * event_annotation 검토 반려 요청 — REVIEWER 가 반려할 때 사유 입력. 사유 필수.
 */
public record EvntAnnoRejectRequest(
        @NotBlank(message = "반려 사유는 필수입니다.")
        @Size(max = 1000, message = "반려 사유는 1000자 이하여야 합니다.")
        String reason
) {
}
