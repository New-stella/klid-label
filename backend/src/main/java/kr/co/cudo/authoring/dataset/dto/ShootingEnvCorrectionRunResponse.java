package kr.co.cudo.authoring.dataset.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 촬영환경 레거시 파생 동결값 정정 <b>실행</b> 결과 요약.
 *
 * <p>1회 실행은 {@code authoring.dataset-video-meta.env-correction.max-per-run} 상한을 타므로
 * 대상이 상한보다 많으면 {@code remaining > 0} 으로 남는다. 상한을 우회하는 "전량 실행" 옵션은 두지
 * 않으며, 호출자는 {@code completed=true} 가 될 때까지 같은 API 를 재호출한다(멱등).
 *
 * @param corrected 이번 호출에서 실제 정정(재동결)된 영상 건수
 * @param remaining 정정 후 남은 대상 건수
 * @param completed 잔여가 0이면 {@code true}(재호출 불필요)
 */
@Schema(description = "촬영환경 파생값 정정 실행 결과")
public record ShootingEnvCorrectionRunResponse(
        @Schema(description = "이번 호출에서 정정된 영상 건수", example = "3")
        int corrected,
        @Schema(description = "정정 후 남은 대상 건수(0 이 아니면 재호출)", example = "5")
        long remaining,
        @Schema(description = "잔여 0 여부", example = "false")
        boolean completed
) {

    public static ShootingEnvCorrectionRunResponse of(int corrected, long remaining) {
        return new ShootingEnvCorrectionRunResponse(corrected, remaining, remaining == 0);
    }
}
