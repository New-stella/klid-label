package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.dto.AssignmentCreateRequest;
import kr.co.cudo.authoring.assignment.dto.AssignmentHistoryResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentResponse;
import kr.co.cudo.authoring.assignment.dto.ReassignRequest;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignHistoryRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.service.AssignmentService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AssignmentServiceTest {

    @Autowired private AssignmentService assignmentService;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsTaskAssignHistoryRepository hstryRepository;
    @Autowired private LsTaskEventLogRepository taskEventLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("assign_트랜잭션_중간_실패시_전체_롤백")
    void rollbackOnDuplicate() {
        AssignmentCreateRequest first = new AssignmentCreateRequest(100L, List.of(1000L));
        assignmentService.assign(first, reviewer());

        // 두 번째 요청에 새 영상 1002 + 중복 1000 포함 → 1000 의 unique 제약 위반으로 롤백 → 1002 도 반영 안 됨
        AssignmentCreateRequest second = new AssignmentCreateRequest(100L, List.of(1002L, 1000L));
        assertThatThrownBy(() -> assignmentService.assign(second, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        List<LsTaskAssignment> all = authrtRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getRawDataId()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("listAssignments에_actor가_null이면_UNAUTHORIZED")
    void listAssignmentsNullActorThrowsUnauthorized() {
        assertThatThrownBy(() -> assignmentService.listAssignments(null, null, PageRequest.of(0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("reassign_시_actor_sub와_시각이_HSTRY에_기록")
    void reassignRecordsActorAndTime() {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        var created = assignmentService.assign(req, reviewer());
        Long authrtSeq = created.items().get(0).authrtSeq();

        assignmentService.reassign(authrtSeq, new ReassignRequest(101L), reviewer());

        var history = hstryRepository.findByAuthrtSeqOrderByChgDtAsc(authrtSeq);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getChgUserNo()).isEqualTo(1L);
        assertThat(history.get(0).getChgDt()).isNotNull();
        assertThat(history.get(0).getPrevUserNo()).isEqualTo(100L);
        assertThat(history.get(0).getNewUserNo()).isEqualTo(101L);
    }

    @Test
    @DisplayName("getHistory_재배정_이력_없을때_ASSIGN_단건_반환")
    void getHistoryReturnsAssignWhenNoReassign() {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        var created = assignmentService.assign(req, reviewer());
        Long authrtSeq = created.items().get(0).authrtSeq();

        List<AssignmentHistoryResponse> history = assignmentService.getHistory(authrtSeq, reviewer());

        assertThat(history).hasSize(1);
        AssignmentHistoryResponse only = history.get(0);
        assertThat(only.eventTypeCd()).isEqualTo("ASSIGN");
        assertThat(only.actorUserNo()).isEqualTo(1L);
        assertThat(only.actorUserName()).isEqualTo("검수자1");
        assertThat(only.subjectUserNo()).isEqualTo(100L);
        assertThat(only.subjectUserName()).isEqualTo("작업자100");
        assertThat(only.prevUserNo()).isNull();
        assertThat(only.prevUserName()).isNull();
        assertThat(only.reason()).isNull();
        assertThat(only.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("getHistory_재배정_2회_있을때_총_3건_시간순_반환")
    void getHistoryReturnsAssignPlusReassigns() {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        var created = assignmentService.assign(req, reviewer());
        Long authrtSeq = created.items().get(0).authrtSeq();

        assignmentService.reassign(authrtSeq, new ReassignRequest(101L), reviewer());
        assignmentService.reassign(authrtSeq, new ReassignRequest(200L), reviewer());

        List<AssignmentHistoryResponse> history = assignmentService.getHistory(authrtSeq, reviewer());

        assertThat(history).hasSize(3);

        AssignmentHistoryResponse first = history.get(0);
        assertThat(first.eventTypeCd()).isEqualTo("ASSIGN");
        assertThat(first.actorUserNo()).isEqualTo(1L);
        assertThat(first.subjectUserNo()).isEqualTo(100L);
        assertThat(first.subjectUserName()).isEqualTo("작업자100");
        assertThat(first.prevUserNo()).isNull();

        AssignmentHistoryResponse second = history.get(1);
        assertThat(second.eventTypeCd()).isEqualTo("REASSIGN");
        assertThat(second.actorUserNo()).isEqualTo(1L);
        assertThat(second.subjectUserNo()).isEqualTo(101L);
        assertThat(second.prevUserNo()).isEqualTo(100L);

        AssignmentHistoryResponse third = history.get(2);
        assertThat(third.eventTypeCd()).isEqualTo("REASSIGN");
        assertThat(third.actorUserNo()).isEqualTo(1L);
        assertThat(third.subjectUserNo()).isEqualTo(200L);
        assertThat(third.prevUserNo()).isEqualTo(101L);

        // 시간순 정합성: ASSIGN <= REASSIGN1 <= REASSIGN2
        assertThat(first.occurredAt()).isBeforeOrEqualTo(second.occurredAt());
        assertThat(second.occurredAt()).isBeforeOrEqualTo(third.occurredAt());
    }

    @Test
    @DisplayName("assign_시_LS_TASK_EVENT_LOG에_ASSIGN_이벤트_누적")
    void assignAccumulatesEventLog() {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L, 1001L));
        assignmentService.assign(req, reviewer());

        List<LsTaskEventLog> rows = taskEventLogRepository.findAll();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getEventTypeCd()).isEqualTo("ASSIGN");
            assertThat(r.getActorUserNo()).isEqualTo(1L);
            assertThat(r.getSubjectUserNo()).isEqualTo(100L);
            assertThat(r.getRawDataId()).isIn(1000L, 1001L);
        });
    }

    @Test
    @DisplayName("listAssignments_응답_Item에_MNG_RESOURCE_CCTV의_cctvName이_매핑되어_반환")
    void listAssignmentsIncludesCctvName() {
        // MNG_RESOURCE_CCTV 마스터 + LS_DATA_RAW 시드 (FK 무관 — 단순 LEFT JOIN lookup 검증).
        // 영상명 컬럼이 "CCTV-강남구-001" 형식으로 표시되도록 한다.
        jdbcTemplate.update("INSERT INTO MNG_RESOURCE_CCTV (VMS_CCTV_ID, CCTV_NM, USE_YN) VALUES (?,?,?)",
                "CCTV-GANGNAM-001", "CCTV-강남구-001", "Y");
        jdbcTemplate.update(
                "INSERT INTO LS_DATA_RAW (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, PRVC_TYPE_CD, PRVC_YN, DE_IDNTF_YN, " +
                        "FILE_PATH, DATA_STTS_CD, REG_DT) VALUES (?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP)",
                1000L, "TEST-CLIP-1000", "CCTV-GANGNAM-001",
                "ANONY", "N", "N", "/tmp/test/1000.mp4", "PENDING");

        // 배정 생성 — rawDataId=1000 으로 LABELER 1건.
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        assignmentService.assign(req, reviewer());

        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                null, reviewer(), PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        AssignmentResponse.Item item = page.getContent().get(0);
        assertThat(item.rawDataId()).isEqualTo(1000L);
        assertThat(item.cctvName()).isEqualTo("CCTV-강남구-001");
        // videoTitle 도 cctvName 으로 채워져 mock 디자인 정합.
        assertThat(item.videoTitle()).isEqualTo("CCTV-강남구-001");
    }

    @Test
    @DisplayName("listAssignments_MNG_RESOURCE_CCTV_매핑_없으면_VMS_CCTV_ID_폴백")
    void listAssignmentsCctvNameFallsBackToVmsCctvId() {
        // CCTV 마스터 시드 없이 LS_DATA_RAW 만 등록 → cctvName 은 vmsCctvId 폴백.
        jdbcTemplate.update(
                "INSERT INTO LS_DATA_RAW (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, PRVC_TYPE_CD, PRVC_YN, DE_IDNTF_YN, " +
                        "FILE_PATH, DATA_STTS_CD, REG_DT) VALUES (?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP)",
                1001L, "TEST-CLIP-1001", "CCTV-ORPHAN-001",
                "ANONY", "N", "N", "/tmp/test/1001.mp4", "PENDING");

        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1001L));
        assignmentService.assign(req, reviewer());

        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                null, reviewer(), PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        AssignmentResponse.Item item = page.getContent().get(0);
        assertThat(item.cctvName()).isEqualTo("CCTV-ORPHAN-001");
    }

    @Test
    @DisplayName("reassign_시_REASSIGN_이벤트가_prev_subject_와_함께_누적")
    void reassignAccumulatesEventLog() {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        var created = assignmentService.assign(req, reviewer());
        Long authrtSeq = created.items().get(0).authrtSeq();

        assignmentService.reassign(authrtSeq, new ReassignRequest(101L), reviewer());

        List<LsTaskEventLog> rows = taskEventLogRepository.findByRawDataIdOrderByOccurredAtAsc(1000L);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getEventTypeCd()).isEqualTo("ASSIGN");
        assertThat(rows.get(0).getSubjectUserNo()).isEqualTo(100L);

        LsTaskEventLog reassign = rows.get(1);
        assertThat(reassign.getEventTypeCd()).isEqualTo("REASSIGN");
        assertThat(reassign.getActorUserNo()).isEqualTo(1L);
        assertThat(reassign.getSubjectUserNo()).isEqualTo(101L);
        assertThat(reassign.getPrevUserNo()).isEqualTo(100L);
    }
}
