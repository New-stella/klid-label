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

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String workerToken;
    private String otherWorkerToken;
    private String reviewerToken;

    @BeforeEach
    void setUp() {
        workerToken = JwtTestSupport.token(secret, String.valueOf(WORKER_NO), "WORKER", "INTERNAL", issuer, 60);
        otherWorkerToken =
                JwtTestSupport.token(secret, String.valueOf(OTHER_WORKER_NO), "WORKER", "INTERNAL", issuer, 60);
        reviewerToken = JwtTestSupport.token(secret, String.valueOf(REVIEWER_NO), "REVIEWER", "INTERNAL", issuer, 60);
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

    private void assignReviewer(LsDataRaw video, long userNo) {
        assignmentRepository.save(LsTaskAssignment.createReviewer(video.getRawSn(), userNo, REVIEWER_NO));
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
