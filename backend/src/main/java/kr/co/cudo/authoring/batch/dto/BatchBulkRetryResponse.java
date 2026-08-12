package kr.co.cudo.authoring.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 배치 일괄 재시작 결과 — <b>부분 성공</b>을 그대로 표현한다. [@design API-199]
 *
 * <h3>왜 all-or-nothing 이 아닌가</h3>
 * <p>일괄 배정({@code POST /v1/assignments})은 전부 실패시키는데, 그건 "승인된 영상에 배정 금지" 같은
 * <b>무결성 조건</b>이 하나라도 깨지면 나머지 배정도 뜻이 없기 때문이다. 재기동에는 그런 조건이 없다 —
 * 10건 중 2건이 이미 진행 중이라고 나머지 8건을 못 돌릴 이유가 없고, 오히려 실패한 1건 때문에 전체를
 * 되돌리면 운영자가 그 1건을 목록에서 빼고 다시 누르는 수작업을 반복하게 된다.
 *
 * <p><b>한 건도 성공하지 못해도 200</b> 이다. HTTP 상태는 "요청을 처리했는가"를 말하고, 각 건의 성패는
 * {@link #results} 가 말한다. 판정은 상태코드가 아니라 결과 목록으로 한다.
 *
 * @param successCount 재기동에 성공한 건수
 * @param failureCount 거부·실패한 건수
 * @param results      요청 순서(중복 제거 후)를 보존한 건별 결과
 */
@Schema(description = "배치 일괄 재시작 결과 — 부분 성공. 한 건도 성공 못 해도 200이며 판정은 results 로 한다")
public record BatchBulkRetryResponse(
        @Schema(description = "재기동 성공 건수", example = "2")
        int successCount,
        @Schema(description = "거부·실패 건수", example = "1")
        int failureCount,
        @Schema(description = "건별 결과(요청 순서 보존)")
        List<Item> results) {

    /**
     * 건별 결과.
     *
     * @param rawSn   영상 식별자
     * @param success 재기동 <b>접수</b> 성공 여부 — 파이프라인 완료가 아니다(실행은 비동기)
     * @param reason  실패 사유(사용자 문구). 성공이면 {@code null}. <b>스택트레이스·내부 경로 미포함</b>
     */
    @Schema(description = "일괄 재시작 건별 결과 — success 는 접수 여부이지 파이프라인 완료가 아니다")
    public record Item(
            @Schema(description = "영상 식별자", example = "12") Long rawSn,
            @Schema(description = "재기동 접수 성공 여부(파이프라인 완료 아님)", example = "true") boolean success,
            @Schema(description = "실패 사유(성공이면 null)") String reason) {

        public static Item ok(Long rawSn) {
            return new Item(rawSn, true, null);
        }

        public static Item fail(Long rawSn, String reason) {
            return new Item(rawSn, false, reason);
        }
    }

    /** 건별 결과 목록에서 집계값을 파생한다 — 카운트와 목록이 어긋날 수 없게 한 곳에서 만든다. */
    public static BatchBulkRetryResponse of(List<Item> results) {
        List<Item> safe = results == null ? List.of() : List.copyOf(results);
        int success = (int) safe.stream().filter(Item::success).count();
        return new BatchBulkRetryResponse(success, safe.size() - success, safe);
    }
}
