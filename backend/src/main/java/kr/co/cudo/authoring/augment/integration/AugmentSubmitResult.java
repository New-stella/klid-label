package kr.co.cudo.authoring.augment.integration;

/**
 * 외부 증강 위탁 202 응답 — 「생성형 AI API 연동명세서 v1.1」 §4.1.
 *
 * <p><b>job_id 발급 주체는 외부</b>다. 본 도구는 {@code request_id} 만 발급하고, 외부가 202 로
 * 돌려준 {@code job_id} 를 {@code LS_DATA_AUG_JOB.OTSD_JOB_ID} 에 적재한다.
 *
 * @param externalJobId 외부가 발급한 job_id. 외부 호출을 하지 않은 구현체(noop)는 null.
 * @param status        외부 접수 상태(정상 위탁이면 {@code RECEIVED})
 */
public record AugmentSubmitResult(String externalJobId, String status) {

    /** 명세서 §3.2 최초 접수 상태. */
    public static final String STATUS_RECEIVED = "RECEIVED";
    /** 외부 호출을 수행하지 않은 구현체(noop)의 상태 표기. */
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static AugmentSubmitResult accepted(String externalJobId) {
        return new AugmentSubmitResult(externalJobId, STATUS_RECEIVED);
    }

    /** 외부 호출 없이 통과 — job_id 가 없으므로 null 을 명시한다(가짜 ID 생성 금지). */
    public static AugmentSubmitResult skipped() {
        return new AugmentSubmitResult(null, STATUS_SKIPPED);
    }
}
