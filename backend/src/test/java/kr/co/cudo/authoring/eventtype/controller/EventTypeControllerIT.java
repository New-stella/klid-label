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
                // ★2026-08-05 정정 — 옵션은 <표시명 그룹> 단위다(R3). 구 단언은 같은 카테고리의
                //   상세 코드를 각각 1행으로 기대했는데, 그게 드롭다운 중복(침수 3·교통사고 3·화재 2)의
                //   원인이었다. 이제 그룹 대표코드(= 최소 코드) 1행 + memberCodes 에 전체 코드가 담긴다.
                //   길이를 절대값으로 묶지 않는다: 마스터 행 구성은 시드가 소유하고, 자동등록으로
                //   늘어날 수 있다(같은 JVM 의 다른 테스트가 유형을 등록할 수 있다).
                .andExpect(jsonPath("$.data[?(@.categoryKey=='EV01000101')].label").value("침수(범람)"))
                .andExpect(jsonPath("$.data[?(@.categoryKey=='EV01000101')].memberCodes.length()").value(3))
                // 비대표 코드는 <자기 옵션>을 갖지 않는다(같은 label 이 두 번 나오지 않는다)
                .andExpect(jsonPath("$.data[?(@.categoryKey=='EV01000102')]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.categoryKey=='EV01000103')]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.categoryKey=='EV03000101')].memberCodes.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.categoryKey=='EV02000101')].memberCodes.length()").value(2))
                // 제외 대분류(08)에 속한 유형은 노출되지 않는다
                .andExpect(jsonPath("$.data[?(@.categoryKey=='EV08000101')]").isEmpty())
                // 비수집(CLCT_YN='N') 유형도 노출되지 않는다
                .andExpect(jsonPath("$.data[?(@.categoryKey=='EV07000201')]").isEmpty());
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
