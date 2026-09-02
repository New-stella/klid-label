package kr.co.cudo.authoring.augment.integration;

/**
 * 외부 증강 위탁 202 응답 — 「생성형 AI API 연동명세서 v1.3」 §4.1.
 *
 * <p><b>job_id 발급 주체는 외부</b>다. 본 도구는 {@code request_id} 만 발급하고, 외부가 202 로
 * 돌려준 {@code job_id} 를 {@code LS_DATA_AUG_JOB.OTSD_JOB_ID} 에 적재한다.
 *
 * <h3>불변식은 타입이 강제한다</h3>
 * <p>{@link AugmentQueryResult} 와 같은 계열 규칙이다 — {@code RECEIVED} 면 job_id 가 <b>반드시</b>
 * 있고({@code null} 이면 "성공했는데 추적 불가"), {@code SKIPPED} 면 <b>반드시</b> 없다(있으면 우리가
 * 지어낸 값이다 — job_id 발급 주체는 외부다). 계약 밖 status 도 접수하지 않는다.
 *
 * @param externalJobId 외부가 발급한 job_id. 외부 호출을 하지 않은 구현체(noop)는 null.
 * @param status        외부 접수 상태(정상 위탁이면 {@code RECEIVED})
 */
public record AugmentSubmitResult(String externalJobId, String status) {

    /** 명세서 §3.2 최초 접수 상태. */
    public static final String STATUS_RECEIVED = "RECEIVED";
    /** 외부 호출을 수행하지 않은 구현체(noop)의 상태 표기. */
    public static final String STATUS_SKIPPED = "SKIPPED";

    public AugmentSubmitResult {
        boolean hasJobId = externalJobId != null && !externalJobId.isBlank();
        if (STATUS_RECEIVED.equals(status)) {
            if (!hasJobId) {
                throw new IllegalArgumentException("위탁 접수(RECEIVED) 결과에는 job_id 가 있어야 한다.");
            }
        } else if (STATUS_SKIPPED.equals(status)) {
            if (hasJobId) {
                throw new IllegalArgumentException("미위탁(SKIPPED) 결과는 job_id 를 가질 수 없다.");
            }
        } else {
            throw new IllegalArgumentException("계약 밖 위탁 상태다: " + status);
        }
    }

    public static AugmentSubmitResult accepted(String externalJobId) {
        return new AugmentSubmitResult(externalJobId, STATUS_RECEIVED);
    }

    /** 외부 호출 없이 통과 — job_id 가 없으므로 null 을 명시한다(가짜 ID 생성 금지). */
    public static AugmentSubmitResult skipped() {
        return new AugmentSubmitResult(null, STATUS_SKIPPED);
    }
}
