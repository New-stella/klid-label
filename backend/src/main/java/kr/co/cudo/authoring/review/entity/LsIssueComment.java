package kr.co.cudo.authoring.review.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Phase 1(이슈 스레드) — 이슈 댓글 (LS_ISSUE_COMMENT, V57).
 *
 * <p>검수자↔작업자 양방향 소통 채널의 단위 메시지. 한 이슈(DATA_ISSUE_SN)에 N개 댓글.
 *
 * <p>보안 — AUTHOR_NO/AUTHOR_ROLE_CD 는 요청 DTO 가 아니라 인증 토큰(actor)에서만 도출한다
 * (시나리오 #1, CWE-915 Mass Assignment 방어). 외래키는 정의하지 않음(klid_system 공유 DB 정책).
 */
@Entity
@Table(name = "LS_ISSUE_COMMENT")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsIssueComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ISSUE_COMMENT_SN")
    private Long issueCommentSn;

    @Column(name = "DATA_ISSUE_SN", nullable = false)
    private Long dataIssueSn;

    @Column(name = "AUTHOR_NO", length = 50, nullable = false)
    private String authorNo;

    @Column(name = "AUTHOR_ROLE_CD", length = 20, nullable = false)
    private String authorRoleCd;

    @Column(name = "CMNT_CN", length = 1000, nullable = false)
    private String cmntCn;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Builder
    private LsIssueComment(Long dataIssueSn, String authorNo, String authorRoleCd,
                           String cmntCn, LocalDateTime regDt) {
        this.dataIssueSn = dataIssueSn;
        this.authorNo = authorNo;
        this.authorRoleCd = authorRoleCd;
        this.cmntCn = cmntCn;
        this.regDt = regDt;
    }

    /**
     * 댓글 생성. authorNo/authorRoleCd 는 호출자가 인증 토큰에서 도출한 값만 전달한다.
     */
    public static LsIssueComment create(Long dataIssueSn, String authorNo, String authorRoleCd,
                                        String content) {
        return LsIssueComment.builder()
                .dataIssueSn(dataIssueSn)
                .authorNo(authorNo)
                .authorRoleCd(authorRoleCd)
                .cmntCn(content)
                .regDt(LocalDateTime.now())
                .build();
    }
}
