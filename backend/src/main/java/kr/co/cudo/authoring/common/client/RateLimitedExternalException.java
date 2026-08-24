package kr.co.cudo.authoring.common.client;

/**
 * 외부 API <b>동시 처리 한도 초과(429)</b> 마커 예외 — 일시적 실패다.
 *
 * <p>{@link NonRetryableExternalException}(결정적 4xx)와 정반대 성격이라 분리한다. 429 는
 * "잠시 뒤 다시 보내면 되는 상태"이지 요청이 잘못된 것이 아니므로:
 *
 * <ul>
 *   <li><b>재시도한다</b> — Resilience4j retry 의 {@code ignore-exceptions} 에 <b>넣지 않는다</b>.</li>
 *   <li><b>서킷은 열지 않는다</b> — circuitbreaker 의 {@code ignore-exceptions} 에 <b>넣는다</b>.</li>
 * </ul>
 *
 * <h3>왜 서킷에서 빼는가</h3>
 * <p>서킷이 열리면 그 구간의 위탁이 즉시 실패해 <b>확정 실패로 기록되고 마킹이 종결 상태로 굳는다</b>.
 * 즉 "벤더가 잠시 바쁘다"가 "이 영상의 위탁은 실패했다"로 승격된다 — 이 분류가 막으려던 바로 그
 * 결과가 다른 문으로 들어오는 셈이다. 위탁 창구가 둘로 늘어 영상 1건당 호출이 2배가 된 뒤로는
 * 한도에 닿기가 더 쉬워져 이 구분이 실질적으로 중요해졌다.
 *
 * <p>과부하 자체에 대한 완충은 서킷이 아니라 <b>재시도의 지수 백오프</b>가 담당한다.
 *
 * <h3>보안</h3>
 * <p>CWE-209 — 외부 응답 본문·스택트레이스를 보존하지 않는다. 상태 코드 기반 판정 결과만 전달한다.
 */
public class RateLimitedExternalException extends RuntimeException {

    public RateLimitedExternalException(String message) {
        super(message);
    }
}
