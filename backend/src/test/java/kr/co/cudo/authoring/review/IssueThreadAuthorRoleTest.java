package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.review.dto.IssueCreateRequest;
import kr.co.cudo.authoring.review.dto.IssueThreadResponse;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.entity.LsIssueComment;
import kr.co.cudo.authoring.review.repository.IssueCommentRepository;
import kr.co.cudo.authoring.review.repository.IssueRepository;
import kr.co.cudo.authoring.review.service.IssueThreadService;
import kr.co.cudo.authoring.user.entity.LsAcntUser;
import kr.co.cudo.authoring.user.entity.LsUserRole;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이슈 스레드 작성자 <b>역할</b> 노출 (SCR-LABEL-001 / SCR-REVIEW-002 이슈 탭).
 *
 * <p>배경: 문의는 원래 작업자→검수자 단방향이었으나 검수자도 문의를 등록할 수 있게 되면서
 * "누가 낸 문의인가"가 실질적 의미를 갖게 됐다. 그런데 역할 축은 <b>댓글</b>
 * ({@code IssueCommentResponse.authorRoleCd})에만 있고 <b>스레드</b> 응답에는 없어, 검수자 문의와
 * 작업자 문의가 화면에서 구분되지 않았다(이름·사번만 보였다).
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li>스레드 응답에 {@code reportedUserRoleCd} 가 실린다 — 댓글의 {@code authorRoleCd} 와 같은
 *       값 공간({@code WORKER}/{@code REVIEWER}/…)·같은 타입(String).</li>
 *   <li>검수자 문의와 작업자 문의가 그 값으로 구분된다.</li>
 *   <li>역할을 해석할 수 없으면(매핑 미존재·비숫자 사번) {@code null} — 지어내지 않고 예외도 던지지
 *       않는다(작성자가 삭제·변경돼도 스레드 조회는 살아 있어야 한다).</li>
 *   <li><b>N+1 금지</b> — 스레드가 N 건이어도 역할 조회는 IN 쿼리 <b>1회</b>다.</li>
 *   <li>기존 필드({@code reportedUserNo}/{@code reportedUserName}/{@code comments[].authorRoleCd})는
 *       이름·타입·의미가 그대로다(추가만 — 외부 FE 팀도 쓰는 계약면).</li>
 * </ul>
 */
class IssueThreadAuthorRoleTest {

    private IssueRepository issueRepository;
    private IssueCommentRepository commentRepository;
    private LsTaskAssignmentRepository assignmentRepository;
    private LsDataSrcRepository srcRepository;
    private UserRepository userRepository;
    private LsUserRoleRepository lsUserRoleRepository;
    private IssueThreadService service;

    private static final Long RAW_SN = 1000L;
    private static final TokenClaims REVIEWER =
            new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, null);
    private static final TokenClaims WORKER =
            new TokenClaims("100", Role.WORKER, Channel.INTERNAL, null);

    @BeforeEach
    void setUp() {
        issueRepository = mock(IssueRepository.class);
        commentRepository = mock(IssueCommentRepository.class);
        assignmentRepository = mock(LsTaskAssignmentRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        userRepository = mock(UserRepository.class);
        lsUserRoleRepository = mock(LsUserRoleRepository.class);
        service = new IssueThreadService(
                issueRepository, commentRepository, assignmentRepository, srcRepository,
                new UserNameResolver(userRepository), lsUserRoleRepository);
        lenient().when(userRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());
        lenient().when(lsUserRoleRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());
        lenient().when(commentRepository.findByDataIssueSnInOrderByRegDtAsc(anyList()))
                .thenReturn(List.of());
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

    private static LsAcntUser user(Long userNo, String userNm) {
        LsAcntUser u = mock(LsAcntUser.class);
        lenient().when(u.getUserNo()).thenReturn(userNo);
        lenient().when(u.getUserNm()).thenReturn(userNm);
        return u;
    }

    private static LsUserRole role(Long userNo, String roleCd) {
        LsUserRole r = mock(LsUserRole.class);
        lenient().when(r.getUserNo()).thenReturn(userNo);
        lenient().when(r.getRoleCd()).thenReturn(roleCd);
        return r;
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("검수자_문의와_작업자_문의가_스레드_응답의_역할로_구분된다")
    void reporterRoleDistinguishesReviewerAndWorkerInquiry() {
        // given — 검수자 1 이 낸 문의 · 작업자 100 이 낸 문의
        List<LsDataIssue> issues = List.of(issue(10L, "1"), issue(11L, "100"));
        List<LsUserRole> roles = List.of(role(1L, "REVIEWER"), role(100L, "WORKER"));
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(issues);
        when(lsUserRoleRepository.findByUserNoIn(anyCollection())).thenReturn(roles);

        // when
        List<IssueThreadResponse> threads = service.listThreads(RAW_SN, REVIEWER);

        // then — 두 문의가 역할로 갈린다(구 동작: 스레드에 역할 축 자체가 없어 구분 불가)
        assertThat(threads)
                .extracting(IssueThreadResponse::reportedUserRoleCd)
                .containsExactly("REVIEWER", "WORKER");
    }

    @Test
    @DisplayName("역할매핑이_없는_작성자는_역할이_null이고_조회는_실패하지_않는다")
    void unknownReporterRoleIsNull() {
        // given — 사용자 역할 매핑에 999 가 없음(퇴사·미배정 시나리오)
        // (mock 픽스처는 when(...) 밖에서 먼저 만든다 — 중첩 스터빙 금지)
        List<LsDataIssue> issues = List.of(issue(10L, "999"));
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(issues);
        when(lsUserRoleRepository.findByUserNoIn(anyCollection())).thenReturn(List.of());

        // when
        List<IssueThreadResponse> threads = service.listThreads(RAW_SN, REVIEWER);

        // then — 예외 없이 null. 사번은 그대로 남아 화면이 이름/사번만 표시한다
        assertThat(threads.get(0).reportedUserRoleCd()).isNull();
        assertThat(threads.get(0).reportedUserNo()).isEqualTo("999");
    }

    @Test
    @DisplayName("숫자가_아닌_사번은_역할조회_대상에서_빠지고_역할은_null이다")
    void nonNumericReporterNoIsSkippedForRoleLookup() {
        // given — 레거시/외부 채널 사번이 숫자가 아닌 경우
        List<LsDataIssue> issues = List.of(issue(10L, "wkr01"));
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(issues);

        // when
        List<IssueThreadResponse> threads = service.listThreads(RAW_SN, REVIEWER);

        // then — NumberFormatException 으로 스레드 조회 전체가 죽으면 안 된다
        assertThat(threads.get(0).reportedUserRoleCd()).isNull();
        // 파싱 가능한 사번이 하나도 없으면 역할 조회 자체를 하지 않는다(불필요 쿼리 제거)
        verify(lsUserRoleRepository, times(0)).findByUserNoIn(anyCollection());
    }

    @Test
    @DisplayName("스레드가_N건이어도_역할_조회는_1회다_N플러스1_금지")
    void roleLookupIsBatchedIntoSingleQuery() {
        // given — 스레드 6건 · 작성자는 3명(중복)
        List<LsDataIssue> issues = new ArrayList<>();
        long issueSn = 10L;
        for (int i = 0; i < 2; i++) {
            issues.add(issue(issueSn++, "1"));
            issues.add(issue(issueSn++, "100"));
            issues.add(issue(issueSn++, "101"));
        }
        List<LsUserRole> roles =
                List.of(role(1L, "REVIEWER"), role(100L, "WORKER"), role(101L, "WORKER"));
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(issues);
        when(lsUserRoleRepository.findByUserNoIn(anyCollection())).thenReturn(roles);

        // when
        List<IssueThreadResponse> threads = service.listThreads(RAW_SN, REVIEWER);

        // then — 조회 1회 · 중복 제거된 사번 3개만 전달(행마다 조회하면 6회가 된다)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(lsUserRoleRepository, times(1)).findByUserNoIn(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(1L, 100L, 101L);
        assertThat(threads)
                .extracting(IssueThreadResponse::reportedUserRoleCd)
                .containsExactly("REVIEWER", "WORKER", "WORKER", "REVIEWER", "WORKER", "WORKER");
    }

    @Test
    @DisplayName("이슈가_없으면_역할조회도_하지_않는다")
    void noIssuesMeansNoRoleLookup() {
        // given
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(List.of());

        // when
        List<IssueThreadResponse> threads = service.listThreads(RAW_SN, REVIEWER);

        // then
        assertThat(threads).isEmpty();
        verify(lsUserRoleRepository, times(0)).findByUserNoIn(anyCollection());
    }

    @Test
    @DisplayName("역할_추가가_기존_필드를_바꾸지_않는다_추가만")
    void existingFieldsAreUnchanged() {
        // given — 이름·댓글 역할 축이 함께 있는 스레드
        List<LsDataIssue> issues = List.of(issue(10L, "100"));
        List<LsIssueComment> comments = List.of(comment(1L, 10L, "1", "REVIEWER"));
        List<LsAcntUser> users = List.of(user(1L, "검수자1"), user(100L, "작업자100"));
        List<LsUserRole> roles = List.of(role(100L, "WORKER"));
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(issues);
        when(commentRepository.findByDataIssueSnInOrderByRegDtAsc(anyList())).thenReturn(comments);
        when(userRepository.findByUserNoIn(anyCollection())).thenReturn(users);
        when(lsUserRoleRepository.findByUserNoIn(anyCollection())).thenReturn(roles);

        // when
        IssueThreadResponse thread = service.listThreads(RAW_SN, REVIEWER).get(0);

        // then — 기존 필드는 이름·타입·의미 그대로, 역할만 추가됐다
        assertThat(thread.issueSn()).isEqualTo(10L);
        assertThat(thread.issueTypeCd()).isEqualTo("INQUIRY");
        assertThat(thread.issueSttsCd()).isEqualTo("OPEN");
        assertThat(thread.reason()).isEqualTo("확인 부탁드립니다");
        assertThat(thread.reportedUserNo()).isEqualTo("100");
        assertThat(thread.reportedUserName()).isEqualTo("작업자100");
        assertThat(thread.regDt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 0));
        // 댓글의 작성 시점 역할 컬럼은 그대로 쓰인다(스레드 역할 해석이 덮어쓰지 않는다)
        assertThat(thread.comments()).hasSize(1);
        assertThat(thread.comments().get(0).authorRoleCd()).isEqualTo("REVIEWER");
        assertThat(thread.comments().get(0).authorName()).isEqualTo("검수자1");
        // 신규 필드
        assertThat(thread.reportedUserRoleCd()).isEqualTo("WORKER");
    }

    @Test
    @DisplayName("댓글_역할은_작성시점_컬럼이고_스레드_역할은_현재매핑이라_서로_독립이다")
    void commentRoleSnapshotIsIndependentFromThreadCurrentRole() {
        // given — 같은 사람(사번 1)이 검수자로 댓글을 썼고, 지금은 작업자로 바뀌었다
        List<LsDataIssue> issues = List.of(issue(10L, "1"));
        List<LsIssueComment> comments = List.of(comment(1L, 10L, "1", "REVIEWER"));
        List<LsUserRole> roles = List.of(role(1L, "WORKER"));
        when(issueRepository.findByDataRawSnOrderByRegDtAsc(RAW_SN)).thenReturn(issues);
        when(commentRepository.findByDataIssueSnInOrderByRegDtAsc(anyList())).thenReturn(comments);
        when(lsUserRoleRepository.findByUserNoIn(anyCollection())).thenReturn(roles);

        // when
        IssueThreadResponse thread = service.listThreads(RAW_SN, REVIEWER).get(0);

        // then — 댓글은 박아 둔 값, 스레드는 현재 매핑. 한쪽이 다른 쪽을 덮지 않는다
        assertThat(thread.comments().get(0).authorRoleCd()).isEqualTo("REVIEWER");
        assertThat(thread.reportedUserRoleCd()).isEqualTo("WORKER");
    }

    @Test
    @DisplayName("문의_등록_응답에도_역할이_실리고_추가_조회를_하지_않는다")
    void createInquiryCarriesActorRoleWithoutExtraLookup() {
        // given — 검수자가 문의를 등록한다(요청 바디에 역할 필드는 없다 — CWE-915)
        LsDataIssue saved = issue(30L, "1");
        when(issueRepository.save(any(LsDataIssue.class))).thenReturn(saved);
        lenient().when(userRepository.findByUserNo(anyLong())).thenReturn(java.util.Optional.empty());

        // when
        IssueThreadResponse created =
                service.createInquiry(RAW_SN, new IssueCreateRequest("재추출이 필요합니다", null), REVIEWER);

        // then — 토큰 역할이 그대로 실린다 + 방금 쓴 행을 되읽지 않는다
        assertThat(created.reportedUserRoleCd()).isEqualTo("REVIEWER");
        verify(lsUserRoleRepository, times(0)).findByUserNoIn(anyCollection());
        verify(lsUserRoleRepository, times(0)).findByUserNo(anyLong());
    }

    @Test
    @DisplayName("작업자_문의_등록_응답의_역할은_WORKER다")
    void createInquiryByWorkerCarriesWorkerRole() {
        // given
        LsDataIssue saved = issue(31L, "100");
        when(assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong()))
                .thenReturn(true);
        when(issueRepository.save(any(LsDataIssue.class))).thenReturn(saved);
        lenient().when(userRepository.findByUserNo(anyLong())).thenReturn(java.util.Optional.empty());

        // when
        IssueThreadResponse created =
                service.createInquiry(RAW_SN, new IssueCreateRequest("이 라벨 맞나요?", null), WORKER);

        // then
        assertThat(created.reportedUserRoleCd()).isEqualTo("WORKER");
    }
}
