package kr.co.cudo.authoring.dataset.controller;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.dataset.dto.EnvironmentMetaResponse;
import kr.co.cudo.authoring.dataset.service.EnvironmentMetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 — 촬영환경 메타 API 슬라이스 테스트 (인증/인가 + 입력 검증 + ApiResponse 표준 래핑).
 *
 * <p>보안 매트릭스: 미인증 401 · 포털 채널 토큰 403(채널 격리) · 길이 초과/허용값 외 400.
 * 서비스 로직은 {@link MockBean} 으로 격리한다(단위 검증은 {@code EnvironmentMetaServiceTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class EnvironmentMetaControllerTest {

    private static final String URL = "/v1/videos/1/environment-meta";

    @Autowired private MockMvc mockMvc;
    @MockBean private EnvironmentMetaService environmentMetaService;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String workerToken;
    private String reviewerToken;
    private String portalToken;

    @BeforeEach
    void setUp() {
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        portalToken = JwtTestSupport.token(secret, "500", "PORTAL_USER", "PORTAL", issuer, 60);
    }

    @Test
    @DisplayName("미인증_401")
    void 미인증_401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("포털채널_토큰으로_호출시_차단")
    void 포털채널_토큰으로_호출시_차단() throws Exception {
        // 포털 채널 토큰은 내부 API(/v1/videos/**) 채널 격리로 차단된다.
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(URL)
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weather\":\"맑음\",\"timeOfDay\":\"DAY\",\"season\":\"SUMMER\"}"))
                .andExpect(status().isForbidden());
        verify(environmentMetaService, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("WORKER_조회시_200_및_프리필_응답")
    void WORKER_조회시_200() throws Exception {
        when(environmentMetaService.get(eq(1L), any())).thenReturn(new EnvironmentMetaResponse(
                1L, null, "NGT", "WINTER",
                null, EnvironmentMetaResponse.SOURCE_DERIVED, EnvironmentMetaResponse.SOURCE_DERIVED));

        mockMvc.perform(get(URL).header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.weather").doesNotExist())
                .andExpect(jsonPath("$.data.timeOfDay").value("NGT"))
                .andExpect(jsonPath("$.data.seasonSource").value("DERIVED"));
    }

    @Test
    @DisplayName("REVIEWER_저장시_200_및_저장값_응답")
    void REVIEWER_저장시_200() throws Exception {
        // given — REVIEWER 는 배정 제한 없이 전체 영상의 촬영환경을 저장할 수 있다
        when(environmentMetaService.update(eq(1L), any(), any())).thenReturn(new EnvironmentMetaResponse(
                1L, "눈", "NGT", "WINTER",
                EnvironmentMetaResponse.SOURCE_MANUAL, EnvironmentMetaResponse.SOURCE_MANUAL,
                EnvironmentMetaResponse.SOURCE_MANUAL));

        // when / then
        mockMvc.perform(put(URL)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weather\":\"눈\",\"timeOfDay\":\"NGT\",\"season\":\"WINTER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.weather").value("눈"))
                .andExpect(jsonPath("$.data.weatherSource").value("MANUAL"));
        verify(environmentMetaService).update(eq(1L), any(), any());
    }

    @Test
    @DisplayName("길이초과_문자열_PUT시_400")
    void 길이초과_문자열_PUT시_400() throws Exception {
        String tooLong = "맑".repeat(50);
        mockMvc.perform(put(URL)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weather\":\"" + tooLong + "\",\"timeOfDay\":\"DAY\",\"season\":\"SUMMER\"}"))
                .andExpect(status().isBadRequest());
        verify(environmentMetaService, never()).update(any(), any(), any());
    }
}
