package kr.co.cudo.authoring.stats;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /v1/stats/worker — 작업자 통계 (SCR-STAT-001).
 *
 * <p>FE WorkerStatPage 가 호출하는 단일 엔드포인트. 정책:
 * <ul>
 *   <li>WORKER 는 본인(workerId == sub) 통계만 조회. 타인 조회 시 403 (CWE-639 IDOR 차단).</li>
 *   <li>REVIEWER 는 workerId 파라미터로 임의 작업자 통계 조회 가능, 미지정 시 본인.</li>
 *   <li>데이터 없는 사용자는 모든 카운트 0, 비율 0.0, dailyCompletion/monthly 빈 배열 (NPE/NaN 차단).</li>
 *   <li>completed=APPROVED, inProgress=ASSIGNED+IN_REVIEW, rejected=REJECTED — LS_TASK_ASSIGNMENT(LABELER) ⨝ LS_RAW_DATA_STATUS.</li>
 *   <li>labelCount = 워커에게 LABELER 로 배정된 raw 의 LsDataSrc 에 달린 LsDataLbl 총 수.</li>
 *   <li>autoLabelRate = 위 집합에서 regUserNo IS NULL (자동) 라벨 비율.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data-stats-clean.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class WorkerStatsControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LsTaskAssignmentRepository taskAssignmentRepository;
    @Autowired private LsRawDataStatusRepository rawDataStatusRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private static final Long WORKER_ID = 100L;
    private static final Long OTHER_WORKER_ID = 101L;

    @Test
    @DisplayName("WORKER_본인_통계_데이터_없으면_모든_카운트_0_안전_응답")
    void worker_emptyData_returnsZeros() throws Exception {
        String token = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/worker")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.workerId").value("100"))
                .andExpect(jsonPath("$.data.completed").value(0))
                .andExpect(jsonPath("$.data.inProgress").value(0))
                .andExpect(jsonPath("$.data.rejected").value(0))
                .andExpect(jsonPath("$.data.labelCount").value(0))
                .andExpect(jsonPath("$.data.autoLabelRate").value(0.0))
                .andExpect(jsonPath("$.data.rejectRate").value(0.0))
                .andExpect(jsonPath("$.data.dailyCompletion").isArray())
                .andExpect(jsonPath("$.data.monthly").isArray());
    }

    @Test
    @DisplayName("WORKER_본인_통계_완료_진행중_반려_상태별_정확히_카운트")
    void worker_statusBreakdown_isCorrect() throws Exception {
        // given: WORKER 100 에게 5건 배정. 2 APPROVED, 1 IN_REVIEW, 1 ASSIGNED, 1 REJECTED.
        seedAssignment(WORKER_ID, 9001L, LsRawDataStatus.STTS_APPROVED);
        seedAssignment(WORKER_ID, 9002L, LsRawDataStatus.STTS_APPROVED);
        seedAssignment(WORKER_ID, 9003L, LsRawDataStatus.STTS_IN_REVIEW);
        seedAssignment(WORKER_ID, 9004L, LsRawDataStatus.STTS_ASSIGNED);
        seedAssignment(WORKER_ID, 9005L, LsRawDataStatus.STTS_REJECTED);

        String token = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/worker")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed").value(2))      // APPROVED
                .andExpect(jsonPath("$.data.inProgress").value(2))     // ASSIGNED + IN_REVIEW
                .andExpect(jsonPath("$.data.rejected").value(1))       // REJECTED
                .andExpect(jsonPath("$.data.rejectRate").value(1.0 / 3.0)); // 1/(2+1)
    }

    @Test
    @DisplayName("WORKER_본인_라벨_수와_오토라벨_비율_집계_정확")
    void worker_labelCountAndAutoRate() throws Exception {
        // given: WORKER 100 의 영상(rawDataId=9001) 에 프레임 2개. 라벨 4개: 자동 3 + 수동 1.
        seedAssignment(WORKER_ID, 9001L, LsRawDataStatus.STTS_IN_REVIEW);
        Long srcA = seedSrc(9001L, 1);
        Long srcB = seedSrc(9001L, 2);
        seedAutoLabel(srcA);
        seedAutoLabel(srcA);
        seedAutoLabel(srcB);
        seedManualLabel(srcB, WORKER_ID);

        String token = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/worker")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labelCount").value(4))
                .andExpect(jsonPath("$.data.autoLabelRate").value(0.75));
    }

    @Test
    @DisplayName("WORKER가_타인_workerId_쿼리시_403_IDOR_차단")
    void worker_cannotQueryOthers_returns403() throws Exception {
        String token = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/worker")
                        .param("workerId", String.valueOf(OTHER_WORKER_ID))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REVIEWER가_임의_workerId_쿼리시_정상_조회")
    void reviewer_canQueryAnyWorker() throws Exception {
        String token = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/worker")
                        .param("workerId", "100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workerId").value("100"));
    }

    @Test
    @DisplayName("미인증_요청시_401")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/v1/stats/worker"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PORTAL_USER_요청시_403")
    void portalUser_returns403() throws Exception {
        String token = JwtTestSupport.token(secret, "999", "PORTAL_USER", "PORTAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/worker")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("workerId_숫자_아닌_값_요청시_400_입력검증")
    void worker_invalidWorkerId_returns400() throws Exception {
        String token = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/worker")
                        .param("workerId", "abc; DROP TABLE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    // ---- test data helpers ----

    private void seedAssignment(Long userNo, Long rawDataId, String status) {
        taskAssignmentRepository.save(LsTaskAssignment.createLabeler(rawDataId, userNo, userNo));
        LsRawDataStatus rds = LsRawDataStatus.initial(rawDataId);
        rds.transitionTo(status);
        rawDataStatusRepository.save(rds);
    }

    private Long seedSrc(Long rawSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, "/tmp/" + rawSn + "_" + frameNo + ".png",
                LocalDateTime.now());
        return srcRepository.save(src).getSrcSn();
    }

    private void seedManualLabel(Long srcSn, Long regUserNo) {
        lblRepository.save(LsDataLbl.createManual(srcSn, LsDataLbl.TYPE_BBOX, null, "person",
                "[[0,0],[10,10]]", regUserNo));
    }

    private void seedAutoLabel(Long srcSn) {
        // createAutoBbox 는 regUserNo 를 설정하지 않음 → NULL → "자동 라벨" 식별 가능.
        lblRepository.save(LsDataLbl.createAutoBbox(srcSn, null, "car", "[[0,0],[10,10]]",
                BigDecimal.valueOf(0.9), "t1"));
    }
}
