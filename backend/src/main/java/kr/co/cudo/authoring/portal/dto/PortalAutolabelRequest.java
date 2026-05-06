package kr.co.cudo.authoring.portal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Phase 11 — 포털 간편 라벨링(YOLO) 요청.
 *
 *  - portalVideoSn : 본인 업로드 영상 (PortalLabelService 가 IDOR 검증)
 *  - imageB64      : 단일 프레임 base64 (10MB 가량 한도 — Spring 기본 multipart 제한 활용)
 */
public record PortalAutolabelRequest(
        @NotNull(message = "portalVideoSn 은 필수입니다.") Long portalVideoSn,
        @NotBlank(message = "imageB64 는 필수입니다.")
        @Size(max = 20_000_000, message = "imageB64 길이 한도 초과") String imageB64
) {
}
