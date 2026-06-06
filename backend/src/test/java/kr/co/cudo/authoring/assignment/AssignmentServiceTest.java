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
    @DisplayName("assign_요청에_reviewerId가_있으면_응답_Item에도_reviewerId가_반영")
    void assignReturnsReviewerIdInResponse() {
        // given — workerId=100, reviewerId=1 (REVIEWER 시드) 함께 배정 요청.
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L), 1L);

        // when
        AssignmentResponse response = assignmentService.assign(req, reviewer());

        // then — 응답 Item 의 reviewerId 가 요청값(1)으로 반영되어야 한다 (이전엔 항상 null 반환 버그).
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).reviewerId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("assign_요청에_reviewerId가_없으면_응답_Item의_reviewerId는_null")
    void assignReturnsNullReviewerIdWhenNotRequested() {
        // given — reviewerId 미지정.
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));

        // when
        AssignmentResponse response = assignmentService.assign(req, reviewer());

        // then
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).reviewerId()).isNull();
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
                        "RAW_FILE_PATH_NM, DATA_STTS_CD, REG_DT) VALUES (?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP)",
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
                        "RAW_FILE_PATH_NM, DATA_STTS_CD, REG_DT) VALUES (?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP)",
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
    @DisplayName("listAssignments_응답_Item에_eventName_eventTypeCd가_매핑되어_반환")
    void listAssignmentsIncludesEventInfo() {
        // given — LS_DATA_RAW 시드에 EVNT_TYPE_CD 포함. eventName/eventTypeCd 양쪽에 EVNT_TYPE_CD 값이 반환된다 (Phase 1 정책).
        // RAW_SN 은 다른 테스트와 충돌 방지를 위해 별도 ID 사용 + 사전 삭제.
        jdbcTemplate.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", 2000L);
        jdbcTemplate.update(
                "INSERT INTO LS_DATA_RAW (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, PRVC_TYPE_CD, PRVC_YN, DE_IDNTF_YN, " +
                        "RAW_FILE_PATH_NM, DATA_STTS_CD, REG_DT) VALUES (?,?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP)",
                2000L, "TEST-CLIP-EVT-2000", "CCTV-EVT-001",
                "FIRE", "ANONY", "N", "N", "/tmp/test/2000.mp4", "PENDING");

        // when
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(2000L));
        assignmentService.assign(req, reviewer());

        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                null, reviewer(), PageRequest.of(0, 20));

        // then
        assertThat(page.getContent()).hasSize(1);
        AssignmentResponse.Item item = page.getContent().get(0);
        assertThat(item.eventTypeCd()).isEqualTo("FIRE");
        assertThat(item.eventName()).isEqualTo("FIRE");
    }

    @Test
    @DisplayName("listAssignments_LS_DATA_RAW_없을때_eventName_null_폴백")
    void listAssignmentsEventNameNullFallback() {
        // given — LS_DATA_RAW 미시딩. 배정만 존재 → eventName/eventTypeCd 는 null 로 폴백.
        // 이전 테스트의 LS_DATA_RAW 잔존 데이터를 미리 비워 lookup 이 null 을 반환하는 시나리오 보장.
        jdbcTemplate.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", 2001L);
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(2001L));
        assignmentService.assign(req, reviewer());

        // when
        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                null, reviewer(), PageRequest.of(0, 20));

        // then
        assertThat(page.getContent()).hasSize(1);
        AssignmentResponse.Item item = page.getContent().get(0);
        assertThat(item.eventName()).isNull();
        assertThat(item.eventTypeCd()).isNull();
    }

    @Test
    @DisplayName("requireReviewer_actor가_null이면_UNAUTHORIZED")
    void assignWithNullActorThrowsUnauthorized() {
        // given — assign 진입 시 actor 가 null 이면 actor.role() NPE 가 아니라
        // CustomException(UNAUTHORIZED) 으로 일관 처리되어야 한다 (CWE-476 방어).
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));

        // when / then
        assertThatThrownBy(() -> assignmentService.assign(req, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("reassign에_actor가_null이면_UNAUTHORIZED")
    void reassignWithNullActorThrowsUnauthorized() {
        assertThatThrownBy(() -> assignmentService.reassign(1L, new ReassignRequest(101L), null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("listAssignments_응답_Item에_status가_FE_AssignmentStatus로_매핑되어_반환")
    void listAssignmentsIncludesStatus() {
        // given — 배정 생성 (assign 호출이 LS_RAW_DATA_STATUS 를 ASSIGNED 로 마킹).
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        assignmentService.assign(req, reviewer());

        // when
        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                null, reviewer(), PageRequest.of(0, 20));

        // then — ASSIGNED → FE 'PENDING' 매핑.
        assertThat(page.getContent()).hasSize(1);
        AssignmentResponse.Item item = page.getContent().get(0);
        assertThat(item.status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("listAssignments_LS_RAW_DATA_STATUS_미존재_시_status는_PENDING_폴백")
    void listAssignmentsStatusFallbackToPending() {
        // given — LS_TASK_ASSIGNMENT 만 직접 INSERT (assign 메서드를 우회해 LS_RAW_DATA_STATUS 미생성).
        // dataSttsByVideo lookup 이 키를 찾지 못해 mapToFeStatus(null) → 'PENDING' 폴백을 거쳐야 한다.
        jdbcTemplate.update(
                "INSERT INTO LS_TASK_ASSIGNMENT (USER_NO, RAW_DATA_ID, TASK_TYPE_CD, REG_USER_NO, REG_DT) " +
                        "VALUES (?,?,?,?, CURRENT_TIMESTAMP)",
                100L, 9999L, "LABELER", 1L);

        // when
        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                null, reviewer(), PageRequest.of(0, 20));

        // then
        assertThat(page.getContent()).hasSize(1);
        AssignmentResponse.Item item = page.getContent().get(0);
        assertThat(item.rawDataId()).isEqualTo(9999L);
        assertThat(item.status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("reassign_완료된_배정은_거부_ASSIGNMENT_ALREADY_COMPLETED")
    void reassignRejectsCompletedAssignment() {
        // given — 배정 생성 후 LS_RAW_DATA_STATUS 를 APPROVED 로 강제 전이 (검수 승인 시뮬레이션).
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        var created = assignmentService.assign(req, reviewer());
        Long authrtSeq = created.items().get(0).authrtSeq();
        jdbcTemplate.update(
                "UPDATE LS_RAW_DATA_STATUS SET DATA_STTS_CD = 'APPROVED', UPD_DT = CURRENT_TIMESTAMP " +
                        "WHERE RAW_DATA_ID = ?", 1000L);

        // when / then — 재배정 시도 시 CONFLICT 로 거부.
        assertThatThrownBy(() ->
                assignmentService.reassign(authrtSeq, new ReassignRequest(101L), reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSIGNMENT_ALREADY_COMPLETED);

        // 회귀 가드 — 배정의 USER_NO 가 변경되지 않아야 한다.
        LsTaskAssignment after = authrtRepository.findById(authrtSeq).orElseThrow();
        assertThat(after.getUserNo()).isEqualTo(100L);
    }

    @Test
    @DisplayName("reassign_시_REASSIGN_이벤트가_prev_subject_와_함께_누적")
    void reassignAccumulatesEventLog() {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        var created = assignmentService.assign(req, reviewer());
        Long authrtSeq = created.items().get(0).authrtSeq();

        assignmentService.reassign(authrtSeq, new ReassignRequest(101L), reviewer());

        List<LsTaskEventLog> rows = taskEventLogRepository.findByRawDataIdOrderByOcrnDtAsc(1000L);
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
