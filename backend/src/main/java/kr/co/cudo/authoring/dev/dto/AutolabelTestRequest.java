package kr.co.cudo.authoring.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

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
 *
 * <p>{@code enabledStages} 는 4단계 (FRAME_EXTRACT/DEIDENTIFY/YOLO/SAM2) 의 On/Off 토글이다.
 * 누락/null 인 키는 모두 {@code true} 로 처리한다 (back-compat — 기존 클라이언트는 전 단계 실행).
 *
 * <p>Phase 3 (단일 파이프라인 수렴) 신 순서 의미:
 * <ul>
 *   <li>DEIDENTIFY: 선두 비식별 수행 여부(dev 한정 skip 가능). off 시 deIdntfYn 미설정 →
 *       FRAME_EXTRACT 가드에 걸리므로 FRAME 도 off 권장.</li>
 *   <li>FRAME_EXTRACT: 합성 마킹(frameIndex 0) 기반 프레임 추출 여부. on 이면 dev 합성 마킹 생성.</li>
 *   <li>YOLO/SAM2: 단일 파이프라인 step 의 {@code isEnabled} 로 skip.</li>
 * </ul>
 * 토글은 {@link kr.co.cudo.authoring.batch.runner.DevPipelineRunner} 를 거쳐
 * {@code BatchOrchestrator.process(rawSn, toggles)} 로 전달된다.
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

        @Schema(description = "이벤트 타입 코드 (SoT 6종: EVT_FALL/EVT_VIOLENCE/EVT_ACCIDENT/EVT_ABNORMAL/EVT_FLOOD/EVT_FIRE)",
                example = "EVT_FALL", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "eventTypeCd 는 필수입니다.")
        @Pattern(regexp = "^EVT_(FALL|VIOLENCE|ACCIDENT|ABNORMAL|FLOOD|FIRE)$",
                message = "지원하지 않는 이벤트 타입입니다")
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
        Instant capturedAt,

        @Schema(description = "배치 단계 On/Off 토글 — 키: FRAME_EXTRACT/DEIDENTIFY/YOLO/SAM2. 누락 시 true(실행).",
                example = "{\"FRAME_EXTRACT\":true,\"DEIDENTIFY\":true,\"YOLO\":true,\"SAM2\":true}",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Map<String, Boolean> enabledStages
) {

    /** 단계 토글 키 — Service 레이어와 1:1 매핑되어야 한다. */
    public static final String STAGE_FRAME_EXTRACT = "FRAME_EXTRACT";
    public static final String STAGE_DEIDENTIFY = "DEIDENTIFY";
    public static final String STAGE_YOLO = "YOLO";
    public static final String STAGE_SAM2 = "SAM2";

    /** 개인정보 유형 — LsDataRaw 의 코드와 매핑. */
    public enum PrvcType {
        ANONY,
        PRVC,
        PSDO
    }

    /**
     * 호출자에게 안전한 stage 토글 맵을 반환한다.
     * <ul>
     *   <li>{@code enabledStages == null} → 4단계 모두 {@code true} 인 새 맵.</li>
     *   <li>각 키 누락/{@code null} → {@code true} (back-compat).</li>
     *   <li>모르는 키는 무시되지 않고 그대로 반환 (서비스에서 미사용 처리).</li>
     * </ul>
     */
    public Map<String, Boolean> resolveEnabledStages() {
        Map<String, Boolean> resolved = new HashMap<>();
        resolved.put(STAGE_FRAME_EXTRACT, true);
        resolved.put(STAGE_DEIDENTIFY, true);
        resolved.put(STAGE_YOLO, true);
        resolved.put(STAGE_SAM2, true);
        if (enabledStages == null || enabledStages.isEmpty()) {
            return resolved;
        }
        enabledStages.forEach((k, v) -> {
            if (k != null) {
                resolved.put(k, v != null ? v : Boolean.TRUE);
            }
        });
        return resolved;
    }
}
