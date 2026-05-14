package kr.co.cudo.authoring.stats;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 진입 시 비워 공유 H2 컨텍스트에서 다른 테스트가 남긴 EVT_FALL 등 잔재로 인한
 * 회귀를 차단한다 (FALL count 0 검증이 1 로 깨지는 문제).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data-stats-clean.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StatsControllerTest {

    @Autowired private MockMvc mockMvc;
    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

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
    @DisplayName("이벤트_분포는_6종_모두_포함_데이터_없으면_0")
    void eventDistributionAlwaysContainsSixCodes() throws Exception {
        String workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);

        mockMvc.perform(get("/v1/stats/summary")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventDistribution.length()").value(6))
                .andExpect(jsonPath("$.data.eventDistribution[0].eventTypeCd").value("FALL"))
                .andExpect(jsonPath("$.data.eventDistribution[0].label").value("쓰러짐"))
                .andExpect(jsonPath("$.data.eventDistribution[1].eventTypeCd").value("VIOLENCE"))
                .andExpect(jsonPath("$.data.eventDistribution[2].eventTypeCd").value("TRAFFIC_ACCIDENT"))
                .andExpect(jsonPath("$.data.eventDistribution[3].eventTypeCd").value("ABNORMAL_BEHAVIOR"))
                .andExpect(jsonPath("$.data.eventDistribution[4].eventTypeCd").value("FLOOD"))
                .andExpect(jsonPath("$.data.eventDistribution[5].eventTypeCd").value("WILDFIRE"))
                // 데이터 없으면 0
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
