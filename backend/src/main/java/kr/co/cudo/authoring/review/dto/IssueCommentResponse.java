package kr.co.cudo.authoring.review.dto;

import kr.co.cudo.authoring.review.entity.LsIssueComment;

import java.time.LocalDateTime;

/**
 * 이슈 댓글 응답 DTO.
 *
 * <p>보안 — {@code authorNo}(작성자 사번) 노출은 내부 도구(WORKER/REVIEWER 전용) 기준 의도된 노출이다.
 * 검수자↔작업자 소통 채널에서 작성 주체 식별이 필요하기 때문이다. PORTAL(외부 채널) 재사용 시에는
 * 사번이 외부에 노출되므로 이 DTO 를 그대로 쓰지 말고 별도 마스킹 DTO 를 사용해야 한다.
 */
public record IssueCommentResponse(
        Long commentSn,
        String authorNo,
        String authorRoleCd,
        String content,
        LocalDateTime regDt
) {
    public static IssueCommentResponse from(LsIssueComment c) {
        return new IssueCommentResponse(
                c.getIssueCommentSn(),
                c.getAuthorNo(),
                c.getAuthorRoleCd(),
                c.getCmntCn(),
                c.getRegDt()
        );
    }
}
