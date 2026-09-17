package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.service.ReviewClaimSupport;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.review.dto.RejectRequest;
import kr.co.cudo.authoring.support.ThreadScopedQueryProbe;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>검수 점유</b> 회귀 가드 — 「지금 누가 이 영상을 보고 있는가」가 실제 창구에서 성립하는지 고정한다.
 *
 * <h3>이 시험이 지키는 것 (ADR-067 · API-013 · API-008 · API-009)</h3>
 * <ul>
 *   <li>남이 점유 중이면 검수 시작이 409 이고 <b>누가 보고 있는지</b>가 응답에 실린다</li>
 *   <li><b>유예가 지나면</b> 남이 시작할 수 있고 점유 주인이 그 사람으로 바뀐다</li>
 *   <li>본인 재진입은 409 가 아니라 200(멱등)이고 <b>점유 시각이 갱신</b>된다</li>
 *   <li>승인·반려 뒤에는 점유가 비워진다 — <b>푸는 동작 없이</b> 종결 이벤트만으로</li>
 *   <li>재검수 건(승인 + 재검토 표시)의 검수 시작은 <b>상태를 되돌리지 않는다</b></li>
 *   <li>검수 목록은 배정·점유와 무관하게 <b>전체</b>를 보여준다</li>
 *   <li>관리자가 검수 시작·승인·반려를 전부 통과하고 이력이 <b>ADMIN</b> 으로 남는다</li>
 *   <li>점유·승인자 조회가 목록 행 수에 따라 <b>늘지 않는다</b>(N+1)</li>
 * </ul>
 *
 * <h3>★만료를 흉내 내지 않는다 — 실제로 지나간다</h3>
 * 점유를 고정값으로 흉내 내면 만료 구간을 한 번도 밟지 않은 채 시험이 초록이 된다. 여기서는 이미 쌓인
 * 「검수 시작」의 발생일시를 <b>뒤로 밀어</b> 만료 <b>전</b>과 <b>후</b>를 양쪽 다 지나간다. 미는 폭은
 * 배포가 실제로 쓰는 유예({@link ReviewClaimSupport#grace()})에서 계산하므로, 설정을 바꿔도 시험이
 * 조용히 무의미해지지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReviewClaimIT {

    /** 검수자 A — 공용 시드에 이름(검수자1)이 있어 점유자 표시 이름을 단언할 수 있다. */
    private static final long REVIEWER_A = 1L;
    /** 검수자 B — 공용 시드의 다른 검수자 역할 보유자. */
    private static final long REVIEWER_B = 1001L;
    /** 이 시험 전용 관리자 — 공용 시드에 관리자를 넣으면 부트스트랩 창구가 영구히 닫힌다. */
    private static final long ADMIN_NO = 969_900_011L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsTaskEventLogRepository taskEventLogRepository;
    @Autowired private ReviewClaimSupport reviewClaimSupport;
    @Autowired private UserRoleResolver userRoleResolver;

    @Autowired @Qualifier("controlDataSource")
    private javax.sql.DataSource controlDataSource;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String tokenA;
    private String tokenB;
    private Long videoId;

    @BeforeEach
    void setUp() {
        tokenA = JwtTestSupport.token(secret, String.valueOf(REVIEWER_A), "REVIEWER", "INTERNAL", issuer, 60);
        tokenB = JwtTestSupport.token(secret, String.valueOf(REVIEWER_B), "REVIEWER", "INTERNAL", issuer, 60);
        videoId = seedVideo("CLIP-CLAIM-001", LsRawDataStatus.STTS_PENDING);
    }

    // ---------------------------------------------------- 점유 성립·충돌

    @Test
    @DisplayName("A가_검수를_시작하면_B의_검수시작은_409이고_누가_보고_있는지_알려준다")
    void otherReviewerIsRejectedWithOwnerName() throws Exception {
        start(videoId, tokenA).andExpect(status().isOk());

        start(videoId, tokenB)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                // ★표시 이름만 싣는다 — 사용자 식별자·역할·소속은 거절 응답에 담지 않는다.
                .andExpect(jsonPath("$.data.reviewingUserName").value("검수자1"))
                .andExpect(jsonPath("$.message").value("검수자1 님이 검수 중입니다."));

        // 거절이 상태를 건드리지 않았는지 — A 가 세운 IN_REVIEW 그대로여야 한다.
        assertThat(sttsOf(videoId)).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);
    }

    /**
     * ★<b>만료 경계를 실제로 지나간다.</b> 먼저 유예 <b>안쪽</b>에서 여전히 막히는지 보고(대조군),
     * 그다음 유예 <b>바깥</b>으로 밀어 넘어가는지 본다. 대조군이 없으면 「원래 안 막혔다」와
     * 「만료돼서 열렸다」를 구분할 수 없다.
     */
    @Test
    @DisplayName("유예가_지나면_B가_검수를_시작할_수_있고_점유_주인이_B로_바뀐다")
    void claimExpiresAfterGraceAndOwnerChanges() throws Exception {
        start(videoId, tokenA).andExpect(status().isOk());
        long graceMinutes = reviewClaimSupport.grace().toMinutes();

        // (1) 아직 유예 안 — 대조군. 여기서 통과해 버리면 아래 단언은 만료를 증명하지 못한다.
        backdateStartReview(videoId, Math.max(1, graceMinutes - 5));
        start(videoId, tokenB).andExpect(status().isConflict());

        // (2) 유예 바깥 — 점유가 풀려 B 가 집어간다.
        backdateStartReview(videoId, graceMinutes + 5);
        start(videoId, tokenB).andExpect(status().isOk());

        detail(videoId, tokenB)
                .andExpect(jsonPath("$.data.reviewingUserId").value((int) REVIEWER_B))
                .andExpect(jsonPath("$.data.reviewStartedAt").isNotEmpty());
        // 유예로 풀린 검수 진행 영상을 집어가는 갈래라 상태는 그대로다.
        assertThat(sttsOf(videoId)).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);
    }

    @Test
    @DisplayName("본인_재진입은_409가_아니라_200이고_점유_시각이_갱신된다_최초_시작_기록은_남는다")
    void reentryIsIdempotentAndRefreshesClaim() throws Exception {
        long step = Math.max(1, reviewClaimSupport.grace().toMinutes() - 1);

        start(videoId, tokenA).andExpect(status().isOk());
        // (1) 만료 직전까지 민다.
        shiftStartReviewBack(videoId, step);
        // (2) 같은 사람이 다시 연다 — 여기서 「검수 시작」이 새로 쌓여야 점유 시각이 갱신된다.
        start(videoId, tokenA).andExpect(status().isOk());
        // (3) 같은 폭만큼 <b>한 번 더</b> 민다. 갱신이 있었다면 마지막 기록이 아직 유예 안이라 점유가
        //     살아 있고, 갱신이 없었다면(이벤트를 새로 남기지 않았다면) 유일한 기록이 유예를 두 배로
        //     지나 만료돼 B 가 집어간다 — 그래서 이 단언은 <b>갱신 여부를 실제로 가른다</b>.
        shiftStartReviewBack(videoId, step);

        start(videoId, tokenB).andExpect(status().isConflict());

        // ★최초 시작 기록을 덮어쓰지 않는다 — 이력 행이 둘 남는다.
        List<LsTaskEventLog> events = taskEventLogRepository.findByRawDataIdOrderByOcrnDtAsc(videoId);
        assertThat(events).filteredOn(e -> LsTaskEventLog.EVENT_START_REVIEW.equals(e.getEventTypeCd()))
                .hasSize(2);
    }

    // ---------------------------------------------------- 점유 해제 (푸는 동작 없이)

    @Test
    @DisplayName("승인하면_점유가_비워진다_푸는_동작_없이_종결_이벤트만으로")
    void approvalClearsClaim() throws Exception {
        seedFrameWithLabel(videoId);
        start(videoId, tokenA).andExpect(status().isOk());

        mockMvc.perform(post("/v1/reviews/" + videoId + "/approve")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        detail(videoId, tokenA)
                .andExpect(jsonPath("$.data.reviewingUserId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.reviewStartedAt").value(org.hamcrest.Matchers.nullValue()))
                // 최근 승인자는 반대로 채워진다 — 역할은 행위 시점 값이다.
                .andExpect(jsonPath("$.data.lastApproverId").value((int) REVIEWER_A))
                .andExpect(jsonPath("$.data.lastApproverRole").value("REVIEWER"))
                .andExpect(jsonPath("$.data.lastApprovedAt").isNotEmpty());
    }

    @Test
    @DisplayName("반려하면_점유가_비워진다")
    void rejectionClearsClaim() throws Exception {
        start(videoId, tokenA).andExpect(status().isOk());

        mockMvc.perform(post("/v1/reviews/" + videoId + "/reject")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RejectRequest("좌표가 부정확합니다."))))
                .andExpect(status().isOk());

        detail(videoId, tokenA).andExpect(jsonPath("$.data.reviewingUserId").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---------------------------------------------------- 재검수 건 (상태를 되돌리지 않는다)

    /**
     * ★★상태를 내리면 관제 조회 뷰가 라이브 승인 상태로 행을 걸러 <b>이미 통지한 영상이 관제에서
     * 사라진다</b>. 그래서 재검수 건의 검수 시작은 점유만 세운다.
     */
    @Test
    @DisplayName("재검수_건의_검수시작은_점유만_세우고_승인_상태를_되돌리지_않는다")
    void startOnRecheckKeepsApprovedStatus() throws Exception {
        LsRawDataStatus stts = dataSttsRepository.findById(videoId).orElseThrow();
        stts.transitionTo(LsRawDataStatus.STTS_APPROVED);
        stts.markNeedsRecheck();
        dataSttsRepository.saveAndFlush(stts);

        start(videoId, tokenA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSttsCd").value(LsRawDataStatus.STTS_APPROVED))
                // 이 창구는 재검토 표시를 해제하지 않는다 — 해제는 재승인의 몫이다.
                .andExpect(jsonPath("$.data.needsRecheck").value(true))
                .andExpect(jsonPath("$.data.reviewingUserId").value((int) REVIEWER_A));

        assertThat(sttsOf(videoId)).isEqualTo(LsRawDataStatus.STTS_APPROVED);
    }

    @Test
    @DisplayName("재검토_표시가_없는_승인_영상의_검수시작은_종전대로_거절된다")
    void startOnPlainApprovedIsStillRejected() throws Exception {
        LsRawDataStatus stts = dataSttsRepository.findById(videoId).orElseThrow();
        stts.transitionTo(LsRawDataStatus.STTS_APPROVED);
        dataSttsRepository.saveAndFlush(stts);

        start(videoId, tokenA).andExpect(status().isConflict());
    }

    // ---------------------------------------------------- 목록은 전체를 본다

    @Test
    @DisplayName("검수_목록은_남이_점유한_건도_보여준다_점유는_표시이지_필터가_아니다")
    void listShowsEveryoneClaims() throws Exception {
        start(videoId, tokenA).andExpect(status().isOk());

        // B 가 조회해도 그 행이 사라지지 않고, 점유자가 A 로 표시되며 B 의 일괄 승인 자격만 꺼진다.
        mockMvc.perform(get("/v1/reviews").param("size", "100")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.videoId == " + videoId + ")]").isNotEmpty())
                .andExpect(jsonPath("$.data.content[?(@.videoId == " + videoId + ")].reviewingUserId")
                        .value(org.hamcrest.Matchers.contains((int) REVIEWER_A)))
                .andExpect(jsonPath("$.data.content[?(@.videoId == " + videoId + ")].bulkApprovable")
                        .value(org.hamcrest.Matchers.contains(false)));

        // A 가 조회하면 같은 행의 자격이 켜진다 — 자격은 요청자에 따라 갈리는 유일한 축이다.
        mockMvc.perform(get("/v1/reviews").param("size", "100")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.data.content[?(@.videoId == " + videoId + ")].bulkApprovable")
                        .value(org.hamcrest.Matchers.contains(true)));
    }

    @Test
    @DisplayName("검수_목록_응답에_일괄승인_건수_상한이_항목마다가_아니라_한_번_실린다")
    void listCarriesBulkApproveLimitOnce() throws Exception {
        mockMvc.perform(get("/v1/reviews").param("size", "100")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bulkApproveLimit").isNumber())
                .andExpect(jsonPath("$.data.bulkApproveLimit").value(org.hamcrest.Matchers.greaterThan(0)))
                // 항목에 복제되지 않는다.
                .andExpect(jsonPath("$.data.content[0].bulkApproveLimit").doesNotExist());
    }

    /**
     * ★<b>점유·승인자 조회가 행마다 돌지 않는지</b>를 실제 쿼리 수로 고정한다. 판정은 절대값이 아니라
     * <b>같은 경로 두 실행의 차이</b>다 — 페이지 크기를 크게 늘려도 쿼리가 늘지 않아야 한다.
     * (계측 축은 스레드 범위 계측기다. 전역 통계로 재면 배경 작업이 섞여 값이 흔들린다.)
     */
    @Test
    @DisplayName("검수_목록의_점유_승인자_조회는_행_수에_따라_늘지_않는다_N플러스1_금지")
    void listClaimLookupDoesNotScaleWithRows() throws Exception {
        for (int i = 0; i < 6; i++) {
            Long id = seedVideo("CLIP-CLAIM-N" + i, LsRawDataStatus.STTS_PENDING);
            start(id, tokenA).andExpect(status().isOk());
        }

        long small;
        long large;
        try (ThreadScopedQueryProbe probe = ThreadScopedQueryProbe.attach()) {
            probe.reset();
            mockMvc.perform(get("/v1/reviews").param("size", "1")
                    .header("Authorization", "Bearer " + tokenA)).andExpect(status().isOk());
            small = probe.countOnThisThread();

            probe.reset();
            mockMvc.perform(get("/v1/reviews").param("size", "100")
                    .header("Authorization", "Bearer " + tokenA)).andExpect(status().isOk());
            large = probe.countOnThisThread();
        }

        // 행이 1건에서 7건 이상으로 늘어도 조회 횟수가 <b>늘지 않아야</b> 한다. 행마다 점유를 뒤지면
        //   그 차이가 곧바로 드러난다(변이 실증: claimsOf → 행마다 claimOf 로 바꾸면 여기서 FAILED).
        //   ⚠ 「같아야 한다」로 두지 않는다 — 한 쪽만 건수 집계 쿼리를 더 내는 경우가 있어(마지막
        //     쪽이면 집계를 생략한다) 절대값이 서로 다른 것이 정상이다. 판정 축은 <b>증가 여부</b>다.
        assertThat(large)
                .as("페이지 크기만 키웠는데 쿼리가 늘었다 — 점유·승인자 조회가 행마다 돈다 (small=%d large=%d)",
                        small, large)
                .isLessThanOrEqualTo(small);
    }

    // ---------------------------------------------------- 관리자 (계층 + 이력 역할)

    @Test
    @DisplayName("관리자는_검수시작과_승인을_전부_통과하고_승인_이력이_ADMIN으로_남는다")
    void adminRunsFullReviewAndIsRecordedAsAdmin() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(controlDataSource);
        jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", ADMIN_NO);
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'ADMIN', CURRENT_TIMESTAMP)",
                ADMIN_NO);
        userRoleResolver.evict(ADMIN_NO);
        try {
            String adminToken = JwtTestSupport.token(
                    secret, String.valueOf(ADMIN_NO), "ADMIN", "INTERNAL", issuer, 60);
            seedFrameWithLabel(videoId);

            start(videoId, adminToken)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reviewingUserId").value((int) ADMIN_NO));
            mockMvc.perform(post("/v1/reviews/" + videoId + "/approve")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.dataSttsCd").value(LsRawDataStatus.STTS_APPROVED))
                    // ★계층으로 승격된 값(REVIEWER)이 아니라 실제 역할이 남아야 한다.
                    .andExpect(jsonPath("$.data.lastApproverRole").value("ADMIN"))
                    .andExpect(jsonPath("$.data.lastApproverId").value((int) ADMIN_NO));

            assertThat(taskEventLogRepository.findByRawDataIdOrderByOcrnDtAsc(videoId))
                    .filteredOn(e -> LsTaskEventLog.EVENT_APPROVE.equals(e.getEventTypeCd()))
                    .allSatisfy(e -> assertThat(e.getActorRoleCd()).isEqualTo("ADMIN"));
        } finally {
            jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", ADMIN_NO);
            userRoleResolver.evict(ADMIN_NO);
        }
    }

    @Test
    @DisplayName("관리자는_반려도_통과하고_반려_이력이_ADMIN으로_남는다")
    void adminRejectsAndIsRecordedAsAdmin() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(controlDataSource);
        jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", ADMIN_NO);
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'ADMIN', CURRENT_TIMESTAMP)",
                ADMIN_NO);
        userRoleResolver.evict(ADMIN_NO);
        try {
            String adminToken = JwtTestSupport.token(
                    secret, String.valueOf(ADMIN_NO), "ADMIN", "INTERNAL", issuer, 60);

            start(videoId, adminToken).andExpect(status().isOk());
            mockMvc.perform(post("/v1/reviews/" + videoId + "/reject")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new RejectRequest("다시 확인이 필요합니다."))))
                    .andExpect(status().isOk());

            assertThat(taskEventLogRepository.findByRawDataIdOrderByOcrnDtAsc(videoId))
                    .filteredOn(e -> LsTaskEventLog.EVENT_REJECT.equals(e.getEventTypeCd()))
                    .singleElement()
                    .satisfies(e -> assertThat(e.getActorRoleCd()).isEqualTo("ADMIN"));
        } finally {
            jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", ADMIN_NO);
            userRoleResolver.evict(ADMIN_NO);
        }
    }

    // ---------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions start(Long id, String token) throws Exception {
        return mockMvc.perform(post("/v1/reviews/" + id + "/start")
                .header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions detail(Long id, String token) throws Exception {
        return mockMvc.perform(get("/v1/reviews/" + id)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    private String sttsOf(Long id) {
        return dataSttsRepository.findById(id).orElseThrow().getDataSttsCd();
    }

    private Long seedVideo(String clipId, String sttsCd) {
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                clipId, "CCTV-CLAIM", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.now(), 30));
        LsRawDataStatus stts = LsRawDataStatus.initial(raw.getRawSn());
        stts.transitionTo(sttsCd);
        dataSttsRepository.saveAndFlush(stts);
        return raw.getRawSn();
    }

    private void seedFrameWithLabel(Long id) {
        LsDataSrc frame = srcRepository.save(
                LsDataSrc.create(id, 0, "/var/raw/f0.jpg", LocalDateTime.now()));
        labelRepository.save(LsDataLbl.createManual(frame.getSrcSn(), "BBOX", null,
                "person", "[[1.0,1.0],[2.0,2.0]]", "100"));
    }

    /**
     * 그 영상의 「검수 시작」 이벤트 <b>전부</b>를 주어진 분만큼 과거로 민다.
     *
     * <p>시계를 조작하는 대신 사실(발생일시)을 옮긴다 — 판정이 실제로 만료 계산을 지나가고, 시험
     * 컨텍스트를 공유하는 다른 시험의 시각 인식을 흔들지 않는다.
     */
    private void backdateStartReview(Long id, long minutes) {
        new JdbcTemplate(controlDataSource).update(
                "UPDATE LS_TASK_EVNT_LOG SET OCRN_DT = ? WHERE RAW_DATA_ID = ? AND EVNT_TYPE_CD = ?",
                LocalDateTime.now().minusMinutes(minutes), id, LsTaskEventLog.EVENT_START_REVIEW);
    }

    /**
     * 「검수 시작」 이벤트들을 <b>각자의 시각에서</b> 주어진 분만큼 뒤로 민다(상대 이동).
     *
     * <p>절대 시각으로 덮어쓰면 행이 몇 개든 모두 같은 시각이 되어, 재진입이 새 기록을 남겼는지
     * 여부가 판정에 드러나지 않는다 — 그러면 그 시험은 무엇을 바꿔도 통과하는 항상-참이 된다.
     */
    private void shiftStartReviewBack(Long id, long minutes) {
        new JdbcTemplate(controlDataSource).update(
                "UPDATE LS_TASK_EVNT_LOG"
                        + "   SET OCRN_DT = OCRN_DT - CAST(? AS INTEGER) * INTERVAL '1 minute'"
                        + " WHERE RAW_DATA_ID = ? AND EVNT_TYPE_CD = ?",
                (int) minutes, id, LsTaskEventLog.EVENT_START_REVIEW);
    }
}
