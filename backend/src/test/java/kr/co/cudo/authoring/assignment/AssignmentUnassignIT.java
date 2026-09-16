package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ★<b>배정 해제</b>({@code DELETE /v1/assignments/{assignmentId}}) 회귀 가드.
 * [@design ADR-069] [@design API-259] [@design AC-1122] [@design AC-1123] [@design AC-1127]
 *
 * <h2>이 창구가 없으면 무엇이 막히나</h2>
 * <p>배정을 푸는 수단이 지금까지 없었다 — 쓰기 창구는 생성과 재배정뿐이고 배정 원장에는 상태 칸조차
 * 없었다. 그 구멍은 영상 제외에서 드러났다: 배정이 있는 영상은 제외가 거부되는데 「먼저 배정을
 * 해제하세요」를 따를 수단이 없으면 그 안내가 <b>막다른 길</b>이 된다. 그래서 이 클래스는 해제 자체뿐
 * 아니라 <b>「거부 → 해제 → 제외 성공」이 한 흐름으로 이어지는지</b>까지 함께 고정한다 — 두 창구를
 * 따로 검증하면 그 연결이 끊겨도 둘 다 통과한다.
 *
 * <h2>핵심 관측점 — 배정만 풀리고 작업 결과는 남는다</h2>
 * <p>배정 원장에 상태 칸이 없어 해제는 <b>행이 사라지는 것</b>으로 나타난다. 그래서 「지운다」가 어디까지
 * 미치는지를 관측으로 못박지 않으면, 다음 사람이 「배정과 함께 그 작업물도 정리하자」로 넓히기 쉽다.
 * 해제 <b>전후</b>의 라벨 건수·식별자 집합과 라벨 이력 건수를 견주어 한 건도 지워지지 않음을 고정한다.
 *
 * <h2>인가는 {@code LS_USER_ROLE} 이 진실원이다</h2>
 * <p>JWT 의 {@code role} 클레임은 인가에 쓰이지 않는다. 역할을 실제로 심지 않으면 403 이 「작업자라서」가
 * 아니라 「무권한이라서」 나고, 그러면 이 시험은 이름만 남는다. 공용 시드가 1=검수자 · 100/101=작업자를
 * 심으므로 그것을 쓰고, 관리자만 이 클래스가 직접 심고 반드시 지운다(공용 시드에 관리자를 넣으면
 * 관리자 0명일 때만 열리는 부트스트랩 창구 시험이 통째로 깨진다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AssignmentUnassignIT {

    /** 공용 시드의 검수자 · 작업자 (진실원은 {@code LS_USER_ROLE}). */
    private static final long REVIEWER_NO = 1L;
    private static final long WORKER_NO = 100L;
    private static final long OTHER_WORKER_NO = 101L;
    /** 이 클래스 전용 관리자 — 직접 심고 {@code @AfterEach} 에서 반드시 지운다. */
    private static final long ADMIN_NO = 969_300_041L;

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private LsTaskEventLogRepository taskEventLogRepository;
    @Autowired private UserRoleResolver userRoleResolver;

    /** 이 저장소는 {@code JdbcTemplate} 빈을 두지 않는다 — control 데이터소스로 직접 만든다. */
    private final JdbcTemplate jdbcTemplate;

    AssignmentUnassignIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        reviewerToken = token(REVIEWER_NO);
        workerToken = token(WORKER_NO);
        adminToken = token(ADMIN_NO);
        jdbcTemplate.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", ADMIN_NO);
        jdbcTemplate.update(
                "INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'ADMIN', CURRENT_TIMESTAMP)",
                ADMIN_NO);
        userRoleResolver.evict(ADMIN_NO);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", ADMIN_NO);
        userRoleResolver.evict(ADMIN_NO);
    }

    private String token(long userNo) {
        // role/channel 클레임은 진입 채널 판정에만 쓰인다 — 인가 역할은 LS_USER_ROLE 이 정한다.
        return JwtTestSupport.token(secret, String.valueOf(userNo), "REVIEWER", "INTERNAL", issuer, 60);
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private Long newVideo() {
        return videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-unassign-" + System.nanoTime(), "cam" + System.nanoTime(), "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30)).getRawSn();
    }

    private Long assign(Long rawSn, long workerNo) throws Exception {
        mockMvc.perform(post("/v1/assignments")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":" + workerNo + ",\"rawDataIds\":[" + rawSn + "]}"))
                .andExpect(status().isCreated());
        return currentAssignmentId(rawSn);
    }

    private Long currentAssignmentId(Long rawSn) {
        List<LsTaskAssignment> rows = assignmentRepository
                .findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(LsTaskAssignment.TASK_LABELER, List.of(rawSn));
        return rows.isEmpty() ? null : rows.get(0).getAssignmentId();
    }

    /** 그 영상에 프레임 1건 + 라벨 2건 + 라벨 이력 1건을 심는다 — 「작업 결과」의 관측 대상. */
    private Long seedWork(Long rawSn) {
        Long srcSn = jdbcTemplate.queryForObject(
                "INSERT INTO ls_data_src (raw_sn, frm_no, src_file_path_nm, reg_dt)"
                        + " VALUES (?, 1, '/frames/1.jpg', CURRENT_TIMESTAMP) RETURNING src_sn",
                Long.class, rawSn);
        for (int i = 0; i < 2; i++) {
            jdbcTemplate.update(
                    "INSERT INTO ls_data_lbl (src_sn, lbl_type_cd, lbl_nm, point_cn, reg_dt)"
                            + " VALUES (?, 'BBOX', '사람', '[1,2,3,4]', CURRENT_TIMESTAMP)", srcSn);
        }
        jdbcTemplate.update(
                "INSERT INTO ls_data_lbl_hstry (src_sn, reg_dt, reg_id, add_cnt, mdfcn_cnt, del_cnt)"
                        + " VALUES (?, CURRENT_TIMESTAMP, 'w100', 2, 0, 0)", srcSn);
        return srcSn;
    }

    private List<Long> labelIds(Long srcSn) {
        return jdbcTemplate.queryForList(
                "SELECT lbl_sn FROM ls_data_lbl WHERE src_sn = ? ORDER BY lbl_sn", Long.class, srcSn);
    }

    private long labelHistoryCount(Long srcSn) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ls_data_lbl_hstry WHERE src_sn = ?", Long.class, srcSn);
        return n != null ? n : 0L;
    }

    private void seedWorkflowStatus(Long rawSn, String dataSttsCd) {
        jdbcTemplate.update("DELETE FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", rawSn);
        jdbcTemplate.update(
                "INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, STP_CYCL, IGI_CYCL, UPD_DT, VER)"
                        + " VALUES (?, ?, 0, 0, CURRENT_TIMESTAMP, 0)", rawSn, dataSttsCd);
    }

    private List<LsTaskEventLog> events(Long rawSn) {
        return taskEventLogRepository.findByRawDataIdOrderByOcrnDtAsc(rawSn);
    }

    // ── AC-1122 정상 경로 ─────────────────────────────────────────────────────

    /**
     * ★★<b>핵심</b> — 배정만 풀리고 라벨·라벨 이력은 한 건도 지워지지 않는다.
     *
     * <p>이력의 대상 사용자 칸에 <b>해제된 작업자</b>가 실리는지, 사유 칸이 <b>비어</b> 있는지(해제는
     * 사유를 받지 않는다), 이전 담당 칸이 <b>비어</b> 있는지(해제는 담당을 바꾸는 것이 아니라 없애는
     * 것이라 이동이 없다)까지 함께 본다.
     */
    @Test
    @DisplayName("★★배정_해제는_배정만_풀고_라벨과_라벨이력은_한_건도_지우지_않는다")
    void unassignRemovesOnlyTheAssignment() throws Exception {
        Long rawSn = newVideo();
        Long srcSn = seedWork(rawSn);
        Long assignmentId = assign(rawSn, WORKER_NO);

        List<Long> labelsBefore = labelIds(srcSn);
        long historyBefore = labelHistoryCount(srcSn);
        assertThat(labelsBefore).as("기준값 자체가 성립해야 시험이 의미를 갖는다").hasSize(2);
        assertThat(historyBefore).isEqualTo(1L);

        mockMvc.perform(delete("/v1/assignments/" + assignmentId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());

        assertThat(assignmentRepository.findById(assignmentId))
                .as("해제는 배정 행이 사라지는 것으로 나타난다(원장에 상태 칸이 없다)")
                .isEmpty();
        assertThat(labelIds(srcSn))
                .as("★해제가 라벨을 지웠다 — 해제는 배정만 푸는 것이다")
                .containsExactlyElementsOf(labelsBefore);
        assertThat(labelHistoryCount(srcSn))
                .as("★해제가 라벨 이력을 지웠다")
                .isEqualTo(historyBefore);

        List<LsTaskEventLog> log = events(rawSn);
        assertThat(log).extracting(LsTaskEventLog::getEventTypeCd)
                .containsExactly(LsTaskEventLog.EVENT_ASSIGN, LsTaskEventLog.EVENT_UNASSIGN);
        LsTaskEventLog unassigned = log.get(1);
        assertThat(unassigned.getActorUserNo()).isEqualTo(REVIEWER_NO);
        assertThat(unassigned.getActorRoleCd())
                .as("행위 시점의 역할이 실려야 한다")
                .isEqualTo("REVIEWER");
        assertThat(unassigned.getOcrnDt()).isNotNull();
        assertThat(unassigned.getSubjectUserNo())
                .as("배정이 풀린 작업자가 대상 사용자 칸에 실린다(재배정과 같은 축)")
                .isEqualTo(WORKER_NO);
        assertThat(unassigned.getRsn())
                .as("해제는 사유를 받지 않는다 — 감추는 쪽(제외)만 사유를 남긴다")
                .isNull();
        assertThat(unassigned.getPrevUserNo())
                .as("해제는 담당을 바꾸는 것이 아니라 없애는 것이라 이동이 없다")
                .isNull();
    }

    /** 되돌리기는 <b>재배정</b>이며 그때 남아 있던 작업 위에서 이어서 한다 — 해제 취소 창구는 없다. */
    @Test
    @DisplayName("해제한_영상을_다시_배정할_수_있고_남아_있던_라벨_위에서_이어서_한다")
    void reassignAfterUnassignKeepsExistingWork() throws Exception {
        Long rawSn = newVideo();
        Long srcSn = seedWork(rawSn);
        List<Long> labelsBefore = labelIds(srcSn);
        Long assignmentId = assign(rawSn, WORKER_NO);

        mockMvc.perform(delete("/v1/assignments/" + assignmentId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());

        Long reassigned = assign(rawSn, OTHER_WORKER_NO);

        assertThat(reassigned).as("다시 배정할 수 있어야 되돌리기 경로가 실재한다").isNotNull();
        assertThat(assignmentRepository.findById(reassigned)).isPresent();
        assertThat(labelIds(srcSn)).containsExactlyElementsOf(labelsBefore);
    }

    /** 관리자는 검수자 역할을 <b>계층으로</b> 물려받는다 — 관리자에게 검수자 역할을 따로 주지 않는다. */
    @Test
    @DisplayName("관리자는_역할_계층으로_배정을_해제한다")
    void adminUnassignsThroughRoleHierarchy() throws Exception {
        Long rawSn = newVideo();
        Long assignmentId = assign(rawSn, WORKER_NO);

        mockMvc.perform(delete("/v1/assignments/" + assignmentId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        assertThat(assignmentRepository.findById(assignmentId)).isEmpty();
        assertThat(events(rawSn)).last()
                .satisfies(e -> {
                    assertThat(e.getEventTypeCd()).isEqualTo(LsTaskEventLog.EVENT_UNASSIGN);
                    assertThat(e.getActorRoleCd())
                            .as("계층으로 승격된 값이 아니라 행위자의 실제 역할이 남아야 한다")
                            .isEqualTo("ADMIN");
                });
    }

    // ── AC-1123 거부 갈래 ─────────────────────────────────────────────────────

    /**
     * ★★<b>네 상태를 한 시험에서 함께 돌려 구분 자체를 고정한다.</b>
     *
     * <p>검수 대기·검수 중·승인은 거부하고 <b>반려는 푼다</b>. 나눠 두면 「반려도 검수 축이니 함께 막자」로
     * 한쪽이 조용히 뒤집혀도 반대쪽 시험이 통과한다. 그 귀결로 「반려 → 해제 → 제외」가 이어지므로,
     * 반려를 거부 쪽으로 옮기면 잘못 배정한 반려 건이 영영 풀리지 않는다.
     */
    @Test
    @DisplayName("★★검수대기·검수중·승인은_해제가_거부되고_반려는_해제된다")
    void reviewStagesAreBlockedButRejectedIsNot() throws Exception {
        for (String blocked : List.of(LsRawDataStatus.STTS_PENDING,
                LsRawDataStatus.STTS_IN_REVIEW,
                LsRawDataStatus.STTS_APPROVED)) {
            Long rawSn = newVideo();
            Long assignmentId = assign(rawSn, WORKER_NO);
            int eventsBefore = events(rawSn).size();
            seedWorkflowStatus(rawSn, blocked);

            mockMvc.perform(delete("/v1/assignments/" + assignmentId)
                            .header("Authorization", "Bearer " + reviewerToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("ASSIGNMENT_SUBMITTED"));

            assertThat(assignmentRepository.findById(assignmentId))
                    .as("거부된 요청은 배정 행을 그대로 둬야 한다 (%s)", blocked)
                    .isPresent();
            assertThat(events(rawSn))
                    .as("거부된 요청은 작업 이벤트 원장에 아무것도 남기지 않는다 (%s)", blocked)
                    .hasSize(eventsBefore);
        }

        Long rejectedRawSn = newVideo();
        Long rejectedAssignmentId = assign(rejectedRawSn, WORKER_NO);
        seedWorkflowStatus(rejectedRawSn, LsRawDataStatus.STTS_REJECTED);

        mockMvc.perform(delete("/v1/assignments/" + rejectedAssignmentId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());

        assertThat(assignmentRepository.findById(rejectedAssignmentId))
                .as("★반려는 작업자에게 되돌아온 상태라 그 작업 자체를 접을 수 있어야 한다")
                .isEmpty();
    }

    /**
     * 거부 어휘가 <b>이 조건 전용</b>이어야 한다 — 재배정의 「완료된 작업은 재배정할 수 없습니다」를
     * 나눠 쓰면 호출자가 일시 조건과 다른 조건을 구분하지 못하고, 그 문구는 이 창구에서 두 겹으로
     * 어긋난다(거부되는 상태가 「완료」가 아니고 이 창구는 재배정이 아니다).
     */
    @Test
    @DisplayName("거부_어휘가_재배정_불가_어휘와_다른_값이다")
    void rejectionUsesItsOwnErrorCode() throws Exception {
        Long rawSn = newVideo();
        Long assignmentId = assign(rawSn, WORKER_NO);
        seedWorkflowStatus(rawSn, LsRawDataStatus.STTS_APPROVED);

        mockMvc.perform(delete("/v1/assignments/" + assignmentId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ASSIGNMENT_SUBMITTED"))
                .andExpect(jsonPath("$.errorCode").value(
                        org.hamcrest.Matchers.not("ASSIGNMENT_ALREADY_COMPLETED")));
    }

    @Test
    @DisplayName("없는_배정_식별자는_충돌이_아니라_없음이다")
    void missingAssignmentIsNotFound() throws Exception {
        mockMvc.perform(delete("/v1/assignments/999999999")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    /**
     * ★<b>작업자는 이 창구를 수행할 수 없다.</b> 인가 축은 배정 생성·재배정과 같은 검수자 이상이다.
     *
     * <p>대조군으로 <b>같은 배정</b>에 검수자 호출이 성립함을 이어서 확인한다 — 그것이 없으면 403 이
     * 「작업자라서」인지 「그 배정이 애초에 못 풀리는 것이라서」인지 구분되지 않는다.
     */
    @Test
    @DisplayName("작업자는_배정을_해제할_수_없다_그리고_같은_배정을_검수자는_해제한다")
    void workerCannotUnassign() throws Exception {
        Long rawSn = newVideo();
        Long assignmentId = assign(rawSn, WORKER_NO);

        mockMvc.perform(delete("/v1/assignments/" + assignmentId)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
        assertThat(assignmentRepository.findById(assignmentId))
                .as("거부된 요청은 배정을 그대로 둔다").isPresent();

        // 대조군 — 같은 배정이 검수자에게는 풀린다(403 이 역할 축 때문임을 확정한다).
        mockMvc.perform(delete("/v1/assignments/" + assignmentId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());
    }

    // ── AC-1127 제외와의 연결 ─────────────────────────────────────────────────

    /**
     * ★★<b>거부 안내가 막다른 길이 아니다</b> — 배정 있는 영상의 제외 거부 → 해제 → 같은 제외 요청 성공을
     * <b>한 흐름</b>으로 확인한다.
     *
     * <p>두 창구를 따로 검증하면 이 연결이 끊겨도 둘 다 통과한다. 해제 창구를 없애거나 인가를 좁히면
     * 제외 거부 안내가 곧바로 막다른 길이 되므로, 그 위험은 이 시험 하나로만 드러난다.
     */
    @Test
    @DisplayName("★★제외_거부_안내가_막다른_길이_아니다_거부→해제→제외성공이_한_흐름으로_이어진다")
    void exclusionRejectionLeadsToAWorkingUnassignPath() throws Exception {
        Long rawSn = newVideo();
        Long assignmentId = assign(rawSn, WORKER_NO);

        mockMvc.perform(post("/v1/videos/" + rawSn + "/exclusion")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"시험 데이터\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/v1/assignments/" + assignmentId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/v1/videos/" + rawSn + "/exclusion")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"시험 데이터\"}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT excl_yn FROM ls_data_raw WHERE raw_sn = ?", String.class, rawSn))
                .as("배정을 푼 뒤에는 같은 제외 요청이 수락돼야 한다 — 일시 조건이지 영구 거부가 아니다")
                .isEqualTo(LsDataRaw.EXCL_YES);
    }
}
