package kr.co.cudo.authoring.review.dto;

import kr.co.cudo.authoring.review.entity.LsDataIssue;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 이슈 스레드 응답 DTO — 이슈 1건 + 그에 달린 댓글 목록(시간순).
 *
 * <p>반려(REJECTION) 이력과 문의(INQUIRY) 가 동일 스레드 목록으로 통합 조회된다.
 *
 * <p>보안 — {@code reportedUserNo}(피신고/작성 대상 사번)/{@code reportedUserName}(이름) 노출은
 * 내부 도구(WORKER/REVIEWER 전용) 기준 의도된 노출이다. PORTAL(외부 채널) 재사용 시 사번·실명이
 * 외부에 노출되므로 이 DTO 를 그대로 쓰지 말고 별도 마스킹 DTO 를 사용해야 한다.
 *
 * @param reportedUserName 작성자 이름. 사용자 마스터에 없거나 사번이 숫자가 아니면 {@code null}
 *                         (화면은 사번으로 폴백한다).
 * @param reportedUserRoleCd 작성자 역할 코드({@code WORKER}/{@code REVIEWER}/{@code PORTAL_USER}).
 *                           문의를 검수자도 등록할 수 있게 된 뒤로 "누가 낸 문의인가"가 실질적 의미를
 *                           가지는데, 스레드 응답에는 역할 축이 없어 화면이 이름·사번만 보여 주고 있었다
 *                           (댓글({@link IssueCommentResponse#authorRoleCd})에는 이미 있었다).
 *                           <p>댓글의 {@code authorRoleCd} 와 <b>같은 값 공간·같은 타입</b>이며 화면도
 *                           같은 표기 헬퍼를 쓴다. 이름은 {@code reportedUser*} 접두를 따라 짝을 이루는
 *                           {@code reportedUserNo}/{@code reportedUserName} 와 한 묶음임을 드러낸다 —
 *                           같은 응답 안에 {@code comments[].authorRoleCd} 가 이미 있어 그 이름을 그대로
 *                           쓰면 <b>스레드 작성자와 댓글 작성자가 같은 이름으로 갈린다</b>.
 *                           <p>해석 실패(사용자 역할 매핑 미존재·비숫자 사번·null 사번)는 {@code null} 이며
 *                           지어내지 않는다. 화면은 역할 없이 이름만 보여 준다.
 *                           <p>⚠ 댓글과 <b>해석 시점이 다르다</b>: 댓글은 작성 시점 역할을 컬럼
 *                           ({@code AUTHOR_ROLE_CD})에 박아 두지만, 스레드에는 그 컬럼이 없어
 *                           <b>조회 시점의 현재 역할</b>을 사용자 역할 매핑에서 읽는다. 따라서 작성 후
 *                           역할이 바뀐 사용자는 새 역할로 표시된다.
 */
public record IssueThreadResponse(
        Long issueSn,
        String issueTypeCd,
        String issueSttsCd,
        Long srcSn,
        String reason,
        String reportedUserNo,
        String reportedUserName,
        String reportedUserRoleCd,
        LocalDateTime regDt,
        List<IssueCommentResponse> comments
) {
    public static IssueThreadResponse from(LsDataIssue issue,
                                           String reportedUserName,
                                           String reportedUserRoleCd,
                                           List<IssueCommentResponse> comments) {
        return new IssueThreadResponse(
                issue.getDataIssueSn(),
                issue.getIssueTypeCd(),
                issue.getIssueSttsCd(),
                issue.getSrcSn(),
                issue.getIssueRsn(),
                issue.getReportedUserNo(),
                reportedUserName,
                reportedUserRoleCd,
                issue.getRegDt(),
                comments
        );
    }
}
