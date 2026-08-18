package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 온디맨드 AI 추론 <b>취소</b> — 진행 중인 호출이 실제로 끊기는가.
 *
 * <h3>이 테스트가 증명하는 것</h3>
 * <ul>
 *   <li>취소하면 대기가 <b>즉시</b> 풀리고 {@link AiCallCancelledException} 이 난다.</li>
 *   <li>취소가 <b>구독 해지</b>로 이어진다 — 그래야 WebClient 가 추론 서버 연결을 끊는다.</li>
 *   <li>취소는 <b>서킷 브레이커의 실패로 집계되지 않는다</b> — 사용자가 누른 취소 때문에 다른
 *       사람의 추론이 막히면 안 된다.</li>
 *   <li>스코프가 없는 경로(배치)는 <b>기존 블로킹 동작 그대로</b>다.</li>
 *   <li>취소된 뒤에는 <b>새 추론을 시작하지 않는다</b> — 프레임을 훑는 경로에서 특히 중요하다.</li>
 * </ul>
 */
// ★ 제한시간 필수 — 취소가 깨지면 이 테스트들은 «실패» 가 아니라 «영원히 멈춤» 이 된다(실측: 취소
//   배선을 지우니 빌드가 10분을 넘겨 강제 종료됐다). 멈춘 테스트는 원인을 가리므로 빠르게 죽인다.
@Timeout(20)
class AiCallCancellationTest {

    private final AiCallCancellationRegistry registry = new AiCallCancellationRegistry();
    private final ExecutorService canceller = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        registry.unbind();
        canceller.shutdownNow();
    }

    @Test
    @DisplayName("취소하면_진행_중이던_대기가_풀리고_취소_예외가_난다")
    void cancelUnblocksTheWait() throws Exception {
        AiCallScope scope = registry.open("req-1", "worker-1");
        CountDownLatch subscribed = new CountDownLatch(1);
        Mono<String> never = Mono.<String>never().doOnSubscribe(s -> subscribed.countDown());

        canceller.submit(() -> {
            awaitQuietly(subscribed);
            registry.cancel("req-1", "worker-1");
        });

        assertThatThrownBy(() -> CancellableAiCall.block(never))
                .isInstanceOf(AiCallCancelledException.class);
        scope.close();
    }

    @Test
    @DisplayName("취소는_구독_해지로_이어진다_추론_서버_연결이_실제로_끊긴다")
    void cancelPropagatesAsSubscriptionCancellation() throws Exception {
        AiCallScope scope = registry.open("req-2", "worker-1");
        AtomicBoolean cancelledUpstream = new AtomicBoolean(false);
        CountDownLatch subscribed = new CountDownLatch(1);
        Mono<String> never = Mono.<String>never()
                .doOnSubscribe(s -> subscribed.countDown())
                .doOnCancel(() -> cancelledUpstream.set(true));

        canceller.submit(() -> {
            awaitQuietly(subscribed);
            registry.cancel("req-2", "worker-1");
        });

        assertThatThrownBy(() -> CancellableAiCall.block(never))
                .isInstanceOf(AiCallCancelledException.class);
        // 예외만 던지고 구독을 그대로 두면 서버는 계속 기다리고 GPU 도 물려 있다 — 그것이 핵심 결함이다.
        assertThat(cancelledUpstream).isTrue();
        scope.close();
    }

    @Test
    @DisplayName("취소는_서킷_브레이커의_실패로_집계되지_않는다")
    void cancellationIsNotACircuitBreakerFailure() throws Exception {
        CircuitBreaker breaker = CircuitBreaker.of("ai-test", CircuitBreakerConfig.custom()
                .minimumNumberOfCalls(1)
                .slidingWindowSize(2)
                .failureRateThreshold(50)
                .build());
        AiCallScope scope = registry.open("req-3", "worker-1");
        CountDownLatch subscribed = new CountDownLatch(1);
        Mono<String> guarded = Mono.<String>never()
                .doOnSubscribe(s -> subscribed.countDown())
                .transformDeferred(CircuitBreakerOperator.of(breaker));

        canceller.submit(() -> {
            awaitQuietly(subscribed);
            registry.cancel("req-3", "worker-1");
        });

        assertThatThrownBy(() -> CancellableAiCall.block(guarded))
                .isInstanceOf(AiCallCancelledException.class);

        assertThat(breaker.getMetrics().getNumberOfFailedCalls())
                .as("사용자가 누른 취소가 실패로 집계되면 서킷이 열려 남의 추론까지 막힌다")
                .isZero();
        assertThat(breaker.getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
        scope.close();
    }

    @Test
    @DisplayName("이미_취소된_요청은_새_추론을_시작하지_않는다")
    void doesNotStartNewCallsAfterCancel() {
        AiCallScope scope = registry.open("req-4", "worker-1");
        registry.cancel("req-4", "worker-1");

        AtomicBoolean subscribed = new AtomicBoolean(false);
        Mono<String> next = Mono.fromSupplier(() -> {
            subscribed.set(true);
            return "결과";
        });

        assertThatThrownBy(() -> CancellableAiCall.block(next))
                .isInstanceOf(AiCallCancelledException.class);
        // 프레임을 훑는 경로에서 이게 없으면 «취소했는데 남은 프레임이 계속 나간다» 가 된다.
        assertThat(subscribed).isFalse();
        scope.close();
    }

    @Test
    @DisplayName("스코프가_없는_경로는_기존_블로킹_동작_그대로다_배치_무영향")
    void unscopedCallsBehaveExactlyAsBefore() {
        // 배치 파이프라인 스레드에는 스코프가 매이지 않는다.
        assertThat(CancellableAiCall.block(Mono.just("결과"))).isEqualTo("결과");
        assertThat(CancellableAiCall.block(Mono.just("결과"), Duration.ofSeconds(1))).isEqualTo("결과");
    }

    @Test
    @DisplayName("추적_중인_요청도_결과가_오면_평소대로_값을_돌려준다")
    void scopedCallStillReturnsValue() {
        AiCallScope scope = registry.open("req-5", "worker-1");
        assertThat(CancellableAiCall.block(Mono.just("결과"), Duration.ofSeconds(5))).isEqualTo("결과");
        scope.close();
    }

    @Test
    @DisplayName("제한시간을_넘기면_취소가_아니라_기존과_같은_형태로_실패한다")
    void timeoutKeepsExistingFailureShape() {
        AiCallScope scope = registry.open("req-6", "worker-1");
        assertThatThrownBy(() -> CancellableAiCall.block(Mono.never(), Duration.ofMillis(50)))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(AiCallCancelledException.class);
        scope.close();
    }

    @Test
    @DisplayName("남의_식별자로는_취소되지_않는다")
    void cannotCancelSomeoneElsesRequest() {
        AiCallScope scope = registry.open("req-7", "worker-1");
        assertThat(registry.cancel("req-7", "worker-2")).isFalse();
        assertThat(scope.isCancelled()).isFalse();
        scope.close();
    }

    @Test
    @DisplayName("요청이_끝나면_등록이_지워져_뒤늦은_취소가_남지_않는다")
    void closingScopeRemovesRegistration() {
        AiCallScope scope = registry.open("req-8", "worker-1");
        assertThat(registry.trackedCount()).isEqualTo(1);
        scope.close();
        assertThat(registry.trackedCount()).isZero();
        assertThat(registry.cancel("req-8", "worker-1")).isFalse();
    }

    @Test
    @DisplayName("형식에_맞지_않는_식별자는_추적하지_않는다_로그_위조_방지")
    void rejectsMalformedRequestId() {
        AiCallScope scope = registry.open("bad id\nwith newline", "worker-1");
        assertThat(registry.trackedCount()).isZero();
        scope.close();
    }

    @Test
    @DisplayName("추적_상한을_넘으면_추적만_포기하고_추론은_그대로_한다")
    void giveUpTrackingButNeverTheInference() {
        AtomicReference<AiCallScope> last = new AtomicReference<>();
        for (int i = 0; i < AiCallCancellationRegistry.MAX_TRACKED; i++) {
            // 요청이 끝나지 않은 상태를 흉내낸다(close 하지 않는다).
            registry.open("req-fill-" + i, "worker-1");
        }
        registry.unbind();
        last.set(registry.open("req-over", "worker-1"));

        assertThat(registry.trackedCount()).isEqualTo(AiCallCancellationRegistry.MAX_TRACKED);
        // 추적은 못 해도 추론 자체는 평소대로 수행돼야 한다.
        assertThat(CancellableAiCall.block(Mono.just("결과"))).isEqualTo("결과");
        last.get().close();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
