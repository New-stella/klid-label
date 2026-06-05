package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.entity.LsIssueComment;
import kr.co.cudo.authoring.review.repository.IssueCommentRepository;
import kr.co.cudo.authoring.review.repository.IssueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V57 마이그레이션 + 이슈 스레드 리포지토리 검증 (Testcontainers PostgreSQL + Flyway).
 */
@SpringBootTest
@ActiveProfiles("local")
class IssueThreadRepositoryTest {

    @Autowired private IssueRepository issueRepository;
    @Autowired private IssueCommentRepository commentRepository;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Test
    @DisplayName("V57_적용후_기존_반려행은_REJECTION_RESOLVED_로_backfill_된다")
    void rejectionRowBackfilledType() {
        // 기존 반려 경로(LsDataIssue.create) — V57 backfill 규칙과 동일하게 REJECTION/RESOLVED.
        LsDataIssue saved = issueRepository.save(LsDataIssue.create(9001L, "반려", "1"));
        LsDataIssue found = issueRepository.findById(saved.getDataIssueSn()).orElseThrow();
        assertThat(found.getIssueTypeCd()).isEqualTo("REJECTION");
        assertThat(found.getIssueSttsCd()).isEqualTo("RESOLVED");
        assertThat(found.getVersion()).isNotNull();
    }

    @Test
    @DisplayName("LS_ISSUE_COMMENT_테이블이_V57로_생성되어_댓글_저장_조회된다")
    void issueCommentTableCreated() {
        LsDataIssue issue = issueRepository.save(LsDataIssue.createInquiry(9002L, "문의", "100", null));
        commentRepository.save(LsIssueComment.create(issue.getDataIssueSn(), "1", "REVIEWER", "답변"));

        var comments = commentRepository.findByDataIssueSnOrderByRegDtAsc(issue.getDataIssueSn());
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).getAuthorRoleCd()).isEqualTo("REVIEWER");
    }

    @Test
    @DisplayName("미해소_문의_카운트는_OPEN_ANSWERED만_합산하고_REJECTION과_RESOLVED는_제외")
    void countUnresolvedInquiries() {
        Long rawSn = 9003L;
        issueRepository.save(LsDataIssue.createInquiry(rawSn, "open", "100", null));   // OPEN
        LsDataIssue answered = LsDataIssue.createInquiry(rawSn, "answered", "100", null);
        answered.markAnswered();
        issueRepository.save(answered);                                                 // ANSWERED
        LsDataIssue resolved = LsDataIssue.createInquiry(rawSn, "resolved", "100", null);
        resolved.resolve();
        issueRepository.save(resolved);                                                 // RESOLVED (제외)
        issueRepository.save(LsDataIssue.create(rawSn, "rejection", "1"));              // REJECTION (제외)

        assertThat(issueRepository.countUnresolvedInquiries(rawSn)).isEqualTo(2L);
    }

    @Test
    @DisplayName("SRC_SN_부분인덱스가_생성되어_있다")
    void srcSnPartialIndexExists() {
        JdbcTemplate jdbc = new JdbcTemplate(controlDataSource);
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'ix_ls_data_issue_src'",
                Integer.class);
        assertThat(cnt).isEqualTo(1);
    }
}
