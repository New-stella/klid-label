package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ControlIngestRequeueBulkRequest;
import kr.co.cudo.authoring.video.dto.ControlIngestRequeueBulkResponse;
import kr.co.cudo.authoring.video.dto.ControlIngestRequeueResponse;
import kr.co.cudo.authoring.video.service.ControlIngestRequeueService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관제 인입 재큐 API 슬라이스 테스트 — 설계 §6-0-1-b.
 *
 * <p>검증 축: <b>인가</b>(미인증 401 · 타 역할 403, CWE-862/863) · <b>입력 검증</b>(상한 위반 400,
 * CWE-770/20) · {@code ApiResponse} 표준 래핑 · 상태코드 매핑. 회수 로직은 {@link MockBean} 으로
 * 격리한다(서비스 단위 테스트는 {@code ControlIngestRequeueServiceTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ControlIngestRequeueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ControlIngestRequeueService requeueService;

    @Value("${authoring.jwt.secret}")
    private String secret;

    @Value("${authoring.jwt.issuer}")
    private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    @Test
    @DisplayName("미인증_단건재큐는_401이다")
    void singleRequeueUnauthenticated401() throws Exception {
        mockMvc.perform(post("/v1/control-ingests/1/requeue"))
                .andExpect(status().isUnauthorized());
        verify(requeueService, never()).requeue(any(), any());
    }

    @Test
    @DisplayName("REVIEWER가_아니면_단건재큐는_403이다")
    void singleRequeueWorker403() throws Exception {
        // given — 인입 행 상태를 되살리는 것은 관리 행위다(WORKER 권한 밖).
        mockMvc.perform(post("/v1/control-ingests/1/requeue")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
        verify(requeueService, never()).requeue(any(), any());
    }

    @Test
    @DisplayName("REVIEWER_단건재큐는_200과_재큐결과를_반환한다")
    void singleRequeueReviewer200() throws Exception {
        // given
        when(requeueService.requeue(eq(1024L), any()))
                .thenReturn(new ControlIngestRequeueResponse(1024L, 1, 7L));

        // when / then
        mockMvc.perform(post("/v1/control-ingests/1024/requeue")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rcptnSn").value(1024))
                .andExpect(jsonPath("$.data.requeued").value(1))
                .andExpect(jsonPath("$.data.remainingFailed").value(7));
    }

    @Test
    @DisplayName("FAILED가_아닌_행_단건재큐는_409다")
    void singleRequeueConflict409() throws Exception {
        // given
        when(requeueService.requeue(eq(1024L), any()))
                .thenThrow(new CustomException(ErrorCode.CONFLICT, "종결(FAILED)된 인입 행만 재큐할 수 있습니다."));

        // when / then
        mockMvc.perform(post("/v1/control-ingests/1024/requeue")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("미인증_일괄재큐는_401이다")
    void bulkRequeueUnauthenticated401() throws Exception {
        mockMvc.perform(post("/v1/control-ingests/requeue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":10}"))
                .andExpect(status().isUnauthorized());
        verify(requeueService, never()).requeueBatch(any(), any());
    }

    @Test
    @DisplayName("REVIEWER가_아니면_일괄재큐는_403이다")
    void bulkRequeueWorker403() throws Exception {
        mockMvc.perform(post("/v1/control-ingests/requeue")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":10}"))
                .andExpect(status().isForbidden());
        verify(requeueService, never()).requeueBatch(any(), any());
    }

    @Test
    @DisplayName("REVIEWER_일괄재큐는_200과_처리건수를_반환한다")
    void bulkRequeueReviewer200() throws Exception {
        // given
        when(requeueService.requeueBatch(any(), any()))
                .thenReturn(new ControlIngestRequeueBulkResponse(100, 100, 42L));

        // when / then
        mockMvc.perform(post("/v1/control-ingests/requeue")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requeued").value(100))
                .andExpect(jsonPath("$.data.limit").value(100))
                .andExpect(jsonPath("$.data.remainingFailed").value(42));
    }

    @Test
    @DisplayName("일괄재큐_상한초과_요청은_400이고_서비스까지_가지_않는다")
    void bulkRequeueOversizedLimit400() throws Exception {
        // given — 무제한 갱신 차단(CWE-770). 검증은 진입점에서 끝낸다.
        int oversized = ControlIngestRequeueBulkRequest.MAX_LIMIT + 1;

        // when / then
        mockMvc.perform(post("/v1/control-ingests/requeue")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":" + oversized + "}"))
                .andExpect(status().isBadRequest());
        verify(requeueService, never()).requeueBatch(any(), any());
    }

    @Test
    @DisplayName("일괄재큐_0이하_상한_요청은_400이다")
    void bulkRequeueNonPositiveLimit400() throws Exception {
        mockMvc.perform(post("/v1/control-ingests/requeue")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":0}"))
                .andExpect(status().isBadRequest());
        verify(requeueService, never()).requeueBatch(any(), any());
    }
}
