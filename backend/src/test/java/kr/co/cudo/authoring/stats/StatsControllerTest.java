package kr.co.cudo.authoring.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.config.CacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /v1/stats/summary — 메인 대시보드 요약 (Phase 12 — SCR-DASH-001).
 *
 * <p>FE DashboardPage 가 호출하는 단일 엔드포인트. REVIEWER/WORKER 모두 허용,
 * PORTAL_USER 는 차단. 데이터 비어있는 환경에서 모든 카운트는 0 으로 응답한다.
 *
 * <p>{@code test-data-stats-clean.sql} 로 LS_DATA_RAW 등 통계 대상 테이블을 매 테스트
 * 진입 시 비워 공유 컨텍스트에서 다른 테스트가 남긴 EVT_FALL 등 잔재로 인한
 * 회귀를 차단하고, 이벤트 분포의 전제인 관제 이벤트 타입 마스터(MNG_EX_EVNT_TYPE)를
 * 같은 스크립트에서 직접 세운다(선행 테스트의 dev-seed 적재에 의존하지 않는다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data-stats-clean.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StatsControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CacheManager cacheManager;
    @Autowired private ObjectMapper objectMapper;
    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    /**
     * 이벤트 분포의 소스인 {@code EventTypeService.filterOptions()} 는 장수명(6h) {@code eventType}
     * 캐시에 담긴다. 같은 Spring 컨텍스트를 공유하는 다른 테스트 클래스가 관제 마스터가 비어있던
     * 시점에 이 캐시를 빈 결과로 채워두면, 위 @Sql 이 마스터를 시드해도 분포가 0건으로 나온다.
     * 캐시를 비워 매 테스트가 시드된 DB 를 다시 읽게 한다(@Sql 은 @BeforeEach 보다 먼저 실행됨).
     */
    @BeforeEach
    void evictEventTypeCache() {
        Cache eventType = cacheManager.getCache(CacheConfig.CACHE_EVENT_TYPE);
        if (eventType != null) {
            eventType.clear();
        }
    }

    @Test
    @DisplayName("REVIEWER가_GET_stats_summary_호출시_KPI_3종_및_누적_2종_반환")
    void reviewerCanFetchSummary() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/summary")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pendingCount").exists())
                .andExpect(jsonPath("$.data.completedCount").exists())
                .andExpect(jsonPath("$.data.rejectedCount").exists())
                .andExpect(jsonPath("$.data.cumulativeImageCount").exists())
                .andExpect(jsonPath("$.data.cumulativeVideoCount").exists());
    }

    @Test
    @DisplayName("WORKER가_GET_stats_summary_호출시_myTask_분해_4종_노출")
    void workerCanFetchSummary() throws Exception {
        String workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/summary")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.myTaskCount").exists())
                .andExpect(jsonPath("$.data.myTask.pendingCount").exists())
                .andExpect(jsonPath("$.data.myTask.inProgressCount").exists())
                .andExpect(jsonPath("$.data.myTask.reviewPendingCount").exists())
                .andExpect(jsonPath("$.data.myTask.rejectedCount").exists());
    }

    @Test
    @DisplayName("이벤트_분포는_관제_9종_카테고리_모두_포함_데이터_없으면_0")
    void eventDistributionAlwaysContainsControlCategories() throws Exception {
        String workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        // Phase 3 — 분포는 EventTypeService.filterOptions() 의 관제 카테고리(EV-코드 기반,
        // CLCT_YN='Y' AND cls!='08', categoryKey 오름차순)로 집계한다. dev-seed 기준 9 카테고리:
        // 침수(범람)/산사태/화재/쓰러짐/파손/교통사고/싸움/흉기소지/납치(유괴).
        // eventTypeCd 값의 의미가 UI약어("FALL")에서 categoryKey("010001")로 변경됨.
        mockMvc.perform(get("/v1/stats/summary")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventDistribution.length()").value(9))
                .andExpect(jsonPath("$.data.eventDistribution[0].eventTypeCd").value("010001"))
                .andExpect(jsonPath("$.data.eventDistribution[0].label").value("침수(범람)"))
                .andExpect(jsonPath("$.data.eventDistribution[3].eventTypeCd").value("020002"))
                .andExpect(jsonPath("$.data.eventDistribution[3].label").value("쓰러짐"))
                // 비수집 카테고리(기타 상황 070002)는 그리드에 포함되지 않는다
                .andExpect(jsonPath("$.data.eventDistribution[?(@.eventTypeCd=='070002')]").isEmpty())
                // 데이터 없으면(clean seed) 0
                .andExpect(jsonPath("$.data.eventDistribution[0].count").value(0));
    }

    /**
     * 분포 배열 길이를 <b>절대값(9)으로 단언하지 않는 이유</b>: 관제 이벤트 유형 마스터
     * (MNG_EX_EVNT_TYPE) 의 행 구성은 시드 스크립트가 소유하므로, approved 분포 검증까지
     * 절대값에 묶으면 시드가 바뀔 때마다 두 곳을 고쳐야 한다. 신규 필드 검증은 시드 구성에
     * 의존하지 않도록 <b>기존 분포와의 상대 관계</b>로 고정한다 — approved 분포는 전체 분포와
     * 카테고리 구성·순서가 항상 같아야 한다.
     *
     * <p>(과거에는 마스터가 dev-seed 로만 채워져 이 패키지 단독 실행 시 분포가 0 건이 되는
     * 순서 의존성이 있었으나, 위 {@code test-data-stats-clean.sql} 자기 시드 + eventType 캐시
     * 무효화로 해소되어 {@code 이벤트_분포는_관제_9종...} 은 이제 단독 실행에서도 통과한다.)
     */
    private JsonNode fetchData(String url, String token) throws Exception {
        String body = mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private static void assertSameCategories(JsonNode approved, JsonNode total) {
        assertThat(approved.isArray()).isTrue();
        assertThat(approved.size()).isEqualTo(total.size());
        for (int i = 0; i < total.size(); i++) {
            assertThat(approved.get(i).path("eventTypeCd").asText())
                    .isEqualTo(total.get(i).path("eventTypeCd").asText());
            assertThat(approved.get(i).path("label").asText())
                    .isEqualTo(total.get(i).path("label").asText());
        }
    }

    @Test
    @DisplayName("대시보드_응답에_approved_4필드가_포함된다")
    void summaryExposesApprovedFields() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        // "이미지/영상 학습데이터" 카드는 검수 승인분만 세야 한다(검수 완료 = 학습데이터 확정).
        // 기존 cumulative* 는 전체 기준 의미를 그대로 유지한다(하위호환).
        JsonNode data = fetchData("/v1/stats/summary", reviewerToken);

        // clean seed — 승인 영상 0건
        assertThat(data.path("approvedImageCount").isNumber()).isTrue();
        assertThat(data.path("approvedImageCount").asLong()).isZero();
        assertThat(data.path("approvedVideoCount").asLong()).isZero();
        // 기존 필드는 그대로 유지(삭제·리네임 없음)
        assertThat(data.path("cumulativeImageCount").isNumber()).isTrue();
        assertThat(data.path("cumulativeVideoCount").isNumber()).isTrue();
        // approved 는 항상 전체 이하
        assertThat(data.path("approvedImageCount").asLong())
                .isLessThanOrEqualTo(data.path("cumulativeImageCount").asLong());
        assertThat(data.path("approvedVideoCount").asLong())
                .isLessThanOrEqualTo(data.path("cumulativeVideoCount").asLong());
        // 분포 4종의 카테고리 구성·순서 동일
        assertSameCategories(data.path("approvedEventDistribution"), data.path("eventDistribution"));
        assertSameCategories(data.path("approvedImageDistribution"), data.path("imageDistribution"));
    }

    @Test
    @DisplayName("전체구축현황_응답에도_approved_3필드가_포함된다")
    void overallExposesApprovedFields() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        JsonNode data = fetchData("/v1/stats/overall", reviewerToken);

        assertThat(data.path("approvedImageCount").isNumber()).isTrue();
        assertThat(data.path("approvedImageCount").asLong()).isZero();
        assertThat(data.path("approvedVideoCount").asLong()).isZero();
        // approvedVideoCount 는 processing.approved 와 동일 원천이어야 한다(드리프트 금지)
        assertThat(data.path("approvedVideoCount").asLong())
                .isEqualTo(data.path("processing").path("approved").asLong());
        assertThat(data.path("cumulativeImageCount").isNumber()).isTrue();
        assertThat(data.path("cumulativeVideoCount").isNumber()).isTrue();
        assertSameCategories(data.path("approvedEventDistribution"), data.path("eventDistribution"));
    }

    @Test
    @DisplayName("전체구축현황_응답의_dailyCounts는_30건_0채움_date오름차순이다")
    void overallExposesZeroFilledDailyCounts() throws Exception {
        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        JsonNode data = fetchData("/v1/stats/overall", reviewerToken);
        JsonNode daily = data.path("dailyCounts");

        // FE OverallStatPage 는 dailyCounts 를 그대로 막대차트에 넣는다 — 항상 30건이어야
        // X축이 날짜 연속으로 그려진다(빈 날짜 0-fill).
        assertThat(daily.isArray()).isTrue();
        assertThat(daily.size()).isEqualTo(30);

        String prev = null;
        for (JsonNode d : daily) {
            // FE 계약 키: date / count (WorkerStatSummary.dailyCompletion 과 동일 형태)
            assertThat(d.path("date").isTextual()).isTrue();
            assertThat(d.path("count").isNumber()).isTrue();
            assertThat(d.path("date").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
            if (prev != null) {
                assertThat(d.path("date").asText()).isGreaterThan(prev);
            }
            prev = d.path("date").asText();
        }
        assertThat(daily.get(29).path("date").asText())
                .isEqualTo(java.time.LocalDate.now().toString());
        // 통계 클린 시드 환경 — 승인 이력 없으므로 전부 0
        assertThat(daily.get(29).path("count").asLong()).isZero();

        // 기존 필드 불변(하위호환)
        assertThat(data.path("processing").isObject()).isTrue();
        assertThat(data.path("workers").isArray()).isTrue();
    }

    @Test
    @DisplayName("WORKER는_전체구축현황_조회시_403")
    void workerCannotFetchOverall() throws Exception {
        String workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/overall")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("notices_필드는_빈_배열로_응답_FE호환")
    void noticesAlwaysReturnsArray() throws Exception {
        String workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/summary")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notices").isArray());
    }

    @Test
    @DisplayName("stats_summary_미인증_요청시_401")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/v1/stats/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PORTAL_USER가_stats_summary_호출시_403")
    void portalUserForbidden() throws Exception {
        String portalToken = JwtTestSupport.token(secret, "999", "PORTAL_USER", "PORTAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/summary")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
    }
}
