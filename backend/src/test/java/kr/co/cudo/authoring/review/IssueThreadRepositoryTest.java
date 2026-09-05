package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.entity.LsIssueComment;
import kr.co.cudo.authoring.review.repository.IssueCommentRepository;
import kr.co.cudo.authoring.review.repository.IssueRepository;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** 본 테스트가 쓰는 영상 ID — V146 이후 LS_DATA_ISSUE 가 LS_DATA_RAW 를 FK 로 참조한다. */
    private static final long[] RAW_SNS = {9001L, 9002L, 9003L, 9004L};

    @BeforeEach
    void seedParentVideos() {
        RawVideoFixture.seedRaws(controlDataSource, RAW_SNS);
    }

    @AfterEach
    void removeParentVideos() {
        // 부모 삭제 = 이슈 행 CASCADE 삭제. ★V10 이후 LS_ISSUE_COMMENT 는 RESTRICT FK 라 CASCADE 로
        // 지워지지 않고 <부모 삭제를 막는다> — RawVideoFixture.deleteRaws 가 댓글을 먼저 지운다.
        // (구 주석 "이슈 FK 가 없어 그대로 남는다 — 기존 동작" 은 바로 그 고아 결함의 서술이었다.)
        RawVideoFixture.deleteRaws(controlDataSource, RAW_SNS);
    }

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

    /**
     * ★ 관리자 댓글이 <실제 DB 에> 들어간다 — CHECK 제약(V27)이 관리자를 거부하지 않는다.
     *
     * <p><b>왜 이 자리에 또 만드나</b>: 같은 시나리오를 이미 검증하던 시험이 있었는데도 결함이
     * 그대로 나갔다. 그 시험이 순수 목이라 저장이 흉내였고, 그래서 <b>CHECK 제약이 한 번도
     * 실행되지 않았다</b>. 여기는 진짜 PostgreSQL 을 쓰는 자리라 저장이 제약을 실제로 지난다.
     * 이 축을 목으로 대체하지 말 것 — 대체하는 순간 같은 방식으로 또 통과한다.
     *
     * <p>{@code saveAndFlush} 인 것도 의도다. {@code save} 만 하면 flush 가 뒤로 밀려 제약이
     * 이 단언 밖에서 터진다.
     *
     * [design: ERD-023]
     */
    @Test
    @DisplayName("★관리자_역할_댓글이_실제_DB에_저장되고_되읽어도_ADMIN이다")
    void adminRoleCommentPersists() {
        LsDataIssue issue = issueRepository.save(LsDataIssue.createInquiry(9004L, "문의", "100", null));

        LsIssueComment saved = commentRepository.saveAndFlush(
                LsIssueComment.create(issue.getDataIssueSn(), "admin-1", "ADMIN", "관리자 답변"));

        LsIssueComment found = commentRepository.findById(saved.getIssueCommentSn()).orElseThrow();
        assertThat(found.getAuthorRoleCd()).isEqualTo("ADMIN");
        assertThat(readRoleDirect(saved.getIssueCommentSn())).isEqualTo("ADMIN");
    }

    /**
     * 넓히기가 기존 두 역할을 건드리지 않았음 — 회귀 축. [design: ERD-023]
     */
    @Test
    @DisplayName("기존_WORKER_REVIEWER_댓글_저장_경로가_그대로_동작한다")
    void existingRolesStillPersist() {
        LsDataIssue issue = issueRepository.save(LsDataIssue.createInquiry(9004L, "문의", "100", null));

        LsIssueComment worker = commentRepository.saveAndFlush(
                LsIssueComment.create(issue.getDataIssueSn(), "worker-1", "WORKER", "작업자 댓글"));
        LsIssueComment reviewer = commentRepository.saveAndFlush(
                LsIssueComment.create(issue.getDataIssueSn(), "reviewer-1", "REVIEWER", "검수자 댓글"));

        assertThat(readRoleDirect(worker.getIssueCommentSn())).isEqualTo("WORKER");
        assertThat(readRoleDirect(reviewer.getIssueCommentSn())).isEqualTo("REVIEWER");
    }

    /**
     * ★ 제약을 <없앤> 것이 아니라 <넓힌> 것임을 증명하는 축. 셋 밖의 값은 여전히 거부된다.
     *
     * <p>포털 회원은 내부 전용인 이 스레드에 애초에 들어올 수 없다 — 창구에서 이미 막히지만,
     * 그 위층이 뚫려도 표가 마지막으로 거부한다는 것이 이 단언의 뜻이다.
     *
     * [design: ERD-023]
     */
    @Test
    @DisplayName("★허용목록_밖_역할은_여전히_제약위반으로_거부된다")
    void roleOutsideAllowlistRejected() {
        LsDataIssue issue = issueRepository.save(LsDataIssue.createInquiry(9004L, "문의", "100", null));

        assertThatThrownBy(() -> commentRepository.saveAndFlush(
                LsIssueComment.create(issue.getDataIssueSn(), "portal-1", "PORTAL_USER", "외부 댓글")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 거부는 <저장되지 않음> 까지 확인해야 성립한다.
        assertThat(commentRepository.findByDataIssueSnOrderByRegDtAsc(issue.getDataIssueSn())).isEmpty();
    }

    /** 영속 계층을 거치지 않고 컬럼 원값을 확인한다(1차 캐시·매핑에 기대지 않는 증거). */
    private String readRoleDirect(Long commentSn) {
        return new JdbcTemplate(controlDataSource).queryForObject(
                "SELECT author_role_cd FROM ls_issue_comment WHERE cmnt_sn = ?", String.class, commentSn);
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
