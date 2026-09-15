package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.support.IngestFlatValueSeeder;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.assignment.dto.AssignmentCreateRequest;
import kr.co.cudo.authoring.assignment.dto.AssignmentHistoryResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentSearchCondition;
import kr.co.cudo.authoring.assignment.dto.ReassignRequest;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
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
    @DisplayName("배정해도_검수자_배정_행이_새로_생기지_않는다")
    void assignNeverCreatesReviewerRow() {
        // given / when — 검수자 항목이 없는 요청(계약 축소 후의 유일한 형태).
        assignmentService.assign(new AssignmentCreateRequest(100L, List.of(1000L)), reviewer());

        // then — 배정의 대상은 작업자뿐이다(ADR-067). 검수자 유형 행은 한 건도 생기지 않는다.
        Long reviewerRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM LS_TASK_ALTMNT WHERE TASK_TYPE_CD = ?",
                Long.class, LsTaskAssignment.TASK_REVIEWER);
        assertThat(reviewerRows).isZero();
        // 작업자 배정은 그대로 만들어진다(회귀 가드 — 검수자 축만 걷어냈다).
        assertThat(authrtRepository.findAll())
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.getTaskTypeCd()).isEqualTo(LsTaskAssignment.TASK_LABELER);
                    assertThat(a.getUserNo()).isEqualTo(100L);
                });
    }

    @Test
    @DisplayName("응답_항목에_검수자_축이_없다")
    void assignResponseHasNoReviewerAxis() {
        AssignmentResponse response =
                assignmentService.assign(new AssignmentCreateRequest(100L, List.of(1000L)), reviewer());

        assertThat(response.items()).hasSize(1);
        // 필드 자체가 사라졌으므로 직렬화 결과에 그 이름이 없다 — 되살리면 이 단언이 깨진다.
        assertThat(AssignmentResponse.Item.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("reviewerId", "reviewerName");
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

    /**
     * 재배정하면 <b>이력이 남는다</b>는 불변식. 구 {@code LS_TASK_ASSIGN_HISTORY} 이중 쓰기가 V4 로
     * 제거됐으므로 단독 적재처인 {@code LS_TASK_EVNT_LOG} 로 <b>단언을 이관</b>했다(검증 유실 없음).
     * 이 테스트가 고정하는 축은 "누가·언제" 이고, "무엇이 어떻게 바뀌었나" 는
     * {@link #reassignAccumulatesEventLog()} 가 별도로 고정한다.
     */
    @Test
    @DisplayName("재배정하면_작업이벤트로그에_REASSIGN_1건이_남는다")
    void reassignRecordsActorAndTime() {
        AssignmentCreateRequest req = new AssignmentCreateRequest(100L, List.of(1000L));
        var created = assignmentService.assign(req, reviewer());
        Long authrtSeq = created.items().get(0).authrtSeq();

        assignmentService.reassign(authrtSeq, new ReassignRequest(101L), reviewer());

        List<LsTaskEventLog> reassigns = taskEventLogRepository.findByRawDataIdOrderByOcrnDtAsc(1000L).stream()
                .filter(e -> LsTaskEventLog.EVENT_REASSIGN.equals(e.getEventTypeCd()))
                .toList();
        assertThat(reassigns).hasSize(1);
        assertThat(reassigns.get(0).getActorUserNo()).isEqualTo(1L);
        assertThat(reassigns.get(0).getOcrnDt()).isNotNull();
        assertThat(reassigns.get(0).getPrevUserNo()).isEqualTo(100L);
        assertThat(reassigns.get(0).getSubjectUserNo()).isEqualTo(101L);
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
        // 배정은 역할을 남기지 않는 종류다 — 비어 있는 것이 정상이며 지어낸 값으로 채우지 않는다.
        assertThat(only.actorRoleCd()).isNull();
    }

    /**
     * 「검수 시작」도 이 타임라인의 조회 대상이다(ADR-067) — 점유가 전용 표가 아니라 이 원장의
     * 이벤트로 표현되므로 배정·제출·승인·반려와 같은 자리에 실린다. 아울러 <b>행위 시점 역할</b>이
     * 항목에 함께 실리고, 관리자가 한 행위는 관리자로 남는지 고정한다.
     */
    @Test
    @DisplayName("검수_시작도_이력에_실리고_행위_시점_역할이_함께_보인다")
    void historyCarriesStartReviewAndActorRole() {
        var created = assignmentService.assign(
                new AssignmentCreateRequest(100L, List.of(1000L)), reviewer());
        Long authrtSeq = created.items().get(0).authrtSeq();
        Long rawDataId = created.items().get(0).rawDataId();

        // 관리자가 검수를 시작하고 승인했다.
        taskEventLogRepository.saveAndFlush(LsTaskEventLog.startReview(rawDataId, 1L, Role.ADMIN));
        taskEventLogRepository.saveAndFlush(LsTaskEventLog.approve(rawDataId, 1L, Role.ADMIN));

        List<AssignmentHistoryResponse> history = assignmentService.getHistory(authrtSeq, reviewer());

        assertThat(history)
                .extracting(AssignmentHistoryResponse::eventTypeCd)
                .containsExactly(
                        LsTaskEventLog.EVENT_ASSIGN,
                        LsTaskEventLog.EVENT_START_REVIEW,
                        LsTaskEventLog.EVENT_APPROVE);
        assertThat(history)
                .extracting(AssignmentHistoryResponse::actorRoleCd)
                .as("옛 형태(배정)는 null, 새로 쌓인 두 건은 계층 승격값이 아닌 실제 역할 ADMIN")
                .containsExactly(null, Role.ADMIN.name(), Role.ADMIN.name());
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
    @DisplayName("assign_시_LS_TASK_EVNT_LOG에_ASSIGN_이벤트_누적")
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
    @DisplayName("listAssignments_응답_Item에_인입_평면값의_cctvName이_매핑되어_반환")
    void listAssignmentsIncludesCctvName() {
        // 관제 인입 평면값 등록(V167 — 구 CCTV 마스터 조인 대체) + 영상 1000(test-data.sql 시드)의
        // CCTV 지정. 영상명 컬럼이 "CCTV-강남구-001" 형식으로 표시되도록 한다.
        //   ★조인 축이 VMS_CCTV_ID 가 아니라 영상(RAW_SN)이다(IngestSourceLink).
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET VMS_CCTV_ID = ? WHERE RAW_SN = ?",
                "CCTV-GANGNAM-001", 1000L);
        IngestFlatValueSeeder.seedName(jdbcTemplate, 1000L, "CCTV-GANGNAM-001", "CCTV-강남구-001");

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
    @DisplayName("listAssignments_인입_평면값에_CCTV명이_없으면_VMS_CCTV_ID_폴백")
    void listAssignmentsCctvNameFallsBackToVmsCctvId() {
        // 인입 행이 없는(=CCTV 명을 조달할 수 없는) 영상 → cctvName 은 vmsCctvId 폴백.
        jdbcTemplate.update("DELETE FROM LS_DATA_INGEST WHERE RAW_SN = ?", 1001L);
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
        //   LS_TASK_ALTMNT → LS_DATA_RAW FK 를 세워 그 상태는 <구조적으로 불가능>해졌다
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
        // given — LS_TASK_ALTMNT 만 직접 INSERT (assign 메서드를 우회해 LS_RAW_DATA_STATUS 미생성).
        // dataSttsByVideo lookup 이 키를 찾지 못해 mapToFeStatus(null) → 'PENDING' 폴백을 거쳐야 한다.
        // 배정 행의 부모 영상은 실재해야 한다(V146 FK) — 검증 대상은 <작업 상태 행> 부재이지 영상 부재가 아니다.
        RawVideoFixture.seedRaw(jdbcTemplate, 9999L);
        jdbcTemplate.update(
                "INSERT INTO LS_TASK_ALTMNT (USER_NO, RAW_DATA_ID, TASK_TYPE_CD, REG_USER_NO, REG_DT) " +
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

    /** 파생 영상 1행 시드 — {@code VMS_CLIP_ID} 원문과 {@code AUG_TYPE_CD} 컬럼값을 따로 지정한다. */
    private void seedDerivedVideo(long rawSn, String vmsClipId, String augTypeCd) {
        jdbcTemplate.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        jdbcTemplate.update(
                "INSERT INTO LS_DATA_RAW (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, ORGNL_RAW_SN, PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN," +
                        "RAW_FILE_PATH_NM, DATA_STTS_CD, SRC_TYPE, AUG_TYPE_CD, REG_DT)" +
                        // AUG_TYPE_CD 는 null 케이스를 함께 시드하므로 파라미터 타입을 명시 CAST 한다.
                        " VALUES (?,?,?,?,?,?,?,?,?,?,CAST(? AS VARCHAR(20)), CURRENT_TIMESTAMP)",
                rawSn, vmsClipId, "CCTV-AUG-001", 3000L,
                "ANONY", "N", "N", "/tmp/test/" + rawSn + ".mp4", "PENDING", "AUGMENTED", augTypeCd);
    }

    private AssignmentResponse.Item listSingleItem(long rawSn) {
        assignmentService.assign(new AssignmentCreateRequest(100L, List.of(rawSn)), reviewer());
        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                AssignmentSearchCondition.none(), reviewer(), PageRequest.of(0, 20));
        assertThat(page.getContent()).hasSize(1);
        AssignmentResponse.Item item = page.getContent().get(0);
        assertThat(item.rawDataId()).isEqualTo(rawSn);
        return item;
    }

    @Test
    @DisplayName("배정목록_응답의_augType이_컬럼값으로_반환된다")
    void listAssignmentsDerivedVideoAugmented() {
        // given — 파생 영상(ORGNL_RAW_SN=3000). VMS_CLIP_ID 에는 <마커가 전혀 없고> 컬럼만 값을 갖는다.
        //         판별 원천이 문자열 역파싱 → AUG_TYPE_CD 컬럼으로 옮겨졌음을 고정한다
        //         (구 AugTypeParser 는 마커 없는 이 clipId 를 null 로 판정했다).
        seedDerivedVideo(3001L, "TEST-3000-no-marker", "RESL_480P");

        // when
        AssignmentResponse.Item item = listSingleItem(3001L);

        // then — 파생 RAW 는 작업 목록에 유지되며(R2) augmented=true + augType=컬럼값.
        assertThat(item.augmented()).isTrue();
        assertThat(item.augType()).isEqualTo("RESL_480P");
    }

    @Test
    @DisplayName("배정목록_augType은_VMS_CLIP_ID_드리프트와_무관하게_컬럼값을_따른다")
    void listAssignmentsAugTypeIgnoresClipIdDrift() {
        // given — 실데이터(cudo_246) 이중접두 드리프트 clipId 지만 컬럼은 WINTER.
        //         구 파서라면 clipId 를 읽어 RESL_480P 를 냈을 형태 → 이제 컬럼이 이긴다.
        seedDerivedVideo(3003L, "TEST-3000_RESL_RESL_480P_123", "WINTER");

        // when
        AssignmentResponse.Item item = listSingleItem(3003L);

        // then
        assertThat(item.augmented()).isTrue();
        assertThat(item.augType()).isEqualTo("WINTER");
    }

    @Test
    @DisplayName("배정목록_AUG_TYPE_CD가_null인_파생행도_예외없이_augType_null로_응답된다")
    void listAssignmentsDerivedVideoWithNullAugTypeColumn() {
        // given — 백필/쓰기측 배선 이전 경로로 만들어진 파생행(컬럼 미채움) 방어.
        seedDerivedVideo(3004L, "TEST-3000_AUG_WINTER_123", null);

        // when
        AssignmentResponse.Item item = listSingleItem(3004L);

        // then — 파생 여부(ORGNL_RAW_SN)는 그대로 true, 종류만 미상(null). 예외 없음.
        assertThat(item.augmented()).isTrue();
        assertThat(item.augType()).isNull();
    }

    /**
     * ★ 현행 증강 종류 단일값({@code AUGMENT})이 배정목록에 <b>노출</b>된다(ADR-059).
     *
     * <p>이 축은 {@code LsDataAug.CONTRACT_AUG_TYPES} 화이트리스트가 게이팅한다 — 거기서 현행
     * 값이 빠지면 신규 파생본의 {@code augType} 이 조용히 {@code null} 로 떨어져 <b>화면의 종류
     * 배지가 사라진다</b>. 오류가 아니라 «값이 안 보임» 이라 기존 시험(RESL_*·구 값·null·레거시
     * RESOLUTION 네 케이스)이 전부 GREEN 인 채로 지나간다 — 그래서 <b>현행 값을 직접 심는
     * 케이스</b>가 따로 필요하다. 아래 레거시 {@code RESOLUTION} 케이스의 정확한 대칭이다.
     */
    @Test
    @DisplayName("배정목록_현행_AUGMENT_값은_FE계약값이므로_그대로_노출된다")
    void listAssignmentsCurrentAugmentTypeIsExposed() {
        // given — 구 3종이 아니라 현행 단일값. clipId 에는 마커가 없어 컬럼이 유일한 조달처다.
        seedDerivedVideo(3006L, "TEST-3006-no-marker", LsDataAug.AUG_AUGMENT);

        // when
        AssignmentResponse.Item item = listSingleItem(3006L);

        // then
        assertThat(item.augmented()).isTrue();
        assertThat(item.augType())
                .as("CONTRACT_AUG_TYPES 에서 현행 값이 빠지면 여기서 null 로 떨어진다")
                .isEqualTo("AUGMENT");
    }

    @Test
    @DisplayName("배정목록_레거시_RESOLUTION_값은_FE계약값이_아니므로_augType_null이다")
    void listAssignmentsLegacyResolutionAugTypeIsNotExposed() {
        // given — 통합 이전 레거시 단일 코드(LsDataAug.AUG_RESOLUTION). 구 파서는 _AUG_ 뒤 세그먼트에서
        //         WINTER/NIGHT/RAIN 을 못 찾아 null 을 냈다 — 컬럼 교체 후에도 같은 값이어야 한다.
        seedDerivedVideo(3005L, "TEST-3000_AUG_RESOLUTION_123", "RESOLUTION");

        // when
        AssignmentResponse.Item item = listSingleItem(3005L);

        // then — FE 계약값(AUGMENT|WINTER|NIGHT|RAIN|RESL_1080P|RESL_720P|RESL_480P) 밖이면 노출하지 않는다.
        assertThat(item.augmented()).isTrue();
        assertThat(item.augType()).isNull();
    }

    @Test
    @DisplayName("원본영상은_augType이_null이다")
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
