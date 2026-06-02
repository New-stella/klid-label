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
 * @param vlmMetaItems   결과 = 마킹별 자연어 서술(시계열 정렬). 각 항목은 "해당 시점(frameIndex) → 자연어 설명"이며,
 *                       항목별로 {@code LS_DATA_META}(K/V) 에 적재되고 {@code LS_DATA_META_REVIEW} 검수큐로 진입한다.
 *                       고정 속성 스키마가 아니라 마킹/시간 포인트별 항목 배열로 수신한다.
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
     * 마킹별 자연어 서술 항목 — 시계열 메타 1건.
     *
     * <p>VLM 결과는 고정 속성 스키마가 아니라 마킹/시간 포인트별 자연어 서술이다.
     * 단일 거대 blob 이 아니라 시점별 항목으로 받아 {@code LS_DATA_META}(K/V) 에 1행씩 적재한다.
     *
     * @param metaKey 시계열 정렬 키 — 마킹 frameIndex 권장(0-base, 영상 내 유니크).
     *                {@code LS_DATA_META (RAW_SN, META_KEY)} UNIQUE 제약과 정합되도록 영상 내 유니크해야 한다
     *                (timestamp 는 중복 가능하므로 키로 부적합 — frameIndex 사용).
     * @param metaVal 해당 시점의 자연어 서술(≤2000자/세그먼트). 자연어 서술이 핵심이므로 빈 값은 거부한다({@code @NotBlank}).
     */
    public record MetaItem(
            @NotBlank
            @Size(max = 64)
            String metaKey,

            @NotBlank(message = "metaVal(자연어 서술)은 빈 값일 수 없습니다.")
            @Size(max = 2000)
            String metaVal
    ) {}
}
