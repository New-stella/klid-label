package kr.co.cudo.authoring.eventtype.controller;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EventTypeController 통합 테스트 — dev-seed 실데이터 위에서 API 표준 응답·인가를 고정한다.
 *
 * <p>{@code @ActiveProfiles("local")} + {@code authoring.dev.seed.enabled=true} 로 관제 이벤트
 * 타입 마스터/매핑이 Testcontainer PostgreSQL 에 멱등 적재된다(Phase 1 IT 와 동일 시드).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = "authoring.dev.seed.enabled=true")
class EventTypeControllerIT {

    @Autowired private MockMvc mockMvc;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;
    private String portalToken;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "2", "WORKER", "INTERNAL", issuer, 60);
        portalToken = JwtTestSupport.token(secret, "3", "PORTAL_USER", "PORTAL", issuer, 60);
    }

    @Test
    @DisplayName("event_types_API가_ApiResponse_표준_200으로_필터목록을_반환한다")
    void filterOptionsReturnsStandardResponse() throws Exception {
        mockMvc.perform(get("/v1/event-types")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 침수/산사태/화재/쓰러짐/파손/교통사고/싸움/흉기소지/납치 = 9개 카테고리(dedup)
                .andExpect(jsonPath("$.data.length()").value(9))
                .andExpect(jsonPath("$.data[?(@.categoryKey=='010001')].label").value("침수(범람)"))
                .andExpect(jsonPath("$.data[?(@.categoryKey=='010001')].memberCodes.length()").value(3))
                // ignore 대분류(08) 카테고리는 노출되지 않는다
                .andExpect(jsonPath("$.data[?(@.categoryKey =~ /08.*/)]").isEmpty());
    }

    @Test
    @DisplayName("event_types_labels_API가_코드라벨맵을_반환한다")
    void labelsReturnsCodeLabelMap() throws Exception {
        mockMvc.perform(get("/v1/event-types/labels")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data['EV02000201']").value("쓰러짐"))
                // 비수집 코드도 라벨 해석된다
                .andExpect(jsonPath("$.data['EV07000201']").value("기타 상황"));
    }

    @Test
    @DisplayName("WORKER도_인증되면_이벤트타입_필터목록을_조회할_수_있다")
    void workerCanAccessFilterOptions() throws Exception {
        mockMvc.perform(get("/v1/event-types")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("미인증_요청은_401을_반환한다")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/v1/event-types"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("labels_API_미인증_요청은_401을_반환한다")
    void labelsUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/v1/event-types/labels"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PORTAL_채널_토큰으로_이벤트타입_접근시_차단된다")
    void portalChannelTokenIsForbidden() throws Exception {
        // /v1/** 는 SecurityConfig 가 CHANNEL_INTERNAL authority 를 요구한다. PORTAL 채널 토큰은
        // 인증은 되지만(유효 JWT) CHANNEL_PORTAL 만 보유 → 인가 단계에서 거부(403 FORBIDDEN).
        mockMvc.perform(get("/v1/event-types")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/event-types/labels")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
    }
}
