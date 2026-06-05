package kr.co.cudo.authoring.review.dto;

import kr.co.cudo.authoring.review.entity.LsDataIssue;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 이슈 스레드 응답 DTO — 이슈 1건 + 그에 달린 댓글 목록(시간순).
 *
 * <p>반려(REJECTION) 이력과 문의(INQUIRY) 가 동일 스레드 목록으로 통합 조회된다.
 *
 * <p>보안 — {@code reportedUserNo}(피신고/작성 대상 사번) 노출은 내부 도구(WORKER/REVIEWER 전용)
 * 기준 의도된 노출이다. PORTAL(외부 채널) 재사용 시 사번이 외부에 노출되므로 이 DTO 를 그대로 쓰지 말고
 * 별도 마스킹 DTO 를 사용해야 한다.
 */
public record IssueThreadResponse(
        Long issueSn,
        String issueTypeCd,
        String issueSttsCd,
        Long srcSn,
        String reason,
        String reportedUserNo,
        LocalDateTime regDt,
        List<IssueCommentResponse> comments
) {
    public static IssueThreadResponse from(LsDataIssue issue, List<IssueCommentResponse> comments) {
        return new IssueThreadResponse(
                issue.getDataIssueSn(),
                issue.getIssueTypeCd(),
                issue.getIssueSttsCd(),
                issue.getSrcSn(),
                issue.getIssueRsn(),
                issue.getReportedUserNo(),
                issue.getRegDt(),
                comments
        );
    }
}
