package kr.co.cudo.authoring.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 외부 생성형 AI 증강 결과 인계 페이로드 — Phase 2 webhook.
 *
 * <p>{@code POST /v1/augments/result} 요청 본문. ccarch {@code if-augment-result-handover} 매핑.
 *
 * @param idempotencyKey 원래 위탁 요청 ID
 * @param externalJobId  외부 증강 시스템 작업 ID
 * @param status         {@code SUCCESS|FAILED|PARTIAL} 화이트리스트
 * @param originAugSn    LS_DATA_AUG 행 PK (PENDING 상태로 사전 등록된 행)
 * @param augType        {@code WINTER|NIGHT|RAIN|RESOLUTION} 화이트리스트
 * @param resultFilePath 결과 영상 위치 URL/경로 (SSRF 검증 대상)
 * @param labelMapping   원본↔증강 라벨 매핑 (옵션)
 */
public record AugmentResultRequest(

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
        Long originAugSn,

        @NotBlank
        @Pattern(regexp = "^(WINTER|NIGHT|RAIN|RESOLUTION)$",
                message = "augType 은 WINTER|NIGHT|RAIN|RESOLUTION 중 하나여야 합니다.")
        String augType,

        @Size(max = 1000)
        String resultFilePath,

        @Size(max = 1000, message = "labelMapping 은 최대 1000개까지만 허용됩니다.")
        List<LabelMap> labelMapping
) {

    /**
     * 원본↔증강 라벨 매핑.
     */
    public record LabelMap(
            Long originLabelId,
            Long augLabelId,
            String mappingType
    ) {}
}
