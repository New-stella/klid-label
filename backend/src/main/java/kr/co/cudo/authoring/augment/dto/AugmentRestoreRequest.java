package kr.co.cudo.authoring.augment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 폐기(반려)된 증강 파생영상 <b>복구</b> 요청 — Phase 7. 되돌린 이유는 필수(감사).
 *
 * <p>{@code RejectRequest} 와 같은 상한(500자)을 쓴다 — 두 사유가 같은 원장에 나란히 남으므로
 * 길이 정책이 갈리면 화면 표시가 어긋난다.
 */
public record AugmentRestoreRequest(
        @NotBlank(message = "복구 사유는 필수입니다.")
        @Size(max = 500, message = "복구 사유는 최대 500자까지 입력 가능합니다.")
        String reason
) {
}
