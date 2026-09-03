package kr.co.cudo.authoring.augment.integration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 「생성형 AI API 연동명세서 v1.3」 §4.1 {@code POST /api/genai/jobs} 요청 바디.
 *
 * <p>필드명은 외부 계약(snake_case)을 그대로 따른다. 미선언 필드는 보내지 않는다.
 *
 * <h3>v1.3 — 생성 조건과 자유 지시문을 최상위에서 분리한다</h3>
 * <p>구 계약(v1.1)은 생성 조건 5항목을 {@code prompt} 라는 자유 구조 dict 로 받았다. v1.3 은 그것을
 * 최상위 {@link #mtdt} 로 옮기고 {@link #prompt} 를 <b>자유 지시문 문자열</b>로 재정의했다. 구 객체
 * 형식({@code prompt.condition}/{@code prompt.text})과 최상위 {@code condition} 은 계약에서 제외됐고,
 * 명세가 <i>"prompt 값으로 객체를 전달하지 않습니다"</i> 를 명시하며 {@code prompt} 에 객체를 실으면
 * {@code 400 INVALID_PARAMETER} 다. <b>{@code prompt} 의 타입을 Map 으로 되돌리지 말 것.</b>
 *
 * <p>레코드 컴포넌트 순서가 곧 직렬화 순서다 — 명세서 샘플과 같은 순서로 읽히게 유지한다.
 *
 * @param requestId      우리가 발급한 멱등 키(1~64)
 * @param requestChannel 요청 채널 — 저작도구는 {@code AUTHORING}
 * @param requestUserId  요청자(≤64, 선택)
 * @param evntType       이벤트 유형 — 서버 고정 {@link #EVENT_TYPE_ETC}(요청자가 고르지 않는다)
 * @param operationType  작업 유형 — 증강은 {@code AUGMENT}
 * @param generationMode 생성 모드 — 이미지→이미지 증강이므로 {@code I2I}
 * @param inputFiles     입력 파일(I2I 는 1건 이상 필수, 최대 100)
 * @param mtdt           구조화 생성 조건(허용 코드로 닫힌 5항목) — 계약상 최소 1항목, 우리는 전부 채운다
 * @param prompt         자유 지시문 문자열(≤1000, 선택). <b>객체가 아니다</b>
 * @param callbackUrl    결과 회신 URL(≤500, 선택)
 * @design INT-008
 * @design ADR-059
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenAiJobSubmitRequest(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("request_channel") String requestChannel,
        @JsonProperty("request_user_id") String requestUserId,
        @JsonProperty("evnt_type") String evntType,
        @JsonProperty("operation_type") String operationType,
        @JsonProperty("generation_mode") String generationMode,
        @JsonProperty("input_files") List<GenAiInputFile> inputFiles,
        @JsonProperty("mtdt") Map<String, Object> mtdt,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("callback_url") String callbackUrl) {

    /**
     * 요청 채널 — 저작도구 고정.
     *
     * <p>포털 증강 경로가 나중에 붙어도 이 값은 그대로다(2026-09-02 사용자 확정) — 이 값은
     * "어느 채널에 배포됐는가" 가 아니라 "누가 요청을 보내는가" 를 가리키며 그것은 언제나 저작도구다.
     * {@code PORTAL} 로 바꾸지 말 것.
     */
    public static final String CHANNEL_AUTHORING = "AUTHORING";

    /**
     * 이벤트 유형 — <b>서버가 고정 송신하는 중립값</b>(ADR-059 · INT-008).
     *
     * <h3>왜 요청자가 고르지 않는가</h3>
     * <p>벤더의 이미지 증강(I2I)은 <b>배경 이미지에 이벤트 장면을 만들어 넣는</b> 작업이라 이 값이
     * "무엇을 만들지" 를 정한다. 우리 증강은 <b>이미 이벤트가 담긴 프레임</b>을 겨울·야간·우천 등으로
     * 변환할 뿐이라 만들 장면을 정할 자리가 없다. 게다가 우리 이벤트 체계는 넓은데 벤더 허용값은
     * 좁아, 대응되지 않는 영상은 요청자가 <b>사실과 다른 값</b>을 고를 수밖에 없었다.
     *
     * <h3>★ 규격서 판본에는 아직 이 값이 없다 — 되돌리지 말 것</h3>
     * <p>{@code ETC} 는 <b>벤더와 협의가 끝난 값</b>이다(2026-09-02 사용자 확정). 다만 우리가 보유한
     * 「생성형 AI API 연동정의서 v1.3」(갱신일 2026-08-12) §4.1 의 {@code evnt_type} 허용값은
     * {@code FLOOD | WILDFIRE} 뿐이고 오류표에 {@code UNSUPPORTED_EVENT_TYPE}(400)이 있다.
     * <b>그 문서만 보고 "계약에 없는 값" 이라며 되돌리지 말 것</b> — 그때까지는 문서가 아니라 합의가
     * 근거다. 규격서 개정판을 받으면 값과 오류 코드를 대조한다.
     *
     * <p>참고: 위탁 주소가 주입되지 않은 형상에서는 실호출이 없다(전송 가드가 막는다).
     *
     * <p>세부 유형({@code evnt_subtype})은 <b>레코드 컴포넌트 자체를 두지 않는다</b> — 침수 전용 축이라
     * 중립값에서는 성립하지 않고, 필드가 없으면 키가 실릴 경로도 구조적으로 존재하지 않는다.
     */
    public static final String EVENT_TYPE_ETC = "ETC";
    /** 작업 유형 — 증강 고정. */
    public static final String OPERATION_AUGMENT = "AUGMENT";
    /** 생성 모드 — 증강 AI 는 이미지-to-이미지(프레임 이미지만 변환)라 I2I 고정. */
    public static final String MODE_I2I = "I2I";
}
