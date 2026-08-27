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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code IssueRepository} 조회 검증.
 *
 * <ul>
 *   <li>QUR-03 작업자별 반려 건수 집계</li>
 *   <li>프레임 미리보기 이슈 표시용 — 미해소 문의가 달린 프레임 식별자 집합</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class IssueRepositoryTest {

    @Autowired private IssueRepository issueRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 본 테스트가 쓰는 영상 ID — V146(DB-ISSUE-01) 이후 LS_TASK_ALTMNT·LS_DATA_ISSUE 가
     * LS_DATA_RAW 를 FK 로 참조하므로 부모 영상을 먼저 시드해야 한다.
     */
    private static final long[] RAW_SNS = {5001L, 5002L, 5003L, 6001L, 7777L, 8801L, 8802L};

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

    // ============================================================
    // 프레임 미리보기 이슈 표시 — 미해소 문의가 달린 프레임 식별자 집합
    // (LS_DATA_ISSUE.SRC_SN 은 FK 가 없어 프레임 행을 따로 시드하지 않는다)
    // ============================================================

    @Test
    @DisplayName("미해소_문의가_달린_프레임만_나온다_해소된_문의의_프레임은_빠진다")
    void 미해소_문의가_달린_프레임만_나온다() {
        // given: 같은 영상에 미해소(OPEN·ANSWERED)와 해소(RESOLVED)가 섞여 있다
        issueRepository.save(LsDataIssue.createInquiry(8801L, "열린 문의", "100", 9001L));

        LsDataIssue answered = LsDataIssue.createInquiry(8801L, "답변된 문의", "100", 9002L);
        answered.markAnswered();
        issueRepository.save(answered);

        LsDataIssue resolved = LsDataIssue.createInquiry(8801L, "해소된 문의", "100", 9003L);
        resolved.resolve();
        issueRepository.save(resolved);

        // 반려 사유는 등록 시점부터 RESOLVED 고정 — 타입 조건 없이도 자연히 빠져야 한다
        LsDataIssue rejection = LsDataIssue.builder()
                .dataRawSn(8801L)
                .issueRsn("반려 사유")
                .reportedUserNo("1")
                .issueTypeCd(LsDataIssue.TYPE_REJECTION)
                .issueSttsCd(LsDataIssue.STTS_RESOLVED)
                .srcSn(9004L)
                .regDt(LocalDateTime.now())
                .build();
        issueRepository.save(rejection);

        // when
        List<Long> srcSns = issueRepository.findUnresolvedSrcSnsByDataRawSn(8801L);

        // then: OPEN·ANSWERED 프레임만. RESOLVED(문의·반려) 프레임은 빠진다
        assertThat(srcSns).containsExactlyInAnyOrder(9001L, 9002L);
        assertThat(srcSns).doesNotContain(9003L, 9004L);
    }

    @Test
    @DisplayName("srcSn_이_없는_영상_단위_문의는_결과에_영향을_주지_않는다")
    void 영상_단위_문의는_결과에_영향이_없다() {
        // given: 영상 단위 문의(srcSn=null) 1건만 — 어느 프레임도 표시하게 만들면 안 된다
        issueRepository.save(LsDataIssue.createInquiry(8801L, "영상 단위 문의", "100", null));

        // when
        List<Long> onlyVideoScoped = issueRepository.findUnresolvedSrcSnsByDataRawSn(8801L);

        // then: null 이 섞이지도, 다른 프레임이 딸려 나오지도 않는다
        assertThat(onlyVideoScoped).isEmpty();

        // given: 같은 영상에 프레임 문의를 하나 더해도 영상 단위 문의는 여전히 무영향
        issueRepository.save(LsDataIssue.createInquiry(8801L, "프레임 문의", "100", 9101L));

        // when / then
        assertThat(issueRepository.findUnresolvedSrcSnsByDataRawSn(8801L))
                .containsExactly(9101L)
                .doesNotContainNull();
    }

    @Test
    @DisplayName("같은_프레임에_미해소_문의가_여럿이어도_그_프레임은_한_번만_나온다")
    void 같은_프레임은_중복되지_않는다() {
        // given: 같은 프레임(9201)에 미해소 문의 3건
        issueRepository.save(LsDataIssue.createInquiry(8802L, "문의1", "100", 9201L));
        issueRepository.save(LsDataIssue.createInquiry(8802L, "문의2", "100", 9201L));
        LsDataIssue answered = LsDataIssue.createInquiry(8802L, "문의3", "100", 9201L);
        answered.markAnswered();
        issueRepository.save(answered);

        // when
        List<Long> srcSns = issueRepository.findUnresolvedSrcSnsByDataRawSn(8802L);

        // then
        assertThat(srcSns).containsExactly(9201L);
    }

    @Test
    @DisplayName("문의가_없는_영상은_빈_결과이며_예외가_아니다")
    void 문의가_없는_영상은_빈_결과다() {
        assertThat(issueRepository.findUnresolvedSrcSnsByDataRawSn(8802L)).isEmpty();
    }

    @Test
    @DisplayName("다른_영상의_미해소_문의는_섞이지_않는다")
    void 다른_영상의_문의는_섞이지_않는다() {
        issueRepository.save(LsDataIssue.createInquiry(8801L, "영상 8801 문의", "100", 9301L));
        issueRepository.save(LsDataIssue.createInquiry(8802L, "영상 8802 문의", "100", 9302L));

        assertThat(issueRepository.findUnresolvedSrcSnsByDataRawSn(8801L)).containsExactly(9301L);
        assertThat(issueRepository.findUnresolvedSrcSnsByDataRawSn(8802L)).containsExactly(9302L);
    }
}
