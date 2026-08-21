package kr.co.cudo.authoring.batch;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.support.RawVideoFixture;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import kr.co.cudo.authoring.batch.status.VlmDefaultSkipMarker;

/**
 * B-ISSUE-01 (1차 B-ISSUE-22 이월) 회귀 가드 — <b>배치 파이프라인 진입의 원자 클레임</b>을 실 DB
 * (PostgreSQL Testcontainer) + 실제 동시 스레드로 고정한다 (CWE-362).
 *
 * <h3>막는 실패 모드</h3>
 * <p>{@code BatchOrchestrator.process} 의 진입 가드는 "검수 소유 작업 상태"만 판정했고
 * {@code PROCESSING} 은 차단 집합 밖이라, 동일 {@code rawSn} 에 진입 요청이 여러 건 겹치면
 * <b>전부 통과</b>했다(실측: 동시 5요청 → 파이프라인 5벌 병렬 실행 + 외부 VLM 5중 위탁).
 * 마킹 브리지의 {@code SKIP_BATCH_STAGES} 가드는 조회→판정→별도 UPDATE 의 check-then-act 라
 * 레이스 창을 닫지 못한다. 상호배제는 <b>단일 조건부 UPDATE</b>(claim)로만 성립한다.
 *
 * <h3>왜 오케스트레이터를 손수 조립하는가</h3>
 * <p>실 파이프라인 단계(VLM·ffmpeg·YOLO)는 외부 의존이라 IT 에서 돌릴 수 없다. 대신 실행 횟수를
 * 세고 래치로 <b>실행 구간을 붙잡아 두는</b> 가짜 {@link BatchStep}(stage=VLM) 을 끼워, "동시에 몇 벌이
 * 외부 위탁 단계에 들어갔는가"를 직접 관측한다. 상태 전이({@link BatchTransitionService})와 영상 행
 * ({@link VideoRepository})은 <b>실 빈·실 DB</b> 라 클레임의 원자성이 그대로 검증된다.
 *
 * <h3>범위</h3>
 * <p>2노드 Active-Active 의 완전한 재현은 단일 JVM 으로는 불가능하다. 여기서는 동일 DB 를 향한
 * 다중 커넥션 경합으로 근사하며(클레임은 DB 가 직렬화하므로 노드 수와 무관), 실제 2노드 검증은
 * 스테이징에서 별도 확인한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class BatchOrchestratorConcurrentEntryIT {

    /** 동시 진입 요청 수 — 실측 재현(동시 5요청 → 5벌 실행)과 동일하게 경합시킨다. */
    private static final int CONCURRENCY = 5;
    /** 래치/Future 대기 상한(초). */
    private static final int AWAIT_SEC = 30;

    @Autowired
    private BatchTransitionService transitionService;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> seeded = new ArrayList<>();

    @AfterEach
    void cleanup() {
        seeded.forEach(rawSn -> RawVideoFixture.deleteRaws(jdbcTemplate, rawSn));
        seeded.clear();
    }

    // ------------------------------------------------------------------ fixture

    /** 마킹 완료 직후(MARKING_READY) 영상 1건 — 배치 진입의 정상 출발 상태. */
    private long seedMarkingReadyRaw() {
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        seeded.add(rawSn);
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ? WHERE RAW_SN = ?",
                LsDataRaw.DATA_STTS_MARKING_READY, rawSn);
        return rawSn;
    }

    private String rawStage(long rawSn) {
        return jdbcTemplate.queryForObject(
                "SELECT DATA_STTS_CD FROM LS_DATA_RAW WHERE RAW_SN = ?", String.class, rawSn);
    }

    /**
     * 실행 횟수를 세는 가짜 외부 위탁 단계(VLM). {@code gate} 가 주어지면 그 래치가 열릴 때까지
     * 실행 구간에 머물러, 동시 진입이 "겹치는 순간"을 강제로 만든다.
     *
     * <p><b>static 이 아닌 inner class 인 이유</b>: {@code BatchStepTransactionBoundaryTest} 는
     * {@code kr.co.cudo.authoring} 전체를 classpath 스캔해 <b>모든</b> {@link BatchStep} 구현의
     * {@code execute} 에 {@code @Transactional(REQUIRES_NEW)} 를 강제한다(테스트 클래스도 스캔 대상).
     * inner class 는 스캐너의 {@code isIndependent()} 판정에서 제외되므로, 프로덕션 가드를 약화시키지
     * 않고(면제 목록에 테스트 더블을 추가하지 않고) 테스트 더블만 비켜간다.
     */
    private final class CountingVlmStep implements BatchStep {
        private final AtomicInteger executions = new AtomicInteger();
        private final CountDownLatch gate;
        /** 실행 시 던질 오류 — {@code RuntimeException}(정상 실패 경로) 또는 {@code Error}(전파 경로). */
        private final Throwable failure;

        CountingVlmStep(CountDownLatch gate, Throwable failure) {
            this.gate = gate;
            this.failure = failure;
        }

        @Override
        public BatchStage stage() {
            return BatchStage.VLM;
        }

        @Override
        public void execute(BatchContext ctx) {
            executions.incrementAndGet();
            if (gate != null) {
                try {
                    if (!gate.await(AWAIT_SEC, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gate 대기 시간 초과");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            if (failure instanceof Error err) {
                throw err;
            }
            if (failure instanceof RuntimeException re) {
                throw re;
            }
        }
    }

    private BatchOrchestrator orchestratorWith(CountingVlmStep step) {
        BatchStatusService statusService = mock(BatchStatusService.class);
        BatchRetryQueue retryQueue = mock(BatchRetryQueue.class);
        when(retryQueue.enqueueIfRetryable(any())).thenReturn(false);
        return new BatchOrchestrator(
                new BatchPipeline(List.of(step)), statusService, transitionService, retryQueue,
                videoRepository, mock(VlmDefaultSkipMarker.class));
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("동시_5개_배치_진입_요청_중_정확히_1건만_처리되고_나머지_4건은_SKIPPED된다")
    void concurrentEntries_exactlyOneProcesses() throws Exception {
        // given — 마킹 완료 영상 1건 + 실행 구간을 붙잡는 가짜 VLM 단계
        long rawSn = seedMarkingReadyRaw();
        CountDownLatch gate = new CountDownLatch(1);
        CountingVlmStep step = new CountingVlmStep(gate, null);
        BatchOrchestrator orchestrator = orchestratorWith(step);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger returned = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<BatchStage> stages = new ArrayList<>();
        try {
            List<Future<BatchStage>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENCY; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return orchestrator.process(rawSn);
                    } finally {
                        returned.incrementAndGet();
                    }
                }));
            }

            // when — 5개 요청이 동시에 진입한다(재시도·중복 트리거·2노드 동시 픽업으로 자연 발생)
            start.countDown();

            // 클레임에 실패한 요청은 즉시 반환된다. 승자는 gate 에 붙잡혀 있으므로 반환 수가 4에서 멈춘다.
            // (결함 상태에서는 5벌이 모두 실행 구간에 진입해 반환 수가 0 에서 멈춘다 → 아래 단언이 깨진다.)
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SEC);
            while (returned.get() < CONCURRENCY - 1 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            gate.countDown();

            for (Future<BatchStage> f : futures) {
                stages.add(f.get(AWAIT_SEC, TimeUnit.SECONDS));
            }
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }

        // then — 외부 위탁 단계는 정확히 1회만 실행된다(구 구현: 5회 = VLM 5중 위탁)
        assertThat(step.executions.get())
                .as("동일 영상에 대한 외부 위탁(VLM) 단계는 동시 진입 몇 건이든 1회만 실행돼야 한다")
                .isEqualTo(1);
        assertThat(stages).filteredOn(s -> s == BatchStage.COMPLETED).hasSize(1);
        assertThat(stages).filteredOn(s -> s == BatchStage.SKIPPED).hasSize(CONCURRENCY - 1);
        assertThat(rawStage(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
    }

    @Test
    @DisplayName("이미_PROCESSING_상태인_영상에_재진입_시도하면_클레임에_실패해_SKIPPED된다")
    void alreadyProcessing_reentry_isSkipped() {
        // given — 다른 노드가 이미 클레임해 처리 중인 영상
        long rawSn = seedMarkingReadyRaw();
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ? WHERE RAW_SN = ?",
                LsDataRaw.DATA_STTS_PROCESSING, rawSn);
        CountingVlmStep step = new CountingVlmStep(null, null);
        BatchOrchestrator orchestrator = orchestratorWith(step);

        // when
        BatchStage stage = orchestrator.process(rawSn);

        // then — step 을 한 건도 실행하지 않고 즉시 종료하며, 남의 PROCESSING 을 건드리지 않는다
        assertThat(stage).isEqualTo(BatchStage.SKIPPED);
        assertThat(step.executions.get()).isZero();
        assertThat(rawStage(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_PROCESSING);
    }

    @Test
    @DisplayName("클레임_성공_후_정상완료_시_PROCESSING이_해제되고_후속_재진입이_가능하다")
    void completedRun_releasesClaim_andAllowsReentry() {
        // given
        long rawSn = seedMarkingReadyRaw();
        CountingVlmStep step = new CountingVlmStep(null, null);
        BatchOrchestrator orchestrator = orchestratorWith(step);

        // when — 1회차 정상 완료
        assertThat(orchestrator.process(rawSn)).isEqualTo(BatchStage.COMPLETED);

        // then — PROCESSING 해제(COMPLETED) → 클레임이 다시 가능해야 한다(stale 고착 금지)
        assertThat(rawStage(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
        assertThat(orchestrator.process(rawSn)).isEqualTo(BatchStage.COMPLETED);
        assertThat(step.executions.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("클레임_성공_후_예외발생_시에도_PROCESSING이_해제된다")
    void failedRun_releasesClaim() {
        // given — 단계가 예외를 던지는 파이프라인
        long rawSn = seedMarkingReadyRaw();
        CountingVlmStep step = new CountingVlmStep(null, new IllegalStateException("step boom"));
        BatchOrchestrator orchestrator = orchestratorWith(step);

        // when
        BatchStage stage = orchestrator.process(rawSn);

        // then — FAILED 로 해제되어야 재처리(FAILED→PROCESSING 클레임)가 가능하다
        assertThat(stage).isEqualTo(BatchStage.FAILED);
        assertThat(rawStage(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_FAILED);
        assertThat(transitionService.tryClaimReprocessFromFailed(rawSn))
                .as("실패로 해제된 영상은 수동 재처리 클레임이 성립해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("클레임_성공_후_Error_전파_시에도_PROCESSING이_해제되고_Error가_그대로_전파된다")
    void errorRun_releasesClaim_andRethrows() {
        // given — 단계가 Error(치명적 오류 계열)를 던지는 파이프라인.
        //   RuntimeException 과 달리 이 경로는 FAILED 로 위장 종료하지 않고 원인을 되던진다. 그래도
        //   클레임을 해제하지 않으면 stage 가 PROCESSING 으로 영구 고착돼 이후 모든 진입이 막힌다.
        long rawSn = seedMarkingReadyRaw();
        CountingVlmStep step = new CountingVlmStep(null, new SimulatedFatalError("fatal in step"));
        BatchOrchestrator orchestrator = orchestratorWith(step);

        // when / then — ① Error 는 삼켜지지 않는다
        assertThatThrownBy(() -> orchestrator.process(rawSn))
                .isInstanceOf(SimulatedFatalError.class)
                .hasMessage("fatal in step");

        // ② 실 DB 에서 클레임이 실제로 해제됐고 ③ 후속 재처리 클레임이 성립한다
        assertThat(rawStage(rawSn)).isEqualTo(LsDataRaw.DATA_STTS_FAILED);
        assertThat(transitionService.tryClaimReprocessFromFailed(rawSn))
                .as("Error 로 종료된 영상도 PROCESSING 고착 없이 재처리 클레임이 성립해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("클레임_미보유_인계_진입도_폴백_클레임을_걸어_동시_일반진입을_막는다")
    void heldClaimEntry_withoutActualClaim_takesFallbackClaim() throws Exception {
        // given — 인계 진입(processWithHeldStageClaim)을 <b>클레임 없이</b> 호출하는 오사용.
        //   "클레임을 보유했다"는 검증 불가능한 호출자 주장이라, 그대로 믿고 재클레임을 생략하면 이 진입점
        //   하나에서 상호배제가 통째로 사라진다(B-ISSUE-01 의 실패 모드가 되살아난다).
        long rawSn = seedMarkingReadyRaw();
        CountDownLatch gate = new CountDownLatch(1);
        CountingVlmStep step = new CountingVlmStep(gate, null);
        BatchOrchestrator orchestrator = orchestratorWith(step);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        BatchStage heldEntryStage;
        BatchStage normalEntryStage;
        try {
            Future<BatchStage> heldEntry = pool.submit(() -> orchestrator.processWithHeldStageClaim(rawSn));

            // 오사용 호출이 실행 구간(가짜 VLM 단계)에 들어갈 때까지 기다린다 — 이 시점엔 진입 전이가
            // 이미 커밋돼 있으므로, 폴백 클레임이 걸렸는지를 후속 진입으로 관측할 수 있다.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SEC);
            while (step.executions.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(step.executions.get()).as("오사용 호출이 실행 구간에 진입해야 한다").isEqualTo(1);

            // when — 같은 영상에 일반 진입이 겹친다(마킹 브리지·Quartz 큐 등 실제 경로)
            normalEntryStage = orchestrator.process(rawSn);

            gate.countDown();
            heldEntryStage = heldEntry.get(AWAIT_SEC, TimeUnit.SECONDS);
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }

        // then — 폴백 클레임 덕분에 일반 진입이 막힌다(구 동작: 클레임 미설정 → 통과 → 2벌 동시 실행)
        assertThat(normalEntryStage)
                .as("오사용 호출이 폴백 클레임을 걸었으므로 동시 일반 진입은 SKIPPED 여야 한다")
                .isEqualTo(BatchStage.SKIPPED);
        assertThat(step.executions.get())
                .as("외부 위탁 단계는 1회만 실행돼야 한다")
                .isEqualTo(1);
        assertThat(heldEntryStage).isEqualTo(BatchStage.COMPLETED);
    }

    /** 테스트 전용 {@code Error} 서브클래스 — 실제 OOM/StackOverflow 유발 없이 전파 경로만 재현한다. */
    private static final class SimulatedFatalError extends Error {
        SimulatedFatalError(String message) {
            super(message);
        }
    }
}
