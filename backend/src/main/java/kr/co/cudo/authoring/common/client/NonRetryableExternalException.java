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

    /**
     * 외부 응답 본문이 실어 보낸 <b>벤더 오류 코드</b>. 없으면 null.
     *
     * <p>{@link #statusCode} 와 같은 이유로 <b>값만</b> 보존한다 — 소비 계층이 사유를 구분하려고
     * 메시지를 파싱하면 문구가 바뀔 때 조용히 오분류된다.
     *
     * <p>외부 시계열 위탁의 경우 <b>정의되지 않은 이벤트 유형일 때만</b> 본문에 코드가 실린다.
     * 저작도구는 이벤트 유형을 <b>우리 허용목록으로 사전 차단하지 않고 벤더 응답이 판정하게</b> 하므로,
     * 이 코드가 그 판정의 <b>정확한 사유</b>를 주는 단일 근거다. 그것이 없으면 실패 기록이
     * "4xx 응답"으로만 남아 <b>왜 거부됐는지 알 수 없다</b>.
     *
     * <p>⚠ <b>응답 본문 원문은 여전히 보존하지 않는다</b>(CWE-209). 정수 코드만 담는다.
     */
    private final Integer vendorCode;

    public NonRetryableExternalException(String message) {
        this(message, (Integer) null);
    }

    public NonRetryableExternalException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
        this.vendorCode = null;
    }

    /**
     * 상태 코드를 보존하는 생성자.
     *
     * @param statusCode 외부 응답 상태 코드(4xx). 알 수 없으면 null
     */
    public NonRetryableExternalException(String message, Integer statusCode) {
        this(message, statusCode, null);
    }

    /**
     * 상태 코드와 <b>벤더 오류 코드</b>를 함께 보존하는 생성자.
     *
     * @param statusCode 외부 응답 상태 코드(4xx). 알 수 없으면 null
     * @param vendorCode 외부 응답 본문이 실어 보낸 벤더 오류 코드. 없거나 읽지 못했으면 null
     *                   (지어내지 않는다 — 본문이 JSON 이 아니거나 코드가 없는 것이 정상 응답이다)
     */
    public NonRetryableExternalException(String message, Integer statusCode, Integer vendorCode) {
        super(message);
        this.statusCode = statusCode;
        this.vendorCode = vendorCode;
    }

    /** 외부 응답 상태 코드(없으면 null) — 결정적 거부(404/409) 판별에 쓴다. */
    public Integer getStatusCode() {
        return statusCode;
    }

    /** 벤더 오류 코드(없으면 null) — 「미지원 이벤트 유형」 등 거부 사유 판별에 쓴다. */
    public Integer getVendorCode() {
        return vendorCode;
    }
}
