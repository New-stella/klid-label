package kr.co.cudo.authoring.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 외부 생성형 AI 증강 결과 인계 페이로드 — Phase 2 webhook.
 *
 * <p>{@code POST /v1/augments/result} 요청 본문. ccarch {@code if-augment-result-handover} 매핑.
 *
 * <p>해상도 변경(RESOLUTION)은 저작도구가 직접 수행하므로(RQ-SFR-06-03) 외부 콜백 화이트리스트에서
 * 제외한다. 외부 증강 콜백은 날씨·계절·시간 3종(WINTER/NIGHT/RAIN)만 허용한다.
 *
 * @param idempotencyKey 원래 위탁 요청 ID
 * @param externalJobId  외부 증강 시스템 작업 ID
 * @param status         {@code SUCCESS|FAILED|PARTIAL} 화이트리스트
 * @param originAugSn    LS_DATA_AUG 행 PK (PENDING 상태로 사전 등록된 행)
 * @param augType        {@code WINTER|NIGHT|RAIN} 화이트리스트
 * @param resultFilePath 결과 영상 위치 URL/경로 (SSRF 검증 대상)
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
        @Pattern(regexp = "^(WINTER|NIGHT|RAIN)$",
                message = "augType 은 WINTER|NIGHT|RAIN 중 하나여야 합니다.")
        String augType,

        @Size(max = 1000)
        String resultFilePath
) {
}
