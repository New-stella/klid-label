package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * KPST {@code GET /retrieve_report} 응답 DTO — 처리 결과 리포트. [req: R14]
 *
 * <p>규격: {@code docs/v2-wiki/22-deid-solution-api.md} §22.3.7. 처리 완료(procState=2)된
 * 데이터셋에 대해 <b>얼굴/번호판 검출 집계</b>와 처리 시각을 돌려준다.
 * <b>완료된 데이터셋이 없는 프로젝트는 결과에서 제외</b>되므로 {@code prjStatus} 가 빈 배열일 수 있다.
 *
 * <h3>진행조회 응답과 다른 점</h3>
 * <ul>
 *   <li>{@code prjStatus[]} 에 {@code prjId}/{@code prjName}/{@code dsCount} 가 <b>규격 필드표에
 *       없다</b>(실제 목서버도 {@code progressRate}+{@code dsStatus} 만 준다). 따라서 우리 원장과의
 *       매칭은 프로젝트가 아니라 {@code dsStatus[].dsId} 로 한다. {@code prjId} 는 벤더가 실어 줄
 *       수도 있어 <b>있으면 받되 판정에 쓰지 않는다</b>.</li>
 *   <li>{@code procState} 가 없다 — 완료 건만 담기는 응답이기 때문이다.</li>
 * </ul>
 *
 * <p>모든 숫자 필드는 boxed 다. 벤더가 {@code null} 을 주는 경우가 실재하고(미시작/미집계),
 * primitive 로 받으면 {@code FAIL_ON_NULL_FOR_PRIMITIVES} 로 <b>역직렬화 자체가 실패</b>해
 * 부가 정보 하나 때문에 완료 흐름이 흔들린다. 규격에 없는 추가 필드는 무시한다.
 *
 * <p>{@code startTime}/{@code endTime} 은 <b>문자열</b>로 받는다 — 실서버가 미시작 구간에서
 * {@code "None"} 을 반환하는 것이 진행조회에서 실측됐기 때문이다. 시각 해석은
 * {@code KpstDeidentReportSummary} 가 관대하게(실패 시 null) 수행한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KpstReportResponse(
        String result,
        Data data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            Integer prjCount,
            List<PrjStatus> prjStatus
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrjStatus(
            Long prjId,
            Double progressRate,
            List<DsStatus> dsStatus
    ) {
    }

    /**
     * 데이터셋(=영상 파일) 단위 리포트 1행.
     *
     * @param dsId       데이터셋 ID — 우리 원장 {@code DE_IDNTF_DATST_ID} 와의 유일한 매칭 키
     * @param fileName   실측 계약상 <b>원본 입력파일의 절대경로</b>(비식별 산출물 경로가 아니다)
     * @param faceCount  얼굴 검출 수(전체 검출 - 번호판)
     * @param lpCount    번호판(license plate) 검출 수
     * @param totalFrame 총 프레임 수
     * @param startTime  처리 시작 시각 문자열(해석 불가 값이 올 수 있다)
     * @param endTime    처리 종료 시각 문자열(해석 불가 값이 올 수 있다)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DsStatus(
            Long dsId,
            String fileName,
            Long faceCount,
            Long lpCount,
            Long totalFrame,
            String startTime,
            String endTime
    ) {
    }
}
