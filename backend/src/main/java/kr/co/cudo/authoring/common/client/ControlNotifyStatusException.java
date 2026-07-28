package kr.co.cudo.authoring.common.client;

/**
 * 관제 통지 4xx 응답 — 상태코드를 보존하는 결정적 실패 예외.
 *
 * <p>{@link NonRetryableExternalException} 을 상속하므로 Resilience4j retry·circuitbreaker 의
 * {@code ignore-exceptions} 에 걸려 <b>재시도·failure 집계에서 제외</b>된다. 다만 ignore-exceptions 는
 * "재시도하지 않고 <b>그대로 던진다</b>" 는 의미이므로, 예외 자체는 호출자(ControlNotifyService)까지
 * 전파되어 409/404 자기치유 분기를 발동시킨다(S4·S5).
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>CWE-209: 외부 응답 본문을 사용자 대면 메시지에 싣지 않는다. 진단용 요약({@link #bodySummary()})은
 *       200자로 절단하고 개행(CRLF)을 제거해(CWE-117 로그 위조 방어) 운영 로그에만 사용한다.</li>
 * </ul>
 */
public class ControlNotifyStatusException extends NonRetryableExternalException {

    /** 진단 로그에 남길 응답 본문 최대 길이. */
    private static final int MAX_BODY_SUMMARY = 200;

    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_NOT_FOUND = 404;

    private final int statusCode;
    private final String bodySummary;

    public ControlNotifyStatusException(int statusCode, String rawBody) {
        super("관제 통지 거부 status=" + statusCode);
        this.statusCode = statusCode;
        this.bodySummary = sanitize(rawBody);
    }

    public int statusCode() {
        return statusCode;
    }

    /** 진단용 응답 본문 요약 (절단·개행 제거). 절대 사용자 응답에 노출하지 않는다. */
    public String bodySummary() {
        return bodySummary;
    }

    /** 409 — 이미 등록된 job_id (최초 완료 통지 중복). */
    public boolean isConflict() {
        return statusCode == HTTP_CONFLICT;
    }

    /** 404 — 선행 완료 통지가 없는 job_id. */
    public boolean isNotFound() {
        return statusCode == HTTP_NOT_FOUND;
    }

    private static String sanitize(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return "";
        }
        String oneLine = rawBody.replaceAll("[\\r\\n]+", " ").trim();
        return oneLine.length() <= MAX_BODY_SUMMARY
                ? oneLine
                : oneLine.substring(0, MAX_BODY_SUMMARY);
    }
}
