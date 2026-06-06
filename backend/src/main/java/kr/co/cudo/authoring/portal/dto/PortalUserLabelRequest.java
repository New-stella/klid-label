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
        // R17 이슈2 — points 누락 시 빈 라벨 row 가 생성되어 포털 로드/렌더 크래시를 유발.
        // @NotBlank 로 NULL/공백 저장을 1차 차단 (빈 좌표 JSON '[]' 는 서비스에서 추가 차단).
        @NotBlank(message = "points 는 필수입니다.") String points
) {}
