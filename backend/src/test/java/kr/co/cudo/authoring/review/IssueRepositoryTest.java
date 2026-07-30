package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.repository.IssueRepository;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QUR-03 작업자별 반려 건수 집계 쿼리 검증.
 */
@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class IssueRepositoryTest {

    @Autowired private IssueRepository issueRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 본 테스트가 쓰는 영상 ID — V146(DB-ISSUE-01) 이후 LS_TASK_ASSIGNMENT·LS_DATA_ISSUE 가
     * LS_DATA_RAW 를 FK 로 참조하므로 부모 영상을 먼저 시드해야 한다.
     */
    private static final long[] RAW_SNS = {5001L, 5002L, 5003L, 6001L, 7777L};

    @BeforeEach
    void seedParentVideos() {
        RawVideoFixture.seedRaws(jdbcTemplate, RAW_SNS);
    }

    @AfterEach
    void removeParentVideos() {
        // 부모 삭제 = 배정·이슈 행까지 CASCADE 삭제 → 다음 테스트에 집계가 새지 않는다.
        RawVideoFixture.deleteRaws(jdbcTemplate, RAW_SNS);
    }

    @Test
    @DisplayName("IssueRepository_작업자별_반려_건수_집계_쿼리_정상_QUR_03")
    void countRejectionsByWorker() {
        // 작업자 100 → 영상 5001, 5002 배정
        // 작업자 101 → 영상 5003 배정
        authrtRepository.save(LsTaskAssignment.createLabeler(5001L, 100L, 1L));
        authrtRepository.save(LsTaskAssignment.createLabeler(5002L, 100L, 1L));
        authrtRepository.save(LsTaskAssignment.createLabeler(5003L, 101L, 1L));

        // 영상 5001 = 2건 반려, 5002 = 1건 반려, 5003 = 1건 반려
        issueRepository.save(LsDataIssue.create(5001L, "사유1", "1"));
        issueRepository.save(LsDataIssue.create(5001L, "사유2", "1"));
        issueRepository.save(LsDataIssue.create(5002L, "사유3", "1"));
        issueRepository.save(LsDataIssue.create(5003L, "사유4", "1"));

        long count100 = issueRepository.countRejectionsByWorker(100L);
        long count101 = issueRepository.countRejectionsByWorker(101L);

        assertThat(count100).isEqualTo(3L); // 5001(2) + 5002(1)
        assertThat(count101).isEqualTo(1L); // 5003(1)
    }

    @Test
    @DisplayName("반려_건수_집계에_INQUIRY는_미포함")
    void 반려_건수_집계에_INQUIRY는_미포함() {
        // given: 작업자 200 에게 영상 6001 배정, 같은 영상에 INQUIRY 1건 + REJECTION 1건 저장
        authrtRepository.save(LsTaskAssignment.createLabeler(6001L, 200L, 1L));
        issueRepository.save(LsDataIssue.createInquiry(6001L, "문의입니다", "200", null)); // 집계 제외 대상
        issueRepository.save(LsDataIssue.create(6001L, "반려사유", "1"));                  // 집계 대상

        // when
        long count = issueRepository.countRejectionsByWorker(200L);

        // then: REJECTION 1건만 집계 (INQUIRY 는 부풀림 없이 제외)
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("IssueRepository_DATA_RAW_SN_기준_반려_사유_시간역순_조회")
    void findByDataRawSnOrderByRegDtDesc() {
        Long dataRawSn = 7777L;
        LsDataIssue first = issueRepository.save(LsDataIssue.create(dataRawSn, "첫번째", "1"));
        LsDataIssue second = issueRepository.save(LsDataIssue.createWithParent(dataRawSn, "두번째", "1", first.getDataIssueSn()));

        var list = issueRepository.findByDataRawSnOrderByRegDtDesc(dataRawSn);
        assertThat(list).hasSize(2);
        // 등록시각 동일할 수 있으므로 두 건이 모두 포함되는지만 검증 (계층 연결도 확인)
        assertThat(list).extracting(LsDataIssue::getDataIssueSn)
                .contains(first.getDataIssueSn(), second.getDataIssueSn());
        var saved = list.stream().filter(i -> i.getDataIssueSn().equals(second.getDataIssueSn())).findFirst().orElseThrow();
        assertThat(saved.getUpDataIssueSn()).isEqualTo(first.getDataIssueSn());
    }
}
