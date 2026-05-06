package kr.co.cudo.authoring.augment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 증강 결과 반려 요청. 사유는 필수.
 */
public record RejectRequest(
        @NotBlank(message = "반려 사유는 필수입니다.")
        @Size(max = 500, message = "반려 사유는 최대 500자까지 입력 가능합니다.")
        String reason
) {
}
