package kr.co.cudo.authoring.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/**
 * [개발/검수 전용] 영상 업로드 + 오토라벨 파이프라인 트리거 요청.
 *
 * <p>다음 보안 가드를 1차 적용한다 (서비스 레이어에서 2차 검증):
 * <ul>
 *   <li>{@code vmsClipId}, {@code cctvId} 는 영문/숫자/하이픈/언더스코어만 허용 — 경로 순회·SQL Injection 차단.</li>
 *   <li>{@code localGovCd} 는 숫자만 허용.</li>
 * </ul>
 *
 * <p>{@code durationSec} 는 ffprobe 로 업로드된 영상 파일에서 자동 추출하므로 요청 필드에서 제거되었다
 * (사용자 입력 무시 → 위/변조 차단).
 */
@Schema(description = "[개발/검수 전용] 영상 업로드 + 오토라벨 파이프라인 트리거 메타데이터")
public record AutolabelTestRequest(
        @Schema(description = "VMS 시스템 클립 식별자 (UK). 영문/숫자/-/_ 만 허용",
                example = "TEST-CLIP-001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "vmsClipId 는 필수입니다.")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "vmsClipId 는 영문/숫자/-/_ 1~64자만 허용됩니다.")
        String vmsClipId,

        @Schema(description = "VMS_CCTV_ID — MNG_RESOURCE_CCTV 에 등록되어 있어야 함",
                example = "CCTV-001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "cctvId 는 필수입니다.")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "cctvId 는 영문/숫자/-/_ 1~64자만 허용됩니다.")
        String cctvId,

        @Schema(description = "이벤트 타입 코드 (EVT_FALL/EVT_VIOLENCE/EVT_ACCIDENT/EVT_FIRE 등)",
                example = "EVT_FALL", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "eventTypeCd 는 필수입니다.")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "eventTypeCd 는 대문자/숫자/_ 형식이어야 합니다.")
        String eventTypeCd,

        @Schema(description = "지자체 코드 (숫자)", example = "1168000000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "localGovCd 는 필수입니다.")
        @Pattern(regexp = "^[0-9]{1,10}$", message = "localGovCd 는 숫자 1~10자리만 허용됩니다.")
        String localGovCd,

        @Schema(description = "개인정보 유형 — ANONY/PRVC/PSDO", example = "ANONY",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "prvcTypeCd 는 필수입니다.")
        PrvcType prvcTypeCd,

        @Schema(description = "촬영 시각 (ISO-8601 Instant)", example = "2024-05-01T12:00:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "capturedAt 는 필수입니다.")
        Instant capturedAt
) {

    /** 개인정보 유형 — LsDataRaw 의 코드와 매핑. */
    public enum PrvcType {
        ANONY,
        PRVC,
        PSDO
    }
}
