package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 일괄 적재 진행 상황 (API-218).
 *
 * <h3>이 조회가 있어야 하는 이유</h3>
 * <p>적재 요청이 곧바로 반환하므로 <b>끝나는 시점을 응답으로 알 수 없다</b>. 이 조회가 그 자리를
 * 대신한다. 서버가 다시 떠도 같은 결과를 준다 — 작업과 항목이 행으로 남아 있고 처리 도중에 멈춘
 * 항목은 다시 집히기 때문이다.
 *
 * <h3>집계는 언제나 전체 기준이다</h3>
 * <p>상태로 거르는 것은 <b>담기는 목록</b>뿐이다. 걸렀다고 위쪽 수치가 함께 줄면 사람이 보는 진행률이
 * 필터에 따라 달라져 무엇이 참인지 알 수 없다.
 *
 * @param jobSn          작업 식별번호
 * @param status         작업 상태 — 완료는 모든 항목이 끝나고 실패가 없는 경우, 실패는 모든 항목이
 *                       끝났으나 실패가 남은 경우다
 * @param folderPath     이 작업이 훑은 폴더의 위치
 * @param targetCount    다루기로 한 항목 수 — 진행률의 분모
 * @param doneCount      끝난 항목 수 — 성공과 실패와 건너뜀을 모두 센다
 * @param succeededCount 적재에 성공한 항목 수
 * @param failedCount    실패했거나 건너뛴 항목 수
 * @param startedAt      첫 항목의 처리를 시작한 시각
 * @param finishedAt     마지막 항목이 끝나 작업이 종결된 시각
 * @param itemsTruncated 건별 결과가 상한에 걸려 일부만 담겼는지 여부
 * @param items          건별 결과
 * @design DOMAIN-017
 * @design API-218
 */
@Schema(description = "일괄 적재 진행 상황")
public record MarkingImportProgressResponse(
        long jobSn,
        String status,
        String folderPath,
        int targetCount,
        int doneCount,
        int succeededCount,
        int failedCount,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        boolean itemsTruncated,
        List<Item> items) {

    /**
     * 건별 결과.
     *
     * @param markingFileName 항목의 마킹 문서 이름
     * @param videoFileName   짝지은 영상 파일 이름. 짝을 찾지 못했으면 {@code null}
     * @param status          항목 상태 — 건너뜀은 짝을 찾지 못했거나 적재할 수 없는 상태여서 처리하지
     *                        않은 것이며 실패와 구분한다
     * @param rawSn           적재에 성공해 만들어진 영상의 식별번호. 성공하기 전에는 {@code null}
     * @param failureReason   실패했거나 건너뛴 사유 — 사람이 읽고 무엇을 고쳐야 하는지 알 수 있는
     *                        문장이며 내부 구조를 드러내지 않는다
     */
    @Schema(description = "건별 결과")
    public record Item(
            String markingFileName,
            String videoFileName,
            String status,
            Long rawSn,
            String failureReason) {
    }
}
