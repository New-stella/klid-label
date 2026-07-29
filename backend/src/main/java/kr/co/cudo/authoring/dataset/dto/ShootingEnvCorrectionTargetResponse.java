package kr.co.cudo.authoring.dataset.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 촬영환경 레거시 파생 동결값 정정 <b>대상 건수</b>(dry-run) 응답.
 *
 * <p>건수만 담는다 — rawSn 목록·메타 본문은 담지 않는다(운영 확인 목적에 불필요하고 노출면만 넓힌다).
 *
 * @param targetCount 아직 정정되지 않은 대상 영상 건수
 */
@Schema(description = "촬영환경 파생값 정정 대상 건수(dry-run — 정정을 수행하지 않음)")
public record ShootingEnvCorrectionTargetResponse(
        @Schema(description = "정정 대상 영상 건수", example = "7")
        long targetCount
) {
}
