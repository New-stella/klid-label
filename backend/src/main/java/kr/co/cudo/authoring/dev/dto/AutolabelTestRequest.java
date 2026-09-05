package kr.co.cudo.authoring.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/**
 * [개발/검수 전용] 수동 업로드 + 선두 비식별 파이프라인 트리거 요청.
 *
 * <p>dev 업로드는 운영 시나리오 1:1 고정 플로우다 — 업로드 → 비식별(무조건) → MARKING_READY 정지.
 * 따라서 단계 토글({@code enabledStages})·마킹 직접 수행({@code manualMarking}) 같은 분기 필드는
 * 두지 않는다. 잔여 배치는 사용자가 마킹 화면에서 마킹→완료할 때만 트리거된다.
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
@Schema(description = "수동 업로드 + 선두 비식별 파이프라인 트리거 메타데이터")
public record AutolabelTestRequest(
        @Schema(description = "VMS 시스템 클립 식별자 (UK). 영문/숫자/-/_ 만 허용",
                example = "TEST-CLIP-001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "vmsClipId 는 필수입니다.")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "vmsClipId 는 영문/숫자/-/_ 1~64자만 허용됩니다.")
        String vmsClipId,

        @Schema(description = "VMS_CCTV_ID — 영상이 촬영된 CCTV 식별자(형식 검증만)",
                example = "CCTV-001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "cctvId 는 필수입니다.")
        @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "cctvId 는 영문/숫자/-/_ 1~64자만 허용됩니다.")
        String cctvId,

        @Schema(description = """
                이벤트유형 마스터(LS_EVNT_TYPE)에 등록된 이벤트 코드 — 형식 EVxxxxxxxx (EV + 숫자 8자리).
                예: EV02000201(쓰러짐)/EV05000101(싸움)/EV03000101(교통사고). dev 업로드 도구는 이 코드를
                LS_DATA_RAW 에 적재해 오토라벨 프리셋 매칭(이벤트유형코드→프리셋) 흐름을 시험한다.
                형식만 @Pattern 으로 1차 가드하고, 실제 등록 여부는 서비스(EventTypeService)에서 검증한다(미등록 400).""",
                example = "EV02000201", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "eventTypeCd 는 필수입니다.")
        @Pattern(regexp = "^EV[0-9]{8}$",
                message = "이벤트 타입 코드 형식이 올바르지 않습니다 (EV + 숫자 8자리).")
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
