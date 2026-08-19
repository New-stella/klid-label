package kr.co.cudo.authoring.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 작업 묶음 일괄 <b>해제·재수행</b> 요청(대상 목록만). [@design API-213] [@design API-214]
 *
 * <p>해제와 재수행은 받을 것이 대상 목록뿐이라 요청 타입을 공유한다 — 사유를 받는 스킵만
 * {@link BatchStageSkipBulkRequest} 로 갈린다.
 *
 * <p>원소는 <b>1 이상</b>이어야 한다. 단건 경로가 {@code @Min(1)} 로 0·음수를 400 으로 막는데 일괄만
 * 빠져 있으면 같은 값이 경로에 따라 400 과 "건별 실패"로 갈린다. 검증은 원소 단위라 위반 원소가
 * 하나라도 있으면 요청 전체가 400 이다 — 부분 성공은 <b>존재·상태</b> 판정에만 적용하며, 애초에
 * 식별자가 될 수 없는 값은 접수 대상이 아니다.
 *
 * @param rawSns 대상 영상 식별자 목록(1~{@value BulkRawSns#MAX_SIZE}건, 각 원소 1 이상, 중복은 1건 취급)
 */
@Schema(description = "작업 묶음 일괄 해제·재수행 요청 — 되는 것만 처리하고 거부분은 건별 사유로 돌려준다")
public record BatchStageBulkRequest(
        @Schema(description = "대상 영상 식별자 목록(1~100건, 각 원소 1 이상, 중복은 1건 취급)",
                example = "[12, 43, 45]")
        @NotEmpty(message = "rawSns 는 1건 이상이어야 합니다.")
        @Size(max = BulkRawSns.MAX_SIZE, message = "rawSns 는 " + BulkRawSns.MAX_SIZE + "건 이하여야 합니다.")
        List<@Min(value = 1, message = "rawSn 은 1 이상이어야 합니다.") Long> rawSns) {

    /** 중복·null 을 제거한 처리 대상(요청 순서 보존) — 규칙 원천은 {@link BulkRawSns}. */
    public List<Long> distinctRawSns() {
        return BulkRawSns.distinct(rawSns);
    }
}
