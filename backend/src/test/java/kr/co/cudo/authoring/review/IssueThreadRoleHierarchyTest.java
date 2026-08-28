package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.review.dto.IssueCommentRequest;
import kr.co.cudo.authoring.review.dto.IssueCreateRequest;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.entity.LsIssueComment;
import kr.co.cudo.authoring.review.repository.IssueCommentRepository;
import kr.co.cudo.authoring.review.repository.IssueRepository;
import kr.co.cudo.authoring.review.service.IssueThreadService;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IssueThreadService} — <b>역할 계층(관리자 &gt; 검수자) 반영</b> 회귀 가드.
 * [design: ADR-055] [design: ROLE-004] [design: AC-125] [design: API-104]
 *
 * <h3>고정하는 자리</h3>
 * <ul>
 *   <li><b>문의 등록·스레드 조회·댓글 작성</b> — 셋 다 「작업자 분기 → 그 외 거부」 체인이라
 *       동등 비교로 두면 관리자가 어느 분기에도 안 걸려 이슈 탭이 통째로 403 이 된다.</li>
 *   <li><b>상태 자동 전이</b>({@code markAnswered}) — 관리자 댓글도 OPEN INQUIRY 를 ANSWERED 로
 *       전이시켜야 한다. 빼면 관리자가 답변해도 이슈가 대기 상태에 남는다(API-104 v13).</li>
 *   <li><b>이슈 해소</b> — 창구의 「검수자 전용」은 「검수자 이상」이라 관리자도 해소한다.</li>
 * </ul>
 *
 * <h3>시험이 헛돌지 않게 하는 장치</h3>
 * <p>관리자에게 <b>배정 행을 주지 않고</b>, 나아가 배정 리포지토리가 한 번도 호출되지 않았음까지
 * 확인한다 — 검수자 축에서 즉시 끝났음(= 작업자 전용 배정 검사에 흘러들지 않았음)을 고정한다.
 * 통과 단언만 두면 가드를 무조건-통과로 무력화해도 초록이므로 거부 축(포털 회원·작업자)을 함께 둔다.
 * 자동 전이도 「관리자는 전이시킨다」와 「작업자는 전이시키지 않는다」를 짝으로 둔다 — 대조군이
 * 없으면 {@code markAnswered} 를 무조건 호출하도록 바꿔도 초록이다.
 *
 * <h3>적대검증(mutation) 실증</h3>
 * <p>다섯 지점을 동등 비교로 되돌리면 이 클래스의 관리자 시험 5건이 FAILED 가 된다
 * (앞 넷은 {@code FORBIDDEN}, 자동 전이는 상태가 OPEN 에 머무름).
 */
class IssueThreadRoleHierarchyTest {

    private static final Long RAW_SN = 4200L;
    private static final Long ISSUE_SN = 4300L;

    private static final TokenClaims ADMIN =
            new TokenClaims("900", Role.ADMIN, Channel.INTERNAL, null);
    private static final TokenClaims WORKER =
            new TokenClaims("100", Role.WORKER, Channel.INTERNAL, null);
    private static final TokenClaims PORTAL =
            new TokenClaims("500", Role.PORTAL_USER, Channel.PORTAL, null);

    private IssueRepository issueRepository;
    private IssueCommentRepository commentRepository;
    private LsTaskAssignmentRepository assignmentRepository;
    private LsDataSrcRepository srcRepository;
    private LsUserRoleRepository lsUserRoleRepository;
    private IssueThreadService service;

    @BeforeEach
    void setUp() {
        issueRepository = mock(IssueRepository.class);
        commentRepository = mock(IssueCommentRepository.class);
        assignmentRepository = mock(LsTaskAssignmentRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        lsUserRoleRepository = mock(LsUserRoleRepository.class);

        service = new IssueThreadService(
                issueRepository, commentRepository, assignmentRepository, srcRepository,
                new UserNameResolver(userRepository), lsUserRoleRepository);

        lenient().when(userRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());
        lenient().when(lsUserRoleRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());
        lenient().when(commentRepository.findByDataIssueSnInOrderByRegDtAsc(anyList()))
                .thenReturn(List.of());
        lenient().when(issueRepository.save(any(LsDataIssue.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(commentRepository.save(any(LsIssueComment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // 관리자에게는 배정 행을 주지 않는다 — 통과가 계층 덕분임을 보이기 위한 설정.
        lenient().when(assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                anyLong(), anyString(), anyLong())).thenReturn(false);
    }

    private static LsDataIssue openInquiry() {
        return LsDataIssue.createInquiry(RAW_SN, "확인 부탁드립니다", "100", null);
    }

    // ------------------------------------------------------------ 문의 등록

    @Test
    @DisplayName("관리자는_배정이_없어도_문의를_등록한다_계층")
    void adminCreatesInquiryWithoutAssignment() {
        assertThatCode(() -> service.createInquiry(RAW_SN, new IssueCreateRequest("문의합니다", null), ADMIN))
                .doesNotThrowAnyException();

        // 검수자 축에서 끝났음을 고정 — 작업자 전용 배정 검사에 흘러들지 않는다.
        verify(assignmentRepository, never())
                .existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong());
        verify(issueRepository).save(any(LsDataIssue.class));
    }

    @Test
    @DisplayName("포털회원은_문의_등록에서_여전히_거부된다_계층이_새지_않는다")
    void portalUserStillForbiddenOnCreateInquiry() {
        assertThatThrownBy(() -> service.createInquiry(RAW_SN, new IssueCreateRequest("문의합니다", null), PORTAL))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    // ------------------------------------------------------------ 스레드 조회

    @Test
    @DisplayName("관리자는_배정이_없어도_이슈_스레드를_조회한다_계층")
    void adminListsThreadsWithoutAssignment() {
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(List.of(openInquiry()));

        assertThat(service.listThreads(RAW_SN, ADMIN)).hasSize(1);

        verify(assignmentRepository, never())
                .existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("포털회원은_스레드_조회에서_여전히_거부된다_계층이_새지_않는다")
    void portalUserStillForbiddenOnListThreads() {
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(List.of(openInquiry()));

        assertThatThrownBy(() -> service.listThreads(RAW_SN, PORTAL))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    // ------------------------------------------------------------ 댓글 + 자동 전이

    @Test
    @DisplayName("관리자_댓글도_대기중_문의를_답변완료로_전이시킨다_API_104")
    void adminCommentAnswersOpenInquiry() {
        LsDataIssue issue = openInquiry();
        when(issueRepository.findById(ISSUE_SN)).thenReturn(Optional.of(issue));

        service.addComment(ISSUE_SN, new IssueCommentRequest("확인했습니다"), ADMIN);

        assertThat(issue.getIssueSttsCd()).isEqualTo(LsDataIssue.STTS_ANSWERED);
        verify(assignmentRepository, never())
                .existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong());

        // 댓글 작성자 역할은 요청이 아니라 토큰에서만 도출된다(CWE-915) — 관리자는 ADMIN 으로 남는다.
        ArgumentCaptor<LsIssueComment> captor = ArgumentCaptor.forClass(LsIssueComment.class);
        verify(commentRepository).save(captor.capture());
        assertThat(captor.getValue().getAuthorRoleCd()).isEqualTo(Role.ADMIN.name());
    }

    @Test
    @DisplayName("작업자_댓글은_문의를_전이시키지_않는다_대조군")
    void workerCommentDoesNotAnswerInquiry() {
        LsDataIssue issue = openInquiry();
        when(issueRepository.findById(ISSUE_SN)).thenReturn(Optional.of(issue));
        // 작업자는 본인 작성 이슈라 접근이 열린다(reportedUserNo = 100).
        when(assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong()))
                .thenReturn(true);

        service.addComment(ISSUE_SN, new IssueCommentRequest("추가 설명드립니다"), WORKER);

        assertThat(issue.getIssueSttsCd()).isEqualTo(LsDataIssue.STTS_OPEN);
    }

    @Test
    @DisplayName("포털회원은_댓글_작성에서_여전히_거부된다_계층이_새지_않는다")
    void portalUserStillForbiddenOnAddComment() {
        when(issueRepository.findById(ISSUE_SN)).thenReturn(Optional.of(openInquiry()));

        assertThatThrownBy(() -> service.addComment(ISSUE_SN, new IssueCommentRequest("댓글"), PORTAL))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    // ------------------------------------------------------------ 해소

    @Test
    @DisplayName("관리자는_이슈를_해소한다_검수자_전용은_검수자_이상으로_읽는다")
    void adminResolvesIssue() {
        LsDataIssue issue = openInquiry();
        when(issueRepository.findById(ISSUE_SN)).thenReturn(Optional.of(issue));

        service.resolve(ISSUE_SN, ADMIN);

        assertThat(issue.getIssueSttsCd()).isEqualTo(LsDataIssue.STTS_RESOLVED);
    }

    @Test
    @DisplayName("작업자는_이슈_해소에서_여전히_거부된다_계층이_새지_않는다")
    void workerStillForbiddenOnResolve() {
        assertThatThrownBy(() -> service.resolve(ISSUE_SN, WORKER))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        verify(issueRepository, never()).findById(anyLong());
    }
}
