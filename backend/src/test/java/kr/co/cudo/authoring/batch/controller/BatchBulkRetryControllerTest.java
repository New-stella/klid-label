package kr.co.cudo.authoring.batch.controller;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
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

import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배치 일괄 재시작 E2E (MockMvc 통합). [@design API-199]
 *
 * <p>인가 · 상한/빈 목록 400 · <b>부분 성공 200</b>(되는 것만 재기동, 거부분은 건별 사유)을 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BatchBulkRetryControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;

    @MockBean private BatchOrchestrator orchestrator;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    private Long saveVideo(boolean failed) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-BK-" + System.nanoTime(), "CCTV-BK", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        if (failed) {
            raw.markBatchFailed();
        }
        return videoRepository.save(raw).getRawSn();
    }

    private static String body(Object... rawSns) {
        String joined = java.util.Arrays.stream(rawSns).map(String::valueOf)
                .collect(Collectors.joining(","));
        return "{\"rawSns\":[" + joined + "]}";
    }

    @Test
    @DisplayName("일괄재시작API_WORKER는_403")
    void workerForbidden() throws Exception {
        Long rawSn = saveVideo(true);

        mockMvc.perform(post("/v1/videos/batch/retry")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(rawSn)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("일괄재시작API_인증없음_401")
    void unauthenticated() throws Exception {
        mockMvc.perform(post("/v1/videos/batch/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("일괄재시작API_빈_목록은_400")
    void emptyListBadRequest() throws Exception {
        mockMvc.perform(post("/v1/videos/batch/retry")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawSns\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★일괄재시작API_1_미만_식별자는_400_단건_경로와_같은_축이다")
    void nonPositiveRawSnBadRequest() throws Exception {
        // 단건 경로는 @Min(1) 로 0·음수를 400 으로 막는데 일괄만 빠져 있으면 같은 값이 경로에 따라
        //   400 과 "건별 실패"로 갈린다. 식별자가 될 수 없는 값은 접수 대상이 아니므로 요청 전체가 400 이다.
        mockMvc.perform(post("/v1/videos/batch/retry")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(0)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/v1/videos/batch/retry")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, -5)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("일괄재시작API_상한_100건_초과는_400")
    void overLimitBadRequest() throws Exception {
        String tooMany = IntStream.rangeClosed(1, 101).mapToObj(String::valueOf)
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/v1/videos/batch/retry")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawSns\":[" + tooMany + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★일괄재시작API_되는것만_재기동하고_거부분은_건별사유로_200")
    void partialSuccessReturns200() throws Exception {
        // given — 하나는 FAILED(재기동 가능), 하나는 PENDING(409 거부 대상), 하나는 존재하지 않음(404 대상).
        Long failedSn = saveVideo(true);
        Long pendingSn = saveVideo(false);
        long missingSn = 999999999L;
        when(orchestrator.processWithHeldStageClaim(anyLong())).thenReturn(BatchStage.COMPLETED);

        // when / then — 단건이면 409/404 였을 건들이 목록 전체를 실패시키지 않는다.
        mockMvc.perform(post("/v1/videos/batch/retry")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(failedSn, pendingSn, missingSn)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.successCount").value(1))
                .andExpect(jsonPath("$.data.failureCount").value(2))
                .andExpect(jsonPath("$.data.results.length()").value(3))
                .andExpect(jsonPath("$.data.results[0].rawSn").value(failedSn))
                .andExpect(jsonPath("$.data.results[0].success").value(true))
                .andExpect(jsonPath("$.data.results[1].success").value(false))
                .andExpect(jsonPath("$.data.results[2].success").value(false));
    }

    @Test
    @DisplayName("★일괄재시작API_한건도_성공하지_못해도_200이다")
    void allFailedStill200() throws Exception {
        // given — 전부 PENDING 이라 클레임에 실패한다.
        Long a = saveVideo(false);
        Long b = saveVideo(false);

        // when / then — HTTP 는 "요청을 처리했는가"만 말하고 성패는 results 가 말한다.
        mockMvc.perform(post("/v1/videos/batch/retry")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(a, b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.successCount").value(0))
                .andExpect(jsonPath("$.data.failureCount").value(2));
    }

    @Test
    @DisplayName("일괄재시작API_실패사유에_스택트레이스나_DB제약명이_없다")
    void reasonHasNoInternals() throws Exception {
        Long pendingSn = saveVideo(false);

        mockMvc.perform(post("/v1/videos/batch/retry")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(pendingSn)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].reason").isNotEmpty())
                .andExpect(jsonPath("$.data.results[0].reason",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))))
                .andExpect(jsonPath("$.data.results[0].reason",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("constraint"))));
    }
}
