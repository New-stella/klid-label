package kr.co.cudo.authoring.video.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 관제서버 → 저작도구 영상 수신 요청.
 * - vmsClipId: VMS 시스템 클립 식별자 (UK)
 * - cctvId: VMS_CCTV_ID (MNG_RESOURCE_CCTV 와 매핑되는지 검증)
 * - prvcTypeCd: ANONY / PRVC / PSDO 중 하나. PRVC 또는 PSDO 시 비식별 처리 필요.
 * - filePath: 원본 영상 저장 경로(저장소 기준 경로). 사용자(관제서버) 입력이므로 경로 순회 차단 검증 필요.
 */
public record VideoIngestRequest(
        @NotBlank(message = "vmsClipId 는 필수입니다.")
        @Size(max = 128)
        String vmsClipId,

        @NotBlank(message = "cctvId 는 필수입니다.")
        @Size(max = 64)
        String cctvId,

        @Size(max = 32)
        String eventTypeCd,

        @Size(max = 32)
        String localGovCd,

        @NotBlank(message = "prvcTypeCd 는 필수입니다.")
        @Size(max = 16)
        String prvcTypeCd,

        @NotBlank(message = "filePath 는 필수입니다.")
        @Size(max = 500)
        String filePath,

        @NotNull(message = "capturedAt 는 필수입니다.")
        Instant capturedAt,

        Integer durationSec
) {
}
