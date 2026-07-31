package kr.co.cudo.authoring.common.client;

/**
 * 외부 API 4xx(결정적·비-일시적) 실패 마커 예외 (K3/V1).
 *
 * <p>4xx 응답(예: 400 형식오류, 404 미존재, 409 중복, 422 파라미터 값 오류)은 재전송해도 결과가
 * 바뀌지 않는 결정적 실패다. 본 예외로 감싸 Resilience4j retry·circuitbreaker 의
 * {@code ignore-exceptions} 에 등록함으로써 <b>재시도와 failure 집계에서 제외</b>한다. 5xx·네트워크
 * 오류만 재시도·서킷 집계 대상으로 남긴다.
 *
 * <p>이로써 정상적인 4xx(예: 중복 409)가 불필요한 지수 백오프 지연을 유발하거나 서킷브레이커를 여는
 * 것을 방지한다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>CWE-209: 외부 응답 원문/스택트레이스를 보존하지 않는다. 상태 코드 기반 매핑 결과만 전달한다.</li>
 * </ul>
 *
 * <p>KPST 경로는 상태코드→{@code ErrorCode} 매핑 결과를 담은 {@code CustomException} 을 {@link #getCause()}
 * 로 실어, 파이프라인 말미 {@code onErrorMap} 이 최종 사용자-대면 예외로 복원한다. VLM 경로는 별도
 * 매핑 없이 본 예외 자체가 호출자(Step)로 전파된다.
 *
 * <h3>{@link #getStatusCode()} — 상태 코드를 <b>값</b>으로 보존한다 (DEV_FIX MED-7)</h3>
 * <p>메시지 문자열에만 상태를 담으면 소비 계층이 "결정적 거부(404/409)" 와 "타임아웃·5xx" 를 구분하려
 * 할 때 <b>메시지를 파싱</b>하게 된다. 그 결과 실제로는 수행 불가능한 재시도 안내가 나가고(증강 취소
 * 경로 실사례), 문구가 바뀌면 조용히 오분류된다. 상태 코드만 별도 필드로 들고 다닌다 —
 * <b>응답 본문/원문은 여전히 보존하지 않는다</b>(CWE-209).
 */
public class NonRetryableExternalException extends RuntimeException {

    /** 외부 4xx 상태 코드. 상태 코드 기반 분류가 아닌 실패(디코딩 등)면 null. */
    private final Integer statusCode;

    public NonRetryableExternalException(String message) {
        this(message, (Integer) null);
    }

    public NonRetryableExternalException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
    }

    /**
     * 상태 코드를 보존하는 생성자.
     *
     * @param statusCode 외부 응답 상태 코드(4xx). 알 수 없으면 null
     */
    public NonRetryableExternalException(String message, Integer statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /** 외부 응답 상태 코드(없으면 null) — 결정적 거부(404/409) 판별에 쓴다. */
    public Integer getStatusCode() {
        return statusCode;
    }
}
