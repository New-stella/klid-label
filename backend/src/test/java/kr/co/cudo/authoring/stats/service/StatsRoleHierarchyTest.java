package kr.co.cudo.authoring.stats.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.stats.dto.DashboardSummaryResponse;
import kr.co.cudo.authoring.stats.dto.MyTaskBreakdown;
import kr.co.cudo.authoring.stats.dto.WorkerStatSummaryResponse;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository.CountRow;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 통계 도메인의 <b>역할 축</b> 회귀 가드 — 관리자가 검수자 시야를 그대로 받고 작업자 전용 축으로
 * 좁혀지지 않는다.
 *
 * <h3>왜 이 시험이 필요한가</h3>
 * 이 서비스에는 역할로 갈리는 자리가 둘 있다 — ①대시보드의 「내 작업」 축(작업자 전용) ②작업자
 * 통계의 IDOR 가드(작업자는 본인 것만). 둘 다 <b>작업자를 붙잡는</b> 검사라 관리자는 원래 통과한다.
 * 즉 동작은 옳은데 <b>그것을 지키는 시험이 없었다</b> — 판정이 「검수자인가」류로 뒤집히거나 계층을
 * 모르는 비교로 되돌아가면 관리자가 조용히 작업자 시야를 받거나 남의 통계에서 거부된다.
 *
 * <h3>게이트 연쇄 주의 (이 저장소의 반복 사고)</h3>
 * 두 자리 모두 역할 판정 <b>뒤에</b> 사번 해석이 이어진다. 사번이 없거나 숫자가 아니면 역할과
 * 무관하게 같은 결과(내 작업 0 / 401)가 나오므로, 픽스처는 <b>뒤따르는 검사를 전부 통과</b>하도록
 * 숫자 사번을 주어 역할 축이 단독으로 검증되게 한다.
 *
 * <p>또 「내 작업」 축의 픽스처는 <b>0 이 아닌 값</b>으로 심는다 — 저장소가 0 을 돌려주는 환경에서는
 * 관리자가 작업자 시야로 좁혀져도 결과가 0 이라 시험이 조용히 항상-참이 된다.
 *
 * @design ADR-055
 * @design AC-125
 */
class StatsRoleHierarchyTest {

    /** 「내 작업」 픽스처를 가진 작업자 사번. 관리자·검수자도 <b>같은 사번</b>으로 조회해 축만 갈린다. */
    private static final long WORKER_NO = 4242L;
    /** 남의 통계를 조회하는 관리자·검수자 사번 (대상과 다른 사람이어야 IDOR 축이 성립한다). */
    private static final long OTHER_NO = 9001L;

    /** 「내 작업」 총계 — 심은 값 그대로 응답에 실려야 한다(non-empty 가 아니라 이 숫자를 짚는다). */
    private static final long MY_TASK_TOTAL = 17L;
    private static final long MY_PENDING = 3L;
    private static final long MY_ASSIGNED = 5L;
    private static final long MY_IN_REVIEW = 7L;
    private static final long MY_REJECTED = 11L;

    /** 작업자 통계 픽스처 — 값을 전부 다르게 두어 축이 뒤바뀌면 단언에서 드러난다. */
    private static final long WORKER_APPROVED = 23L;
    private static final long WORKER_REJECTED = 29L;
    private static final long WORKER_IN_PROGRESS = 31L;

    private StatsQueryRepository statsQueryRepository;
    private LsTaskAssignmentRepository authrtRepository;
    private StatsService service;

    @BeforeEach
    void setUp() {
        statsQueryRepository = mock(StatsQueryRepository.class);
        VideoRepository videoRepository = mock(VideoRepository.class);
        authrtRepository = mock(LsTaskAssignmentRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        EventTypeService eventTypeService = mock(EventTypeService.class);
        service = new StatsService(
                statsQueryRepository, videoRepository, authrtRepository,
                new UserNameResolver(userRepository), eventTypeService);

        // 역할 축 밖의 집계는 이 시험의 관심사가 아니다 — 빈/0 폴백.
        lenient().when(eventTypeService.filterOptions()).thenReturn(List.of());

        // ★「내 작업」 픽스처는 0 이 아니어야 한다. 0 이면 관리자가 작업자 시야로 좁혀져도
        //   결과가 같아 시험이 조용히 항상-참이 된다.
        // ⚠ 행 목킹을 when(...).thenReturn(...) 인자 안에서 하지 않는다 — 진행 중인 스터빙 안에서
        //   또 목을 세우면 Mockito 가 UnfinishedStubbing 으로 죽는다. 먼저 만들어 두고 넘긴다.
        List<CountRow> myTaskRows = List.of(
                countRow(LsRawDataStatus.STTS_PENDING, MY_PENDING),
                countRow(LsRawDataStatus.STTS_ASSIGNED, MY_ASSIGNED),
                countRow(LsRawDataStatus.STTS_IN_REVIEW, MY_IN_REVIEW),
                countRow(LsRawDataStatus.STTS_REJECTED, MY_REJECTED));
        List<CountRow> workerStatusRows = List.of(
                countRow(LsRawDataStatus.STTS_APPROVED, WORKER_APPROVED),
                countRow(LsRawDataStatus.STTS_REJECTED, WORKER_REJECTED));

        lenient().when(authrtRepository.countActiveTasksByUserNo(WORKER_NO)).thenReturn(MY_TASK_TOTAL);
        lenient().when(statsQueryRepository.countMyTaskByStatus(WORKER_NO)).thenReturn(myTaskRows);

        // 작업자 통계 픽스처 — 대상 사번(WORKER_NO) 한 사람 몫.
        lenient().when(statsQueryRepository.countWorkerTaskByStatus(WORKER_NO)).thenReturn(workerStatusRows);
        lenient().when(statsQueryRepository.countInProgressForWorker(WORKER_NO))
                .thenReturn(WORKER_IN_PROGRESS);
    }

    private static CountRow countRow(String code, long cnt) {
        CountRow r = mock(CountRow.class);
        lenient().when(r.getCode()).thenReturn(code);
        lenient().when(r.getCnt()).thenReturn(cnt);
        return r;
    }

    /** 숫자 사번 토큰 — 역할 판정 <b>뒤</b>의 사번 해석 게이트를 반드시 통과시킨다. */
    private static TokenClaims actor(long userNo, Role role) {
        return new TokenClaims(String.valueOf(userNo), role, Channel.INTERNAL, null);
    }

    // ------------------------------------------------------------------
    // ① 대시보드 — 「내 작업」 은 작업자 전용 축이다
    // ------------------------------------------------------------------

    @Test
    @DisplayName("관리자는_대시보드에서_작업자_시야로_좁혀지지_않는다")
    void adminIsNotNarrowedToWorkerView() {
        // given — 관리자가 「내 작업」 픽스처를 가진 사번으로 인증했다(사번 해석 게이트 통과).
        //   작업자 축으로 새면 총계 17 과 분해 3/5/7/11 이 그대로 실린다.
        DashboardSummaryResponse summary = service.getSummary(actor(WORKER_NO, Role.ADMIN));

        // then — 검수자 시야: 「내 작업」 은 비어 있다.
        assertThat(summary.myTaskCount())
                .as("관리자는 작업자 전용 축을 받지 않는다")
                .isZero();
        assertThat(summary.myTask().pendingCount()).isZero();
        assertThat(summary.myTask().inProgressCount()).isZero();
        assertThat(summary.myTask().reviewPendingCount()).isZero();
        assertThat(summary.myTask().rejectedCount()).isZero();

        // 그리고 작업자 전용 집계를 조회조차 하지 않는다(값이 0 이라서 통과한 것이 아님을 고정).
        verify(authrtRepository, never()).countActiveTasksByUserNo(anyLong());
        verify(statsQueryRepository, never()).countMyTaskByStatus(anyLong());
    }

    @Test
    @DisplayName("검수자도_대시보드에서_작업자_시야로_좁혀지지_않는다")
    void reviewerIsNotNarrowedToWorkerView() {
        DashboardSummaryResponse summary = service.getSummary(actor(WORKER_NO, Role.REVIEWER));

        assertThat(summary.myTaskCount()).isZero();
        assertThat(summary.myTask()).isEqualTo(MyTaskBreakdown.empty());
        verify(authrtRepository, never()).countActiveTasksByUserNo(anyLong());
    }

    @Test
    @DisplayName("대조군_작업자는_내작업_집계가_채워진다")
    void workerStillGetsMyTaskBreakdown() {
        // given/when — 같은 사번·같은 픽스처, 역할만 작업자.
        DashboardSummaryResponse summary = service.getSummary(actor(WORKER_NO, Role.WORKER));

        // then — 심은 값을 그대로 짚는다(비어있지 않다가 아니라).
        assertThat(summary.myTaskCount()).isEqualTo(MY_TASK_TOTAL);
        assertThat(summary.myTask().pendingCount()).isEqualTo(MY_PENDING);
        assertThat(summary.myTask().inProgressCount()).isEqualTo(MY_ASSIGNED);
        assertThat(summary.myTask().reviewPendingCount()).isEqualTo(MY_IN_REVIEW);
        assertThat(summary.myTask().rejectedCount()).isEqualTo(MY_REJECTED);
        verify(authrtRepository).countActiveTasksByUserNo(WORKER_NO);
    }

    // ------------------------------------------------------------------
    // ② 작업자 통계 — IDOR 가드는 작업자만 붙잡는다
    // ------------------------------------------------------------------

    @Test
    @DisplayName("관리자는_다른_작업자의_통계를_조회할_수_있다")
    void adminCanQueryAnotherWorkersStats() {
        // given — 관리자(9001)가 남(4242)의 통계를 지정 조회한다.
        WorkerStatSummaryResponse worker =
                service.getWorkerSummary(actor(OTHER_NO, Role.ADMIN), WORKER_NO);

        // then — 거부되지 않고 <대상 작업자> 의 수치가 실린다.
        assertThat(worker.workerId()).isEqualTo(String.valueOf(WORKER_NO));
        assertThat(worker.completed()).isEqualTo(WORKER_APPROVED);
        assertThat(worker.rejected()).isEqualTo(WORKER_REJECTED);
        assertThat(worker.inProgress()).isEqualTo(WORKER_IN_PROGRESS);
    }

    @Test
    @DisplayName("검수자는_다른_작업자의_통계를_조회할_수_있다")
    void reviewerCanQueryAnotherWorkersStats() {
        WorkerStatSummaryResponse worker =
                service.getWorkerSummary(actor(OTHER_NO, Role.REVIEWER), WORKER_NO);

        assertThat(worker.workerId()).isEqualTo(String.valueOf(WORKER_NO));
        assertThat(worker.completed()).isEqualTo(WORKER_APPROVED);
    }

    @Test
    @DisplayName("대조군_작업자는_다른_작업자의_통계를_조회하면_거부된다")
    void workerCannotQueryAnotherWorkersStats() {
        // given — 작업자(9001)가 남(4242)의 통계를 지정 조회한다. 사번은 숫자라
        //   앞선 사번 해석 게이트는 통과하고, 거부는 오직 역할 축에서 나온다.
        assertThatThrownBy(() -> service.getWorkerSummary(actor(OTHER_NO, Role.WORKER), WORKER_NO))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("대조군_작업자는_본인_통계는_조회할_수_있다")
    void workerCanQueryOwnStats() {
        // 가드가 「작업자면 무조건 거부」로 굳지 않았는지 — 같은 역할·같은 창구의 반대편.
        assertThatCode(() -> {
            WorkerStatSummaryResponse mine =
                    service.getWorkerSummary(actor(WORKER_NO, Role.WORKER), WORKER_NO);
            assertThat(mine.completed()).isEqualTo(WORKER_APPROVED);
            assertThat(mine.inProgress()).isEqualTo(WORKER_IN_PROGRESS);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("대상_미지정이면_관리자도_본인_통계를_받는다")
    void adminWithoutTargetGetsOwnStats() {
        // 관리자에게 열린 것은 「대상 지정」이지 「대상 없음이면 남의 것」이 아니다.
        WorkerStatSummaryResponse mine = service.getWorkerSummary(actor(OTHER_NO, Role.ADMIN), null);

        assertThat(mine.workerId()).isEqualTo(String.valueOf(OTHER_NO));
        // 픽스처는 WORKER_NO 몫만 심었으므로 관리자 본인 통계는 0 이다.
        assertThat(mine.completed()).isZero();
        assertThat(mine.inProgress()).isZero();
        verify(statsQueryRepository).countWorkerTaskByStatus(OTHER_NO);
    }

    // ------------------------------------------------------------------
    // ③ 판정 위임 — 계층은 한 곳에서만 해석된다
    // ------------------------------------------------------------------

    @Test
    @DisplayName("작업자_전용_자리는_관리자에게_열리지_않는다는_계층_전제를_고정한다")
    void workerOnlySeatStaysClosedToAdmin() {
        // 이 서비스의 두 자리는 「작업자만」을 요구한다. 계층이 작업자까지 넓어지면 위 두 축이
        // 동시에 무너지므로 그 전제를 여기서 함께 고정한다(판정기는 TokenClaims 소유).
        assertThat(TokenClaims.hasRole(actor(OTHER_NO, Role.ADMIN), Role.WORKER)).isFalse();
        assertThat(TokenClaims.hasRole(actor(OTHER_NO, Role.REVIEWER), Role.WORKER)).isFalse();
        assertThat(TokenClaims.hasRole(actor(WORKER_NO, Role.WORKER), Role.WORKER)).isTrue();
        assertThat(TokenClaims.hasRole(null, Role.WORKER)).isFalse();
    }

    @Test
    @DisplayName("역할_미배정_토큰은_작업자_축을_받지_않는다")
    void roleLessTokenGetsNoWorkerView() {
        // 역할 배정 전 INTERNAL 사용자(role null) — fail-closed 로 검수자 시야도 작업자 시야도
        // 아닌 빈 「내 작업」 을 받는다(NPE 없이).
        DashboardSummaryResponse summary =
                service.getSummary(new TokenClaims(String.valueOf(WORKER_NO), null, Channel.INTERNAL, null));

        assertThat(summary.myTaskCount()).isZero();
        verify(authrtRepository, never()).countActiveTasksByUserNo(anyLong());
    }

    @Test
    @DisplayName("인증정보가_없으면_작업자_축을_받지_않는다")
    void nullActorGetsNoWorkerView() {
        DashboardSummaryResponse summary = service.getSummary(null);

        assertThat(summary.myTaskCount()).isZero();
        verify(authrtRepository, never()).countActiveTasksByUserNo(anyLong());
    }

    @Test
    @DisplayName("사번이_없는_토큰은_작업자_통계에서_401이다")
    void nonNumericSubIsUnauthorized() {
        // 역할 판정 뒤의 사번 게이트 — 이 시험이 있어야 위 403 단언이 사번 실패를 대신 잡고 있는
        // 것이 아님을 알 수 있다(같은 창구, 다른 거부 사유).
        assertThatThrownBy(() -> service.getWorkerSummary(
                new TokenClaims("not-a-number", Role.WORKER, Channel.INTERNAL, null), WORKER_NO))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
    }
}
