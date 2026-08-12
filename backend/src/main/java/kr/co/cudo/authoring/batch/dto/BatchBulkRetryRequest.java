package kr.co.cudo.authoring.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 배치 <b>일괄</b> 재시작 요청. [@design API-199]
 *
 * <p>상한을 두는 이유(CWE-770): 각 건이 파이프라인을 기동하므로 무제한 목록은 한 요청으로 외부 추론
 * 서버·NAS I/O 를 포화시킬 수 있다. 남은 건은 응답을 보고 재호출로 이어서 처리한다.
 *
 * <p>원소는 <b>1 이상</b>이어야 한다({@code minimum: 1}) — 단건 경로가 {@code @Min(1)} 로 0·음수를 400 으로
 * 막는데 일괄만 빠져 있으면 같은 값이 경로에 따라 400 과 "건별 실패"로 갈린다. 검증은 원소 단위라
 * 위반 원소가 하나라도 있으면 요청 전체가 400 이다(부분 성공은 <b>존재·상태</b> 판정에만 적용하며,
 * 애초에 식별자가 될 수 없는 값은 접수 대상이 아니다).
 *
 * @param rawSns 재기동할 영상 식별자 목록(1~{@value #MAX_SIZE}건, 각 원소 1 이상).
 *               <b>중복은 1건으로 취급</b>한다 — 같은 영상을 두 번 실으면 두 번째는 자기 자신이 만든
 *               PROCESSING 클레임에 막혀 "실패"로 보고되는데, 그건 사용자가 알아야 할 실패가 아니라
 *               요청의 잡음이다.
 */
@Schema(description = "배치 일괄 재시작 요청 — 되는 것만 재기동하고 거부분은 건별 사유로 돌려준다")
public record BatchBulkRetryRequest(
        @Schema(description = "재기동 대상 영상 식별자 목록(1~100건, 각 원소 1 이상, 중복은 1건 취급)",
                example = "[12, 43, 45]")
        @NotEmpty(message = "rawSns 는 1건 이상이어야 합니다.")
        @Size(max = MAX_SIZE, message = "rawSns 는 " + MAX_SIZE + "건 이하여야 합니다.")
        List<@Min(value = 1, message = "rawSn 은 1 이상이어야 합니다.") Long> rawSns) {

    /** 1회 호출 상한 — 초과 요청은 400 으로 거부한다. */
    public static final int MAX_SIZE = 100;

    /**
     * 중복·null 을 제거한 처리 대상(요청 순서 보존).
     *
     * <p>순서를 보존하는 이유: 응답의 {@code results} 가 요청 순서와 같아야 화면이 행을 짝지을 수 있다.
     */
    public List<Long> distinctRawSns() {
        if (rawSns == null) {
            return List.of();
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long rawSn : rawSns) {
            if (rawSn != null) {
                unique.add(rawSn);
            }
        }
        return List.copyOf(unique);
    }
}
