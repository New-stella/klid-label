package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.integration.AugmentSubmitResult;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 증강 청크 <b>직렬 전송</b>과 <b>전용 풀 거부</b>의 회귀 가드 (M5 / M2).
 *
 * <h3>왜 별도 테스트인가 — 기존 가드가 변이에 둔감했다</h3>
 * <p>{@code AugmentJobSubmitServiceTest} 는 {@link Schedulers#immediate()} 를 주입해 반응형 체인을
 * 테스트 스레드에서 결정적으로 돌린다. 그 구성에서는 {@code publishOn} 이 사실상 no-op 이라
 * <b>진짜 스케줄러에서의 동시성</b>이 재현되지 않는다. 여기서는 <b>실제 병렬 스케줄러</b> +
 * <b>지연 응답</b>으로, 청크 사이의 비식별 신고 재판정 방어가 의존하는 "앞 청크 완료 → 다음 청크"
 * 순서를 관측 가능한 형태(동시 in-flight 최대치)로 고정한다.
 *
 * <p><b>변이 실증</b>: 운영 코드의 {@code concatMap} 을 {@code flatMap} 으로 바꾸면 3개 청크가 동시에
 * 조립·구독되어 {@code maxConcurrent} 가 1 을 넘고 이 테스트가 RED 가 된다(실측 확인).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AugmentSubmitSerializationGuardTest {

    /** 위탁 payload 의 prompt — 이 테스트의 관심사가 아니라 계약(필수 non-empty)을 채우는 고정값. */
    private static final java.util.Map<String, Object> MTDT = java.util.Map.of("time", "NIGHT", "season", "WINTER", "weather", "RAIN", "terrain", "ROAD", "severity", "HIGH");


    @Mock private LsDataSrcRepository srcRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private AugmentJobRecorder jobRecorder;
    @Mock private ExternalAugmentClient externalClient;
    @Mock private AugmentMetrics metrics;
    @Mock private DeidentReportGate deidentReportGate;
    @Mock private AugmentSubmitOutcomeRecorder outcomeRecorder;

    private final AtomicLong jobSnSeq = new AtomicLong(2000);
    private Scheduler scheduler;

    @BeforeEach
    void setUp() {
        given(videoRepository.findById(anyLong())).willReturn(Optional.empty());
        given(jobRecorder.recordIssued(anyLong(), anyInt(), anyString(), anyList()))
                .willAnswer(inv -> jobSnSeq.incrementAndGet());
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.dispose();
        }
    }

    private AugmentJobSubmitService newService(Scheduler s) {
        return new AugmentJobSubmitService(
                inputFrameSource(), jobRecorder, externalClient, metrics,
                deidentReportGate, outcomeRecorder, s, 100);
    }

    /**
     * 조달기는 <b>실물</b>을 쓴다 — 목으로 대체하면 「기본값은 비식별본」이라는 fail-closed 규약이
     * 이 시험에서 사라진다. 부모 조회가 비어 있으므로(=출처 미상) 조달처는 기본값으로 떨어진다.
     */
    private AugmentInputFrameSource inputFrameSource() {
        return new AugmentInputFrameSource(videoRepository, srcRepository,
                new kr.co.cudo.authoring.video.service.DerivativeSourceVideoResolver(null, null, null));
    }

    private AugmentRequestedItemEvent event() {
        return new AugmentRequestedItemEvent(
                7L, 700L, "WINTER", MTDT, null, "FLOOD", null, "AUG-guard",
                "http://localhost:8080/api/v1/genai/callback", "1");
    }

    private void seedFrames(int count) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[]{(long) i, "/storage/deidentified/frames/" + i + ".jpg"});
        }
        given(srcRepository.findDeidFramePathsByRawSn(700L)).willReturn(rows);
    }

    /**
     * ★ M5 — 실제 병렬 스케줄러에서도 청크는 <b>한 번에 하나만</b> 나간다.
     *
     * <p>외부 호출은 30ms 지연 후 완료하고, 호출 시작/종료 사이의 in-flight 수를 센다. 직렬
     * ({@code concatMap})이면 최대 1, 병렬({@code flatMap})이면 3 이 된다.
     */
    @Test
    @DisplayName("실제_스케줄러에서도_청크는_동시에_나가지_않는다_concatMap_직렬_보장")
    void chunksAreSubmittedStrictlySerially() throws Exception {
        scheduler = Schedulers.newParallel("aug-guard", 4);
        seedFrames(250); // 3 청크
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);

        given(externalClient.requestAugment(any())).willAnswer(inv -> {
            // requestAugment 호출 = "이 청크가 외부로 나갔다" 시점. concatMap 이면 앞 청크의 응답이
            // 도착한 뒤에야 여기에 다시 들어온다.
            int now = inFlight.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            order.add(((kr.co.cudo.authoring.augment.integration.AugmentSubmitCommand) inv.getArgument(0))
                    .jobSeq());
            return Mono.delay(Duration.ofMillis(30))
                    .map(t -> AugmentSubmitResult.accepted("ext-" + t))
                    .doFinally(sig -> inFlight.decrementAndGet());
        });
        org.mockito.Mockito.doAnswer(inv -> {
            done.countDown();
            return null;
        }).when(outcomeRecorder).onSubmitSequenceFinished(anyLong());

        newService(scheduler).submit(event());

        assertThat(done.await(10, TimeUnit.SECONDS)).as("시퀀스 종료 판정이 나와야 한다").isTrue();
        assertThat(maxConcurrent.get())
                .as("병렬 발사(flatMap)로 되돌리면 2 이상이 되어 RED — 청크 사이 신고 재판정 방어가 무력화된다")
                .isEqualTo(1);
        assertThat(order).containsExactly(1, 2, 3);
    }

    /**
     * ★ M2 — 전용 풀이 포화(AbortPolicy)돼 {@code publishOn} 이 거부되면, 그 신호는 <b>이벤트 루프</b>
     * 에서 흐른다. 이때 완료 기록(JPA)을 하거나 다음 청크를 구독하면 이벤트 루프가 블로킹된다.
     * 따라서 기록도 다음 청크도 하지 않고 중단해야 한다(회수는 만료 스윕 담당).
     */
    @Test
    @DisplayName("전용풀_거부시_이벤트루프에서_기록하지_않고_남은_청크도_중단한다")
    void poolRejectionStopsSequenceWithoutDbWrite() {
        seedFrames(250); // 3 청크
        Scheduler rejecting = new RejectingScheduler();
        given(externalClient.requestAugment(any()))
                .willAnswer(inv -> Mono.just(AugmentSubmitResult.accepted("ext-1")));

        newService(rejecting).submit(event());

        // 거부는 onError 로 흐르지만 JPA 기록은 한 건도 하지 않는다.
        verify(outcomeRecorder, never()).onAccepted(anyLong(), anyLong(), anyString(), anyInt(), anyInt());
        verify(outcomeRecorder, never()).onSubmitFailed(anyLong(), anyLong(), anyInt(), anyInt(), any());
        // 종결 판정도 같은 풀로 디스패치되므로 거부되어 수행되지 않는다(만료 스윕이 회수).
        verify(outcomeRecorder, never()).onSubmitSequenceFinished(anyLong());
        // 첫 청크에서 끊겼으므로 두 번째 청크는 나가지 않는다.
        verify(externalClient, org.mockito.Mockito.times(1)).requestAugment(any());
    }

    /** 항상 거부하는 스케줄러 — 전용 풀 포화(AbortPolicy) 재현. */
    private static final class RejectingScheduler implements Scheduler {

        @Override
        public reactor.core.Disposable schedule(Runnable task) {
            throw new RejectedExecutionException("pool saturated (test)");
        }

        @Override
        public Worker createWorker() {
            return new Worker() {
                @Override
                public reactor.core.Disposable schedule(Runnable task) {
                    throw new RejectedExecutionException("pool saturated (test)");
                }

                @Override
                public void dispose() {
                }
            };
        }
    }
}
