package kr.co.cudo.authoring.common.client;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 추론 응답을 기다리되 <b>중간에 끊을 수 있는</b> 블로킹 대기.
 *
 * <h3>왜 {@code Mono.block()} 을 그대로 두지 않았나</h3>
 * <p>{@code block()} 은 결과가 오거나 제한시간이 될 때까지 <b>아무도 개입할 수 없다</b>. 사용자가
 * 취소를 눌러도 서버는 계속 기다리고, 그 사이 추론 서버는 GPU 를 물고 있다. 여기서는 같은 대기를
 * {@link CompletableFuture} 로 바꿔 <b>다른 스레드가 취소할 수 있는 손잡이</b>를 남긴다.
 *
 * <h3>취소 대상이 아닌 호출은 동작이 «완전히» 같다</h3>
 * <p>스레드에 매인 스코프가 없으면(배치 파이프라인, 취소 식별자를 싣지 않은 요청) 곧바로
 * {@code mono.block(...)} 으로 내려간다. 배치 경로가 이 변경의 영향을 받지 않는 근거가 이것이다.
 *
 * <h3>예외 형태를 바꾸지 않는다</h3>
 * <p>기존 호출자들은 {@code catch (RuntimeException)} / {@code catch (Exception)} 으로 502 로 바꾸고
 * 있다. 그래서 이 헬퍼는 원인 예외를 <b>그대로</b> 다시 던지고, 제한시간 초과도 {@code block(Duration)}
 * 과 같은 {@link IllegalStateException} 으로 낸다 — 취소만 전용 예외로 구분한다.
 */
public final class CancellableAiCall {

    private CancellableAiCall() {
    }

    /** 제한시간 없는 대기 — {@code mono.block()} 자리. */
    public static <T> T block(Mono<T> mono) {
        return block(mono, null);
    }

    /**
     * 제한시간 있는 대기 — {@code mono.block(timeout)} 자리.
     *
     * @param timeout {@code null} 이면 제한 없음
     * @throws AiCallCancelledException 사용자가 취소했을 때
     * @throws IllegalStateException    제한시간을 넘겼을 때({@code block(Duration)} 과 같은 형태)
     */
    public static <T> T block(Mono<T> mono, Duration timeout) {
        AiCallScope scope = AiCallCancellationRegistry.current();
        if (scope == null) {
            // 취소 대상이 아닌 경로 — 기존 동작 그대로.
            return timeout == null ? mono.block() : mono.block(timeout);
        }
        if (scope.isCancelled()) {
            // 이미 취소된 요청이면 새 추론을 시작하지 않는다. 프레임을 훑는 경로에서 특히 중요하다 —
            // 여기서 끊지 않으면 취소 이후에도 남은 프레임이 계속 추론 서버로 나간다.
            throw new AiCallCancelledException();
        }

        CompletableFuture<T> call = mono.toFuture();
        scope.register(call);
        try {
            return timeout == null
                    ? call.get()
                    : call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (CancellationException e) {
            throw new AiCallCancelledException();
        } catch (TimeoutException e) {
            // 기다림을 포기하면 구독도 해지한다 — 안 하면 응답을 아무도 안 받는 채로 계속 돈다.
            call.cancel(true);
            throw new IllegalStateException("Timeout on blocking read for "
                    + timeout.toMillis() + " ms", e);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("추론 대기 중 중단되었습니다.", e);
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        } finally {
            scope.unregister(call);
        }
    }

    /**
     * 원인 예외를 형태 그대로 올린다 — 호출자의 {@code catch} 분기가 바뀌지 않게.
     * {@code RuntimeException} 이 아닌 원인만 감싼다.
     */
    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(cause);
    }
}
