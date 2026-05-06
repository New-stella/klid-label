package kr.co.cudo.authoring.review.dto;

import kr.co.cudo.authoring.review.entity.LsDataIssue;

import java.time.LocalDateTime;

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
                i.getVideoId(),
                i.getIssueReason(),
                i.getReportedUserNo(),
                i.getRegisteredAt()
        );
    }
}
