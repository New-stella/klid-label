package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
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
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 (R2) — 작업목록 상태 우선순위 정렬 검증 (AC2).
 *
 * <p>재작업(반려)·검수대기 건이 상단에 오도록 서버 정렬한다:
 * 반려 &gt; 검수대기 &gt; 배정(진행) &gt; 미배정 &gt; 완료 → REG_DT DESC → RAW_SN DESC.
 * FE 표시 상태({@code status}) 는 {@code TaskBoardService.mapBoardStatus}(LS_RAW_DATA_STATUS.DATA_STTS_CD +
 * LABELER 배정 유무) 로 산출되며, 서버 정렬은 이 표시 상태와 일치한다. 응답 스키마는 불변 — 순서만 바뀐다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TaskBoardStatusPrioritySortTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;

    private final JdbcTemplate jdbc;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;

    TaskBoardStatusPrioritySortTest(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    /** 배치 COMPLETED 영상(작업목록 노출 대상) 을 생성한다. */
    private LsDataRaw seedCompletedVideo(String clipId) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, "CCTV-001", "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.now(), 30);
        raw = videoRepository.save(raw);
        raw.changeStatus("COMPLETED");
        return videoRepository.save(raw);
    }

    private void assignLabeler(Long rawSn) {
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    /** LABELER 배정 + 워크플로 상태(LS_RAW_DATA_STATUS.DATA_STTS_CD) 를 부여한다. */
    private void assignAndSetWorkflow(Long rawSn, String workflowStatus) {
        assignLabeler(rawSn);
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(workflowStatus);
        dataSttsRepository.save(stts);
    }

    private void forceRegDt(Long rawSn, LocalDateTime regDt) {
        jdbc.update("UPDATE LS_DATA_RAW SET REG_DT = ? WHERE RAW_SN = ?",
                Timestamp.valueOf(regDt), rawSn);
    }

    @Test
    @DisplayName("작업목록_반려가_최상단으로_정렬된다")
    void rejectedSortedToTop() throws Exception {
        // given: 완료 → 미배정 → 배정(진행) → 검수대기 → 반려 순으로 (일부러 역순으로) 생성
        LsDataRaw approved = seedCompletedVideo("CLIP-P-APPROVED");
        assignAndSetWorkflow(approved.getRawSn(), LsRawDataStatus.STTS_APPROVED);

        LsDataRaw unassigned = seedCompletedVideo("CLIP-P-UNASSIGNED"); // LABELER 배정 없음 → 미배정

        LsDataRaw assigned = seedCompletedVideo("CLIP-P-ASSIGNED");
        assignAndSetWorkflow(assigned.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);

        LsDataRaw reviewPending = seedCompletedVideo("CLIP-P-REVIEW");
        assignAndSetWorkflow(reviewPending.getRawSn(), LsRawDataStatus.STTS_PENDING);

        LsDataRaw rejected = seedCompletedVideo("CLIP-P-REJECTED");
        assignAndSetWorkflow(rejected.getRawSn(), LsRawDataStatus.STTS_REJECTED);

        // when / then: 반려 > 검수대기 > 배정 > 미배정 > 완료
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(5))
                .andExpect(jsonPath("$.data.content[0].videoId").value(rejected.getRawSn()))
                .andExpect(jsonPath("$.data.content[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data.content[1].videoId").value(reviewPending.getRawSn()))
                .andExpect(jsonPath("$.data.content[1].status").value("REVIEW_PENDING"))
                .andExpect(jsonPath("$.data.content[2].videoId").value(assigned.getRawSn()))
                .andExpect(jsonPath("$.data.content[2].status").value("PENDING"))
                .andExpect(jsonPath("$.data.content[3].videoId").value(unassigned.getRawSn()))
                .andExpect(jsonPath("$.data.content[3].status").value("UNASSIGNED"))
                .andExpect(jsonPath("$.data.content[4].videoId").value(approved.getRawSn()))
                .andExpect(jsonPath("$.data.content[4].status").value("COMPLETED"));
    }

    @Test
    @DisplayName("미배정은_반려_status가_있어도_미배정으로_분류돼_배정된_반려보다_아래로_정렬된다")
    void unassignedCasePrecedenceOverridesRejectedStatus() throws Exception {
        // given (A): LABELER 배정 + REJECTED → NOT EXISTS(LABELER)=false → CASE 반려(0)
        LsDataRaw assignedRejected = seedCompletedVideo("CLIP-UA-ASSIGNED-REJ");
        assignAndSetWorkflow(assignedRejected.getRawSn(), LsRawDataStatus.STTS_REJECTED);

        // given (B): LABELER 미배정 + REJECTED status row 만 존재 →
        //            CASE 의 NOT EXISTS(LABELER) 최우선 평가가 REJECTED(0) 를 덮어써 미배정(3) 으로 확정.
        LsDataRaw unassignedRejStatus = seedCompletedVideo("CLIP-UA-UNASSIGNED-REJ");
        LsRawDataStatus stts = LsRawDataStatus.initial(unassignedRejStatus.getRawSn());
        stts.transitionTo(LsRawDataStatus.STTS_REJECTED);
        dataSttsRepository.save(stts); // 배정 없이 상태 row 만 부여 (미배정 우선평가 검증용)

        // when / then: 미배정 우선평가(3) 가 status(REJECTED=0) 를 덮어써 —
        //              배정된 반려(0) 가 위, 미배정(3) 이 아래. FE 표시상태도 UNASSIGNED 로 일치.
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].videoId").value(assignedRejected.getRawSn()))
                .andExpect(jsonPath("$.data.content[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data.content[1].videoId").value(unassignedRejStatus.getRawSn()))
                .andExpect(jsonPath("$.data.content[1].status").value("UNASSIGNED"));
    }

    @Test
    @DisplayName("검수대기가_배정보다_먼저_정렬된다")
    void reviewPendingBeforeAssigned() throws Exception {
        // given: 배정(진행) 을 먼저, 검수대기를 나중에 생성 (regDt 로는 배정이 더 최신)
        LsDataRaw assigned = seedCompletedVideo("CLIP-RP-ASSIGNED");
        assignAndSetWorkflow(assigned.getRawSn(), LsRawDataStatus.STTS_ASSIGNED);

        LsDataRaw reviewPending = seedCompletedVideo("CLIP-RP-REVIEW");
        assignAndSetWorkflow(reviewPending.getRawSn(), LsRawDataStatus.STTS_IN_REVIEW);

        // when / then: 상태 우선순위가 regDt 보다 우선 — 검수대기가 먼저
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].videoId").value(reviewPending.getRawSn()))
                .andExpect(jsonPath("$.data.content[0].status").value("REVIEW_PENDING"))
                .andExpect(jsonPath("$.data.content[1].videoId").value(assigned.getRawSn()))
                .andExpect(jsonPath("$.data.content[1].status").value("PENDING"));
    }

    @Test
    @DisplayName("동일상태내_최신_regDt가_먼저")
    void sameStatusLatestRegDtFirst() throws Exception {
        // given: 반려 2건. 먼저 만든(=작은 rawSn) 영상에 더 최신 regDt 를 부여 → regDt 가 rawSn 보다 우선함을 검증
        LsDataRaw older = seedCompletedVideo("CLIP-DT-A");   // 작은 rawSn
        assignAndSetWorkflow(older.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        LsDataRaw newer = seedCompletedVideo("CLIP-DT-B");   // 큰 rawSn
        assignAndSetWorkflow(newer.getRawSn(), LsRawDataStatus.STTS_REJECTED);

        forceRegDt(older.getRawSn(), LocalDateTime.of(2026, 6, 2, 0, 0, 0)); // 더 최신
        forceRegDt(newer.getRawSn(), LocalDateTime.of(2026, 6, 1, 0, 0, 0)); // 더 과거

        // when / then: regDt DESC 가 rawSn DESC 보다 우선 → older(작은 rawSn, 최신 regDt) 가 먼저
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].videoId").value(older.getRawSn()))
                .andExpect(jsonPath("$.data.content[1].videoId").value(newer.getRawSn()));
    }

    @Test
    @DisplayName("동일상태_동일날짜면_rawSn_tiebreak")
    void sameStatusSameRegDtRawSnTiebreak() throws Exception {
        // given: 반려 2건, regDt 를 완전히 동일하게 고정
        LsDataRaw first = seedCompletedVideo("CLIP-TB-A");   // 작은 rawSn
        assignAndSetWorkflow(first.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        LsDataRaw second = seedCompletedVideo("CLIP-TB-B");  // 큰 rawSn
        assignAndSetWorkflow(second.getRawSn(), LsRawDataStatus.STTS_REJECTED);

        LocalDateTime sameRegDt = LocalDateTime.of(2026, 6, 1, 12, 0, 0);
        forceRegDt(first.getRawSn(), sameRegDt);
        forceRegDt(second.getRawSn(), sameRegDt);

        // when / then: regDt 동일 → rawSn DESC → 큰 rawSn(second) 이 먼저
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].videoId").value(second.getRawSn()))
                .andExpect(jsonPath("$.data.content[1].videoId").value(first.getRawSn()));
    }

    /** 원본에서 파생된 해상도 영상(ORGNL_RAW_SN 세팅, COMPLETED)을 생성한다. */
    private LsDataRaw seedDerivedCompletedVideo(LsDataRaw parent, String goalResCd) {
        LsDataRaw derived = LsDataRaw.createFromResolution(
                parent, "/var/raw/deriv-" + goalResCd + ".mp4", goalResCd);
        derived = videoRepository.save(derived);
        derived.changeStatus("COMPLETED");
        return videoRepository.save(derived);
    }

    @Test
    @DisplayName("작업보드_파생영상은_augmented_true_augType_원본은_false_null")
    void boardAugmentInfo() throws Exception {
        // given: 원본 1건(미배정) + 그 원본의 해상도 파생 1건(미배정, VMS_CLIP_ID 에 RESL 드리프트 포함)
        LsDataRaw origin = seedCompletedVideo("CLIP-AUG-ORIGIN");
        LsDataRaw derived = seedDerivedCompletedVideo(origin, "RESL_480P");

        // when / then: 두 건 모두 작업 목록에 노출(R2)되고, 파생만 augmented=true + augType=RESL_480P.
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                // rawSn DESC tie-break — 파생(나중 생성=큰 rawSn)이 먼저.
                .andExpect(jsonPath("$.data.content[0].videoId").value(derived.getRawSn()))
                .andExpect(jsonPath("$.data.content[0].augmented").value(true))
                .andExpect(jsonPath("$.data.content[0].augType").value("RESL_480P"))
                .andExpect(jsonPath("$.data.content[1].videoId").value(origin.getRawSn()))
                .andExpect(jsonPath("$.data.content[1].augmented").value(false))
                .andExpect(jsonPath("$.data.content[1].augType").isEmpty());
    }

    @Test
    @DisplayName("기존_board_상태필터·페이징_무회귀")
    void boardFilterAndPagingRegression() throws Exception {
        // given: COMPLETED 배치 영상 2건 + PENDING 배치 영상 1건 (COMPLETED 필터에서 제외되어야 함)
        LsDataRaw done1 = seedCompletedVideo("CLIP-RG-DONE1");
        assignAndSetWorkflow(done1.getRawSn(), LsRawDataStatus.STTS_REJECTED);
        LsDataRaw done2 = seedCompletedVideo("CLIP-RG-DONE2");
        assignAndSetWorkflow(done2.getRawSn(), LsRawDataStatus.STTS_APPROVED);

        LsDataRaw pendingBatch = LsDataRaw.createFromIngest(
                "CLIP-RG-PENDING", "CCTV-001", "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/pending.mp4", LocalDateTime.now(), 30);
        videoRepository.save(pendingBatch); // 배치 상태 PENDING 유지

        // when / then: COMPLETED 필터는 배치 COMPLETED 2건만 반환 (PENDING 제외), 페이징 메타 정상
        mockMvc.perform(get("/v1/tasks/board?status=COMPLETED&page=0&size=1")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                // page 0 = 최상위 우선순위(반려) 1건
                .andExpect(jsonPath("$.data.content[0].videoId").value(done1.getRawSn()))
                .andExpect(jsonPath("$.data.content[0].status").value("REJECTED"));
    }
}
