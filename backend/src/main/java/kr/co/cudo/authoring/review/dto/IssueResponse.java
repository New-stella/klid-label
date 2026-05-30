package kr.co.cudo.authoring.review.dto;

import kr.co.cudo.authoring.review.entity.LsDataIssue;

import java.time.LocalDateTime;

/**
 * 검수 반려 사유 응답 DTO.
 *
 * <p>DB/엔티티는 행안부 공통표준 약어(DATA_RAW_SN/ISSUE_RSN/REG_DT)로 변경되었으나,
 * API JSON 키(videoId/issueReason/registeredAt)는 FE 계약 유지를 위해 보존한다.
 * 표준 게터(getDataRawSn/getIssueRsn/getRegDt)는 {@link #from} 매핑에서만 호출한다.
 */
public record IssueResponse(
        Long dataIssueSn,
        Long upDataIssueSn,
        Long videoId,
        String issueReason,
        String reportedUserNo,
        LocalDateTime registeredAt
) {
    public static IssueResponse from(LsDataIssue i) {
        return new IssueResponse(
                i.getDataIssueSn(),
                i.getUpDataIssueSn(),
                i.getDataRawSn(),
                i.getIssueRsn(),
                i.getReportedUserNo(),
                i.getRegDt()
        );
    }
}
