package kr.co.cudo.authoring.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionChangeResponse.CreatedDerivative;
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

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 해상도 변경 컨트롤러 슬라이스 — Phase 3 (파생영상 전환).
 * 인증/인가(REVIEWER only) + 입력 검증 + 신 응답 구조(파생영상 목록) 매핑을 검증한다.
 * 서비스 로직은 {@link MockBean} 으로 격리한다.
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
        mockMvc.perform(post("/v1/videos/1/resolution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("REVIEWER가_아니면_403이다")
    void worker_403() throws Exception {
        mockMvc.perform(post("/v1/videos/1/resolution")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("화이트리스트_외_preset_값_요청_400")
    void invalidPreset_400() throws Exception {
        // presets 목록 원소가 화이트리스트 밖(enum 아님) → Jackson 역직렬화 단계 400
        String body = objectMapper.writeValueAsString(Map.of("presets", List.of("RES_4K")));
        mockMvc.perform(post("/v1/videos/1/resolution")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("presets_미지정_바디시_기본_3종생성_201")
    void missingPresets_defaultsAll_201() throws Exception {
        // presets 는 선택 — 미지정(빈 바디)이면 표준 3종 전체 생성(신 계약: 필수-무시 제거)
        when(videoResolutionService.changeResolution(eq(1L), any(), any()))
                .thenReturn(new ResolutionChangeResponse(List.of(
                        CreatedDerivative.created(501L, "RESL_1080P", 1920, 1080),
                        CreatedDerivative.created(502L, "RESL_720P", 1280, 720),
                        CreatedDerivative.created(503L, "RESL_480P", 854, 480))));

        mockMvc.perform(post("/v1/videos/1/resolution")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.derivatives.length()").value(3));
    }

    @Test
    @DisplayName("응답에_생성된_rawSn목록과_상태가_포함된다")
    void reviewer_201_derivativeList() throws Exception {
        when(videoResolutionService.changeResolution(eq(1L), any(), any()))
                .thenReturn(new ResolutionChangeResponse(List.of(
                        CreatedDerivative.created(501L, "RESL_1080P", 1920, 1080),
                        CreatedDerivative.created(502L, "RESL_720P", 1280, 720),
                        CreatedDerivative.created(503L, "RESL_480P", 854, 480))));

        mockMvc.perform(post("/v1/videos/1/resolution")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.derivatives.length()").value(3))
                .andExpect(jsonPath("$.data.derivatives[0].rawSn").value(501))
                .andExpect(jsonPath("$.data.derivatives[0].goalResCd").value("RESL_1080P"))
                .andExpect(jsonPath("$.data.derivatives[0].status").value("CREATED"))
                // 내부 파일 경로 미노출 (CWE-209)
                .andExpect(jsonPath("$.data.derivatives[0].outputDirPath").doesNotExist())
                .andExpect(jsonPath("$.data.derivatives[0].exportSn").doesNotExist());
    }

    @Test
    @DisplayName("미검수_영상_요청시_409")
    void notApproved_409() throws Exception {
        when(videoResolutionService.changeResolution(eq(2L), any(), any()))
                .thenThrow(new CustomException(ErrorCode.CONFLICT, "검수 완료된 영상만 가능"));

        String body = objectMapper.writeValueAsString(Map.of("presets", List.of("RESL_480P")));
        mockMvc.perform(post("/v1/videos/2/resolution")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("모든_프리셋_생성이_실패하면_500이다")
    void allFailed_500() throws Exception {
        when(videoResolutionService.changeResolution(eq(1L), any(), any()))
                .thenThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "모든 프리셋 생성 실패"));

        mockMvc.perform(post("/v1/videos/1/resolution")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
    }
}
