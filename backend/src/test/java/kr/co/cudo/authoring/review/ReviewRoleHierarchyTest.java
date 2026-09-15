package kr.co.cudo.authoring.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.debounce.ControlNotifyDebounceStore;
import kr.co.cudo.authoring.dataset.service.DatasetVideoMetaSnapshotService;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoReviewService;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.meta.service.MetaService;
import kr.co.cudo.authoring.review.dto.ReviewResponse;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.repository.IssueRepository;
import kr.co.cudo.authoring.review.repository.ReviewQueryRepository;
import kr.co.cudo.authoring.review.repository.ReviewRepository;
import kr.co.cudo.authoring.review.service.ReviewService;
import kr.co.cudo.authoring.review.service.ReviewStateMachine;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.version.service.VersionService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReviewService} — <b>역할 계층(관리자 &gt; 검수자) 반영 + 작업자 전용 자리 보존</b> 회귀 가드.
 * [design: ADR-055] [design: ROLE-004] [design: AC-125]
 *
 * <h3>고정하는 자리 셋</h3>
 * <ul>
 *   <li><b>검수 창구 인가</b>({@code requireReviewer}) — 목록·집계·프레임·이슈·검수시작·승인·반려.
 *       창구의 「검수자 전용」은 「검수자 이상」이라 관리자가 그대로 들어간다.</li>
 *   <li><b>검수 상세 접근</b>({@code requireAssignedOrReviewer}) — 검수자/작업자 분기 뒤에 거부가
 *       오는 형태라 동등 비교면 관리자가 fall-through 로 403 이 된다.</li>
 *   <li><b>★ 검수 제출·제출취소</b>({@code verifyAssignedWorker}) — <b>작업자 전용이라 계층을 타지
 *       않는다.</b> 관리자·검수자는 여전히 거부되어야 하며, 그 동등 비교는 「빠뜨린 곳」이 아니라
 *       의도된 보존이다.</li>
 * </ul>
 *
 * <h3>시험이 헛돌지 않게 하는 장치</h3>
 * <p>「관리자는 제출하지 못한다」 같은 부정 단언만 두면 그 경로가 애초에 도달 불가여도 통과하는
 * <b>항상-참 시험</b>이 된다. 그래서 <b>「작업자는 제출한다」를 대조군으로 함께</b> 고정한다 —
 * 두 단언이 짝을 이뤄야 「이 경로는 살아 있고 다만 역할로 갈린다」가 증명된다.
 * 상세 조회는 관리자에게 배정 행을 주지 않고, 배정 리포지토리가 호출되지 않았음까지 확인한다.
 *
 * <h3>적대검증(mutation) 실증</h3>
 * <ul>
 *   <li>계층 반영 두 지점을 동등 비교로 되돌리면 관리자 시험 3건이 {@code FORBIDDEN} 으로 FAILED.</li>
 *   <li>보존 지점({@code actor.role() != Role.WORKER})을 계층이 새는 형태
 *       ({@code !actor.hasRole(Role.REVIEWER)})로 바꾸면 이 클래스의 제출 시험 4건이 FAILED —
 *       관리자·검수자가 제출에 성공해 버리고 작업자가 거부된다.</li>
 * </ul>
 */
class ReviewRoleHierarchyTest {

    private static final Long VIDEO_ID = 5100L;

    private static final TokenClaims ADMIN =
            new TokenClaims("900", Role.ADMIN, Channel.INTERNAL, null);
    private static final TokenClaims REVIEWER =
            new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, null);
    private static final TokenClaims WORKER =
            new TokenClaims("100", Role.WORKER, Channel.INTERNAL, null);
    private static final TokenClaims PORTAL =
            new TokenClaims("500", Role.PORTAL_USER, Channel.PORTAL, null);

    private ReviewRepository reviewRepository;
    private ReviewQueryRepository reviewQueryRepository;
    private LsTaskAssignmentRepository authrtRepository;
    private LsTaskEventLogRepository taskEventLogRepository;
    private IssueRepository issueRepository;
    private LsDataLblRepository labelRepository;
    private VersionService versionService;
    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        reviewQueryRepository = mock(ReviewQueryRepository.class);
        issueRepository = mock(IssueRepository.class);
        authrtRepository = mock(LsTaskAssignmentRepository.class);
        taskEventLogRepository = mock(LsTaskEventLogRepository.class);
        ReviewStateMachine stateMachine = mock(ReviewStateMachine.class);
        LsDataSrcRepository srcRepository = mock(LsDataSrcRepository.class);
        labelRepository = mock(LsDataLblRepository.class);
        VideoRepository videoRepository = mock(VideoRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        versionService = mock(VersionService.class);

        reviewService = new ReviewService(
                reviewRepository, reviewQueryRepository, issueRepository, authrtRepository,
                taskEventLogRepository, stateMachine, srcRepository, labelRepository, videoRepository,
                new UserNameResolver(userRepository), new ObjectMapper(),
                mock(ApplicationEventPublisher.class), versionService,
                mock(DatasetVideoMetaSnapshotService.class), mock(EvntAnnoReviewService.class),
                mock(MetaService.class), mock(LabelAccessGuard.class),
                mock(ControlNotifyDebounceStore.class),
                // ADR-067 — 검수 점유 조회 단일 창구. 위 이벤트 로그 목이 빈 결과를 돌려주므로
                // 「아무도 점유하지 않음」 상태다(이 시험의 관심사는 점유가 아니다).
                new kr.co.cudo.authoring.assignment.service.ReviewClaimSupport(taskEventLogRepository, 30),
                // 일괄 승인 건수 상한 — 단건 경로를 쓰는 이 시험에서는 읽히지 않는다.
                new kr.co.cudo.authoring.review.service.ReviewBatchApprovePolicy(20));

        // enrichOne 의 N+1 회피 lookup — 이 시험의 관심사가 아니라 빈 결과로 둔다.
        lenient().when(videoRepository.findCctvNamesByRawSns(any())).thenReturn(Collections.emptyList());
        lenient().when(videoRepository.findEventInfoByRawSns(any())).thenReturn(Collections.emptyList());
        lenient().when(authrtRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(any(), any()))
                .thenReturn(Collections.emptyList());
        lenient().when(labelRepository.countLabelsByRawSnIn(any())).thenReturn(Collections.emptyList());
        lenient().when(taskEventLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 관리자에게는 배정 행을 주지 않는다 — 통과가 계층 덕분임을 보이기 위한 설정.
        lenient().when(authrtRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                anyLong(), anyString(), anyLong())).thenReturn(false);
        lenient().when(reviewQueryRepository.search(any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
    }

    private LsRawDataStatus stubStatus(String sttsCd) {
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        lenient().when(stts.getRawDataId()).thenReturn(VIDEO_ID);
        lenient().when(stts.getDataSttsCd()).thenReturn(sttsCd);
        // 실엔티티의 기본값은 「비식별화 완료」다(Y). 목의 기본값(false)을 그대로 두면 승인이
        //   역할과 무관하게 412 로 막혀, 계층 시험이 엉뚱한 이유로 실패한다.
        lenient().when(stts.isDeidentCompleted()).thenReturn(true);
        lenient().when(reviewRepository.findByRawDataId(VIDEO_ID)).thenReturn(Optional.of(stts));
        return stts;
    }

    // ---------------------------------------------------- 검수 창구 (검수자 이상)

    @Test
    @DisplayName("관리자는_검수목록을_조회한다_검수자_전용은_검수자_이상으로_읽는다")
    void adminListsReviews() {
        Pageable pageable = PageRequest.of(0, 20);

        assertThatCode(() -> reviewService.list(null, pageable, ADMIN)).doesNotThrowAnyException();

        verify(reviewQueryRepository).search(any(), any());
    }

    @Test
    @DisplayName("포털회원은_검수목록에서_여전히_거부된다_계층이_새지_않는다")
    void portalUserStillForbiddenOnList() {
        assertThatThrownBy(() -> reviewService.list(null, PageRequest.of(0, 20), PORTAL))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("작업자는_검수목록에서_여전히_거부된다_계층이_새지_않는다")
    void workerStillForbiddenOnList() {
        assertThatThrownBy(() -> reviewService.list(null, PageRequest.of(0, 20), WORKER))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    // ---------------------------------------------------- 검수 상세 (검수자 이상 / 배정 작업자)

    @Test
    @DisplayName("관리자는_배정이_없어도_검수_상세를_조회한다_계층")
    void adminReadsDetailWithoutAssignment() {
        stubStatus(LsRawDataStatus.STTS_PENDING);

        ReviewResponse res = reviewService.getDetail(VIDEO_ID, ADMIN);

        assertThat(res.videoId()).isEqualTo(VIDEO_ID);
        // 검수자 분기에서 즉시 끝났음을 고정 — 작업자 전용 배정 검사에 흘러들지 않는다.
        verify(authrtRepository, never())
                .existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("포털회원은_검수_상세에서_여전히_거부된다_계층이_새지_않는다")
    void portalUserStillForbiddenOnDetail() {
        assertThatThrownBy(() -> reviewService.getDetail(VIDEO_ID, PORTAL))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    // ---------------------------------------------------- 검수 시작·승인·반려 (검수자 이상)

    /**
     * ADR-067 라운드 보강 — 그 전까지 이 클래스는 목록·상세·제출 축만 덮었다. 검수 시작·승인·반려는
     * <b>관리자가 실제로 수행하는 일</b>인데 계층 회귀 가드가 없었다.
     *
     * <p>「거부되지 않는다」만 보면 경로가 도달 불가여도 통과하므로, <b>상태가 실제로 전이되고 이력이
     * 쌓였는지</b>까지 함께 고정한다.
     */
    @Test
    @DisplayName("관리자는_배정이_없어도_검수를_시작한다_계층")
    void adminStartsReview() {
        LsRawDataStatus stts = stubStatus(LsRawDataStatus.STTS_PENDING);

        reviewService.startReview(VIDEO_ID, ADMIN);

        verify(stts).transitionTo(LsRawDataStatus.STTS_IN_REVIEW);
        // 점유를 세우는 「검수 시작」 이력이 쌓인다.
        verify(taskEventLogRepository).save(any());
        // 검수자 분기에서 끝났음을 고정 — 작업자 전용 배정 검사에 흘러들지 않는다.
        verify(authrtRepository, never())
                .existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("포털회원은_검수시작에서_여전히_거부된다_계층이_새지_않는다")
    void portalUserStillForbiddenOnStartReview() {
        assertThatThrownBy(() -> reviewService.startReview(VIDEO_ID, PORTAL))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("작업자는_검수시작·승인·반려에서_여전히_거부된다_계층이_새지_않는다")
    void workerStillForbiddenOnReviewActions() {
        stubStatus(LsRawDataStatus.STTS_IN_REVIEW);

        assertThatThrownBy(() -> reviewService.startReview(VIDEO_ID, WORKER))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> reviewService.approve(VIDEO_ID, null, WORKER))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> reviewService.reject(VIDEO_ID,
                new kr.co.cudo.authoring.review.dto.RejectRequest("사유"), WORKER))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("관리자가_승인하면_이력에_ADMIN이_남는다_계층으로_승격된_REVIEWER가_아니다")
    void adminApprovalIsRecordedAsAdmin() {
        LsRawDataStatus stts = stubStatus(LsRawDataStatus.STTS_IN_REVIEW);
        when(labelRepository.existsAnyByRawSn(anyLong())).thenReturn(true);
        when(versionService.commitApproved(anyLong(), any()))
                .thenReturn(new VersionService.CommitResult(1, 0));

        reviewService.approve(VIDEO_ID, null, ADMIN);

        verify(stts).transitionTo(LsRawDataStatus.STTS_APPROVED);
        ArgumentCaptor<LsTaskEventLog> captor = ArgumentCaptor.forClass(LsTaskEventLog.class);
        verify(taskEventLogRepository).save(captor.capture());
        assertThat(captor.getValue().getEventTypeCd()).isEqualTo(LsTaskEventLog.EVENT_APPROVE);
        // ★ADMIN.satisfies(REVIEWER) 가 참이라고 REVIEWER 를 적으면 「관리자가 승인한 건」을
        //   사후에 가려낼 수 없어 이 컬럼을 둔 이유가 통째로 사라진다.
        assertThat(captor.getValue().getActorRoleCd()).isEqualTo(Role.ADMIN.name());
    }

    @Test
    @DisplayName("관리자가_반려하면_이력에_ADMIN이_남고_이슈의_작성자와_어긋나지_않는다")
    void adminRejectionIsRecordedAsAdmin() {
        LsRawDataStatus stts = stubStatus(LsRawDataStatus.STTS_IN_REVIEW);
        when(issueRepository.findByDataRawSnOrderByRegDtDesc(anyLong()))
                .thenReturn(Collections.emptyList());

        reviewService.reject(VIDEO_ID,
                new kr.co.cudo.authoring.review.dto.RejectRequest("다시 확인이 필요합니다."), ADMIN);

        verify(stts).transitionTo(LsRawDataStatus.STTS_REJECTED);
        ArgumentCaptor<LsTaskEventLog> events = ArgumentCaptor.forClass(LsTaskEventLog.class);
        verify(taskEventLogRepository).save(events.capture());
        assertThat(events.getValue().getActorRoleCd()).isEqualTo(Role.ADMIN.name());

        // 반려는 이슈도 함께 쓴다 — 두 곳의 행위자가 같은 사람을 가리켜야 한다.
        ArgumentCaptor<LsDataIssue> issues = ArgumentCaptor.forClass(LsDataIssue.class);
        verify(issueRepository).save(issues.capture());
        assertThat(issues.getValue().getReportedUserNo()).isEqualTo(ADMIN.sub());
        assertThat(String.valueOf(events.getValue().getActorUserNo())).isEqualTo(ADMIN.sub());
    }

    // ---------------------------------------------------- ★ 작업자 전용 자리 (보존)

    /**
     * ★ <b>배정 행을 일부러 준다.</b> 주지 않으면 관리자는 「역할이 작업자가 아니라서」가 아니라
     * 「배정이 없어서」 거부되어, 계층이 새는 변이를 넣어도 여전히 {@code FORBIDDEN} 이 나 시험이
     * 통과해 버린다(실측으로 확인 — 그 형태의 부정 단언은 변이를 한 건도 잡지 못했다).
     * 배정 행이 있는데도 거부되어야 「역할 축이 막고 있다」가 증명된다.
     */
    @Test
    @DisplayName("관리자는_배정이_있어도_검수_제출을_못_한다_작업자_전용_자리는_계층이_열지_않는다")
    void adminCannotSubmit() {
        LsRawDataStatus stts = stubStatus(LsRawDataStatus.STTS_ASSIGNED);
        when(authrtRepository.existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong()))
                .thenReturn(true);

        assertThatThrownBy(() -> reviewService.submit(VIDEO_ID, ADMIN))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        verify(stts, never()).transitionTo(anyString());
    }

    @Test
    @DisplayName("검수자도_배정이_있어도_검수_제출을_못_한다_작업자_전용_자리는_계층이_열지_않는다")
    void reviewerCannotSubmit() {
        LsRawDataStatus stts = stubStatus(LsRawDataStatus.STTS_ASSIGNED);
        when(authrtRepository.existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong()))
                .thenReturn(true);

        assertThatThrownBy(() -> reviewService.submit(VIDEO_ID, REVIEWER))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        verify(stts, never()).transitionTo(anyString());
    }

    @Test
    @DisplayName("관리자는_배정이_있어도_검수_제출취소를_못_한다_작업자_전용_자리는_계층이_열지_않는다")
    void adminCannotCancelSubmit() {
        LsRawDataStatus stts = stubStatus(LsRawDataStatus.STTS_PENDING);
        when(authrtRepository.existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong()))
                .thenReturn(true);

        assertThatThrownBy(() -> reviewService.cancelSubmit(VIDEO_ID, ADMIN))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        verify(stts, never()).transitionTo(anyString());
    }

    /**
     * 대조군 — 위 부정 단언들이 「경로가 애초에 도달 불가여서」 통과하는 항상-참 시험이 아님을 보인다.
     * 같은 경로로 <b>작업자는 실제로 제출에 성공</b>한다.
     */
    @Test
    @DisplayName("작업자는_본인_배정_영상을_제출한다_대조군")
    void assignedWorkerSubmits() {
        LsRawDataStatus stts = stubStatus(LsRawDataStatus.STTS_ASSIGNED);
        when(authrtRepository.existsByUserNoAndTaskTypeCdAndRawDataId(anyLong(), anyString(), anyLong()))
                .thenReturn(true);

        reviewService.submit(VIDEO_ID, WORKER);

        verify(stts).transitionTo(LsRawDataStatus.STTS_PENDING);
        verify(taskEventLogRepository).save(any());
    }
}
