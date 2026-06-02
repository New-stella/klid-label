package kr.co.cudo.authoring.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse;
import kr.co.cudo.authoring.video.service.VideoResolutionService;
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

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 해상도 변경 컨트롤러 슬라이스 — 인증/인가 + 입력 검증 + 상태코드 매핑 검증.
 * 서비스 로직(ffmpeg)은 {@link MockBean} 으로 격리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VideoResolutionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private VideoResolutionService videoResolutionService;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    @Test
    @DisplayName("미인증_401")
    void unauthenticated_401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("preset", "P50"));
        mockMvc.perform(post("/v1/videos/1/resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("WORKER_권한_403")
    void worker_403() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("preset", "P50"));
        mockMvc.perform(post("/v1/videos/1/resolution")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("프리셋_enum_외_값_요청_400")
    void invalidPreset_400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("preset", "P99"));
        mockMvc.perform(post("/v1/videos/1/resolution")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("preset_누락_400")
    void missingPreset_400() throws Exception {
        mockMvc.perform(post("/v1/videos/1/resolution")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("REVIEWER_정상_요청시_201_새영상_응답")
    void reviewer_201() throws Exception {
        when(videoResolutionService.changeResolution(eq(1L), any()))
                .thenReturn(new ResolutionChangeResponse(999L, 1920, 1080, 960, 540, 0.5, 3, 2, 1));

        String body = objectMapper.writeValueAsString(Map.of("preset", "P50"));
        mockMvc.perform(post("/v1/videos/1/resolution")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.newRawSn").value(999))
                .andExpect(jsonPath("$.data.targetW").value(960));
    }

    @Test
    @DisplayName("미검수_영상_요청시_409")
    void notApproved_409() throws Exception {
        when(videoResolutionService.changeResolution(eq(2L), any()))
                .thenThrow(new CustomException(ErrorCode.CONFLICT, "검수 완료된 영상만 가능"));

        String body = objectMapper.writeValueAsString(Map.of("preset", "P25"));
        mockMvc.perform(post("/v1/videos/2/resolution")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }
}
