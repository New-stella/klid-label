package kr.co.cudo.authoring.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 외부 Deidentify SW 비동기 결과 인계 페이로드 — Phase 2 webhook.
 *
 * <p>{@code POST /v1/deidentify/result} 요청 본문. ccarch {@code if-deidentify-spi} (결과 수신측) 매핑.
 *
 * @param idempotencyKey 원래 위탁 요청 ID (UUID 형식, 본 도구가 발급)
 * @param externalJobId  외부 시스템의 작업 ID
 * @param status         {@code SUCCESS|FAILED|PARTIAL} 화이트리스트
 * @param rawSn          비식별 대상 영상의 RAW_SN (LS_DATA_RAW PK)
 * @param deidentifiedFilePath 결과 비식별 영상의 경로/URL (사설망 IP 차단 — SSRF)
 * @param processedRegions     처리된 비식별 영역 메타 (JSON 배열, 옵션)
 */
public record DeidentifyResultRequest(

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$",
                message = "idempotencyKey 는 영숫자/대시/언더스코어만 허용됩니다.")
        String idempotencyKey,

        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$",
                message = "externalJobId 는 영숫자/대시/언더스코어만 허용됩니다.")
        String externalJobId,

        @NotBlank
        @Pattern(regexp = "^(SUCCESS|FAILED|PARTIAL)$",
                message = "status 는 SUCCESS|FAILED|PARTIAL 중 하나여야 합니다.")
        String status,

        @NotNull
        Long rawSn,

        @Size(max = 1000)
        String deidentifiedFilePath,

        @Size(max = 1000, message = "processedRegions 는 최대 1000개까지만 허용됩니다.")
        List<ProcessedRegion> processedRegions
) {

    /**
     * 처리된 비식별 영역 메타.
     */
    public record ProcessedRegion(
            String frameId,
            String regionType,
            Integer x,
            Integer y,
            Integer width,
            Integer height
    ) {}
}
