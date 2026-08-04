package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.support.IngestFlatValueSeeder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 — 검수목록 KPI 집계({@code GET /v1/reviews/summary}) 검증.
 *
 * <p>검증 축:
 * <ul>
 *   <li><b>불변식</b> — {@code total == pending + inReview + approved + rejected}</li>
 *   <li><b>HIGH-5</b> — 화이트리스트 밖 상태(배치/작업 상태)는 어느 버킷에도 합산되지 않는다</li>
 *   <li><b>HIGH-6</b> — 대상 0건이어도 NULL/NPE 없이 전 필드 0</li>
 *   <li><b>필터 대칭</b> — {@code q} 는 반영, {@code status} 는 무시(카드 자체가 status 선택지)</li>
 *   <li><b>인가</b> — REVIEWER 이중 가드 (403/401)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ReviewSummaryTest {

    /** 인입 평면값 시드용 — CCTV 명의 유일한 조달처(V167). */
    @Autowired
    @Qualifier("controlDataSource")
    private javax.sql.DataSource controlDataSource;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    // ---------------------------------------------------------------- fixtures

    private Long seedReviewVideo(String clipId, String cctvId, String status) {
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                clipId, cctvId, "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0, 0), 30));
        LsRawDataStatus stts = LsRawDataStatus.initial(raw.getRawSn());
        stts.transitionTo(status);
        dataSttsRepository.save(stts);
        // CCTV 명은 관제 인입 평면값에서 온다(V167 — 구 test-data-video.sql 의 CCTV 마스터 시드 대체).
        IngestFlatValueSeeder.seedLegacyName(
                new JdbcTemplate(controlDataSource), raw.getRawSn(), cctvId);
        return raw.getRawSn();
    }

    private RequestBuilder summary(String... params) {
        var builder = get("/v1/reviews/summary").header("Authorization", "Bearer " + reviewerToken);
        for (int i = 0; i + 1 < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        return builder;
    }

    private JsonNode summaryOf(String... params) throws Exception {
        MvcResult result = mockMvc.perform(summary(params)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private static void assertInvariant(JsonNode data) {
        long sum = data.path("pending").asLong() + data.path("inReview").asLong()
                + data.path("approved").asLong() + data.path("rejected").asLong();
        assertThat(data.path("total").asLong())
                .as("total 은 4종 상태 합계와 일치해야 한다")
                .isEqualTo(sum);
    }

    // ------------------------------------------------------------------- tests

    @Test
    @DisplayName("summary_의_total_은_4종_상태_합계와_항상_일치한다")
    void totalEqualsBucketSum() throws Exception {
        // given — 4종 × 서로 다른 건수 (2/3/1/4)
        for (int i = 0; i < 2; i++) seedReviewVideo("CLIP-SUM-P" + i, "CCTV-001", LsRawDataStatus.STTS_PENDING);
        for (int i = 0; i < 3; i++) seedReviewVideo("CLIP-SUM-I" + i, "CCTV-001", LsRawDataStatus.STTS_IN_REVIEW);
        seedReviewVideo("CLIP-SUM-A0", "CCTV-001", LsRawDataStatus.STTS_APPROVED);
        for (int i = 0; i < 4; i++) seedReviewVideo("CLIP-SUM-R" + i, "CCTV-001", LsRawDataStatus.STTS_REJECTED);

        JsonNode data = summaryOf();

        assertThat(data.path("pending").asLong()).isEqualTo(2);
        assertThat(data.path("inReview").asLong()).isEqualTo(3);
        assertThat(data.path("approved").asLong()).isEqualTo(1);
        assertThat(data.path("rejected").asLong()).isEqualTo(4);
        assertThat(data.path("total").asLong()).isEqualTo(10);
        assertInvariant(data);
    }

    @Test
    @DisplayName("summary_는_화이트리스트_밖_배치상태를_어느_버킷에도_포함하지_않는다")
    void batchStatusesExcludedFromAllBuckets() throws Exception {
        // given — 화이트리스트 1건 + 배치/작업 상태 5건
        seedReviewVideo("CLIP-WLS-P", "CCTV-001", LsRawDataStatus.STTS_PENDING);
        for (String batchStatus : List.of(LsRawDataStatus.STTS_ASSIGNED, LsRawDataStatus.STTS_BATCH_QUEUED,
                LsRawDataStatus.STTS_PROCESSING, LsRawDataStatus.STTS_COMPLETED, LsRawDataStatus.STTS_FAILED)) {
            seedReviewVideo("CLIP-WLS-" + batchStatus, "CCTV-001", batchStatus);
        }

        JsonNode data = summaryOf();

        // then — 총합이 1 (배치 상태 5건은 어느 버킷에도 없음)
        assertThat(data.path("total").asLong()).isEqualTo(1);
        assertThat(data.path("pending").asLong()).isEqualTo(1);
        assertThat(data.path("inReview").asLong()).isZero();
        assertThat(data.path("approved").asLong()).isZero();
        assertThat(data.path("rejected").asLong()).isZero();
        assertInvariant(data);
    }

    @Test
    @DisplayName("검수대상이_0건이면_summary_전_필드가_0이다")
    void zeroRowsYieldZeroes() throws Exception {
        // given — 검수 워크플로 대상 없음 (배치 상태 1건만 존재)
        seedReviewVideo("CLIP-ZERO-1", "CCTV-001", LsRawDataStatus.STTS_PROCESSING);

        JsonNode data = summaryOf();

        // then — SUM(CASE ...) 의 NULL 이 그대로 새어나오면(NPE/누락) 여기서 깨진다(HIGH-6)
        assertThat(data.path("total").asLong()).isZero();
        assertThat(data.path("pending").asLong()).isZero();
        assertThat(data.path("inReview").asLong()).isZero();
        assertThat(data.path("approved").asLong()).isZero();
        assertThat(data.path("rejected").asLong()).isZero();
        assertInvariant(data);
    }

    @Test
    @DisplayName("summary_에_검색어_필터가_적용된다")
    void searchFilterApplied() throws Exception {
        // given — 회기로(CCTV-001) 2건 PENDING, 테헤란로(CCTV-002) 1건 APPROVED
        seedReviewVideo("CLIP-QS-1", "CCTV-001", LsRawDataStatus.STTS_PENDING);
        seedReviewVideo("CLIP-QS-2", "CCTV-001", LsRawDataStatus.STTS_PENDING);
        seedReviewVideo("CLIP-QS-3", "CCTV-002", LsRawDataStatus.STTS_APPROVED);

        JsonNode all = summaryOf();
        assertThat(all.path("total").asLong()).isEqualTo(3);

        JsonNode filtered = summaryOf("q", "회기");
        assertThat(filtered.path("total").asLong()).isEqualTo(2);
        assertThat(filtered.path("pending").asLong()).isEqualTo(2);
        assertThat(filtered.path("approved").asLong()).isZero();
        assertInvariant(filtered);

        // 매치 없는 검색어 → 전 필드 0
        JsonNode none = summaryOf("q", "존재하지않는영상명");
        assertThat(none.path("total").asLong()).isZero();
        assertInvariant(none);
    }

    @Test
    @DisplayName("summary_에_작업자명_검색이_적용된다")
    void workerNameSearchApplied() throws Exception {
        Long assigned = seedReviewVideo("CLIP-QW-1", "CCTV-002", LsRawDataStatus.STTS_IN_REVIEW);
        authrtRepository.save(LsTaskAssignment.createLabeler(assigned, 101L, 1L));
        seedReviewVideo("CLIP-QW-2", "CCTV-002", LsRawDataStatus.STTS_PENDING);

        JsonNode data = summaryOf("q", "작업자101");
        assertThat(data.path("total").asLong()).isEqualTo(1);
        assertThat(data.path("inReview").asLong()).isEqualTo(1);
        assertInvariant(data);
    }

    @Test
    @DisplayName("summary_는_status_필터를_반영하지_않는다")
    void statusFilterIgnored() throws Exception {
        seedReviewVideo("CLIP-SI-1", "CCTV-001", LsRawDataStatus.STTS_PENDING);
        seedReviewVideo("CLIP-SI-2", "CCTV-001", LsRawDataStatus.STTS_APPROVED);
        seedReviewVideo("CLIP-SI-3", "CCTV-001", LsRawDataStatus.STTS_REJECTED);

        JsonNode unfiltered = summaryOf();
        // status 를 보내도 카드 4종이 그대로 유지되어야 한다 (1개 카드만 non-zero 가 되면 KPI 가 무의미)
        for (String status : List.of("PENDING", "APPROVED", "REJECTED", "IN_REVIEW")) {
            JsonNode data = summaryOf("status", status);
            assertThat(data.path("total").asLong()).isEqualTo(unfiltered.path("total").asLong());
            assertThat(data.path("pending").asLong()).isEqualTo(1);
            assertThat(data.path("approved").asLong()).isEqualTo(1);
            assertThat(data.path("rejected").asLong()).isEqualTo(1);
            assertInvariant(data);
        }
    }

    @Test
    @DisplayName("summary_는_REVIEWER_아닌_사용자에게_403")
    void summaryForbiddenForWorker() throws Exception {
        mockMvc.perform(get("/v1/reviews/summary").header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("summary_미인증시_401")
    void summaryUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/reviews/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("summary_과대_길이_검색어는_400")
    void summaryOversizedSearchRejected() throws Exception {
        mockMvc.perform(summary("q", "가".repeat(101)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("summary_경로가_검수_상세조회_경로와_충돌하지_않는다")
    void summaryPathDoesNotCollideWithDetail() throws Exception {
        // /v1/reviews/{videoId} 는 Long 이므로 'summary' 리터럴 세그먼트가 우선 매칭되어야 한다.
        mockMvc.perform(summary())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").exists());
    }
}
