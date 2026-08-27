package kr.co.cudo.authoring.augment.integration.dto;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 「생성형 AI API 연동명세서 v1.3」의 <b>코드 공간 단일 원천</b> — Phase 7-A2 (INT-020/030/031).
 *
 * <p>상태·오류코드·미디어유형을 <b>enum 이 아니라 String + 화이트리스트</b>로 다룬다. 이유는 둘이다:
 * <ul>
 *   <li><b>역직렬화 안정성</b> — Jackson 이 미지의 enum 값을 만나면 파싱 자체가 깨져(500) 응답 전체를
 *       잃는다. String 으로 받아두면 "무엇이 왔는지" 를 로그·예외 메시지로 남길 수 있다.</li>
 *   <li><b>fail-closed 판정을 우리가 쥔다</b> — 미지의 status 를 아는 값처럼 흡수하면 롤업이 성공/실패를
 *       오판한다(영구 대기 또는 SUCCEEDED 누락). 매칭 실패는 <b>명시 실패</b>로 끊는다.</li>
 * </ul>
 *
 * <p>{@link #KNOWN_STATUSES} 는 {@code LsDataAugJob.STTS_*}(§3.2 상태머신)와 <b>같은 코드 공간</b>이며
 * 드리프트는 테스트가 감시한다. {@code LS_DATA_AUG.AUG_PROC_STTS_CD}(검수 결과 축)와는 다른 공간이다.
 */
public final class GenAiContract {

    /** §3.2 상태머신 — 최초 접수. */
    public static final String STATUS_RECEIVED = "RECEIVED";
    /** §3.2 상태머신 — 처리중(progress 0~100). */
    public static final String STATUS_RUNNING = "RUNNING";
    /** §3.2 상태머신 — 성공 종결. */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    /** §3.2 상태머신 — 실패 종결. */
    public static final String STATUS_FAILED = "FAILED";
    /** §3.2 상태머신 — 취소 종결. */
    public static final String STATUS_CANCELED = "CANCELED";

    private static final Set<String> KNOWN_STATUSES = Set.of(
            STATUS_RECEIVED, STATUS_RUNNING, STATUS_SUCCEEDED, STATUS_FAILED, STATUS_CANCELED);

    /** §4.2/§4.5 {@code media_type}. */
    public static final String MEDIA_TYPE_IMAGE = "IMAGE";
    /** §4.2/§4.5 {@code media_type}. */
    public static final String MEDIA_TYPE_VIDEO = "VIDEO";

    private static final Set<String> KNOWN_MEDIA_TYPES = Set.of(MEDIA_TYPE_IMAGE, MEDIA_TYPE_VIDEO);

    /**
     * §3.3 오류 코드. <b>판정 축이 아니라 분류 축</b>이다 — 성공/실패 판정은 {@code status} 가 하므로
     * 미등록 오류코드가 왔다고 응답 전체를 실패시키지 않는다(벤더가 코드를 늘리면 조회가 통째로 죽는다).
     * 대신 원문을 보존하고 "미등록" 을 드러내 운영이 인지하게 한다.
     */
    private static final Set<String> KNOWN_ERROR_CODES = Set.of(
            "REQUIRED_FIELD_MISSING", "UNSUPPORTED_EVENT_TYPE", "INVALID_METADATA",
            "INVALID_PARAMETER", "REQUEST_NOT_FOUND", "JOB_NOT_FOUND", "RESULT_NOT_FOUND",
            "STATE_CONFLICT", "GA-MEDIA-001", "MODEL_EXECUTION_FAILED", "RESULT_SAVE_FAILED",
            "CALLBACK_FAILED", "INTERNAL_SERVER_ERROR");

    /**
     * 외부 발급 {@code job_id} 허용 형식 — 웹훅 수신 DTO({@code GenAiCallbackRequest.jobId})와 동일 규약.
     *
     * <p>이 값은 URL <b>경로 세그먼트</b>로 들어가므로 형식 검증이 곧 경로 조작 방어다(CWE-22).
     * URI 템플릿 인코딩과 <b>둘 다</b> 적용한다(방어선 이중화).
     *
     * <p><b>선두 {@code (?!\.+$)} 가 핵심이다</b> — {@code .} 는 문자 클래스 안에서 리터럴이라 구 패턴은
     * {@code ".."} 를 통과시켰다. {@code .} 는 RFC 3986 unreserved 라 URI 템플릿이 인코딩하지 않고
     * {@code URI.create} 도 정규화하지 않으므로, 요청 라인에 {@code /api/genai/jobs/../results} 가 그대로
     * 실려 <b>정규화하는 서버·프록시에서 한 단계 상승</b>한다({@code /api/genai/results}). {@code /} 가
     * 없어 다단계 상승·호스트 변경은 불가능하지만, "형식 검증이 경로 조작을 막는다" 는 주장이 사실이
     * 되려면 점만으로 이뤄진 세그먼트를 끊어야 한다. 점을 <b>포함</b>하는 정상 id 는 계속 허용한다.
     */
    private static final Pattern JOB_ID = Pattern.compile("^(?!\\.+$)[A-Za-z0-9_.:-]{1,200}$");

    private GenAiContract() {
    }

    /** 드리프트 감시용 — 상태 코드 공간 전체. */
    public static Set<String> knownStatuses() {
        return KNOWN_STATUSES;
    }

    /** 드리프트 감시용 — 오류 코드 공간 전체(계약 정본은 벤더 목 {@code ErrorCode} enum). */
    public static Set<String> knownErrorCodes() {
        return KNOWN_ERROR_CODES;
    }

    public static boolean isKnownStatus(String status) {
        return status != null && KNOWN_STATUSES.contains(status);
    }

    public static boolean isKnownMediaType(String mediaType) {
        return mediaType != null && KNOWN_MEDIA_TYPES.contains(mediaType);
    }

    public static boolean isKnownErrorCode(String errorCode) {
        return errorCode != null && KNOWN_ERROR_CODES.contains(errorCode);
    }

    /** 외부 job_id 가 계약 형식인가 — 위반이면 외부 호출 자체를 개시하지 않는다(fail-fast). */
    public static boolean isValidJobId(String jobId) {
        return jobId != null && JOB_ID.matcher(jobId).matches();
    }

    /**
     * §4.1 {@code evnt_type} 허용 코드 — <b>우리가 보내는 값</b>이라 enum 으로 닫는다.
     *
     * <p>위쪽 상태·오류 코드 공간이 String + 화이트리스트인 것과 <b>방향이 반대</b>다: 그쪽은 벤더가
     * 보내오는 값이라 미지의 값에 파싱이 깨지면 응답 전체를 잃지만, 이쪽은 우리가 조립하는 값이라
     * 계약 밖 값을 만들 수 있는 것 자체가 결함이다(요청이 {@code 400} 으로 되돌아온다).
     *
     * <p><b>영상의 관제 이벤트 코드에서 변환하지 않는다</b>: 두 분류 축이 서로 다른 체계라 자동 변환은
     * 추정이 되고, 추정한 값이 그대로 위탁에 실린다. 요청자가 화면에서 고른 값을 그대로 중계한다.
     *
     * @design INT-008
     */
    public enum EventType {
        FLOOD, WILDFIRE
    }

    /**
     * §4.1 {@code evnt_subtype} 허용 코드 — <b>침수 전용</b>(선택).
     *
     * <p>계약에 산불 세부 코드가 정의돼 있지 않으므로 {@link EventType#WILDFIRE} 와 함께 보내면
     * {@code 400} 이다. 그 조합은 우리 접수 단계에서 먼저 끊는다.
     *
     * @design INT-008
     */
    public enum FloodSubtype {
        ROAD_FLOOD, RIVER_OVERFLOW, UNDERPASS_FLOOD, URBAN_INUNDATION, OTHER
    }
}
