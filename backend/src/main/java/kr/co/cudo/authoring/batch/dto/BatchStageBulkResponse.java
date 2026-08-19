package kr.co.cudo.authoring.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 작업 묶음 일괄 스킵·해제·재수행 결과 — <b>부분 성공</b>을 그대로 표현한다.
 * [@design API-212] [@design API-213] [@design API-214]
 *
 * <h3>필드 구성이 일괄 재처리와 같은 이유</h3>
 * <p>설계가 "한 번에 받는 상한과 중복 식별자를 한 건으로 취급하는 규칙, 요청 순서 보존, 부분 성공은
 * <b>배치 일괄 재처리와 같은 계약</b>을 따른다"고 규정한다. 필드명·의미를 그대로 맞춰 두면 화면이
 * 네 엔드포인트의 결과를 <b>같은 렌더러</b>로 그릴 수 있다. 별도 타입인 것은 Swagger 문서에서 두 축의
 * 설명(재기동 vs 묶음 조작)을 갈라 적기 위한 것이며 <b>구조가 갈려도 된다는 뜻이 아니다</b>.
 *
 * <h3>왜 all-or-nothing 이 아닌가</h3>
 * <p>영상끼리는 서로 영향을 주지 않으므로 한 건의 거부가 다른 건의 정합성을 해치지 않는다. 실패한
 * 1건 때문에 전체를 되돌리면 운영자가 그 1건을 목록에서 빼고 다시 누르는 수작업을 반복하게 된다 —
 * 벤더 연동 시점에 수천 건을 해제하는 것이 이 API 의 용도라 그 반복은 치명적이다.
 *
 * <p><b>한 건도 성공하지 못해도 200</b> 이다. HTTP 상태는 "요청을 처리했는가"를 말하고 각 건의 성패는
 * {@link #results} 가 말한다 — 건별 거부 사유를 상태 코드로 올리면 응답이 영상의 상태를 알려주는
 * 오라클이 된다(CWE-209).
 *
 * @param successCount 처리에 성공한 건수
 * @param failureCount 거부·실패한 건수
 * @param results      요청 순서(중복 제거 후)를 보존한 건별 결과
 */
@Schema(description = "작업 묶음 일괄 처리 결과 — 부분 성공. 한 건도 성공 못 해도 200이며 판정은 results 로 한다")
public record BatchStageBulkResponse(
        @Schema(description = "성공 건수", example = "2")
        int successCount,
        @Schema(description = "거부·실패 건수", example = "1")
        int failureCount,
        @Schema(description = "건별 결과(요청 순서 보존)")
        List<Item> results) {

    /**
     * 건별 결과.
     *
     * @param rawSn   영상 식별자
     * @param success 성공 여부. 재수행에서는 <b>접수</b> 성공을 뜻한다(실행은 비동기)
     * @param reason  실패 사유(사용자 문구). 성공이면 {@code null}. <b>스택트레이스·내부 경로 미포함</b>
     */
    @Schema(description = "일괄 처리 건별 결과 — 재수행의 success 는 접수 여부이지 파이프라인 완료가 아니다")
    public record Item(
            @Schema(description = "영상 식별자", example = "12") Long rawSn,
            @Schema(description = "성공 여부", example = "true") boolean success,
            @Schema(description = "실패 사유(성공이면 null)") String reason) {

        public static Item ok(Long rawSn) {
            return new Item(rawSn, true, null);
        }

        public static Item fail(Long rawSn, String reason) {
            return new Item(rawSn, false, reason);
        }
    }

    /** 건별 결과 목록에서 집계값을 파생한다 — 카운트와 목록이 어긋날 수 없게 한 곳에서 만든다. */
    public static BatchStageBulkResponse of(List<Item> results) {
        List<Item> safe = results == null ? List.of() : List.copyOf(results);
        int success = (int) safe.stream().filter(Item::success).count();
        return new BatchStageBulkResponse(success, safe.size() - success, safe);
    }
}
