package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영상 처리 현황({@code GET /v1/videos}) <b>조회 범위 스코핑</b> 회귀 가드.
 * [@design API-042] [@design SCREEN-008] [@design ROLE-002]
 *
 * <h2>이 파일이 고정하는 것</h2>
 * <p>이 창구에는 <b>사용자 축 인가가 아예 없었다</b> — 컨트롤러가 인증 주체를 받지도 않았고 서비스도
 * 사용자 번호를 한 번도 참조하지 않았다. 반면 형제 단건 창구({@code GET /v1/videos/{rawSn}})는
 * 배정을 강제한다. 그래서 <b>목록에는 남의 영상이 나오는데 클릭하면 403</b> 인 비대칭이 있었고,
 * 미배정 영상의 CCTV 명·지자체·이벤트유형·촬영시각·비식별 상태가 그대로 노출됐다(CWE-639 IDOR).
 *
 * <p>이제 REVIEWER 는 전체를, WORKER 는 본인에게 {@code LABELER} 로 배정된 영상만 본다.
 * <b>범위 제한은 거부가 아니라 결과 축소</b>이며 배정이 없으면 403 이 아니라 빈 목록이다.
 *
 * <h2>기존 3겹이 왜 이 축을 놓쳤나 (같은 사각을 다시 만들지 말 것)</h2>
 * <ul>
 *   <li>{@code VideoContentRoleGateTest} 는 <b>상태코드만</b> 단언했다(200 이면 통과) — 결함 동작을
 *       "정상" 으로 고정하고 있었다. 이번에 본문 단언을 추가했다.</li>
 *   <li>{@code VideoListSearchFilterIT}·{@code ListApiBackwardCompatibilityIT} 는 <b>REVIEWER 토큰
 *       단독</b>이라 WORKER 경로를 한 번도 지나가지 않는다.</li>
 * </ul>
 *
 * <h2>결정성</h2>
 * <p>{@code test-data.sql} 이 매 테스트 앞에서 {@code LS_TASK_ALTMNT} 를 <b>전량 삭제</b>하므로
 * WORKER 가 보는 건수는 이 클래스가 심은 배정만으로 결정된다(공유 컨테이너에 남은 다른 클래스의
 * 영상이 있어도 배정이 없어 잡히지 않는다). REVIEWER 쪽은 전체 건수가 시드 구성에 종속되므로
 * 총계 대신 <b>영상 ID 검색어로 좁혀</b> 대조군을 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VideoListAssignmentScopeIT {

    /** {@code test-data.sql} 시드 — 1=REVIEWER, 100·101=WORKER. */
    private static final long REVIEWER_NO = 1L;
    private static final long WORKER_NO = 100L;
    private static final long OTHER_WORKER_NO = 101L;

    /**
     * 이 시험 전용 관리자 사용자번호 — 공용 시드·다른 시험과 겹치지 않는 대역.
     *
     * <p>★ 공용 시드에 관리자를 넣지 않는 이유: 시스템에 관리자가 <b>항상</b> 있는 셈이 되어
     * 관리자 부트스트랩 창구(관리자 0명일 때만 열린다)가 영구히 닫히고, 그 창구를 검증하는 시험들이
     * 통째로 깨진다. 그래서 이 클래스가 직접 심고 <b>반드시 지운다</b>.
     */
    private static final long ADMIN_NO = 969_300_011L;

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private kr.co.cudo.authoring.common.security.UserRoleResolver userRoleResolver;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("controlDataSource")
    private javax.sql.DataSource controlDataSource;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String workerToken;
    private String otherWorkerToken;
    private String reviewerToken;
    private String adminToken;

    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        workerToken = JwtTestSupport.token(secret, String.valueOf(WORKER_NO), "WORKER", "INTERNAL", issuer, 60);
        otherWorkerToken =
                JwtTestSupport.token(secret, String.valueOf(OTHER_WORKER_NO), "WORKER", "INTERNAL", issuer, 60);
        reviewerToken = JwtTestSupport.token(secret, String.valueOf(REVIEWER_NO), "REVIEWER", "INTERNAL", issuer, 60);
        // JWT 의 role 클레임은 인가에 쓰이지 않는다(LS_USER_ROLE 이 진실원) — 값은 표기일 뿐이다.
        adminToken = JwtTestSupport.token(secret, String.valueOf(ADMIN_NO), "ADMIN", "INTERNAL", issuer, 60);

        // @Sql(BEFORE_TEST_METHOD) 가 LS_USER_ROLE 을 통째로 지우고 재삽입한 <이후>에 심어야 살아남는다
        // (Spring 의 스크립트 실행이 @BeforeEach 보다 앞선다).
        jdbc = new org.springframework.jdbc.core.JdbcTemplate(controlDataSource);
        clearAdminRole();
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'ADMIN', CURRENT_TIMESTAMP)",
                ADMIN_NO);
        userRoleResolver.evict(ADMIN_NO);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        clearAdminRole();
    }

    private void clearAdminRole() {
        jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", ADMIN_NO);
        jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", ADMIN_NO);
        userRoleResolver.evict(ADMIN_NO);
    }

    // ---------------------------------------------------------------- fixtures

    private LsDataRaw seedVideo(String clipId) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, "CCTV-SCOPE", null, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 10, 0, 0, 0), 30);
        return videoRepository.save(raw);
    }

    private void assignLabeler(LsDataRaw video, long userNo) {
        assignmentRepository.save(LsTaskAssignment.createLabeler(video.getRawSn(), userNo, REVIEWER_NO));
    }

    /**
     * <b>옛</b> 검수자 배정 행을 재현한다 — 새로 만드는 경로는 없어졌지만(ADR-067) 이미 적재된 행은
     * 그대로 남으므로, 그 행이 작업자 조회 범위를 넓히지 않는다는 회귀 가드는 계속 필요하다.
     * 그래서 제거된 팩토리 대신 빌더로 직접 세운다.
     */
    private void assignReviewer(LsDataRaw video, long userNo) {
        assignmentRepository.save(LsTaskAssignment.builder()
                .userNo(userNo)
                .rawDataId(video.getRawSn())
                .taskTypeCd(LsTaskAssignment.TASK_REVIEWER)
                .regUserNo(REVIEWER_NO)
                .regDt(java.time.LocalDateTime.now())
                .build());
    }

    // ---------------------------------------------------------------- AC-1 · AC-2

    @Test
    @DisplayName("WORKER_목록에는_본인_LABELER_배정_영상만_나온다")
    void workerSeesOnlyOwnLabelerAssignments() throws Exception {
        // given: 배정 2건(mine1·mine2) + 미배정 1건 + 남의 배정 1건
        LsDataRaw mine1 = seedVideo("CLIP-SCOPE-MINE-1");
        LsDataRaw mine2 = seedVideo("CLIP-SCOPE-MINE-2");
        LsDataRaw unassigned = seedVideo("CLIP-SCOPE-UNASSIGNED");
        LsDataRaw othersVideo = seedVideo("CLIP-SCOPE-OTHERS");
        assignLabeler(mine1, WORKER_NO);
        assignLabeler(mine2, WORKER_NO);
        assignLabeler(othersVideo, OTHER_WORKER_NO);

        // when / then: 본인 배정 2건만 — 미배정·타인 배정·test-data 시드 영상(1000~1003)은 전부 제외.
        //   [AC-2] totalElements 가 배정 건수와 같아야 한다 — count 쿼리에 술어를 붙이지 않으면
        //   여기가 필터 적용 전 값으로 남아 빈 페이지가 딸려 나온다.
        mockMvc.perform(get("/v1/videos").header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[?(@.id == " + mine1.getRawSn() + ")]").exists())
                .andExpect(jsonPath("$.data.content[?(@.id == " + mine2.getRawSn() + ")]").exists())
                .andExpect(jsonPath("$.data.content[?(@.id == " + unassigned.getRawSn() + ")]").doesNotExist())
                .andExpect(jsonPath("$.data.content[?(@.id == " + othersVideo.getRawSn() + ")]").doesNotExist())
                .andExpect(jsonPath("$.data.content[?(@.id == 1000)]").doesNotExist());
    }

    @Test
    @DisplayName("WORKER_에게_REVIEWER_로만_배정된_영상은_보이지_않는다")
    void workerDoesNotSeeReviewerOnlyAssignment() throws Exception {
        // given: 같은 사용자에게 REVIEWER 로만 배정된 영상 — 배정 축은 LABELER 하나다
        //   (단건 가드 LabelAccessGuard.verifyRawAccess 와 같은 축이어야 목록/단건이 갈라지지 않는다).
        LsDataRaw reviewerOnly = seedVideo("CLIP-SCOPE-REVIEWER-ONLY");
        assignReviewer(reviewerOnly, WORKER_NO);

        // when / then
        mockMvc.perform(get("/v1/videos").header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("count쿼리에도_배정_술어가_붙는다_페이지가_가득_차야_드러난다")
    void countQueryIsAlsoScoped() throws Exception {
        // ★★ 이 케이스가 없으면 count 쿼리의 술어 누락을 <아무도 잡지 못한다>(실측으로 확인했다).
        //    Spring Data 는 "offset 0 이고 content 가 page size 보다 작으면" count 쿼리를 아예 실행하지
        //    않고 totalElements 를 content 크기로 채운다. 그래서 기본 size(20)로 2건을 받는 위 케이스들은
        //    count 쿼리를 <한 번도 지나가지 않는다> — 본 쿼리에만 술어를 붙여도 전부 통과한다.
        //    페이지를 가득 채워(size < 배정 건수) count 쿼리를 실제로 태운다.
        LsDataRaw mine1 = seedVideo("CLIP-SCOPE-COUNT-1");
        LsDataRaw mine2 = seedVideo("CLIP-SCOPE-COUNT-2");
        LsDataRaw mine3 = seedVideo("CLIP-SCOPE-COUNT-3");
        assignLabeler(mine1, WORKER_NO);
        assignLabeler(mine2, WORKER_NO);
        assignLabeler(mine3, WORKER_NO);
        // 미배정 영상 — count 술어가 빠지면 이들까지 세어져 totalElements 가 부푼다.
        seedVideo("CLIP-SCOPE-COUNT-OTHER-1");
        seedVideo("CLIP-SCOPE-COUNT-OTHER-2");

        // when / then: 한 페이지 2건 · 총 3건 · 2페이지 (test-data 시드 1000~1003 은 미배정이라 제외)
        mockMvc.perform(get("/v1/videos")
                        .param("size", "2")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));

        // 2페이지에도 본인 배정분만 — 뒷페이지가 미배정 영상으로 채워지지 않는다.
        mockMvc.perform(get("/v1/videos")
                        .param("size", "2").param("page", "1")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    // ---------------------------------------------------------------- AC-4

    @Test
    @DisplayName("배정_0건_WORKER_는_403_이_아니라_200_빈페이지다")
    void workerWithoutAssignmentGetsEmptyPageNotForbidden() throws Exception {
        // given: 영상은 있으나 이 작업자에게는 한 건도 배정되지 않았다
        seedVideo("CLIP-SCOPE-NONE");

        // when / then: 범위 제한은 거부가 아니라 결과 축소다 — 목록을 부르는 행위 자체는 정상이다.
        mockMvc.perform(get("/v1/videos").header("Authorization", "Bearer " + otherWorkerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    // ---------------------------------------------------------------- AC-5

    @Test
    @DisplayName("LABELER_와_REVIEWER_가_함께_배정돼도_행이_증식하지_않는다")
    void duplicateAssignmentRowsDoNotMultiply() throws Exception {
        // given: 한 영상에 같은 사용자의 LABELER · REVIEWER 배정이 함께 있다
        //   (UK 가 작업유형까지 포함하므로 정상적으로 공존한다).
        //   술어가 EXISTS 가 아니라 조인이면 이 영상이 두 행으로 나와 totalElements 까지 부푼다.
        LsDataRaw both = seedVideo("CLIP-SCOPE-BOTH");
        assignLabeler(both, WORKER_NO);
        assignReviewer(both, WORKER_NO);

        // when / then
        mockMvc.perform(get("/v1/videos").header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(both.getRawSn()));
    }

    // ---------------------------------------------------------------- AC-3 (대조군)

    @Test
    @DisplayName("REVIEWER_는_미배정_영상도_그대로_전부_본다")
    void reviewerScopeUnchanged() throws Exception {
        // given: 아무에게도 배정되지 않은 영상
        LsDataRaw unassigned = seedVideo("CLIP-SCOPE-REVIEWER-CONTROL");

        // when / then: REVIEWER 결과는 조금도 달라지지 않는다(전체 범위).
        //   총 건수는 공유 컨테이너의 잔여 영상에 종속되므로 영상 ID 검색어로 좁혀 대조한다.
        mockMvc.perform(get("/v1/videos")
                        .param("cctvNameKeyword", String.valueOf(unassigned.getRawSn()))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(unassigned.getRawSn()));

        // 같은 영상을 WORKER 가 같은 검색어로 찾으면 0건이다 — 스코핑이 검색 축보다 먼저 걸린다.
        mockMvc.perform(get("/v1/videos")
                        .param("cctvNameKeyword", String.valueOf(unassigned.getRawSn()))
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    // ------------------------------------------------- 역할 계층 (ADR-055 · ROLE-004 · AC-125)

    @Test
    @DisplayName("★관리자는_영상목록에서_403이_아니라_검수자와_같은_전체_범위를_본다")
    void adminSeesFullScopeLikeReviewer() throws Exception {
        // given: 아무에게도 배정되지 않은 영상 + 남에게 배정된 영상
        //   [design: ADR-055] [design: ROLE-004] [design: AC-125]
        //
        // ★ 이 창구의 스코프 판정이 역할 <동등 비교>였을 때 관리자는 작업자 분기에도 검수자 분기에도
        //   걸리지 않고 마지막 FORBIDDEN 으로 떨어져 <b>영상 목록이 통째로 403</b> 이었다.
        //   Spring 의 RoleHierarchy 는 권한(authority) 축에만 걸리므로 @PreAuthorize 는 통과시키고
        //   그 뒤 서비스에서 다시 막는 형태였다 — 이 라운드의 release-blocking 대표 증거다.
        LsDataRaw unassigned = seedVideo("CLIP-SCOPE-ADMIN-UNASSIGNED");
        LsDataRaw othersVideo = seedVideo("CLIP-SCOPE-ADMIN-OTHERS");
        assignLabeler(othersVideo, OTHER_WORKER_NO);

        // when / then: 미배정 영상이 보인다 — 판정을 되돌리면 403(전부 실패)이 된다.
        //   총 건수는 공유 컨테이너 잔여에 종속되므로 영상 ID 검색어로 좁혀 대조한다(검수자 케이스와 동일).
        mockMvc.perform(get("/v1/videos")
                        .param("cctvNameKeyword", String.valueOf(unassigned.getRawSn()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(unassigned.getRawSn()));

        // 남에게 배정된 영상도 그대로 보인다 — 관리자가 작업자 분기로 흘러 <본인 배정분>으로 좁혀지면
        // 여기가 0건이 된다(hasRole(WORKER) 가 작업자 전용임을 결과로 고정).
        mockMvc.perform(get("/v1/videos")
                        .param("cctvNameKeyword", String.valueOf(othersVideo.getRawSn()))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(othersVideo.getRawSn()));
    }

    @Test
    @DisplayName("★관리자의_조회_범위는_검수자와_같다_같은_영상을_같은_건수로_본다")
    void adminScopeEqualsReviewerScope() throws Exception {
        // 「403 이 아니다」만 확인하면 관리자가 <일부만> 보는 상태도 통과한다. 같은 검색어로 두 역할의
        // 결과를 맞대어 <범위가 동일함>을 고정한다(ROLE-004: 관리자는 검수자에게 열린 자리에 그대로 들어간다).
        LsDataRaw target = seedVideo("CLIP-SCOPE-ADMIN-PARITY");
        assignLabeler(target, OTHER_WORKER_NO);
        String keyword = String.valueOf(target.getRawSn());

        for (String token : new String[]{reviewerToken, adminToken}) {
            mockMvc.perform(get("/v1/videos")
                            .param("cctvNameKeyword", keyword)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.content[0].id").value(target.getRawSn()));
        }
    }

    // ---------------------------------------------------------------- AC-6

    @Test
    @DisplayName("WORKER_스코핑은_기존_필터와_함께_동작한다")
    void workerScopeComposesWithExistingFilters() throws Exception {
        // given: 본인 배정 2건 중 하나만 COMPLETED
        LsDataRaw completed = seedVideo("CLIP-SCOPE-FILTER-DONE");
        completed.changeStatus(LsDataRaw.DATA_STTS_COMPLETED);
        completed = videoRepository.save(completed);
        LsDataRaw pending = seedVideo("CLIP-SCOPE-FILTER-PENDING");
        assignLabeler(completed, WORKER_NO);
        assignLabeler(pending, WORKER_NO);
        // 미배정 COMPLETED 1건 — 없으면 상태 필터만으로도 1건이 되어 배정 축이 빠져도 통과한다(공허).
        LsDataRaw othersCompleted = seedVideo("CLIP-SCOPE-FILTER-OTHERS");
        othersCompleted.changeStatus(LsDataRaw.DATA_STTS_COMPLETED);
        videoRepository.save(othersCompleted);

        // when / then: 배정 축과 상태 축이 AND 로 합성된다(둘 중 하나만 적용되면 안 된다)
        mockMvc.perform(get("/v1/videos")
                        .param("dataSttsCd", LsDataRaw.DATA_STTS_COMPLETED)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(completed.getRawSn()));
    }
}
