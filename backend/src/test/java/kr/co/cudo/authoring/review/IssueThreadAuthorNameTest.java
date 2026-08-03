package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.review.dto.IssueCommentResponse;
import kr.co.cudo.authoring.review.dto.IssueThreadResponse;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.entity.LsIssueComment;
import kr.co.cudo.authoring.review.repository.IssueCommentRepository;
import kr.co.cudo.authoring.review.repository.IssueRepository;
import kr.co.cudo.authoring.review.service.IssueThreadService;
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이슈 스레드 작성자 <b>이름</b> 노출 (SCR-LABEL-001 / SCR-REVIEW-002 이슈 탭).
 *
 * <p>화면이 작성자를 역할 코드(WORKER/REVIEWER)로만 표시해 "누가 썼는지" 알 수 없던 결함의 회귀 가드.
 * 고정하는 계약:
 * <ul>
 *   <li>댓글·스레드 작성자 이름을 <b>사용자 마스터 배치 조회 1회</b>로 매핑한다 (N+1 금지).</li>
 *   <li>사용자 마스터에 없거나 사번이 숫자가 아니면 이름은 {@code null} — <b>예외를 던지지 않는다</b>
 *       (작성자가 삭제·변경돼도 스레드 조회는 살아 있어야 한다).</li>
 * </ul>
 */
class IssueThreadAuthorNameTest {

    private IssueRepository issueRepository;
    private IssueCommentRepository commentRepository;
    private LsTaskAssignmentRepository assignmentRepository;
    private LsDataSrcRepository srcRepository;
    private UserRepository userRepository;
    private IssueThreadService service;

    private static final Long RAW_SN = 1000L;
    private static final TokenClaims REVIEWER =
            new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, null);

    @BeforeEach
    void setUp() {
        issueRepository = mock(IssueRepository.class);
        commentRepository = mock(IssueCommentRepository.class);
        assignmentRepository = mock(LsTaskAssignmentRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        userRepository = mock(UserRepository.class);
        service = new IssueThreadService(
                issueRepository, commentRepository, assignmentRepository, srcRepository, userRepository);
        lenient().when(userRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());
    }

    // ---------------------------------------------------------------- fixtures

    private static LsDataIssue issue(Long issueSn, String reportedUserNo) {
        LsDataIssue i = mock(LsDataIssue.class);
        lenient().when(i.getDataIssueSn()).thenReturn(issueSn);
        lenient().when(i.getDataRawSn()).thenReturn(RAW_SN);
        lenient().when(i.getIssueTypeCd()).thenReturn("INQUIRY");
        lenient().when(i.getIssueSttsCd()).thenReturn("OPEN");
        lenient().when(i.getIssueRsn()).thenReturn("확인 부탁드립니다");
        lenient().when(i.getReportedUserNo()).thenReturn(reportedUserNo);
        lenient().when(i.getRegDt()).thenReturn(LocalDateTime.of(2026, 8, 1, 10, 0));
        return i;
    }

    private static LsIssueComment comment(Long commentSn, Long issueSn, String authorNo, String roleCd) {
        LsIssueComment c = mock(LsIssueComment.class);
        lenient().when(c.getIssueCommentSn()).thenReturn(commentSn);
        lenient().when(c.getDataIssueSn()).thenReturn(issueSn);
        lenient().when(c.getAuthorNo()).thenReturn(authorNo);
        lenient().when(c.getAuthorRoleCd()).thenReturn(roleCd);
        lenient().when(c.getCmntCn()).thenReturn("답변드립니다");
        lenient().when(c.getRegDt()).thenReturn(LocalDateTime.of(2026, 8, 1, 11, 0));
        return c;
    }

    private static MngAcctUser user(Long userNo, String userNm) {
        MngAcctUser u = mock(MngAcctUser.class);
        lenient().when(u.getUserNo()).thenReturn(userNo);
        lenient().when(u.getUserNm()).thenReturn(userNm);
        return u;
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("댓글_작성자_이름이_사용자마스터에서_매핑된다")
    void commentAuthorNameIsResolved() {
        // given — 검수자 1(검수자1) 이 쓴 댓글 1건
        // (mock 픽스처는 when(...) 밖에서 먼저 만든다 — 중첩 스터빙 금지)
        List<LsDataIssue> issues = List.of(issue(10L, "100"));
        List<LsIssueComment> comments = List.of(comment(1L, 10L, "1", "REVIEWER"));
        List<MngAcctUser> users = List.of(user(1L, "검수자1"), user(100L, "작업자100"));
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(issues);
        when(commentRepository.findByDataIssueSnInOrderByRegDtAsc(anyList())).thenReturn(comments);
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(users);

        // when
        List<IssueThreadResponse> threads = service.listThreads(RAW_SN, REVIEWER);

        // then — 사번·역할은 그대로 두고 이름만 추가된다(하위호환)
        IssueCommentResponse c = threads.get(0).comments().get(0);
        assertThat(c.authorName()).isEqualTo("검수자1");
        assertThat(c.authorNo()).isEqualTo("1");
        assertThat(c.authorRoleCd()).isEqualTo("REVIEWER");
    }

    @Test
    @DisplayName("스레드_작성자_이름도_같은_배치조회로_내려간다")
    void threadReporterNameIsResolved() {
        // given — 스레드 작성자는 작업자100
        List<LsDataIssue> issues = List.of(issue(10L, "100"));
        List<MngAcctUser> users = List.of(user(100L, "작업자100"));
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(issues);
        when(commentRepository.findByDataIssueSnInOrderByRegDtAsc(anyList())).thenReturn(List.of());
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(users);

        // when
        List<IssueThreadResponse> threads = service.listThreads(RAW_SN, REVIEWER);

        // then
        assertThat(threads.get(0).reportedUserName()).isEqualTo("작업자100");
        assertThat(threads.get(0).reportedUserNo()).isEqualTo("100");
    }

    @Test
    @DisplayName("사용자마스터에_없는_작성자는_이름이_null이고_조회는_실패하지_않는다")
    void unknownAuthorFallsBackToNullName() {
        // given — 사용자 마스터에 999 가 없음(퇴사·삭제 시나리오)
        List<LsDataIssue> issues = List.of(issue(10L, "999"));
        List<LsIssueComment> comments = List.of(comment(1L, 10L, "999", "WORKER"));
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(issues);
        when(commentRepository.findByDataIssueSnInOrderByRegDtAsc(anyList())).thenReturn(comments);
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());

        // when
        List<IssueThreadResponse> threads = service.listThreads(RAW_SN, REVIEWER);

        // then — 예외 없이 null (FE 가 사번으로 폴백)
        assertThat(threads.get(0).reportedUserName()).isNull();
        assertThat(threads.get(0).comments().get(0).authorName()).isNull();
        assertThat(threads.get(0).comments().get(0).authorNo()).isEqualTo("999");
    }

    @Test
    @DisplayName("숫자가_아닌_사번은_조회대상에서_빠지고_이름은_null이다")
    void nonNumericAuthorNoIsSkipped() {
        // given — 레거시/외부 채널 사번이 숫자가 아닌 경우
        List<LsDataIssue> issues = List.of(issue(10L, "rev01"));
        List<LsIssueComment> comments = List.of(comment(1L, 10L, "wkr01", "WORKER"));
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(issues);
        when(commentRepository.findByDataIssueSnInOrderByRegDtAsc(anyList())).thenReturn(comments);

        // when
        List<IssueThreadResponse> threads = service.listThreads(RAW_SN, REVIEWER);

        // then — NumberFormatException 으로 스레드 조회 전체가 죽으면 안 된다
        assertThat(threads.get(0).reportedUserName()).isNull();
        assertThat(threads.get(0).comments().get(0).authorName()).isNull();
        // 파싱 가능한 사번이 하나도 없으면 사용자 조회 자체를 하지 않는다(불필요 쿼리 제거)
        verify(userRepository, times(0)).findByUserNoIn(anyCollection());
    }

    @Test
    @DisplayName("댓글이_N건이어도_사용자_조회는_1회다_N플러스1_금지")
    void userLookupIsBatchedIntoSingleQuery() {
        // given — 스레드 3개 · 댓글 9건(작성자 3명 중복)
        List<LsDataIssue> issues = List.of(issue(10L, "100"), issue(11L, "101"), issue(12L, "1"));
        List<LsIssueComment> comments = new ArrayList<>();
        long sn = 1L;
        for (long issueSn = 10L; issueSn <= 12L; issueSn++) {
            comments.add(comment(sn++, issueSn, "1", "REVIEWER"));
            comments.add(comment(sn++, issueSn, "100", "WORKER"));
            comments.add(comment(sn++, issueSn, "101", "WORKER"));
        }
        List<MngAcctUser> users =
                List.of(user(1L, "검수자1"), user(100L, "작업자100"), user(101L, "작업자101"));
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(issues);
        when(commentRepository.findByDataIssueSnInOrderByRegDtAsc(anyList())).thenReturn(comments);
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(users);

        // when
        List<IssueThreadResponse> threads = service.listThreads(RAW_SN, REVIEWER);

        // then — 조회 1회 · 중복 제거된 사번 3개만 전달
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(userRepository, times(1)).findByUserNoIn(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(1L, 100L, 101L);
        assertThat(threads).hasSize(3);
        assertThat(threads.get(0).comments())
                .extracting(IssueCommentResponse::authorName)
                .containsExactly("검수자1", "작업자100", "작업자101");
    }

    @Test
    @DisplayName("이슈가_없으면_사용자조회도_하지_않는다")
    void noIssuesMeansNoUserLookup() {
        // given
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(List.of());

        // when
        List<IssueThreadResponse> threads = service.listThreads(RAW_SN, REVIEWER);

        // then
        assertThat(threads).isEmpty();
        verify(userRepository, times(0)).findByUserNoIn(anyCollection());
    }
}
