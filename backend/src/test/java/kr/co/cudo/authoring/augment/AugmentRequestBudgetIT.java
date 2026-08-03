package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.integration.AugmentQueryResult;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.augment.integration.dto.GenAiCancelResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobStatusResponse;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.webhook.runner.AsyncAugmentFrameRunner;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DEV_FIX HIGH-1 — <b>요청 1회의 외부 호출 시간 예산</b> 검증 (CWE-770/400).
 *
 * <h3>왜 별도 클래스인가</h3>
 * <p>예산을 실제로 소진시키려면 벤더 응답을 초 단위로 지연시켜야 하는데, 운영 기본 예산(20초)으로
 * 그러면 테스트가 수십 초 걸린다. 그래서 <b>예산 프로퍼티만 줄인 컨텍스트</b>에서 검증한다 —
 * 프로덕션 경로(같은 코드·같은 배선)는 그대로 타고 값만 다르다. 프로퍼티가 다르면 Spring 컨텍스트
 * 캐시 키가 달라지므로 {@code AugmentProgressCancelIT} 에 섞지 않고 분리했다(그쪽 컨텍스트 공유 보존).
 *
 * <h3>단언은 "시간" 이 아니라 "호출 개시 여부" 로 한다</h3>
 * <p>경과 시간만 재면 CI 부하에 따라 흔들린다. 예산 로직의 계약은 <b>남은 예산이 없으면 다음 외부
 * 호출을 개시하지 않는다</b> 이므로, <b>호출 횟수</b>를 1차 단언으로 삼고 경과 시간은 넉넉한 상한으로만
 * 보조 확인한다.
 */
@SpringBootTest(properties = {
        // 예산 2초 — 지연 1.2초짜리 응답 1건이면 잔여가 최소 슬라이스(1초) 미만이 되어 다음 호출이 끊긴다.
        "authoring.augment.external.request-budget-seconds=2"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AugmentRequestBudgetIT {

    /** 벤더 응답 지연 — 1회 호출로 예산(2초) 잔여가 최소 슬라이스(1초) 미만이 되게 하는 값. */
    private static final Duration VENDOR_DELAY = Duration.ofMillis(1_200);

    /** 예산이 없었다면 (청크 수 × 지연) 만큼 걸렸을 것이다. 그 절반도 안 되는 상한으로 확인한다. */
    private static final long ELAPSED_CEILING_MS = 4_000L;

    private static final int CHUNKS = 5;

    private static final AtomicInteger SRC_SEQ = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugJobRepository jobRepository;

    @MockBean private ExternalAugmentClient externalClient;
    @MockBean private AsyncAugmentFrameRunner asyncAugmentFrameRunner;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    @Test
    @DisplayName("폴링_1회의_외부호출_총시간이_요청예산을_넘지_않는다")
    void progressStopsIssuingCallsWhenBudgetIsExhausted() throws Exception {
        LsDataAug aug = seedAugWithChunks("BUDGETPRG");
        // 청크마다 1.2초씩 걸리는 벤더 — 예산이 없으면 5 × 1.2 = 6초를 스레드가 붙잡는다.
        when(externalClient.fetchJobStatus(any())).thenAnswer(invocation ->
                Mono.delay(VENDOR_DELAY, Schedulers.parallel())
                        .map(ignored -> AugmentQueryResult.of(
                                statusOf(invocation.getArgument(0), "RUNNING", 10))));

        long startedAt = System.nanoTime();
        mockMvc.perform(get("/v1/augments/{id}/progress", aug.getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        // 계약: 잔여 예산이 최소 슬라이스 미만이면 다음 호출을 <개시하지 않는다>.
        verify(externalClient, atMost(2)).fetchJobStatus(any());
        assertThat(elapsedMs)
                .as("예산이 없으면 %d개 청크 × %dms 를 그대로 소비한다", CHUNKS, VENDOR_DELAY.toMillis())
                .isLessThan(ELAPSED_CEILING_MS);
    }

    @Test
    @DisplayName("취소도_요청예산_상한을_지킨다")
    void cancelStopsIssuingCallsWhenBudgetIsExhausted() throws Exception {
        LsDataAug aug = seedAugWithChunks("BUDGETCXL");
        when(externalClient.cancelJob(any())).thenAnswer(invocation ->
                Mono.delay(VENDOR_DELAY, Schedulers.parallel())
                        .map(ignored -> AugmentQueryResult.of(new GenAiCancelResponse(
                                "req", "job-any", "CANCELED", "2026-07-31T00:00:00Z"))));

        long startedAt = System.nanoTime();
        mockMvc.perform(post("/v1/augments/{id}/cancel", aug.getDataAugSn())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        verify(externalClient, atMost(2)).cancelJob(any());
        assertThat(elapsedMs).isLessThan(ELAPSED_CEILING_MS);

        // 예산 소진으로 보내지 못한 청크는 <숨기지 않는다> — 비종결로 남아 만료 스윕이 회수한다.
        assertThat(jobRepository.findByDataAugSnOrderByJobSeqAsc(aug.getDataAugSn()).stream()
                .filter(job -> !job.isTerminal()).count())
                .as("전달하지 못한 청크는 로컬 취소 종결 대상이 아니다").isGreaterThan(0);
        assertThat(augRepository.findById(aug.getDataAugSn()).orElseThrow().getAugProcSttsCd())
                .as("증강 자체는 종결된다 — PENDING 영구 고착 금지")
                .isEqualTo(LsDataAug.STTS_CANCELED);
    }

    private LsDataAug seedAugWithChunks(String suffix) {
        LsDataAug aug = augRepository.saveAndFlush(LsDataAug.createRequested(
                9_200_000L + SRC_SEQ.incrementAndGet(), LsDataAug.AUG_WINTER, "1",
                "AUG-" + suffix + "-" + java.util.UUID.randomUUID(), null, "{\"time\":\"NIGHT\"}"));
        for (int seq = 1; seq <= CHUNKS; seq++) {
            LsDataAugJob job = LsDataAugJob.createIssued(aug.getDataAugSn(), seq,
                    "AUG-" + suffix + "-" + aug.getDataAugSn() + "-" + seq, 1);
            job.markAccepted("job-" + suffix.toLowerCase() + "-" + aug.getDataAugSn() + "-" + seq);
            jobRepository.saveAndFlush(job);
        }
        return aug;
    }

    private static GenAiJobStatusResponse statusOf(String jobId, String status, Integer progress) {
        return new GenAiJobStatusResponse("req", jobId, status, progress, "step",
                "2026-07-31T00:00:00Z", null, null, null, null, "2026-07-31T00:00:00Z");
    }
}
