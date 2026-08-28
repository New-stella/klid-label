package kr.co.cudo.authoring.augment;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.entity.LsDataAugJobFile;
import kr.co.cudo.authoring.augment.integration.AugmentCancelCommand;
import kr.co.cudo.authoring.augment.integration.AugmentQueryResult;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.augment.integration.dto.GenAiCancelResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobResultsResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobStatusResponse;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobFileRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.augment.service.AugmentProgressService;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 — FE 대상 내부 API(진행률·취소·결과회수) 통합 검증.
 *
 * <h3>왜 통합 테스트인가</h3>
 * <p>이 Phase 의 위험은 대부분 <b>경계</b>에 있다: 트랜잭션 경계(클레임/확정 분리), 잠금 순서
 * (증강 행 FOR UPDATE), 인가 게이트(@PreAuthorize + 서비스 이중 검증), 외부 호출 횟수. 단위 목킹으로는
 * "실제로 몇 번 나갔는가 / 실제 DB 가 어떤 상태인가" 를 고정할 수 없다.
 *
 * <p>외부 벤더({@link ExternalAugmentClient})와 비동기 프레임 러너만 목킹한다 — 전자는 네트워크,
 * 후자는 파일 I/O 라 테스트 대상이 아니다. {@link VideoArtifactRootResolver} 도 목킹해 허용 루트
 * 설정(환경 의존)이 아니라 <b>"회수 경로가 검증기를 실제로 호출하고 거부를 따르는가"</b> 를 본다(S15).
 *
 * <p>공유 Testcontainers DB 오염 방지 — 시드 식별자는 {@code AUGPRG-} 접두 + 시드한 PK 로만 단언한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AugmentProgressCancelIT {

    private static final String EXTERNAL_JOB_1 = "job-augprg-1";
    private static final String EXTERNAL_JOB_2 = "job-augprg-2";

    @Autowired private MockMvc mockMvc;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugJobRepository jobRepository;
    @Autowired private LsDataAugJobFileRepository jobFileRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private VideoRepository videoRepository;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
    /**
     * 회수 부작용의 역할 게이트는 <b>서비스 안</b>에 있다 — 관리자 통과를 확인하려면 그 자리를
     * 직접 불러야 한다. HTTP 경로로 부르면 역할이 인계 토큰이 아니라 역할 저장소에서 해석되어
     * 관리자 행을 심어야 하고, 그러면 「관리자 0명일 때만 열리는」 부트스트랩 창구가 이 시험이
     * 도는 동안 닫힌다.
     */
    @Autowired private AugmentProgressService progressService;

    @MockBean private ExternalAugmentClient externalClient;
    /** 프레임 재추출(ffmpeg·파일 I/O)은 이 Phase 의 검증 대상이 아니다. */
    @MockBean private AsyncAugmentFrameRunner asyncAugmentFrameRunner;
    /** 허용 루트 판정 — 환경 설정이 아니라 "호출되는가/거부를 따르는가" 를 본다. */
    @MockBean private VideoArtifactRootResolver artifactRootResolver;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
        // 서킷은 테스트 간 상태를 공유하므로 매번 초기화한다(다른 테스트의 open 상태가 전이되지 않게).
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    // ============================================================
    // 시드 헬퍼
    // ============================================================

    private LsDataAug seedAug(String status) {
        LsDataAug aug = LsDataAug.createRequested(seedSrcSn(), LsDataAug.AUG_WINTER, "1",
                "AUG-AUGPRG-" + java.util.UUID.randomUUID(), null, "{\"time\":\"NIGHT\"}");
        aug = augRepository.save(aug);
        if (LsDataAug.STTS_ACCEPTED.equals(status) || LsDataAug.STTS_REJECTED.equals(status)) {
            aug.applyGenerationResult(status);
            aug = augRepository.saveAndFlush(aug);
        }
        return aug;
    }

    /** 대표프레임 SRC_SN — 실제 프레임이 필요 없는 케이스는 시퀀스가 닿지 않는 대역을 쓴다. */
    private long seedSrcSn() {
        return 9_100_000L + SRC_SEQ.incrementAndGet();
    }

    private static final AtomicInteger SRC_SEQ = new AtomicInteger();

    private LsDataAugJob seedJob(Long dataAugSn, int jobSeq, int totalCount, String externalJobId) {
        LsDataAugJob job = LsDataAugJob.createIssued(dataAugSn, jobSeq,
                "AUG-AUGPRG-" + dataAugSn + "-" + jobSeq, totalCount);
        if (externalJobId != null) {
            job.markAccepted(externalJobId);
        }
        return jobRepository.saveAndFlush(job);
    }

    private LsDataAugJob seedTerminalJob(Long dataAugSn, int jobSeq, int totalCount, String status) {
        LsDataAugJob job = LsDataAugJob.createIssued(dataAugSn, jobSeq,
                "AUG-AUGPRG-" + dataAugSn + "-" + jobSeq, totalCount);
        if (LsDataAugJob.STTS_SUCCEEDED.equals(status)) {
            job.markSucceeded("job-terminal-" + dataAugSn + "-" + jobSeq);
        } else {
            job.markFailed("job-terminal-" + dataAugSn + "-" + jobSeq, "X", "seed");
        }
        return jobRepository.saveAndFlush(job);
    }

    private static GenAiJobStatusResponse statusOf(String jobId, String status, Integer progress) {
        return new GenAiJobStatusResponse("req", jobId, status, progress, "step",
                "2026-07-31T00:00:00Z", null, null, null, null, "2026-07-31T00:00:00Z");
    }

    private static GenAiCancelResponse canceledOf(String jobId) {
        return new GenAiCancelResponse("req", jobId, "CANCELED", "2026-07-31T00:00:00Z");
    }

    private void stubStatus(String jobId, String status, Integer progress) {
        when(externalClient.fetchJobStatus(jobId))
                .thenReturn(Mono.just(AugmentQueryResult.of(statusOf(jobId, status, progress))));
    }

    /**
     * 모든 job_id 를 <b>정상 처리중(RUNNING)</b> 으로 응답한다 — 자체 상한 시나리오 전용.
     *
     * <p>스텁하지 않은 job_id 는 목이 {@code null} 을 돌려줘 NPE → {@code TRANSIENT_ERROR} 로 degrade
     * 되므로, "우리 자체 상한" 과 "벤더 장애" 를 구분하는 검증이 성립하지 않는다. 이후의 개별
     * {@code when(...)} 스텁이 해당 id 에 대해 이 기본값을 덮어쓴다.
     */
    private void stubRunningForAnyJob() {
        when(externalClient.fetchJobStatus(anyString())).thenAnswer(invocation ->
                Mono.just(AugmentQueryResult.of(
                        statusOf(invocation.getArgument(0), "RUNNING", 10))));
    }

    // ============================================================
    // 진행상태 조회
    // ============================================================

    @Test
    @DisplayName("진행률은_청크_job_들의_파일수_가중평균이다")
    void progressIsWeightedAverageOfChunkFileCounts() throws Exception {
        LsDataAug aug = seedAug(LsDataAug.STTS_PENDING);
        seedTerminalJob(aug.getDataAugSn(), 1, 100, LsDataAugJob.STTS_SUCCEEDED);
        seedJob(aug.getDataAugSn(), 2, 1, EXTERNAL_JOB_2);
        stubStatus(EXTERNAL_JOB_2, "RUNNING", 0);

        mockMvc.perform(get("/v1/augments/{id}/progress", aug.getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // (100×100 + 1×0)/101 = 99 — min 이면 0, 단순평균이면 50 이다
                .andExpect(jsonPath("$.data.progress").value(99))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.unavailableReason").doesNotExist())
                .andExpect(jsonPath("$.data.totalJobCount").value(2))
                .andExpect(jsonPath("$.data.terminalJobCount").value(1))
                .andExpect(jsonPath("$.data.cancelable").value(true))
                .andExpect(jsonPath("$.data.nextPollAfterMs").value(3000));
    }

    @Test
    @DisplayName("externalJobId_가_null_이면_에러가_아니라_ACK대기중_으로_응답한다")
    void missingExternalJobIdDegradesToAwaitingAck() throws Exception {
        LsDataAug aug = seedAug(LsDataAug.STTS_PENDING);
        seedJob(aug.getDataAugSn(), 1, 10, null); // 202 ACK 유실

        mockMvc.perform(get("/v1/augments/{id}/progress", aug.getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress").doesNotExist())
                .andExpect(jsonPath("$.data.unavailableReason").value("AWAITING_ACK"));

        // 클라이언트의 requireValidJobId 예외에 기대지 않는다 — 외부 호출 자체를 개시하지 않아야 한다.
        verify(externalClient, never()).fetchJobStatus(any());
    }

    @Test
    @DisplayName("서킷이_열려도_500_이_아니라_progress_null_로_degrade_한다")
    void circuitOpenDegradesInsteadOfFailing() throws Exception {
        LsDataAug aug = seedAug(LsDataAug.STTS_PENDING);
        seedJob(aug.getDataAugSn(), 1, 10, EXTERNAL_JOB_1);
        when(externalClient.fetchJobStatus(EXTERNAL_JOB_1)).thenReturn(Mono.error(
                CallNotPermittedException.createCallNotPermittedException(
                        circuitBreakerRegistry.circuitBreaker("augmentQuery"))));

        mockMvc.perform(get("/v1/augments/{id}/progress", aug.getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress").doesNotExist())
                .andExpect(jsonPath("$.data.unavailableReason").value("TRANSIENT_ERROR"));
    }

    @Test
    @DisplayName("noop_모드와_일시장애가_서로_다른_사유로_구분된다")
    void noopIsDistinguishedFromTransientFailure() throws Exception {
        LsDataAug noopAug = seedAug(LsDataAug.STTS_PENDING);
        seedJob(noopAug.getDataAugSn(), 1, 10, EXTERNAL_JOB_1);
        when(externalClient.fetchJobStatus(EXTERNAL_JOB_1)).thenReturn(Mono.just(
                AugmentQueryResult.skipped(AugmentQueryResult.SkipReason.EXTERNAL_DISABLED)));

        LsDataAug errorAug = seedAug(LsDataAug.STTS_PENDING);
        seedJob(errorAug.getDataAugSn(), 1, 10, EXTERNAL_JOB_2);
        when(externalClient.fetchJobStatus(EXTERNAL_JOB_2))
                .thenReturn(Mono.error(new IllegalStateException("timeout")));

        mockMvc.perform(get("/v1/augments/{id}/progress", noopAug.getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unavailableReason").value("NOOP"))
                // 미연동은 폴링해도 값이 생기지 않는다 — 폴링 힌트로도 구분된다
                .andExpect(jsonPath("$.data.nextPollAfterMs").value(30000));

        mockMvc.perform(get("/v1/augments/{id}/progress", errorAug.getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unavailableReason").value("TRANSIENT_ERROR"))
                .andExpect(jsonPath("$.data.nextPollAfterMs").value(10000));
    }

    @Test
    @DisplayName("progress_동시_다발_호출에도_reactor_스레드가_고갈되지_않는다")
    void concurrentProgressCallsDoNotStarveReactorThreads() throws Exception {
        LsDataAug aug = seedAug(LsDataAug.STTS_PENDING);
        seedJob(aug.getDataAugSn(), 1, 10, EXTERNAL_JOB_1);
        // 응답을 reactor 공용 풀에서 지연 방출한다 — 요청 스레드가 아니라 reactor 스레드를 블로킹하는
        // 구현이었다면(예: publishOn 후 block, 또는 reactor 스레드 위에서의 block) 여기서 굶어 죽는다.
        when(externalClient.fetchJobStatus(EXTERNAL_JOB_1)).thenAnswer(invocation ->
                Mono.delay(Duration.ofMillis(100), Schedulers.parallel())
                        .map(ignored -> AugmentQueryResult.of(statusOf(EXTERNAL_JOB_1, "RUNNING", 40))));

        int concurrency = 16;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                calls.add(() -> mockMvc.perform(get("/v1/augments/{id}/progress", aug.getDataAugSn())
                                .header("Authorization", "Bearer " + reviewerToken))
                        .andReturn().getResponse().getStatus());
            }
            List<Future<Integer>> futures = pool.invokeAll(calls, 60, TimeUnit.SECONDS);
            for (Future<Integer> future : futures) {
                assertThat(future.isCancelled())
                        .as("동시 폴링이 60초 안에 끝나지 않으면 스레드 고갈이다").isFalse();
                assertThat(future.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("해상도파생_id_로_progress_호출시_400")
    void resolutionDerivativeProgressIsRejected() throws Exception {
        LsDataAug resl = augRepository.saveAndFlush(
                LsDataAug.createResolutionAccepted(seedSrcSn(), LsDataAug.AUG_RESL_720P, "1"));

        mockMvc.perform(get("/v1/augments/{id}/progress", resl.getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
        verify(externalClient, never()).fetchJobStatus(any());
    }

    @Test
    @DisplayName("없는_id_는_404_이고_존재여부를_흘리지_않는다")
    void unknownIdIsNotFoundForBothRoles() throws Exception {
        // REVIEWER / WORKER 모두 404 — 403 과 섞으면 응답 코드가 존재 여부 오라클이 된다(CWE-209).
        mockMvc.perform(get("/v1/augments/{id}/progress", 9_999_999_999L)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/augments/{id}/progress", 9_999_999_999L)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/augments/{id}/cancel", 9_999_999_999L)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // 취소
    // ============================================================

    @Test
    @DisplayName("취소_성공하면_증강이_CANCELED_로_종결된다")
    void cancelTerminatesAugmentAsCanceled() throws Exception {
        LsDataAug aug = seedAug(LsDataAug.STTS_PENDING);
        LsDataAugJob job = seedJob(aug.getDataAugSn(), 1, 10, EXTERNAL_JOB_1);
        when(externalClient.cancelJob(any()))
                .thenReturn(Mono.just(AugmentQueryResult.of(canceledOf(EXTERNAL_JOB_1))));

        mockMvc.perform(post("/v1/augments/{id}/cancel", aug.getDataAugSn())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"오조작\"}")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.canceled").value(true))
                .andExpect(jsonPath("$.data.fullyCanceled").value(true))
                .andExpect(jsonPath("$.data.canceledJobCount").value(1));

        assertThat(augRepository.findById(aug.getDataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_CANCELED);
        assertThat(jobRepository.findById(job.getAugJobSn()).orElseThrow().getJobSttsCd())
                .as("aug 만 바꾸고 job 을 두면 늦은 웹훅이 SUCCEEDED 로 덮어써 두 축이 어긋난다(S12)")
                .isEqualTo(LsDataAugJob.STTS_CANCELED);
    }

    @Test
    @DisplayName("취소_후_만료스윕_대상에서_빠진다")
    void canceledAugmentIsExcludedFromExpirySweep() throws Exception {
        LsDataAug aug = seedAug(LsDataAug.STTS_PENDING);
        seedJob(aug.getDataAugSn(), 1, 10, EXTERNAL_JOB_1);
        when(externalClient.cancelJob(any()))
                .thenReturn(Mono.just(AugmentQueryResult.of(canceledOf(EXTERNAL_JOB_1))));

        mockMvc.perform(post("/v1/augments/{id}/cancel", aug.getDataAugSn())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        LocalDateTime future = LocalDateTime.now().plusDays(1);
        // ① 고아 PENDING 회수 후보 — CANCELED 는 PENDING 이 아니므로 잡히지 않는다
        assertThat(augRepository.findOrphanPendingAugSns(LsDataAug.STTS_PENDING,
                List.of(LsDataAug.AUG_WINTER, LsDataAug.AUG_NIGHT, LsDataAug.AUG_RAIN), future, 100))
                .doesNotContain(aug.getDataAugSn());
        // ② 비종결 job 회수 후보 — CANCELED job 은 terminal 이라 잡히지 않는다
        assertThat(jobRepository.findExpirableAnchors(future, 500).stream()
                .map(row -> (Long) row[1]).toList())
                .doesNotContain(aug.getDataAugSn());
    }

    @Test
    @DisplayName("청크가_여러개면_미종결_job_전부에_취소를_보낸다")
    void cancelIsSentToEveryNonTerminalChunk() throws Exception {
        LsDataAug aug = seedAug(LsDataAug.STTS_PENDING);
        seedJob(aug.getDataAugSn(), 1, 100, EXTERNAL_JOB_1);
        seedTerminalJob(aug.getDataAugSn(), 2, 100, LsDataAugJob.STTS_SUCCEEDED); // 이미 종결 — 취소 대상 아님
        seedJob(aug.getDataAugSn(), 3, 50, EXTERNAL_JOB_2);
        when(externalClient.cancelJob(any())).thenAnswer(invocation -> {
            AugmentCancelCommand command = invocation.getArgument(0);
            return Mono.just(AugmentQueryResult.of(canceledOf(command.externalJobId())));
        });

        mockMvc.perform(post("/v1/augments/{id}/cancel", aug.getDataAugSn())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetJobCount").value(2))
                .andExpect(jsonPath("$.data.canceledJobCount").value(2))
                .andExpect(jsonPath("$.data.fullyCanceled").value(true));

        // 종결 청크는 건드리지 않는다 — 성공한 청크를 취소로 덮으면 산출물이 없는 것처럼 보인다.
        verify(externalClient, times(2)).cancelJob(any());
        List<LsDataAugJob> jobs = jobRepository.findByDataAugSnOrderByJobSeqAsc(aug.getDataAugSn());
        assertThat(jobs.get(1).getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
    }

    @Test
    @DisplayName("일부_job_취소가_실패하면_부분성공을_명시적으로_알린다")
    void partialCancelIsReportedExplicitly() throws Exception {
        LsDataAug aug = seedAug(LsDataAug.STTS_PENDING);
        seedJob(aug.getDataAugSn(), 1, 100, EXTERNAL_JOB_1);
        LsDataAugJob failing = seedJob(aug.getDataAugSn(), 2, 100, EXTERNAL_JOB_2);
        when(externalClient.cancelJob(any())).thenAnswer(invocation -> {
            AugmentCancelCommand command = invocation.getArgument(0);
            if (EXTERNAL_JOB_2.equals(command.externalJobId())) {
                return Mono.error(new IllegalStateException("network"));
            }
            return Mono.just(AugmentQueryResult.of(canceledOf(command.externalJobId())));
        });

        mockMvc.perform(post("/v1/augments/{id}/cancel", aug.getDataAugSn())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canceled").value(true))
                .andExpect(jsonPath("$.data.fullyCanceled").value(false))
                .andExpect(jsonPath("$.data.failedJobSeqs[0]").value(2))
                .andExpect(jsonPath("$.data.canceledJobCount").value(1));

        // 증강은 종결됐으므로 롤업이 영구 대기하지 않는다. 취소 못 한 청크만 비종결로 남아
        // 만료 스윕이 회수한다(무한 대기 없음).
        assertThat(augRepository.findById(aug.getDataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_CANCELED);
        assertThat(jobRepository.findById(failing.getAugJobSn()).orElseThrow().isTerminal()).isFalse();
    }

    @Test
    @DisplayName("취소_동시_2회_요청시_하나만_외부호출하고_다른_하나는_멱등_200")
    void concurrentCancelCallsExternalOnceAndReturnsIdempotent200() throws Exception {
        LsDataAug aug = seedAug(LsDataAug.STTS_PENDING);
        seedJob(aug.getDataAugSn(), 1, 10, EXTERNAL_JOB_1);
        when(externalClient.cancelJob(any()))
                .thenReturn(Mono.just(AugmentQueryResult.of(canceledOf(EXTERNAL_JOB_1))));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> call = () -> mockMvc.perform(
                            post("/v1/augments/{id}/cancel", aug.getDataAugSn())
                                    .contentType(MediaType.APPLICATION_JSON).content("{}")
                                    .header("Authorization", "Bearer " + reviewerToken))
                    // 409 가 사용자에게 노출되면 안 된다(S4).
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<Future<String>> results = pool.invokeAll(List.of(call, call), 60, TimeUnit.SECONDS);
            long claimed = results.stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .filter(body -> body.contains("\"canceled\":true"))
                    .count();
            assertThat(claimed).as("클레임(PENDING→CANCELED)에 성공하는 요청은 정확히 하나여야 한다")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        // 외부 취소는 클레임 성공 측에서만 나간다 — 두 번 나가면 벤더가 409 를 준다.
        verify(externalClient, times(1)).cancelJob(any());
    }

    /**
     * DEV_FIX MED-6b — <b>CANCELED 증강에 대한 웹훅 인계</b> 커버리지.
     *
     * <p>구 테스트는 취소가 <b>성공</b>한 job(=CANCELED, terminal)에 폴링을 걸어, 진행상태 조회가
     * {@code pending.isEmpty()} 조기반환에 걸렸다 — 회수 경로에 <b>진입조차 하지 않아</b>
     * {@code fetchJobResults} 스텁이 한 번도 호출되지 않았고 남은 단언은 앞 테스트와 중복이었다.
     *
     * <p>실제로 그 경로를 타려면 <b>aug=CANCELED 인데 job 은 비종결</b>이어야 한다 — 외부 취소가
     * 전달되지 않은 청크(부분 취소)가 정확히 그 상태다. 그때 벤더가 마저 성공시키면 회수가 job 을
     * SUCCEEDED 로 인계하고 롤업이 돌지만, 증강 행이 이미 non-PENDING 이라
     * {@code AugmentResultService} 의 앵커가 결과를 <b>폐기</b>한다(파생영상 0건).
     */
    @Test
    @DisplayName("취소된_증강에_늦게_도착한_SUCCEEDED_는_회수경로를_타고도_폐기된다")
    void lateSuccessIsDiscardedForCanceledAugment() throws Exception {
        Fixture fx = seedRecoverableAugment("LATECANCEL");
        // 외부 취소를 <실패>시켜 aug=CANCELED · job=비종결 상태를 만든다(부분 취소).
        when(externalClient.cancelJob(any())).thenReturn(Mono.error(new IllegalStateException("network")));

        mockMvc.perform(post("/v1/augments/{id}/cancel", fx.aug().getDataAugSn())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullyCanceled").value(false));
        assertThat(jobRepository.findById(fx.job().getAugJobSn()).orElseThrow().isTerminal())
                .as("이 시나리오의 전제 — job 이 비종결이라야 회수 경로에 진입한다").isFalse();

        // 늦게 도착한 SUCCEEDED 를 진행상태 조회 회수 경로로 재현한다(웹훅과 같은 인계 규칙을 탄다).
        stubRecoverableSuccess(fx, "LATECANCEL");

        mockMvc.perform(get("/v1/augments/{id}/progress", fx.aug().getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        // ① 회수 경로에 실제로 진입했다(구 테스트는 여기서 0회였다).
        verify(externalClient, times(1)).fetchJobResults(fx.externalJobId());
        // ② job 은 인계돼 종결되지만
        assertThat(jobRepository.findById(fx.job().getAugJobSn()).orElseThrow().getJobSttsCd())
                .isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
        // ③ 증강은 CANCELED 로 유지되고 결과는 폐기된다 — 파생영상이 생기면 취소가 무의미해진다.
        assertThat(augRepository.findById(fx.aug().getDataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_CANCELED);
        assertThat(videoRepository.findAllByOrgnlRawSnOrderByRawSnAsc(fx.parentRawSn()))
                .as("취소된 증강의 늦은 성공 결과로 파생영상이 생기면 안 된다").isEmpty();
    }

    /**
     * DEV_FIX MED-7 — 벤더 409({@code STATE_CONFLICT})는 "외부에 취소할 대상이 없다" 는 결정적 응답이라
     * <b>취소 실패로 세지 않는다</b>.
     *
     * <p>실패로 세면 {@code failedJobSeqs} 가 실려 사용자에게 재시도를 권하게 되는데, 재클릭하면 증강이
     * 이미 CANCELED 라 "취소할 수 없습니다" 가 뜬다 — 수행 불가능한 안내(CLAUDE.md 구속조항 위반).
     */
    @Test
    @DisplayName("외부가_409_면_취소실패로_세지_않는다")
    void vendorConflictIsNotCountedAsCancelFailure() throws Exception {
        LsDataAug aug = seedAug(LsDataAug.STTS_PENDING);
        LsDataAugJob conflict = seedJob(aug.getDataAugSn(), 1, 10, EXTERNAL_JOB_1);
        LsDataAugJob timeout = seedJob(aug.getDataAugSn(), 2, 10, EXTERNAL_JOB_2);
        when(externalClient.cancelJob(any())).thenAnswer(invocation -> {
            AugmentCancelCommand command = invocation.getArgument(0);
            if (EXTERNAL_JOB_1.equals(command.externalJobId())) {
                // 벤더 4xx — 클라이언트가 상태 코드를 실어 비재시도 예외로 올린다.
                return Mono.error(new NonRetryableExternalException("STATE_CONFLICT", 409));
            }
            return Mono.error(new IllegalStateException("timeout")); // 진짜 전달 실패
        });

        mockMvc.perform(post("/v1/augments/{id}/cancel", aug.getDataAugSn())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // 409 는 성립으로 세고, 타임아웃만 실패로 남는다
                .andExpect(jsonPath("$.data.failedJobSeqs.length()").value(1))
                .andExpect(jsonPath("$.data.failedJobSeqs[0]").value(2))
                .andExpect(jsonPath("$.data.canceledJobCount").value(1))
                // 안내는 수행 가능한 동선만 — "다시 시도" 를 권하지 않는다
                .andExpect(jsonPath("$.data.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("다시 시도"))));

        assertThat(jobRepository.findById(conflict.getAugJobSn()).orElseThrow().getJobSttsCd())
                .as("409(외부 이미 종결)는 로컬도 취소 종결시킨다").isEqualTo(LsDataAugJob.STTS_CANCELED);
        assertThat(jobRepository.findById(timeout.getAugJobSn()).orElseThrow().isTerminal())
                .as("전달 실패 청크는 비종결로 남아 만료 스윕이 회수한다").isFalse();
    }

    @Test
    @DisplayName("해상도파생_id_로_cancel_호출시_400")
    void resolutionDerivativeCancelIsRejected() throws Exception {
        LsDataAug resl = augRepository.saveAndFlush(
                LsDataAug.createResolutionPending(seedSrcSn(), LsDataAug.AUG_RESL_480P, "1"));

        mockMvc.perform(post("/v1/augments/{id}/cancel", resl.getDataAugSn())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
        verify(externalClient, never()).cancelJob(any());
        // 상태를 건드리지 않는다(내부 생성 라이프사이클 보존).
        assertThat(augRepository.findById(resl.getDataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_PENDING);
    }

    @Test
    @DisplayName("WORKER_가_취소를_호출하면_403")
    void workerCannotCancel() throws Exception {
        LsDataAug aug = seedAug(LsDataAug.STTS_PENDING);
        seedJob(aug.getDataAugSn(), 1, 10, EXTERNAL_JOB_1);

        mockMvc.perform(post("/v1/augments/{id}/cancel", aug.getDataAugSn())
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
        verify(externalClient, never()).cancelJob(any());

        // 반면 진행상태 조회는 WORKER 도 가능하다(기존 목록 API 와 같은 경계).
        stubStatus(EXTERNAL_JOB_1, "RUNNING", 10);
        mockMvc.perform(get("/v1/augments/{id}/progress", aug.getDataAugSn())
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("취소_요청바디의_알려지지_않은_필드는_무시된다")
    void unknownCancelRequestFieldsAreIgnored() throws Exception {
        LsDataAug aug = seedAug(LsDataAug.STTS_PENDING);
        seedJob(aug.getDataAugSn(), 1, 10, EXTERNAL_JOB_1);
        when(externalClient.cancelJob(any()))
                .thenReturn(Mono.just(AugmentQueryResult.of(canceledOf(EXTERNAL_JOB_1))));

        // localTerminal/force/status 를 실어도 종결 판정에 영향을 주지 않아야 한다(S8 Mass Assignment).
        mockMvc.perform(post("/v1/augments/{id}/cancel", aug.getDataAugSn())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"ok\",\"localTerminal\":true,\"force\":true,"
                                + "\"status\":\"ACCEPTED\"}")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        // localTerminal=true 를 믿었다면 외부 호출이 스킵됐을 것이다 — 실제로는 나가야 한다.
        verify(externalClient, times(1)).cancelJob(any());
    }

    @Test
    @DisplayName("취소_사유가_상한을_넘으면_400")
    void oversizedCancelReasonIsRejected() throws Exception {
        LsDataAug aug = seedAug(LsDataAug.STTS_PENDING);

        mockMvc.perform(post("/v1/augments/{id}/cancel", aug.getDataAugSn())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "가".repeat(501) + "\"}")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    // ============================================================
    // 웹훅 유실 회수 (INT-030)
    // ============================================================

    @Test
    @DisplayName("웹훅_미수신_상태에서_결과조회로_결과가_회수되고_파생은_1건만_생성된다")
    void lostWebhookIsRecoveredViaResultsQueryWithoutDuplicateDerivative() throws Exception {
        Fixture fx = seedRecoverableAugment("RECOVER");
        stubRecoverableSuccess(fx, "RECOVER");

        // 폴링 2회 — 두 번째는 이미 종결이라 아무것도 하지 않아야 한다(멱등).
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/v1/augments/{id}/progress", fx.aug().getDataAugSn())
                            .header("Authorization", "Bearer " + reviewerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.data.progress").value(100));
        }

        assertThat(jobRepository.findById(fx.job().getAugJobSn()).orElseThrow().getJobSttsCd())
                .isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
        assertThat(jobFileRepository.findByAugJobSnOrderByFileSeqAsc(fx.job().getAugJobSn()).get(0)
                .getResultFilePathNm()).isEqualTo("/nas/out/RECOVER-1.jpg");
        assertThat(augRepository.findById(fx.aug().getDataAugSn()).orElseThrow().getAugProcSttsCd())
                .as("전 청크 종결 → 롤업이 증강 1건을 확정한다").isEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(videoRepository.findAllByOrgnlRawSnOrderByRawSnAsc(fx.parentRawSn()))
                .as("2회 폴링해도 파생 영상은 1건만 생성돼야 한다(멱등 앵커)")
                .hasSize(1);
        // 결과조회도 1회만 나간다 — 두 번째 폴링은 로컬이 이미 종결이라 외부를 부르지 않는다.
        verify(externalClient, times(1)).fetchJobResults(fx.externalJobId());
    }

    /**
     * DEV_FIX HIGH-2 — 청크가 자체 상한({@code MAX_STATUS_QUERIES}=10)을 넘어도 <b>결과 회수는 시도</b>된다.
     *
     * <p>구 구현은 {@code pending.size() > 상한} 이면 즉시 degrade return 해서 회수 블록에 <b>도달조차
     * 하지 못했다</b> — 청크 11개 이상 증강은 웹훅이 유실되면 영원히 회수되지 못하고 6시간 뒤 만료
     * 스윕이 FAILED 로 폐기해 <b>벤더가 이미 만든 산출물이 유실</b>됐다.
     */
    @Test
    @DisplayName("청크가_상한을_넘어도_결과회수가_시도된다")
    void recoveryRunsEvenWhenChunkCountExceedsLimit() throws Exception {
        Fixture fx = seedRecoverableAugment("OVERLIMIT");
        // 나머지 청크는 아직 처리 중 — 이들 때문에 상한에 걸린다(외부 <장애>가 아니다).
        stubRunningForAnyJob();
        stubRecoverableSuccess(fx, "OVERLIMIT");
        // 상한(10)을 넘기도록 비종결 청크를 추가한다. 이들은 조회 대상에서 잘리지만 회수를 막지 않는다.
        for (int seq = 2; seq <= 12; seq++) {
            seedJob(fx.aug().getDataAugSn(), seq, 1, "job-overlimit-" + seq);
        }

        mockMvc.perform(get("/v1/augments/{id}/progress", fx.aug().getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // 진행률은 왜곡 대신 null — 다만 사유는 <자체 상한>이지 벤더 장애가 아니다
                .andExpect(jsonPath("$.data.progress").doesNotExist())
                .andExpect(jsonPath("$.data.unavailableReason").value("QUERY_LIMIT_EXCEEDED"));

        verify(externalClient, times(1)).fetchJobResults(fx.externalJobId());
        assertThat(jobRepository.findById(fx.job().getAugJobSn()).orElseThrow().getJobSttsCd())
                .as("상한 초과여도 조회된 청크의 산출물은 인계돼야 한다").isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
    }

    /**
     * DEV_FIX 2차 MED-1 — 상태조회 창은 <b>폴링마다 회전</b>한다.
     *
     * <p>구 구현은 {@code pending.subList(0, 10)} 으로 <b>항상 앞 10개</b>만 조회했고 목록은
     * {@code JOB_SEQ} 오름차순 고정이었다. 그래서 앞 10개가 비종결로 머무는 동안 11번째 이후 청크는
     * <b>어떤 폴링에서도 조회되지 않아</b>(폴링을 N 회 반복해도 동일) 벤더가 이미 만든 산출물이
     * 회수되지 못하고 6시간 뒤 만료 스윕에서 FAILED 로 폐기됐다 — HIGH-2 가 막으려던 바로 그 피해다.
     *
     * <p>지금은 창이 회전하므로 <b>모든 비종결 청크가 유한한 폴링 안에</b> 조회된다(창 수 =
     * {@code ceil(비종결 수 / 10)} → 여기서는 2회).
     */
    @Test
    @DisplayName("앞_청크가_비종결이어도_뒤_청크가_유한_폴링_안에_조회된다")
    void statusQueryWindowRotatesSoLaterChunksAreQueriedWithinFinitePolls() throws Exception {
        // 회수 대상(벤더는 이미 SUCCEEDED)을 <맨 뒤> 순번에 둔다.
        Fixture fx = seedRecoverableAugment("ROTATE", 12);
        stubRunningForAnyJob();
        stubRecoverableSuccess(fx, "ROTATE");
        // 앞 11개는 계속 처리중 — 구 구현에서는 이들이 창을 영구 점유했다.
        for (int seq = 1; seq <= 11; seq++) {
            seedJob(fx.aug().getDataAugSn(), seq, 1, "job-rotate-" + fx.aug().getDataAugSn() + "-" + seq);
        }

        // 1회차 — 앞 창(seq 1~10). 회수 대상은 아직 보이지 않는다.
        pollProgress(fx.aug().getDataAugSn())
                .andExpect(jsonPath("$.data.unavailableReason").value("QUERY_LIMIT_EXCEEDED"));
        verify(externalClient, never()).fetchJobResults(any());

        // 2회차 — 창이 회전해 뒤 창(seq 11~12)을 조회하고 산출물을 회수한다.
        pollProgress(fx.aug().getDataAugSn());
        verify(externalClient, times(1)).fetchJobResults(fx.externalJobId());
        assertThat(jobRepository.findById(fx.job().getAugJobSn()).orElseThrow().getJobSttsCd())
                .as("창 회전이 없으면 이 청크는 만료(6시간)까지 영원히 회수되지 않는다")
                .isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
    }

    /**
     * DEV_FIX 2차 MED-2 — <b>청크 단위 실패가 형제 청크를 굶기지 않는다</b>.
     *
     * <p>조회 경로의 계약 검증(MED-3 에서 입구로 옮긴 {@code error_code} 상한 등)은 <b>유지</b>하되,
     * 한 청크의 계약 위반이 {@code queryStatuses} 루프 전체를 중단시키면 뒤따르는 모든 청크의
     * 상태조회·회수가 <b>매 폴링마다 결정적으로</b> 차단된다(벤더가 51자 {@code error_code} 를 주는
     * 한 영원히 재현된다). 바꿀 것은 계약 강도가 아니라 <b>실패의 전파 범위</b>다.
     *
     * <p>여기서는 벤더 51자 {@code error_code} 응답에 대해 클라이언트
     * ({@code HttpExternalAugmentClient#validateStatus})가 올리는 것과 <b>같은 예외</b>
     * ({@code EXTERNAL_API_ERROR})를 재현한다 — 그 계약 검증 자체는
     * {@code HttpExternalAugmentClientQueryTest#oversizedErrorCodeIsRejected} 가 고정한다.
     */
    @Test
    @DisplayName("한_청크의_계약위반이_형제_청크의_조회를_막지_않는다")
    void contractViolationOnOneChunkDoesNotStarveSiblings() throws Exception {
        Fixture fx = seedRecoverableAugment("SIBLING", 2);
        String violatingJobId = "job-sibling-violation-" + fx.aug().getDataAugSn();
        seedJob(fx.aug().getDataAugSn(), 1, 1, violatingJobId);
        when(externalClient.fetchJobStatus(violatingJobId)).thenReturn(Mono.error(
                new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "생성형AI 상태조회 응답이 계약을 위반했습니다: error_code")));
        stubRecoverableSuccess(fx, "SIBLING");

        pollProgress(fx.aug().getDataAugSn())
                // 진행률은 신뢰할 수 없으므로 null — 자체 상한이 아니라 벤더 응답 문제다
                .andExpect(jsonPath("$.data.progress").doesNotExist())
                .andExpect(jsonPath("$.data.unavailableReason").value("TRANSIENT_ERROR"));

        // 앞 청크에서 끊기지 않고 뒤 청크까지 조회가 이어졌다(구 구현은 여기서 0회였다).
        verify(externalClient, times(1)).fetchJobStatus(violatingJobId);
        verify(externalClient, times(1)).fetchJobStatus(fx.externalJobId());
        verify(externalClient, times(1)).fetchJobResults(fx.externalJobId());
        assertThat(jobRepository.findById(fx.job().getAugJobSn()).orElseThrow().getJobSttsCd())
                .as("형제 청크의 산출물은 그대로 회수돼야 한다").isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
    }

    /**
     * DEV_FIX 2차 MED-2 — 계약 위반 청크는 <b>그 청크만</b> degrade 된다(다른 청크·증강 확정에 영향 없음).
     *
     * <p>청크 3개를 <b>정상 → 위반 → 정상</b> 순으로 배치해 두 방향을 함께 고정한다: 앞 청크에서
     * 수집한 상태가 뒤 청크의 실패로 버려지지 않을 것, 그리고 위반 <b>뒤</b> 청크의 조회가 굶지 않을 것.
     */
    @Test
    @DisplayName("계약위반_청크는_그_청크만_degrade_된다")
    void contractViolationDegradesOnlyThatChunk() throws Exception {
        Fixture fx = seedRecoverableAugment("ONLYCHUNK", 1);
        String violatingJobId = "job-onlychunk-violation-" + fx.aug().getDataAugSn();
        String healthyJobId = "job-onlychunk-healthy-" + fx.aug().getDataAugSn();
        LsDataAugJob violating = seedJob(fx.aug().getDataAugSn(), 2, 1, violatingJobId);
        seedJob(fx.aug().getDataAugSn(), 3, 1, healthyJobId);
        stubRecoverableSuccess(fx, "ONLYCHUNK");
        stubStatus(healthyJobId, "RUNNING", 40);
        when(externalClient.fetchJobStatus(violatingJobId)).thenReturn(Mono.error(
                new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "생성형AI 상태조회 응답이 계약을 위반했습니다: error_code")));

        pollProgress(fx.aug().getDataAugSn())
                .andExpect(jsonPath("$.data.unavailableReason").value("TRANSIENT_ERROR"));

        // 위반 청크는 상태가 바뀌지 않는다 — 조회 실패는 종결 근거가 아니다(다음 폴링/만료 스윕이 처리).
        LsDataAugJob reloaded = jobRepository.findById(violating.getAugJobSn()).orElseThrow();
        assertThat(reloaded.isTerminal()).isFalse();
        assertThat(reloaded.getErrorCode()).isNull();
        // 위반 <뒤> 청크도 조회된다 — degrade 범위가 그 청크 하나로 국한된다
        verify(externalClient, times(1)).fetchJobStatus(healthyJobId);
        // 앞 청크는 정상 회수된다
        assertThat(jobRepository.findById(fx.job().getAugJobSn()).orElseThrow().getJobSttsCd())
                .isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
        // 비종결 청크가 남아 있으므로 증강은 확정되지 않는다(부분 프레임셋 확정 금지)
        assertThat(augRepository.findById(fx.aug().getDataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_PENDING);
    }

    /** 진행상태 폴링 1회 — 200 만 고정하고 세부 단언은 호출부가 이어붙인다. */
    private org.springframework.test.web.servlet.ResultActions pollProgress(Long dataAugSn) throws Exception {
        return mockMvc.perform(get("/v1/augments/{id}/progress", dataAugSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
    }

    /**
     * DEV_FIX HIGH-2 — 자체 상한과 벤더 장애를 <b>다른 사유</b>로 표기한다.
     *
     * <p>같은 값({@code TRANSIENT_ERROR})을 쓰면 초대형 영상의 <b>정상 상황</b>과 실제 서킷 open 이
     * 운영 모니터링에서 같은 알람으로 섞인다. 영구적일 수 있는 조건을 "일시적" 이라 말하지 않는다.
     */
    @Test
    @DisplayName("자체상한_초과는_벤더장애와_다른_사유로_표기된다")
    void selfImposedLimitIsReportedSeparatelyFromVendorFailure() throws Exception {
        stubRunningForAnyJob();
        LsDataAug limited = seedAug(LsDataAug.STTS_PENDING);
        for (int seq = 1; seq <= 11; seq++) {
            seedJob(limited.getDataAugSn(), seq, 1, "job-limit-" + limited.getDataAugSn() + "-" + seq);
        }
        LsDataAug failing = seedAug(LsDataAug.STTS_PENDING);
        seedJob(failing.getDataAugSn(), 1, 10, EXTERNAL_JOB_2);
        when(externalClient.fetchJobStatus(EXTERNAL_JOB_2))
                .thenReturn(Mono.error(new IllegalStateException("timeout")));

        mockMvc.perform(get("/v1/augments/{id}/progress", limited.getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unavailableReason").value("QUERY_LIMIT_EXCEEDED"))
                // 폴링 힌트로도 구분된다 — 더 자주 물어도 같은 상한에 다시 걸린다
                .andExpect(jsonPath("$.data.nextPollAfterMs").value(15000));

        mockMvc.perform(get("/v1/augments/{id}/progress", failing.getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unavailableReason").value("TRANSIENT_ERROR"))
                .andExpect(jsonPath("$.data.nextPollAfterMs").value(10000));
    }

    /**
     * DEV_FIX MED-4 — 진행률 <b>읽기</b>는 WORKER 도 가능하지만 <b>회수 부작용</b>은 REVIEWER 만 유발한다.
     *
     * <p>회수는 job 종결 → 롤업 → {@code LS_DATA_RAW} 파생영상 INSERT → AFTER_COMMIT {@code @Async}
     * 프레임 재추출(ffmpeg)까지 연쇄한다. 안전 메서드(GET)가 상태를 바꾸고 <b>그 시점을 비-REVIEWER 가
     * 정하는</b> 것을 막는다(정보 노출 범위는 기존 정책 그대로 — 진행률은 계속 조회된다).
     */
    @Test
    @DisplayName("WORKER_의_진행률_조회는_결과회수를_유발하지_않는다")
    void workerProgressDoesNotTriggerRecovery() throws Exception {
        Fixture fx = seedRecoverableAugment("WORKERNR");
        stubRecoverableSuccess(fx, "WORKERNR");

        mockMvc.perform(get("/v1/augments/{id}/progress", fx.aug().getDataAugSn())
                        .header("Authorization", "Bearer " + workerToken))
                // 읽기 자체는 계속 허용된다(기존 경계 유지)
                .andExpect(status().isOk());

        verify(externalClient, never()).fetchJobResults(any());
        assertThat(jobRepository.findById(fx.job().getAugJobSn()).orElseThrow().isTerminal())
                .as("WORKER 의 GET 이 job 을 종결시키면 안 된다").isFalse();
        assertThat(videoRepository.findAllByOrgnlRawSnOrderByRawSnAsc(fx.parentRawSn()))
                .as("WORKER 의 GET 이 파생영상 생성을 유발하면 안 된다").isEmpty();
    }

    /**
     * 역할 계층 — 관리자는 검수자 자리를 물려받으므로 <b>회수 부작용도 유발한다</b>.
     *
     * <p>이 자리를 역할 동등 비교로 두면 관리자의 폴링은 조회만 되고 회수가 조용히 멈춘다. 그러면
     * 「진행 중」 표시가 관리자 화면에서만 영영 풀리지 않는다(오류가 나지 않아 더 조용하다).
     *
     * <p>바로 위 {@link #workerProgressDoesNotTriggerRecovery} 가 짝이다 — 작업자는 읽되 회수하지
     * 않는다는 경계가 그대로여야 「관리자가 통과한다」가 의미를 갖는다.
     *
     * @design ADR-055
     * @design ROLE-004
     * @design AC-125
     */
    @Test
    @DisplayName("ADMIN_의_진행률_조회도_회수를_유발한다_계층으로_검수자_자리를_물려받는다")
    void adminProgressTriggersRecovery() {
        Fixture fx = seedRecoverableAugment("ADMINRC");
        stubRecoverableSuccess(fx, "ADMINRC");
        TokenClaims admin = new TokenClaims(
                "9", Role.ADMIN, Channel.INTERNAL, Instant.now().plusSeconds(3600));

        var response = progressService.progress(fx.aug().getDataAugSn(), admin);

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        verify(externalClient, times(1)).fetchJobResults(fx.externalJobId());
        assertThat(jobRepository.findById(fx.job().getAugJobSn()).orElseThrow().getJobSttsCd())
                .as("관리자의 폴링도 종결 청크를 실제로 회수해야 한다")
                .isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
    }

    @Test
    @DisplayName("REVIEWER_의_진행률_조회는_회수를_유발한다")
    void reviewerProgressTriggersRecovery() throws Exception {
        Fixture fx = seedRecoverableAugment("REVIEWERR");
        stubRecoverableSuccess(fx, "REVIEWERR");

        mockMvc.perform(get("/v1/augments/{id}/progress", fx.aug().getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        verify(externalClient, times(1)).fetchJobResults(fx.externalJobId());
        assertThat(jobRepository.findById(fx.job().getAugJobSn()).orElseThrow().getJobSttsCd())
                .isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
    }

    @Test
    @DisplayName("외부경로가_허용루트_밖이면_회수경로에서도_차단된다")
    void recoveryRejectsOutputPathOutsideAllowedRoots() throws Exception {
        Fixture fx = seedRecoverableAugment("BADPATH");
        stubStatus(fx.externalJobId(), "SUCCEEDED", 100);
        when(externalClient.fetchJobResults(fx.externalJobId())).thenReturn(Mono.just(
                AugmentQueryResult.of(new GenAiJobResultsResponse("req", fx.externalJobId(), "SUCCEEDED",
                        List.of(new GenAiJobResultsResponse.ResultItem(
                                "g1", "IMAGE", "/etc/passwd", null, null))))));
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.INVALID_INPUT, "outside"))
                .when(artifactRootResolver).verifyExternalReadablePath(anyString());

        mockMvc.perform(get("/v1/augments/{id}/progress", fx.aug().getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        // 회수하지 않는다 — job 은 비종결로 남고(만료 스윕이 회수) 산출 경로는 적재되지 않는다.
        assertThat(jobRepository.findById(fx.job().getAugJobSn()).orElseThrow().isTerminal()).isFalse();
        assertThat(jobFileRepository.findByAugJobSnOrderByFileSeqAsc(fx.job().getAugJobSn()).get(0)
                .getResultFilePathNm()).isNull();
        assertThat(augRepository.findById(fx.aug().getDataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_PENDING);
        assertThat(videoRepository.findAllByOrgnlRawSnOrderByRawSnAsc(fx.parentRawSn())).isEmpty();
    }

    /**
     * 회수 시나리오 시드 — 부모 영상(비식별 완료 + 프레임 1) + 대표프레임 기준 PENDING 증강 1건 + 청크 1건.
     *
     * <p><b>외부 job_id 는 시나리오마다 다르다</b>({@code job-augprg-{suffix}}) — {@code OTSD_JOB_ID} 는
     * 증강 간 <b>전역 유일</b>이라({@code uk_aug_external_job_id} + 선점 검사) 공유하면 먼저 회수에
     * 성공한 테스트가 그 id 를 점유해 <b>뒤 테스트가 409 로 회수 실패</b>한다(테스트 순서 의존).
     */
    private Fixture seedRecoverableAugment(String suffix) {
        return seedRecoverableAugment(suffix, 1);
    }

    /**
     * 회수 대상 청크의 {@code JOB_SEQ} 를 지정하는 변형 — 상태조회 <b>창 회전</b>(DEV_FIX 2차 MED-1)
     * 검증용이다. 조회 순서는 {@code JOB_SEQ} 오름차순이므로, 회수 대상을 뒤쪽 순번에 두어야
     * "앞 청크가 창을 점유하는" 상황을 재현할 수 있다.
     */
    private Fixture seedRecoverableAugment(String suffix, int jobSeq) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "AUGPRG-" + suffix, "CCTV-AUGPRG-" + suffix, "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/videos/AUGPRG-" + suffix + ".mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
        parent = videoRepository.saveAndFlush(parent);

        LsDataSrc frame = LsDataSrc.create(parent.getRawSn(), 0L, 0L,
                "/nas/frames/raw/" + suffix + "/f0.jpg", LocalDateTime.now());
        frame.attachDeidPath("/nas/frames/deid/" + suffix + "/f0.jpg");
        frame = srcRepository.saveAndFlush(frame);

        LsDataAug aug = augRepository.saveAndFlush(LsDataAug.createRequested(
                frame.getSrcSn(), LsDataAug.AUG_WINTER, "1",
                "AUG-AUGPRG-" + java.util.UUID.randomUUID(), null, "{\"time\":\"NIGHT\"}"));
        String externalJobId = "job-augprg-" + suffix.toLowerCase();
        LsDataAugJob job = seedJob(aug.getDataAugSn(), jobSeq, 1, externalJobId);
        jobFileRepository.saveAndFlush(
                LsDataAugJobFile.issued(job.getAugJobSn(), 1, frame.getSrcSn()));
        return new Fixture(parent.getRawSn(), aug, job, externalJobId);
    }

    /** 성공 회수 스텁 — 상태조회 SUCCEEDED + 결과조회 1건(허용 루트는 목이 통과시킨다). */
    private void stubRecoverableSuccess(Fixture fx, String suffix) {
        stubStatus(fx.externalJobId(), "SUCCEEDED", 100);
        when(externalClient.fetchJobResults(fx.externalJobId())).thenReturn(Mono.just(
                AugmentQueryResult.of(new GenAiJobResultsResponse("req", fx.externalJobId(), "SUCCEEDED",
                        List.of(new GenAiJobResultsResponse.ResultItem(
                                "g1", "IMAGE", "/nas/out/" + suffix + "-1.jpg", null, null))))));
    }

    private record Fixture(Long parentRawSn, LsDataAug aug, LsDataAugJob job, String externalJobId) {
    }
}
