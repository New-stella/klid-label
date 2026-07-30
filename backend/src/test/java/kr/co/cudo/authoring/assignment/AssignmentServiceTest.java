package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.dto.AssignmentCreateRequest;
import kr.co.cudo.authoring.assignment.dto.AssignmentHistoryResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentSearchCondition;
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
import kr.co.cudo.authoring.support.RawVideoFixture;
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
        assertThatThrownBy(() -> assignmentService.listAssignments(AssignmentSearchCondition.none(), null, PageRequest.of(0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    /**
     * 인가 fail-closed — WORKER/REVIEWER 가 아닌 역할은 조회 자체가 거부된다.
     *
     * <p>HTTP 경로에서는 SecurityConfig 의 {@code /v1/**} 매처(INTERNAL 채널 + REVIEWER|WORKER)가
     * 먼저 막지만, 그 매처가 완화되면 이 분기가 <b>유일한</b> 방어가 된다. 따라서 서비스 레벨에서
     * 직접 고정한다(별도 경로인 배정 이력 403 테스트로는 이 분기가 덮이지 않는다).
     */
    @Test
    @DisplayName("PORTAL_USER_역할은_목록_조회시_403")
    void listAssignmentsRejectsPortalUser() {
        TokenClaims portal = new TokenClaims("3", Role.PORTAL_USER, Channel.PORTAL,
                Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> assignmentService.listAssignments(
                AssignmentSearchCondition.none(), portal, PageRequest.of(0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("역할_미배정_토큰도_목록_조회시_403")
    void listAssignmentsRejectsRolelessToken() {
        TokenClaims roleless = new TokenClaims("1", null, Channel.INTERNAL,
                Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> assignmentService.listAssignments(
                AssignmentSearchCondition.none(), roleless, PageRequest.of(0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    /**
     * fail-closed 필터 — 미지 {@code workStatus} 는 조용히 무시(= WHERE 절 증발)되지 않고 400 이다.
     * 컨트롤러 {@code @Pattern} 을 우회한 호출에서도 필터가 사라지지 않음을 서비스 레벨에서 고정한다.
     */
    @Test
    @DisplayName("미지_workStatus_는_필터가_무시되지_않고_INVALID_INPUT")
    void listAssignmentsRejectsUnknownWorkStatus() {
        AssignmentSearchCondition bogus =
                AssignmentSearchCondition.ofRequest(null, null, "UNASSIGNED", null);

        assertThatThrownBy(() -> assignmentService.listAssignments(
                bogus, reviewer(), PageRequest.of(0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
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
        // MNG_RESOURCE_CCTV 마스터 등록 + 영상 1000(test-data.sql 시드)의 CCTV 를 그 마스터로 지정.
        // 영상명 컬럼이 "CCTV-강남구-001" 형식으로 표시되도록 한다.
        jdbcTemplate.update("INSERT INTO MNG_RESOURCE_CCTV (VMS_CCTV_ID, CCTV_NM, USE_YN) VALUES (?,?,?) "
                        + "ON CONFLICT (VMS_CCTV_ID) DO NOTHING",
                "CCTV-GANGNAM-001", "CCTV-강남구-001", "Y");
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET VMS_CCTV_ID = ? WHERE RAW_SN = ?",
                "CCTV-GANGNAM-001", 1000L);

        // 배정 생성 — rawDataId=1000 으로 LABELER 1건.
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        assignmentService.assign(req, reviewer());

        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                AssignmentSearchCondition.none(), reviewer(), PageRequest.of(0, 20));

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
        // CCTV 마스터에 없는 VMS_CCTV_ID 를 가진 영상 → cctvName 은 vmsCctvId 폴백.
        jdbcTemplate.update("DELETE FROM MNG_RESOURCE_CCTV WHERE VMS_CCTV_ID = ?", "CCTV-ORPHAN-001");
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET VMS_CCTV_ID = ? WHERE RAW_SN = ?",
                "CCTV-ORPHAN-001", 1001L);

        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1001L));
        assignmentService.assign(req, reviewer());

        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                AssignmentSearchCondition.none(), reviewer(), PageRequest.of(0, 20));

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
                "INSERT INTO LS_DATA_RAW (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN," +
                        "RAW_FILE_PATH_NM, DATA_STTS_CD, REG_DT) VALUES (?,?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP)",
                2000L, "TEST-CLIP-EVT-2000", "CCTV-EVT-001",
                "FIRE", "ANONY", "N", "N", "/tmp/test/2000.mp4", "PENDING");

        // when
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(2000L));
        assignmentService.assign(req, reviewer());

        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                AssignmentSearchCondition.none(), reviewer(), PageRequest.of(0, 20));

        // then
        assertThat(page.getContent()).hasSize(1);
        AssignmentResponse.Item item = page.getContent().get(0);
        assertThat(item.eventTypeCd()).isEqualTo("FIRE");
        assertThat(item.eventName()).isEqualTo("FIRE");
    }

    @Test
    @DisplayName("listAssignments_EVNT_TYPE_CD_없을때_eventName_null_폴백")
    void listAssignmentsEventNameNullFallback() {
        // given — EVNT_TYPE_CD 가 비어 있는 영상(test-data.sql 시드 1003)에 배정 1건.
        //   구 시나리오는 "LS_DATA_RAW 자체가 없는 배정"(=고아 행)이었으나, V146(DB-ISSUE-01)이
        //   LS_TASK_ASSIGNMENT → LS_DATA_RAW FK 를 세워 그 상태는 <구조적으로 불가능>해졌다
        //   (프로덕션에서도 도달 불가). 검증 대상인 eventName/eventTypeCd null 폴백은 이벤트 유형이
        //   비어 있는 영상으로 그대로 성립하므로 단언은 동일하게 유지한다.
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = NULL WHERE RAW_SN = ?", 1003L);
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1003L));
        assignmentService.assign(req, reviewer());

        // when
        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                AssignmentSearchCondition.none(), reviewer(), PageRequest.of(0, 20));

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
                AssignmentSearchCondition.none(), reviewer(), PageRequest.of(0, 20));

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
        // 배정 행의 부모 영상은 실재해야 한다(V146 FK) — 검증 대상은 <작업 상태 행> 부재이지 영상 부재가 아니다.
        RawVideoFixture.seedRaw(jdbcTemplate, 9999L);
        jdbcTemplate.update(
                "INSERT INTO LS_TASK_ASSIGNMENT (USER_NO, RAW_DATA_ID, TASK_TYPE_CD, REG_USER_NO, REG_DT) " +
                        "VALUES (?,?,?,?, CURRENT_TIMESTAMP)",
                100L, 9999L, "LABELER", 1L);

        // when
        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                AssignmentSearchCondition.none(), reviewer(), PageRequest.of(0, 20));

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

    // ── D-ISSUE-01: 신규 배정이 APPROVED 를 무검증 강등하는 결함 회귀 가드 ──

    /** LS_RAW_DATA_STATUS 현재 상태 조회 (row 없으면 null). */
    private String currentStatus(long rawDataId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT DATA_STTS_CD FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", String.class, rawDataId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    @DisplayName("APPROVED_영상에_신규배정시_409_이고_상태가_APPROVED_로_유지됨")
    void assignRejectsApprovedVideo() {
        // given — 1000 배정 후 검수 승인(APPROVED) 시뮬레이션.
        assignmentService.assign(new AssignmentCreateRequest(100L, List.of(1000L)), reviewer());
        jdbcTemplate.update(
                "UPDATE LS_RAW_DATA_STATUS SET DATA_STTS_CD = 'APPROVED', UPD_DT = CURRENT_TIMESTAMP " +
                        "WHERE RAW_DATA_ID = ?", 1000L);

        // when / then — 다른 작업자에게 신규 배정 시도 → 재배정과 동일 코드(409)로 거부.
        assertThatThrownBy(() ->
                assignmentService.assign(new AssignmentCreateRequest(101L, List.of(1000L)), reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSIGNMENT_ALREADY_COMPLETED);

        // 상태 불변 — V_COMPLETED_VIDEO 에서 검수완료 영상이 사라지지 않아야 한다.
        assertThat(currentStatus(1000L)).isEqualTo("APPROVED");
        // 신규 배정 row 미생성 (기존 LABELER 1건만 유지)
        assertThat(authrtRepository.findAll()).hasSize(1);
        assertThat(authrtRepository.findAll().get(0).getUserNo()).isEqualTo(100L);
    }

    @Test
    @DisplayName("APPROVED_가_아닌_영상_신규배정은_기존대로_201_이고_ASSIGNED_로_전이됨")
    void assignStillWorksForNonApprovedVideo() {
        // given — 상태 row 가 없는 신규 영상(=정상 배정 경로).
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1001L));

        // when
        AssignmentResponse response = assignmentService.assign(req, reviewer());

        // then — 게이트 도입 후에도 정상 배정 플로우는 그대로 동작해야 한다(회귀 방어).
        assertThat(response.items()).hasSize(1);
        assertThat(currentStatus(1001L)).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("배정목록에_APPROVED_가_1건_섞이면_전체실패하고_어떤_배정도_생성되지_않음")
    void assignFailsEntirelyWhenAnyVideoApproved() {
        // given — 1000 은 검수완료(APPROVED), 1001 은 미배정 신규.
        assignmentService.assign(new AssignmentCreateRequest(100L, List.of(1000L)), reviewer());
        jdbcTemplate.update(
                "UPDATE LS_RAW_DATA_STATUS SET DATA_STTS_CD = 'APPROVED', UPD_DT = CURRENT_TIMESTAMP " +
                        "WHERE RAW_DATA_ID = ?", 1000L);

        // when / then — 복수 배정 중 1건이라도 APPROVED 면 전체 실패 (부분성공 금지 — 어느 영상이
        //               배정되지 않았는지 호출자가 알 수 없어 모호해지는 것을 방지).
        assertThatThrownBy(() ->
                assignmentService.assign(new AssignmentCreateRequest(101L, List.of(1001L, 1000L)), reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ASSIGNMENT_ALREADY_COMPLETED);

        assertThat(currentStatus(1000L)).isEqualTo("APPROVED");
        // 1001 은 상태 row 조차 생성되지 않아야 한다(전체 실패).
        assertThat(currentStatus(1001L)).isNull();
        assertThat(authrtRepository.findAll()).hasSize(1);
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

    @Test
    @DisplayName("listAssignments_파생영상은_augmented_true_augType_반환")
    void listAssignmentsDerivedVideoAugmented() {
        // given — 파생 영상(ORGNL_RAW_SN=3000, VMS_CLIP_ID 에 RESL_RESL_480P 드리프트 포함) 시드 후 배정.
        jdbcTemplate.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", 3001L);
        jdbcTemplate.update(
                "INSERT INTO LS_DATA_RAW (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, ORGNL_RAW_SN, PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN," +
                        "RAW_FILE_PATH_NM, DATA_STTS_CD, REG_DT) VALUES (?,?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP)",
                3001L, "TEST-3000_RESL_RESL_480P_123", "CCTV-AUG-001", 3000L,
                "ANONY", "N", "N", "/tmp/test/3001.mp4", "PENDING");

        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(3001L));
        assignmentService.assign(req, reviewer());

        // when
        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                AssignmentSearchCondition.none(), reviewer(), PageRequest.of(0, 20));

        // then — 파생 RAW 는 작업 목록에 유지되며(R2) augmented=true + 정규화 augType=RESL_480P.
        assertThat(page.getContent()).hasSize(1);
        AssignmentResponse.Item item = page.getContent().get(0);
        assertThat(item.rawDataId()).isEqualTo(3001L);
        assertThat(item.augmented()).isTrue();
        assertThat(item.augType()).isEqualTo("RESL_480P");
    }

    @Test
    @DisplayName("listAssignments_원본영상은_augmented_false_augType_null")
    void listAssignmentsOriginalVideoNotAugmented() {
        // given — 원본 영상(ORGNL_RAW_SN=null) 시드 후 배정.
        jdbcTemplate.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", 3002L);
        jdbcTemplate.update(
                "INSERT INTO LS_DATA_RAW (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN," +
                        "RAW_FILE_PATH_NM, DATA_STTS_CD, REG_DT) VALUES (?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP)",
                3002L, "TEST-CLIP-ORIG-3002", "CCTV-ORIG-001",
                "ANONY", "N", "N", "/tmp/test/3002.mp4", "PENDING");

        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(3002L));
        assignmentService.assign(req, reviewer());

        // when
        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                AssignmentSearchCondition.none(), reviewer(), PageRequest.of(0, 20));

        // then — 원본은 augmented=false, augType=null.
        assertThat(page.getContent()).hasSize(1);
        AssignmentResponse.Item item = page.getContent().get(0);
        assertThat(item.rawDataId()).isEqualTo(3002L);
        assertThat(item.augmented()).isFalse();
        assertThat(item.augType()).isNull();
    }
}
