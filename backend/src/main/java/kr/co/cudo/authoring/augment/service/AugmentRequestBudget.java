package kr.co.cudo.authoring.augment.service;

import java.time.Duration;

/**
 * 요청 1회의 <b>외부 호출 wall-clock 예산</b> — 인바운드 요청 하나가 스레드를 무한정 점유하지 못하게
 * 한다 (DEV_FIX HIGH-1, CWE-770/400).
 *
 * <h3>왜 "호출 건수 상한" 으로는 부족한가</h3>
 * <p>진행률 조회·취소는 비종결 청크마다 외부 왕복을 하나씩 개시한다. 건수 상한(예: 10건)은
 * <b>시간 예산이 아니다</b> — 벤더가 건당 15초(블로킹 상한)를 다 쓰면 요청 하나가 150초, 결과회수까지
 * 겹치면 300초 동안 서블릿 스레드를 붙잡는다. Tomcat 기본 200 스레드에서 동시 폴링 200건이면 전 API가
 * 멈춘다. 게다가 <b>14초에 성공하는 느린 벤더</b>는 실패로 집계되지 않아 서킷이 영원히 열리지 않으므로
 * Resilience4j 로는 이 상황이 완화되지 않는다.
 *
 * <p>그래서 요청 단위 데드라인을 둔다. 이 저장소에는 같은 판단의 선례가 있다 —
 * {@code AutolabelOnlineService} 의 "개별 {@code block(60s)} 를 N 회 무한 반복하지 않도록 전체 처리에
 * 단일 데드라인" (폴리곤 배치 예산).
 *
 * <h3>속도 제한(RateLimiter)이 아니다</h3>
 * <p>도입하지 않기로 확정된 것은 <b>유입 제한</b>이다. 이 예산은 우리가 <b>스스로 소비하는 시간</b>의
 * 상한이라 그 결정과 충돌하지 않는다(자기 방어).
 *
 * <h3>예산 소진은 실패가 아니라 degrade / 부분 수행이다</h3>
 * <p>남은 외부 호출을 <b>개시하지 않고</b> 지금까지의 결과로 회신한다. 진행률은 왜곡 대신 null 로
 * 내려가고({@code QUERY_LIMIT_EXCEEDED}), 취소는 전달하지 못한 청크를 응답에 드러낸다.
 */
final class AugmentRequestBudget {

    /**
     * 남은 예산이 이 값보다 작으면 새 외부 호출을 <b>개시하지 않는다</b>.
     *
     * <p>남은 0.2초로 호출을 걸면 거의 확실히 타임아웃 예외가 나고, 그것은 "벤더 장애
     * ({@code TRANSIENT_ERROR})" 로 분류돼 <b>우리 자체 상한이 벤더 장애로 위장</b>된다. 개시 전에
     * 끊어야 사유가 정확해진다.
     */
    private static final Duration MIN_SLICE = Duration.ofSeconds(1);

    private final long deadlineNanos;

    private AugmentRequestBudget(Duration total) {
        this.deadlineNanos = System.nanoTime() + Math.max(0, total.toNanos());
    }

    /** 지금부터 {@code total} 동안 유효한 예산을 시작한다. */
    static AugmentRequestBudget start(Duration total) {
        return new AugmentRequestBudget(total);
    }

    /** 새 외부 호출을 개시할 만큼 예산이 남았는가. */
    boolean hasSlice() {
        return remainingNanos() >= MIN_SLICE.toNanos();
    }

    /**
     * 다음 호출에 걸 블로킹 상한 = <b>잔여 예산</b>. (호출 전 {@link #hasSlice()} 로 걸러진 뒤에만 쓴다.)
     *
     * <p>개별 호출 상한({@code AugmentExternalProbe} 의 {@code query-block-timeout-seconds})과의 min 은
     * probe 가 적용한다 — 상한 두 개를 양쪽에서 계산하면 한쪽만 고쳤을 때 조용히 어긋난다.
     */
    Duration remaining() {
        return Duration.ofNanos(Math.max(1, remainingNanos()));
    }

    private long remainingNanos() {
        return deadlineNanos - System.nanoTime();
    }
}
