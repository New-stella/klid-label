package kr.co.cudo.authoring.webhook.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 외부 VLM 시계열 메타 분석 결과 인계 페이로드 — Phase 2 webhook.
 *
 * <p>{@code POST /v1/vlm/result} 요청 본문. ccarch {@code if-vlm-timeseries-spi} (결과 수신측) 매핑.
 *
 * @param idempotencyKey 원래 위탁 요청 ID (Phase 1 VlmClient 발급)
 * @param externalJobId  외부 VLM 서비스 작업 ID
 * @param status         {@code SUCCESS|FAILED|PARTIAL} 화이트리스트
 * @param rawSn          분석 대상 영상의 RAW_SN
 * @param vlmMetaItems   key/value 페어 배열 — 시계열 메타
 * @param resultFilePath 결과 위치 URL/경로 (옵션, SSRF 검증 대상)
 */
public record VlmResultRequest(

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

        @NotEmpty(message = "vlmMetaItems 는 최소 1개 이상 필요합니다.")
        @Size(max = 500, message = "vlmMetaItems 는 최대 500개까지만 허용됩니다.")
        @Valid
        List<MetaItem> vlmMetaItems,

        @Size(max = 1000)
        String resultFilePath
) {

    /**
     * 시계열 메타 key/value 페어.
     */
    public record MetaItem(
            @NotBlank
            @Size(max = 64)
            String metaKey,

            @Size(max = 2000)
            String metaVal
    ) {}
}
