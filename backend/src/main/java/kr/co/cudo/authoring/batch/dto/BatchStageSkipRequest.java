package kr.co.cudo.authoring.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.batch.status.ManualStageSkip;

/**
 * 배치 단계 수동 스킵 요청. [@design API-198]
 *
 * <p>사유는 <b>필수</b>다 — 스킵은 학습데이터의 부가 정보를 영구히 비우는 결정이라, 나중에 "왜 이
 * 영상만 시계열 메타가 없나"를 되짚을 근거가 반드시 남아야 한다. 공백만 있는 문자열은
 * {@code @NotBlank} 가 400 으로 거부한다(사유 없는 스킵을 빈 문자열로 우회할 수 없게).
 *
 * @param reason 스킵 사유(공백 불가, 최대 {@value ManualStageSkip#REASON_MAX_LENGTH}자).
 *               저장 시 제어문자가 제거되고 접두가 붙는다(CWE-117 · 재개 사유 충돌 차단).
 */
@Schema(description = "배치 단계 수동 스킵 요청 — 사유 필수")
public record BatchStageSkipRequest(
        @Schema(description = "스킵 사유(공백 불가, 최대 500자)", example = "외부 VLM 벤더 장애로 이 영상은 시계열 분석 없이 진행")
        @NotBlank(message = "스킵 사유는 필수입니다.")
        @Size(max = ManualStageSkip.REASON_MAX_LENGTH,
                message = "스킵 사유는 " + ManualStageSkip.REASON_MAX_LENGTH + "자 이하여야 합니다.")
        String reason) {
}
