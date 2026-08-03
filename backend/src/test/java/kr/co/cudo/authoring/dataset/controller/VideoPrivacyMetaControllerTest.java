package kr.co.cudo.authoring.dataset.controller;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.dataset.dto.VideoPrivacyMetaResponse;
import kr.co.cudo.authoring.dataset.service.VideoPrivacyMetaService;
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
 * 영상 개인정보 메타 API 슬라이스 테스트 (인증/인가 + 입력 검증 + ApiResponse 표준 래핑).
 *
 * <p>보안 매트릭스: 미인증 401 · 포털 채널 토큰 403(채널 격리) · 허용값(Y/N) 외 400.
 * 서비스 로직은 {@link MockBean} 으로 격리한다(단위 검증은 {@code VideoPrivacyMetaServiceTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VideoPrivacyMetaControllerTest {

    private static final String URL = "/v1/videos/1/privacy-meta";

    @Autowired private MockMvc mockMvc;
    @MockBean private VideoPrivacyMetaService videoPrivacyMetaService;

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
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(URL)
                        .header("Authorization", "Bearer " + portalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"anonymity\":\"Y\",\"pseudonymity\":\"N\",\"privacyIncluded\":\"N\"}"))
                .andExpect(status().isForbidden());
        verify(videoPrivacyMetaService, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("WORKER_조회시_200_및_프리필_출처가_함께_내려온다")
    void WORKER_조회시_200() throws Exception {
        when(videoPrivacyMetaService.get(eq(1L), any())).thenReturn(new VideoPrivacyMetaResponse(
                1L, "Y", "N", "N",
                VideoPrivacyMetaResponse.SOURCE_DERIVED, VideoPrivacyMetaResponse.SOURCE_DERIVED,
                VideoPrivacyMetaResponse.SOURCE_DERIVED));

        mockMvc.perform(get(URL).header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.anonymity").value("Y"))
                .andExpect(jsonPath("$.data.privacyIncluded").value("N"))
                .andExpect(jsonPath("$.data.anonymitySource").value("DERIVED"));
    }

    @Test
    @DisplayName("REVIEWER_저장시_200_및_MANUAL_출처_응답")
    void REVIEWER_저장시_200() throws Exception {
        when(videoPrivacyMetaService.update(eq(1L), any(), any())).thenReturn(new VideoPrivacyMetaResponse(
                1L, "N", "Y", "Y",
                VideoPrivacyMetaResponse.SOURCE_MANUAL, VideoPrivacyMetaResponse.SOURCE_MANUAL,
                VideoPrivacyMetaResponse.SOURCE_MANUAL));

        mockMvc.perform(put(URL)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"anonymity\":\"N\",\"pseudonymity\":\"Y\",\"privacyIncluded\":\"Y\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pseudonymity").value("Y"))
                .andExpect(jsonPath("$.data.anonymitySource").value("MANUAL"));
        verify(videoPrivacyMetaService).update(eq(1L), any(), any());
    }

    @Test
    @DisplayName("허용값_외_문자열_PUT시_400이고_서비스에_도달하지_않는다")
    void 허용값_외_문자열_PUT시_400() throws Exception {
        mockMvc.perform(put(URL)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"anonymity\":\"<script>alert(1)</script>\","
                                + "\"pseudonymity\":\"N\",\"privacyIncluded\":\"N\"}"))
                .andExpect(status().isBadRequest());
        verify(videoPrivacyMetaService, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("Mass_Assignment_시도_필드는_무시되고_허용필드만_바인딩된다")
    void Mass_Assignment_시도_필드는_무시된다() throws Exception {
        when(videoPrivacyMetaService.update(eq(1L), any(), any())).thenReturn(new VideoPrivacyMetaResponse(
                1L, "Y", "N", "N",
                VideoPrivacyMetaResponse.SOURCE_MANUAL, VideoPrivacyMetaResponse.SOURCE_DERIVED,
                VideoPrivacyMetaResponse.SOURCE_DERIVED));

        // given — 미지 필드(role/prvcTypeCd) 주입 시도 → @JsonIgnoreProperties 로 무시(400 아님)
        mockMvc.perform(put(URL)
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"anonymity\":\"Y\",\"pseudonymity\":null,\"privacyIncluded\":null,"
                                + "\"role\":\"REVIEWER\",\"prvcTypeCd\":\"ANONY\"}"))
                .andExpect(status().isOk());
        verify(videoPrivacyMetaService).update(eq(1L), any(), any());
    }
}
