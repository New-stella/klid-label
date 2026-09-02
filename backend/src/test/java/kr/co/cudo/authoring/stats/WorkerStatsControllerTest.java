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
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
 *   <li>completed=APPROVED, inProgress=APPROVED 가 아닌 배정 전부, rejected=REJECTED —
 *       LS_TASK_ALTMNT(LABELER) ⨝ LS_RAW_DATA_STATUS. inProgress 판정 축은 전체 구축 현황 화면과
 *       동일하며 축 자체의 회귀 가드는 {@code StatsInProgressAxisIT} 가 맡는다.</li>
 *   <li>labelCount = 워커에게 LABELER 로 배정된 raw 의 LsDataSrc 에 달린 LsDataLbl 총 수.</li>
 *   <li>autoLabelRate = 위 집합에서 자동 생성 라벨({@code LS_DATA_LBL_AI_INFO.AUTO_LBL_YN='Y'}) 비율(0~1).
 *       판정 축은 전체 구축 현황 화면과 동일하며 축 자체의 회귀 가드는 {@code StatsAutoLabelRateAxisIT} 가 맡는다.</li>
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
    @Autowired private JdbcTemplate jdbcTemplate;
    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private static final Long WORKER_ID = 100L;
    private static final Long OTHER_WORKER_ID = 101L;

    /** 본 테스트가 쓰는 영상 ID — V146 이후 배정·작업상태·프레임이 LS_DATA_RAW 를 FK 로 참조한다. */
    private static final long[] RAW_SNS = {9001L, 9002L, 9003L, 9004L, 9005L};

    @AfterEach
    void removeParentVideos() {
        // 부모 삭제 = 배정·작업상태·프레임·라벨까지 CASCADE 정리(마지막 테스트 후 잔재 방지).
        RawVideoFixture.deleteRaws(jdbcTemplate, RAW_SNS);
    }

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
                // 구 기대값 2(ASSIGNED + IN_REVIEW 열거)에서 3 으로 정정 — 반려도 아직 완료되지 않은
                // 작업이라 진행 중에 포함된다. 열거 방식은 전체 구축 현황과 숫자가 갈렸다(StatsInProgressAxisIT).
                .andExpect(jsonPath("$.data.inProgress").value(3))     // IN_REVIEW + ASSIGNED + REJECTED
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
        seedAutoLabel(9001L, srcA);
        seedAutoLabel(9001L, srcA);
        seedAutoLabel(9001L, srcB);
        seedManualLabel(srcB, WORKER_ID);

        String token = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/worker")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labelCount").value(4))
                .andExpect(jsonPath("$.data.autoLabelRate").value(0.75));
    }

    @Test
    @DisplayName("WORKER_월별_라벨수_현재월에_실제_라벨_수와_일치")
    void worker_monthly_labelCount_currentMonth() throws Exception {
        // given: WORKER 100 의 영상(rawDataId=9001) 에 프레임 2개 + 라벨 3건 (자동 2 + 수동 1).
        // 모두 LsDataLbl.createXxx() 가 regDt=LocalDateTime.now() 로 채우므로 현재 월 키로 묶임.
        seedAssignment(WORKER_ID, 9001L, LsRawDataStatus.STTS_IN_REVIEW);
        Long srcA = seedSrc(9001L, 1);
        Long srcB = seedSrc(9001L, 2);
        seedAutoLabel(9001L, srcA);
        seedAutoLabel(9001L, srcB);
        seedManualLabel(srcB, WORKER_ID);

        String currentMonthKey = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String token = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/worker")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                // 월별 표에 현재 월 행이 라벨수=3 으로 포함되어야 함 (직전 hotfix 의 0 하드코딩 회귀 방지).
                .andExpect(jsonPath("$.data.monthly[?(@.month == '" + currentMonthKey + "')].labelCount")
                        .value(org.hamcrest.Matchers.contains(3)));
    }

    @Test
    @DisplayName("WORKER_월별_라벨_0건이면_labelCount_0_안전응답")
    void worker_monthly_labelCount_zero_whenNoLabels() throws Exception {
        // given: 배정만 있고 라벨이 없는 경우 → completed 행은 있어도 labelCount=0.
        seedAssignment(WORKER_ID, 9001L, LsRawDataStatus.STTS_APPROVED);

        String token = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/worker")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                // 라벨 없으므로 모든 monthly 행 labelCount == 0.
                .andExpect(jsonPath("$.data.monthly[*].labelCount")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(0))));
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
        RawVideoFixture.seedRaw(jdbcTemplate, rawDataId); // 부모 영상 선시드(V146 FK, 멱등)
        taskAssignmentRepository.save(LsTaskAssignment.createLabeler(rawDataId, userNo, userNo));
        LsRawDataStatus rds = LsRawDataStatus.initial(rawDataId);
        rds.transitionTo(status);
        rawDataStatusRepository.save(rds);
    }

    private Long seedSrc(Long rawSn, int frameNo) {
        RawVideoFixture.seedRaw(jdbcTemplate, rawSn); // 부모 영상 선시드(V146 FK, 멱등)
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, "/tmp/" + rawSn + "_" + frameNo + ".png",
                LocalDateTime.now());
        return srcRepository.save(src).getSrcSn();
    }

    private void seedManualLabel(Long srcSn, Long regUserNo) {
        lblRepository.save(LsDataLbl.createManual(srcSn, LsDataLbl.TYPE_BBOX, null, "person",
                "[[0,0],[10,10]]", String.valueOf(regUserNo)));
    }

    /**
     * 자동 라벨 — 본체 + {@code LS_DATA_LBL_AI_INFO}(AUTO_LBL_YN='Y') 페어로 심는다.
     * 오토라벨링 경로는 항상 이 둘을 함께 쓰며, 자동 여부 판정도 이 AI 정보 행으로만 한다.
     */
    private void seedAutoLabel(Long rawSn, Long srcSn) {
        LsDataLbl lbl = lblRepository.save(LsDataLbl.createAutoBbox(srcSn, null, "car", "[[0,0],[10,10]]",
                BigDecimal.valueOf(0.9), "t1"));
        // V6 — 생산이력이 라벨 행의 컬럼이라 AI 정보 행 대신 그 라벨에 직접 부여한다.
        lbl.applyAiSource(LsDataLbl.SRC_YOLO, BigDecimal.valueOf(0.9));
        lblRepository.saveAndFlush(lbl);
    }
}
