package kr.co.cudo.authoring.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.batch.status.ManualStageSkip;

import java.util.List;

/**
 * 작업 묶음 일괄 <b>스킵</b> 요청. [@design API-212]
 *
 * <p>사유는 단건과 마찬가지로 <b>필수</b>다 — 스킵은 학습데이터의 부가 정보를 영구히 비우는 결정이라,
 * 나중에 "왜 이 영상들만 시계열 메타가 없나"를 되짚을 근거가 반드시 남아야 한다.
 *
 * <p><b>사유는 요청당 하나</b>이며 대상 전건에 같은 값으로 기록된다 — 영상마다 다른 사유를 받으면
 * 일괄로 처리할 이유가 사라진다(설계 규범).
 *
 * @param rawSns 대상 영상 식별자 목록(1~{@value BulkRawSns#MAX_SIZE}건, 각 원소 1 이상, 중복은 1건 취급)
 * @param reason 스킵 사유(공백 불가, 최대 {@value ManualStageSkip#REASON_MAX_LENGTH}자).
 *               저장 시 제어문자가 제거되고 접두가 붙는다(CWE-117 · 재개 사유 충돌 차단).
 */
@Schema(description = "작업 묶음 일괄 스킵 요청 — 사유 필수(요청당 하나, 대상 전건에 같은 값으로 기록)")
public record BatchStageSkipBulkRequest(
        @Schema(description = "대상 영상 식별자 목록(1~100건, 각 원소 1 이상, 중복은 1건 취급)",
                example = "[12, 43, 45]")
        @NotEmpty(message = "rawSns 는 1건 이상이어야 합니다.")
        @Size(max = BulkRawSns.MAX_SIZE, message = "rawSns 는 " + BulkRawSns.MAX_SIZE + "건 이하여야 합니다.")
        List<@Min(value = 1, message = "rawSn 은 1 이상이어야 합니다.") Long> rawSns,

        @Schema(description = "스킵 사유(공백 불가, 최대 500자)",
                example = "외부 시계열 분석 벤더 연동 전이라 시계열 없이 진행")
        @NotBlank(message = "스킵 사유는 필수입니다.")
        @Size(max = ManualStageSkip.REASON_MAX_LENGTH,
                message = "스킵 사유는 " + ManualStageSkip.REASON_MAX_LENGTH + "자 이하여야 합니다.")
        String reason) {

    /** 중복·null 을 제거한 처리 대상(요청 순서 보존) — 규칙 원천은 {@link BulkRawSns}. */
    public List<Long> distinctRawSns() {
        return BulkRawSns.distinct(rawSns);
    }
}
