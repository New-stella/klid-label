package kr.co.cudo.authoring.webhook.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 외부 생성형 AI 증강 결과 인계 페이로드 — 연동정의서 정합.
 *
 * <p>{@code POST /v1/aug/callback} 요청 본문. 외부 시스템은 snake_case 로 송신한다.
 * <pre>
 * {"data_aug_sn":8,"otsd_job_id":"aug_008","aug_type_cd":"RAIN",
 *  "aug_proc_sts_cd":"SUCCESS","raw_file_path_nm":"/nas/aug/8/aug_008.mp4"}
 * </pre>
 *
 * <p>해상도 변경(RESOLUTION)은 저작도구가 직접 수행하므로(RQ-SFR-06-03) 외부 콜백 화이트리스트에서
 * 제외한다. 외부 증강 콜백은 날씨·계절·시간 3종(WINTER/NIGHT/RAIN)만 허용한다.
 *
 * <p><b>멱등/재전송 방어</b>: 별도 {@code idempotency_key} 필드는 제거되었다. 콜백 재전송 중복은
 * {@code otsd_job_id}(→ {@code LS_DATA_AUG.OTSD_JOB_ID} UNIQUE {@code uk_aug_external_job_id})
 * 를 앵커로 {@code AugmentResultService} 에서 판별한다.
 *
 * @param dataAugSn     LS_DATA_AUG 행 PK (요청 시 PENDING 으로 사전 등록된 행)
 * @param otsdJobId     외부 증강 시스템 작업 ID (콜백 시점 부여, 재전송 멱등 앵커)
 * @param augTypeCd     {@code WINTER|NIGHT|RAIN} 화이트리스트(코드)
 * @param augProcStsCd  {@code SUCCESS|FAILED|PARTIAL} 화이트리스트
 * @param rawFilePathNm 결과 영상 위치 URL/경로 (SSRF 검증 대상)
 */
public record AugmentResultRequest(

        @NotNull
        @JsonProperty("data_aug_sn")
        Long dataAugSn,

        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$",
                message = "otsd_job_id 는 영숫자/대시/언더스코어만 허용됩니다.")
        @JsonProperty("otsd_job_id")
        String otsdJobId,

        @NotBlank
        @Pattern(regexp = "^(WINTER|NIGHT|RAIN)$",
                message = "aug_type_cd 는 WINTER|NIGHT|RAIN 중 하나여야 합니다.")
        @JsonProperty("aug_type_cd")
        String augTypeCd,

        @NotBlank
        @Pattern(regexp = "^(SUCCESS|FAILED|PARTIAL)$",
                message = "aug_proc_sts_cd 는 SUCCESS|FAILED|PARTIAL 중 하나여야 합니다.")
        @JsonProperty("aug_proc_sts_cd")
        String augProcStsCd,

        @Size(max = 1000)
        @JsonProperty("raw_file_path_nm")
        String rawFilePathNm
) {
}
