package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.support.RejectingScheduler;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase C-1 — <b>VLM 제출 논블로킹 계약</b> 고정 테스트.
 *
 * <p>여기서 고정하는 것은 "그냥 통과하는" 성질이 아니라 <b>관측 가능한</b> 세 가지다:
 * <ol>
 *   <li>ACK 가 영원히 오지 않아도 호출 스레드가 풀려난다(구 {@code .block(45s)} 였다면 45초 붙잡힌다).</li>
 *   <li>선커밋(상관키 등록 + 마킹 전이)이 <b>제출보다 먼저</b> 일어난다(콜백 선행 레이스 폐쇄).</li>
 *   <li>완료 핸들러가 <b>호출 스레드가 아닌 전용 풀 스레드</b>에서 실행된다(이벤트 루프 JPA 차단).</li>
 * </ol>
 */
class VlmTimeseriesStepNonBlockingTest {

    private VlmClient vlmClient;
    private VideoRepository videoRepository;
    private IngestSourceRepository ingestSourceRepository;
    private BatchStatusService batchStatusService;
    private WebhookIdempotencyLedger ledger;
    private LsDeidentProcLogRepository deidentProcLogRepository;
    private DeidentReportGate deidentReportGate;
    private VlmMarkingTxService markingTxService;
    private VlmSubmitOutcomeRecorder outcomeRecorder;

    private ExecutorService dedicatedPool;
    private Scheduler dedicatedScheduler;
    private VlmTimeseriesStep step;

    @BeforeEach
    void setUp() {
        vlmClient = mock(VlmClient.class);
        videoRepository = mock(VideoRepository.class);
        ingestSourceRepository = mock(IngestSourceRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        ledger = mock(WebhookIdempotencyLedger.class);
        deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);
        deidentReportGate = mock(DeidentReportGate.class);
        markingTxService = mock(VlmMarkingTxService.class);
        outcomeRecorder = mock(VlmSubmitOutcomeRecorder.class);

        // 운영과 동형: 완료 신호 전용 풀(이름으로 식별 가능).
        dedicatedPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "vlm-submit-test"));
        dedicatedScheduler = Schedulers.fromExecutor(dedicatedPool);

        step = newStep(dedicatedScheduler);
    }

    @AfterEach
    void tearDown() {
        dedicatedPool.shutdownNow();
    }

    private void seed(Long rawSn) {
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        LsDeidentProcLog plog = mock(LsDeidentProcLog.class);
        lenient().when(plog.getDeIdntfFilePathNm()).thenReturn("/data/deid/" + rawSn + ".mp4");
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn))
                .thenReturn(Optional.of(plog));
        // 관제 인입 검증이벤트유형 — Phase 2 이후 위탁의 사전 조건(@req R6).
        IngestSourceRow source = mock(IngestSourceRow.class);
        lenient().when(source.getVrfcEvntTypeCd()).thenReturn("fire");
        lenient().when(ingestSourceRepository.findSourceMeta(rawSn)).thenReturn(source);
        when(vlmClient.isEnabled()).thenReturn(true);
    }

    @Test
    @DisplayName("ACK가_끝내_오지_않아도_제출호출이_파이프라인_스레드를_붙잡지_않는다")
    void submitDoesNotBlockPipelineThread() {
        // given — 외부가 영원히 응답하지 않는다(구 구현이면 BLOCK_TIMEOUT 45s 동안 스레드 점유).
        seed(500L);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class))).thenReturn(Mono.never());

        // when
        long startedAt = System.nanoTime();
        VlmTimeseriesResponse resp = step.run(500L);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // then — 즉시 반환. 임계 2초는 CI 지터를 넉넉히 흡수하면서도 45s 블로킹과는 구분된다.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        assertThat(resp.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
        // ACK 가 없으므로 어떤 완료 기록도 아직 없다 — 이 상태를 회수하는 것이 미결 스위퍼의 역할이다.
        verify(outcomeRecorder, never()).onAccepted(any(), any(), any());
        verify(outcomeRecorder, never()).onSubmitFailed(any(), any(), any());
    }

    @Test
    @DisplayName("상관키_등록과_마킹_VLM_REQUESTED_전이는_외부_제출보다_먼저_수행된다")
    void preCommitHappensBeforeSubmit() {
        // given
        seed(501L);
        LsMarking marking = LsMarking.createAuto(501L, "fire", 5, "/raw/501.mp4",
                "[{\"frameIndex\":0,\"timestamp\":0.0}]", 1L);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class))).thenReturn(Mono.never());

        // when
        step.runWithMarking(501L, marking);

        // then — 순서가 뒤집히면(제출 후 전이) 저지연 벤더의 콜백이 먼저 커밋돼
        //        전이 대상 0건 → 이후 PENDING→VLM_REQUESTED 승격 → 영구 고착이 된다.
        InOrder order = inOrder(ledger, markingTxService, vlmClient);
        order.verify(ledger).recordIssued(any(), eq(LsWebhookIdempotency.CHANNEL_VLM), isNull(), eq(501L));
        order.verify(markingTxService).persistVlmRequested(marking);
        order.verify(vlmClient).submitTimeseries(any(VlmTimeseriesRequest.class));
    }

    @Test
    @DisplayName("완료핸들러는_호출_스레드가_아닌_전용_풀에서_실행된다")
    void completionHandlerRunsOnDedicatedPool() throws Exception {
        // given — ACK 응답이 도착하면 완료 핸들러가 어느 스레드에서 도는지 포착한다.
        seed(502L);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.just(new VlmTimeseriesResponse("REQ-502", "accepted")));

        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<String> handlerThread = new AtomicReference<>();
        doAnswer(inv -> {
            handlerThread.set(Thread.currentThread().getName());
            handled.countDown();
            return null;
        }).when(outcomeRecorder).onAccepted(eq(502L), any(), any());

        String callerThread = Thread.currentThread().getName();

        // when
        step.run(502L);

        // then — publishOn 고정이 없으면 호출 스레드(운영에서는 reactor-netty 이벤트 루프)에서
        //        JPA 쓰기가 실행돼 모든 외부 호출이 동반 지연된다.
        assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(handlerThread.get()).isEqualTo("vlm-submit-test");
        assertThat(handlerThread.get()).isNotEqualTo(callerThread);
    }

    @Test
    @DisplayName("제출_확정실패시_배치_작업상태를_강등하지_않고_기록만_남긴다")
    void submitFailureDoesNotDemoteStatus() throws Exception {
        // given
        seed(503L);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.error(new IllegalStateException("boom")));
        CountDownLatch handled = new CountDownLatch(1);
        doAnswer(inv -> {
            handled.countDown();
            return null;
        }).when(outcomeRecorder).onSubmitFailed(eq(503L), isNull(), any());

        // when — 실패는 완료 핸들러에 위임되고, 스텝은 상태 강등 경로를 직접 타지 않는다
        //        (오케스트레이터가 이미 파이프라인을 끝냈을 수 있어 FAILED 로 내리면 동선이 역행한다).
        VlmTimeseriesResponse resp = step.run(503L);

        // then
        assertThat(resp.status()).isEqualTo(VlmTimeseriesResponse.STATUS_SUBMITTED);
        assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
        verify(batchStatusService, never()).markFailed(any(), any());
        verify(batchStatusService, never()).markStage(any(), any());
        verify(outcomeRecorder).onSubmitFailed(eq(503L), isNull(), any(IllegalStateException.class));
    }

    // ───────────────────── M2 — 전용 풀 거부 시 이벤트 루프에서 JPA 를 돌리지 않는다 ─────────────────────

    /**
     * ★ M2 — 전용 풀이 포화(AbortPolicy)되면 완료 기록을 <b>포기</b>한다.
     *
     * <p>구 구현({@code publishOn(vlmSubmitScheduler)})에서는 스케줄 거부가 곧 onError 이고, 그 onError
     * 는 <b>시그널을 나른 스레드(reactor-netty 이벤트 루프)</b>에서 downstream 으로 흐른다. downstream 이
     * 실패 핸들러의 JPA 쓰기면 이벤트 루프가 커넥션 대기에 묶여 같은 루프를 쓰는 모든 외부 호출
     * (VLM·KPST·증강·ai-server)이 동반 지연된다. 동기 {@code subscribe()} 를 감싼 try/catch 는 이
     * 거부를 잡지 못한다(거부는 응답 도착 <b>후</b> 발생).
     *
     * <p>잃는 것은 "기록"이지 "위탁"이 아니다 — 선커밋된 ISSUED 원장이 남아 미결 스위퍼가 회수한다.
     *
     * <p><b>RED 실증</b>: {@code SubmitSignalDispatch.run(...)} 을 걷어내고 핸들러를 직접 호출하도록
     * (또는 구 {@code publishOn} 으로) 되돌리면 호출 스레드에서 핸들러가 실행되어 RED 다.
     *
     * <p>{@code AugmentSubmitSerializationGuardTest.전용풀_거부시_이벤트루프에서_기록하지_않고...} 의
     * 대칭 가드다.
     */
    @Test
    @DisplayName("전용풀_거부시_ACK기록을_호출스레드에서_수행하지_않고_예외도_새지_않는다")
    void poolRejectionDropsAckRecordWithoutEventLoopJpa() {
        // given — 응답(ACK)은 정상 도착하지만 전용 풀이 포화라 기록을 스케줄할 수 없다.
        seed(504L);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.just(new VlmTimeseriesResponse("REQ-504", "accepted")));
        AtomicReference<String> handlerThread = new AtomicReference<>();
        recordHandlerThread(handlerThread);
        VlmTimeseriesStep rejectingStep = newStep(new RejectingScheduler());

        // when / then — 거부가 호출자에게 예외로 새어 나가지 않는다.
        assertThatCode(() -> rejectingStep.run(504L)).doesNotThrowAnyException();

        // then — ①DB 기록 핸들러는 한 번도 호출되지 않는다 ②따라서 호출 스레드에서 JPA 가 돌지 않는다.
        verify(outcomeRecorder, never()).onAccepted(any(), any(), any());
        verify(outcomeRecorder, never()).onSubmitFailed(any(), any(), any());
        assertThat(handlerThread.get())
                .as("거부된 기록이 호출 스레드(운영에서는 이벤트 루프)에서 실행되면 안 된다")
                .isNull();
    }

    @Test
    @DisplayName("전용풀_거부시_실패기록도_호출스레드에서_수행하지_않는다")
    void poolRejectionDropsFailureRecordWithoutEventLoopJpa() {
        // given — 외부가 에러로 종료(실패 기록 경로) + 전용 풀 포화.
        seed(505L);
        when(vlmClient.submitTimeseries(any(VlmTimeseriesRequest.class)))
                .thenReturn(Mono.error(new IllegalStateException("boom")));
        AtomicReference<String> handlerThread = new AtomicReference<>();
        recordHandlerThread(handlerThread);
        VlmTimeseriesStep rejectingStep = newStep(new RejectingScheduler());

        // when / then
        assertThatCode(() -> rejectingStep.run(505L)).doesNotThrowAnyException();

        verify(outcomeRecorder, never()).onSubmitFailed(any(), any(), any());
        verify(outcomeRecorder, never()).onAccepted(any(), any(), any());
        assertThat(handlerThread.get()).isNull();
    }

    /** 완료 핸들러가 <b>어느 스레드에서든</b> 호출되면 그 스레드명을 남긴다(호출 자체가 위반). */
    private void recordHandlerThread(AtomicReference<String> sink) {
        doAnswer(inv -> {
            sink.set(Thread.currentThread().getName());
            return null;
        }).when(outcomeRecorder).onAccepted(any(), any(), any());
        doAnswer(inv -> {
            sink.set(Thread.currentThread().getName());
            return null;
        }).when(outcomeRecorder).onSubmitFailed(any(), any(), any());
    }

    /** 동일 협력자 + 스케줄러만 교체한 스텝 인스턴스. */
    private VlmTimeseriesStep newStep(Scheduler scheduler) {
        return new VlmTimeseriesStep(vlmClient, videoRepository, ingestSourceRepository,
                batchStatusService, ledger, deidentProcLogRepository, deidentReportGate,
                markingTxService, outcomeRecorder,
                mock(kr.co.cudo.authoring.batch.vlm.VlmTimeseriesMetaPresence.class),
                new ObjectMapper(), scheduler);
    }
}
