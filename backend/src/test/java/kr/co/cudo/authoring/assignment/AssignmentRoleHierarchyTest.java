package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.dto.AssignmentCreateRequest;
import kr.co.cudo.authoring.assignment.dto.AssignmentHistoryResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentSearchCondition;
import kr.co.cudo.authoring.assignment.dto.EventTypeOptionsResponse;
import kr.co.cudo.authoring.assignment.dto.ReassignRequest;
import kr.co.cudo.authoring.assignment.dto.TaskBoardItemResponse;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSearchCondition;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSummaryResponse;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.service.AssignmentService;
import kr.co.cudo.authoring.assignment.service.TaskBoardService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 작업 배정 도메인 — <b>역할 계층(관리자 &gt; 검수자) 반영 + 작업자 전용 좁힘 보존</b> 회귀 가드.
 * [design: ADR-055] [design: ROLE-004] [design: AC-125]
 *
 * <h3>고정하는 자리</h3>
 * <ul>
 *   <li><b>배정 창구</b>({@code AssignmentService.requireReviewer}) — 배정 생성·재배정.
 *       「검수자 전용」은 「검수자 이상」이라 관리자가 그대로 들어간다.</li>
 *   <li><b>작업목록 창구</b>({@code TaskBoardService.requireReviewer}) — 목록·KPI·이벤트유형 옵션.</li>
 *   <li><b>배정목록 인가 축</b>({@code scopeForActor}) — 검수자 분기가 동등 비교면 관리자가
 *       fall-through 로 403 이 된다. 작업자 분기는 <b>본인 배정분 좁힘</b>이라 계층을 타지 않는다.</li>
 *   <li><b>배정 이력 IDOR 가드</b>({@code getHistory}) — 작업자만 본인 배정으로 좁혀지고
 *       관리자·검수자는 좁힘에 걸리지 않는다.</li>
 * </ul>
 *
 * <h3>시험이 헛돌지 않게 하는 장치</h3>
 * <p>「관리자가 통과한다」는 단언은 그 뒤에 오는 검사(작업자 존재·승인 여부·등재 게이트)를 전부
 * 통과하는 픽스처 위에서만 의미를 갖는다 — 뒤의 검사가 같은 거부를 던지면 역할 축 변형을 한 건도
 * 잡지 못한다. 그래서 배정·재배정은 <b>실재하는 시드 작업자·시드 영상</b>으로 끝까지 성공시키고
 * 결과 행까지 확인한다. 부정 단언(작업자·포털 거부)에는 반드시 <b>대조군</b>(그 경로가 검수자·
 * 작업자에게 살아 있음)을 짝으로 둔다.
 *
 * <h3>ADMIN 픽스처</h3>
 * <p>공용 시드({@code /db/test-data.sql})에 관리자를 심지 않는다 — 시스템에 관리자가 항상 있는
 * 셈이 되어 관리자 0명일 때만 열리는 부트스트랩 창구 시험이 통째로 깨진다. 이 클래스는 서비스를
 * 직접 호출하며 인가 판정이 {@link TokenClaims} 만 읽으므로, 토큰만 만들면 되고 사용자 행이
 * 필요 없다(배정 이력의 actor 는 이름 미해석으로 남을 뿐이다).
 */
@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AssignmentRoleHierarchyTest {

    /** 시드에 없는 sub — 인가 판정은 토큰만 읽는다(위 클래스 주석 「ADMIN 픽스처」). */
    private static final Long ADMIN_NO = 900L;

    @Autowired private AssignmentService assignmentService;
    @Autowired private TaskBoardService taskBoardService;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsTaskEventLogRepository taskEventLogRepository;

    private TokenClaims admin() {
        return new TokenClaims(String.valueOf(ADMIN_NO), Role.ADMIN, Channel.INTERNAL,
                Instant.now().plusSeconds(60));
    }

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    private TokenClaims worker(long userNo) {
        return new TokenClaims(String.valueOf(userNo), Role.WORKER, Channel.INTERNAL,
                Instant.now().plusSeconds(60));
    }

    private TokenClaims portal() {
        return new TokenClaims("3", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(60));
    }

    /**
     * 작업목록 조회 조건 — 배치 상태를 <b>시드 영상의 상태({@code PENDING})로 명시</b>한다.
     *
     * <p>{@code TaskBoardSearchCondition} 의 기본 조건은 배치 상태가 {@code COMPLETED} 로 채워지는데
     * 공용 시드({@code /db/test-data.sql})의 영상 1000~1003 은 {@code PENDING} 이다. 기본 조건으로
     * 두면 이 클래스를 단독 실행할 때 목록이 비어, 「검수자·관리자가 목록을 받는다」는 대조군 단언이
     * <b>다른 테스트 클래스가 공유 컨테이너에 남긴 COMPLETED 영상</b>에 얹혀 통과하게 된다(실제로
     * 그 상태였고 단독 실행에서 드러났다). 조건을 명시해 이 클래스 안에서 자족하게 만든다.
     */
    private TaskBoardSearchCondition boardCondition() {
        return new TaskBoardSearchCondition("PENDING", null, null, null, null);
    }

    // ── 배정 창구 (requireReviewer) ────────────────────────────────────────────

    @Test
    @DisplayName("관리자는_검수자와_같이_배정을_생성한다")
    void adminCanAssign() {
        AssignmentResponse response =
                assignmentService.assign(new AssignmentCreateRequest(100L, List.of(1000L)), admin());

        assertThat(response.items()).hasSize(1);
        // 게이트만 통과하고 끝나는 것이 아니라 실제로 행이 만들어졌음을 확인한다.
        List<LsTaskAssignment> all = authrtRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getRawDataId()).isEqualTo(1000L);
        assertThat(all.get(0).getUserNo()).isEqualTo(100L);
        // 배정 이벤트의 actor 가 관리자로 기록된다.
        assertThat(taskEventLogRepository.findByRawDataIdOrderByOcrnDtAsc(1000L))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getEventTypeCd()).isEqualTo(LsTaskEventLog.EVENT_ASSIGN);
                    assertThat(e.getActorUserNo()).isEqualTo(ADMIN_NO);
                });
    }

    @Test
    @DisplayName("관리자는_검수자와_같이_재배정한다")
    void adminCanReassign() {
        Long authrtSeq = assignmentService
                .assign(new AssignmentCreateRequest(100L, List.of(1000L)), reviewer())
                .items().get(0).authrtSeq();

        assignmentService.reassign(authrtSeq, new ReassignRequest(101L), admin());

        assertThat(authrtRepository.findById(authrtSeq)).get()
                .extracting(LsTaskAssignment::getUserNo).isEqualTo(101L);
        List<LsTaskEventLog> reassigns = taskEventLogRepository
                .findByRawDataIdOrderByOcrnDtAsc(1000L).stream()
                .filter(e -> LsTaskEventLog.EVENT_REASSIGN.equals(e.getEventTypeCd()))
                .toList();
        assertThat(reassigns).singleElement()
                .satisfies(e -> assertThat(e.getActorUserNo()).isEqualTo(ADMIN_NO));
    }

    /**
     * 대조군 — 배정 창구는 <b>작업자·포털에게 닫혀 있다</b>. 위 두 시험이 「경로가 살아 있고 다만
     * 역할로 갈린다」를 뜻하려면 이 축이 함께 고정돼야 한다.
     */
    @Test
    @DisplayName("작업자와_포털회원은_배정_생성이_거부된다")
    void nonReviewerCannotAssign() {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));

        assertThatThrownBy(() -> assignmentService.assign(req, worker(100L)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> assignmentService.assign(req, portal()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // 거부가 게이트에서 났고 부작용이 없음을 확인(뒤의 검사에서 난 거부와 구분).
        assertThat(authrtRepository.findAll()).isEmpty();
    }

    // ── 배정목록 인가 축 (scopeForActor) ───────────────────────────────────────

    @Test
    @DisplayName("관리자의_배정목록은_본인분으로_좁혀지지_않고_전체가_보인다")
    void adminSeesAllAssignments() {
        assignmentService.assign(new AssignmentCreateRequest(100L, List.of(1000L)), reviewer());
        assignmentService.assign(new AssignmentCreateRequest(101L, List.of(1001L)), reviewer());

        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                AssignmentSearchCondition.none(), admin(), PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).extracting(AssignmentResponse.Item::workerId)
                .containsExactlyInAnyOrder(100L, 101L);
    }

    /**
     * 대조군 — 작업자 전용 좁힘은 계층 도입 뒤에도 그대로다. 이 단언이 없으면 위 시험은
     * 「좁힘이 아예 사라져도」 통과한다.
     */
    @Test
    @DisplayName("작업자의_배정목록은_본인_배정분으로_좁혀진다")
    void workerSeesOnlyOwnAssignments() {
        assignmentService.assign(new AssignmentCreateRequest(100L, List.of(1000L)), reviewer());
        assignmentService.assign(new AssignmentCreateRequest(101L, List.of(1001L)), reviewer());

        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                AssignmentSearchCondition.none(), worker(100L), PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).workerId()).isEqualTo(100L);
        assertThat(page.getContent().get(0).rawDataId()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("포털회원은_배정목록_조회가_거부된다")
    void portalCannotListAssignments() {
        assertThatThrownBy(() -> assignmentService.listAssignments(
                AssignmentSearchCondition.none(), portal(), PageRequest.of(0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ── 배정 이력 IDOR 가드 (getHistory) ───────────────────────────────────────

    @Test
    @DisplayName("관리자는_남의_배정_이력도_조회한다")
    void adminReadsOthersHistory() {
        Long authrtSeq = assignmentService
                .assign(new AssignmentCreateRequest(100L, List.of(1000L)), reviewer())
                .items().get(0).authrtSeq();

        List<AssignmentHistoryResponse> history = assignmentService.getHistory(authrtSeq, admin());

        assertThat(history).hasSize(1);
        assertThat(history.get(0).eventTypeCd()).isEqualTo("ASSIGN");
    }

    /**
     * 대조군 — 작업자 전용 좁힘 보존. 배정 당사자는 읽고, 남은 403 이다.
     * 좁힘을 없애는 변형({@code if (false)})을 넣으면 두 번째 단언이 깨진다.
     */
    @Test
    @DisplayName("작업자는_본인_배정_이력만_조회하고_남의_이력은_403")
    void workerReadsOnlyOwnHistory() {
        Long authrtSeq = assignmentService
                .assign(new AssignmentCreateRequest(100L, List.of(1000L)), reviewer())
                .items().get(0).authrtSeq();

        assertThat(assignmentService.getHistory(authrtSeq, worker(100L))).hasSize(1);

        assertThatThrownBy(() -> assignmentService.getHistory(authrtSeq, worker(101L)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ── 작업목록 창구 (TaskBoardService.requireReviewer) ───────────────────────

    @Test
    @DisplayName("관리자는_검수자와_같이_작업목록_KPI_이벤트유형옵션을_조회한다")
    void adminCanReadTaskBoard() {
        assignmentService.assign(new AssignmentCreateRequest(100L, List.of(1000L)), reviewer());

        Page<TaskBoardItemResponse> board = taskBoardService.listBoard(
                boardCondition(), admin(), PageRequest.of(0, 50));
        // 단순 non-empty 는 남의 클래스가 남긴 행으로도 참이 된다 — 이 클래스가 심은 영상을 짚는다.
        assertThat(board.getContent()).extracting(TaskBoardItemResponse::videoId).contains(1000L);

        assertThatCode(() -> {
            TaskBoardSummaryResponse summary =
                    taskBoardService.summarizeBoard(boardCondition(), admin());
            assertThat(summary).isNotNull();
            EventTypeOptionsResponse options =
                    taskBoardService.listEventTypeOptions(boardCondition(), admin());
            assertThat(options).isNotNull();
        }).doesNotThrowAnyException();
    }

    /**
     * 대조군 — 작업목록은 검수자 이상 전용이라 작업자·포털에게 닫혀 있다(인가 축 불변).
     * 검수자가 같은 호출로 결과를 받는 것까지 함께 고정해 「경로가 살아 있음」을 증명한다.
     */
    @Test
    @DisplayName("작업목록은_검수자에게_열려_있고_작업자_포털회원에게는_403")
    void taskBoardStaysReviewerOnly() {
        assignmentService.assign(new AssignmentCreateRequest(100L, List.of(1000L)), reviewer());

        assertThat(taskBoardService.listBoard(
                boardCondition(), reviewer(), PageRequest.of(0, 50)).getContent())
                .extracting(TaskBoardItemResponse::videoId).contains(1000L);

        assertThatThrownBy(() -> taskBoardService.listBoard(
                boardCondition(), worker(100L), PageRequest.of(0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> taskBoardService.listBoard(
                boardCondition(), portal(), PageRequest.of(0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
