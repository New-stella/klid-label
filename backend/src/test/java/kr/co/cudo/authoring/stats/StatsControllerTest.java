package kr.co.cudo.authoring.stats;

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
