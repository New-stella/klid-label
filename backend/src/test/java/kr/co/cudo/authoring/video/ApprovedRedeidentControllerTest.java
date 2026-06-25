package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.RedeidentResponse;
import kr.co.cudo.authoring.video.service.ApprovedRedeidentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 / UC018 — ApprovedRedeidentController 슬라이스 테스트.
 *
 * <p>검수완료 영상 재비식별 엔드포인트(POST /v1/videos/{rawSn}/redeident, REVIEWER 전용)의
 * 인증/인가 + ApiResponse 표준 래핑 + 상태코드 매핑 검증. 오케스트레이션 로직은 {@link MockBean}
 * 으로 격리한다(서비스 단위 테스트는 {@code ApprovedRedeidentServiceTest}).
 *
 * <p>컨트롤러와 서비스 모두 {@code @ConditionalOnProperty(kpst.deid.enabled=true)} 라 기본(OFF)
 * 프로파일에서는 등록되지 않는다. 따라서 본 테스트는 {@code kpst.deid.enabled=true} + http base-url
 * (ca-cert 없이 기동, {@code KpstHttpBootIntegrationTest} 패턴) 로 컨트롤러 빈을 활성화하고,
 * 오케스트레이션 서비스는 {@link MockBean} 으로 격리한다(서비스 단위 테스트는 {@code ApprovedRedeidentServiceTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "kpst.deid.enabled=true",
        "kpst.deid.base-url=http://10.20.30.40:9989",
        "kpst.deid.ca-cert-path=",
        // 폴링 잡 트리거가 슬라이스 검증 중 자동 발화하지 않도록 충분히 늦춘다.
        "kpst.deid.poll-interval-sec=86400"
})
class ApprovedRedeidentControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ApprovedRedeidentService approvedRedeidentService;

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
        mockMvc.perform(post("/v1/videos/1/redeident"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("REVIEWER가_아니면_403")
    void worker_403() throws Exception {
        mockMvc.perform(post("/v1/videos/1/redeident")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REVIEWER_요청시_202_ACCEPTED_응답")
    void reviewer_202_accepted() throws Exception {
        when(approvedRedeidentService.requestRedeident(eq(1L), any()))
                .thenReturn(RedeidentResponse.accepted(1L, 55L, 101L));

        mockMvc.perform(post("/v1/videos/1/redeident")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rawSn").value(1))
                .andExpect(jsonPath("$.data.procLogSn").value(55))
                .andExpect(jsonPath("$.data.kpstPrjId").value(101))
                .andExpect(jsonPath("$.data.status").value(RedeidentResponse.STATUS_ACCEPTED));
    }

    @Test
    @DisplayName("존재하지_않는_rawSn_404")
    void notFound_404() throws Exception {
        when(approvedRedeidentService.requestRedeident(eq(9999999L), any()))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        mockMvc.perform(post("/v1/videos/9999999/redeident")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("APPROVED아닌_영상_409")
    void notApproved_409() throws Exception {
        when(approvedRedeidentService.requestRedeident(eq(2L), any()))
                .thenThrow(new CustomException(ErrorCode.CONFLICT, "검수완료 영상만 재비식별 가능합니다."));

        mockMvc.perform(post("/v1/videos/2/redeident")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }
}
