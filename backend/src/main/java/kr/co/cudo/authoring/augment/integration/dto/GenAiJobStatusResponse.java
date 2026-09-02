package kr.co.cudo.authoring.augment.integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 「생성형 AI API 연동명세서 v1.3」 §4.2 작업 상태 조회 응답(200) — INT-020.
 *
 * <p>{@code GET /api/genai/jobs/{job_id}} 의 본문이다. 상태·오류코드는 <b>enum 이 아니라 String</b>
 * 으로 받고 {@link GenAiContract} 화이트리스트로 판정한다(미지 값에 파싱이 깨지지 않게).
 *
 * <p><b>이 응답은 DB 를 덮어쓰는 근거가 아니다</b> — 진행 상태의 단일 진실원은
 * {@code LS_DATA_AUG_JOB.JOB_STTS_CD}(웹훅으로 갱신)이며, 본 조회는 "웹훅이 아직 안 왔을 때의
 * 보조 신호" 다. 자세한 규약은 {@code ExternalAugmentClient#fetchJobStatus} Javadoc 참조.
 *
 * @param requestId   우리가 발급한 request_id echo (필수)
 * @param jobId       외부가 발급한 job_id echo (필수)
 * @param status      §3.2 상태 (필수, RECEIVED|RUNNING|SUCCEEDED|FAILED|CANCELED)
 * @param progress    진행률 0~100 (선택 — RECEIVED 등에서는 null 일 수 있다)
 * @param currentStep 현재 단계 표기 (선택)
 * @param receivedAt  접수 시각 (필수, 외부 표기 문자열)
 * @param startedAt   시작 시각 (선택)
 * @param completedAt 종료 시각 (선택)
 * @param errorCode   실패 사유 코드 (선택 — FAILED 에만)
 * @param errorMessage 실패 사유 메시지 (선택)
 * @param updatedAt   최종 갱신 시각 (필수)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenAiJobStatusResponse(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("job_id") String jobId,
        @JsonProperty("status") String status,
        @JsonProperty("progress") Integer progress,
        @JsonProperty("current_step") String currentStep,
        @JsonProperty("received_at") String receivedAt,
        @JsonProperty("started_at") String startedAt,
        @JsonProperty("completed_at") String completedAt,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("updated_at") String updatedAt) {

    /**
     * {@code error_code} 계약 상한 — 웹훅 DTO({@code GenAiCallbackRequest.errorCode})의
     * {@code @Size(max=50)} · 적재 컬럼 {@code LS_DATA_AUG_JOB.ERR_CD VARCHAR(50)} 과 <b>같은 값</b>이다.
     * 조회 경로만 검증이 없으면 초과 값이 적재 단계까지 내려가 {@code 22001} 로 회수가 고착된다(MED-3).
     */
    public static final int ERROR_CODE_MAX = 50;

    /**
     * {@code error_message} 계약 상한 — 웹훅 DTO {@code @Size(max=1000)} ·
     * {@code LS_DATA_AUG_JOB.ERR_MSG_CN VARCHAR(1000)} 과 같은 값.
     */
    public static final int ERROR_MESSAGE_MAX = 1000;

    /**
     * 오류 코드가 §3.3 등록 코드인가. 미등록이어도 실패로 승격하지 않고 원문을 보존한다
     * (판정 축은 {@link #status()} 다) — 다만 운영이 인지하도록 클라이언트가 WARN 을 남긴다.
     */
    public boolean hasKnownErrorCode() {
        return GenAiContract.isKnownErrorCode(errorCode);
    }
}
