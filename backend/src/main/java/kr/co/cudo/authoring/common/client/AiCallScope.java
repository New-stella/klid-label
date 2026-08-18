package kr.co.cudo.authoring.common.client;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <b>취소 가능한 한 요청</b>의 범위 — 그 요청이 띄운 추론 호출들을 붙잡고 있다가 한꺼번에 끊는다.
 *
 * <p>{@code cancel()} 은 요청을 처리하는 스레드가 아니라 <b>다른 요청(취소 요청)의 스레드</b>에서
 * 불린다. 그래서 상태는 전부 동시성 안전한 자료구조로 둔다.
 *
 * <h3>취소 = 구독 해지 (예외를 던지는 것이 아니다)</h3>
 * <p>등록된 {@link CompletableFuture} 는 {@code Mono.toFuture()} 가 만든 것이라, 그것을 취소하면
 * <b>구독이 해지</b>되어 WebClient 가 ai-server 와의 연결을 실제로 끊는다. 서버가 계속 돌면서 GPU 를
 * 물고 있는 것을 막는 지점이 바로 여기다.
 *
 * <p>서킷 브레이커 관점에서도 <b>취소는 실패가 아니다</b> — resilience4j 의 리액터 연산자는 구독
 * 해지를 «permit 반납» 으로 처리하고 실패로 집계하지 않는다(회귀 고정:
 * {@code AiCallCancellationTest.취소는_서킷_브레이커의_실패로_집계되지_않는다}).
 */
public final class AiCallScope implements AutoCloseable {

    /** 이 스코프를 닫을 때 레지스트리에서 지우기 위한 되부름. */
    @FunctionalInterface
    interface Closer {
        void close(AiCallScope scope);
    }

    /** 취소 식별자. {@code null} 이면 «추적하지 않는 스코프»(기존 동작과 완전히 같다). */
    private final String requestId;

    /** 취소를 허용할 주체(토큰 subject). 남의 요청을 끊지 못하게 하는 유일한 근거다. */
    private final String ownerId;

    private final Closer closer;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 아직 끝나지 않은 추론 호출들. 한 요청이 프레임을 훑으면 순차적으로 여러 건이 오간다. */
    private final Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();

    AiCallScope(String requestId, String ownerId, Closer closer) {
        this.requestId = requestId;
        this.ownerId = ownerId;
        this.closer = closer;
    }

    /** 추적하지 않는 스코프 — 취소 식별자를 싣지 않은 요청용. */
    static AiCallScope untracked() {
        return new AiCallScope(null, null, scope -> { });
    }

    boolean isTracked() {
        return requestId != null;
    }

    String requestId() {
        return requestId;
    }

    boolean isOwnedBy(String candidate) {
        return ownerId != null && ownerId.equals(candidate);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 진행 중인 추론 호출을 등록한다.
     *
     * <p>등록과 취소가 겹칠 수 있으므로 <b>등록 직후 다시 확인</b>한다 — 그 사이에 취소가 지나갔다면
     * 이 호출은 등록만 되고 아무도 끊지 않는 «유령» 이 된다.
     */
    void register(CompletableFuture<?> call) {
        inFlight.add(call);
        if (cancelled.get()) {
            call.cancel(true);
        }
    }

    void unregister(CompletableFuture<?> call) {
        inFlight.remove(call);
    }

    /**
     * 이 요청의 추론 호출을 전부 끊는다.
     *
     * @return 처음 취소한 경우 {@code true}. 이미 취소된 스코프면 {@code false}(멱등).
     */
    boolean cancel() {
        boolean first = cancelled.compareAndSet(false, true);
        // 이미 취소된 뒤에 등록된 호출이 남아 있을 수 있으므로 멱등 여부와 무관하게 훑는다.
        for (CompletableFuture<?> call : inFlight) {
            call.cancel(true);
        }
        return first;
    }

    @Override
    public void close() {
        closer.close(this);
    }
}
