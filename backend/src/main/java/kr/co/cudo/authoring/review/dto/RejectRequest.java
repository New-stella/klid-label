package kr.co.cudo.authoring.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 검수 반려 요청. 사유는 필수 (작업자가 다음 회차 수정 시 참조).
 */
public record RejectRequest(
        @NotBlank(message = "반려 사유는 필수입니다.")
        @Size(max = 1000, message = "반려 사유는 최대 1000자까지 입력 가능합니다.")
        String reason
) {
}
