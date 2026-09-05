package kr.co.cudo.authoring.webhook.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 생성형 AI(증강) 결과 웹훅 페이로드 — 「생성형 AI API 연동명세서 v1.3」 §4.5 최종 상태 Webhook 정합.
 *
 * <p>{@code POST /v1/genai/callback} 요청 본문. 외부는 snake_case 로 <b>무서명</b> 송신한다.
 * <pre>
 * {"request_id":"AUG-xxxx-1","job_id":"genai-0001","status":"SUCCEEDED",
 *  "progress":100,"current_step":"COMPLETED","updated_at":"2026-07-27T10:00:00Z",
 *  "results":[{"generated_data_id":"...","media_type":"IMAGE",
 *              "output_file_path":"/nas-storage/genai/genai-0001/001_x_gen.jpg",
 *              "checksum":"...","media_metadata":{...}}]}
 * </pre>
 *
 * <h3>계약이 보장하는 것 (v1.3 §4.5)</h3>
 * <ul>
 *   <li>{@code callback_url} 이 있으면 작업이 {@code SUCCEEDED} 또는 {@code FAILED} 로
 *       <b>처음 종료되는 시점에 1회</b> POST 된다. <b>{@code RUNNING} 웹훅은 계약에 없다.</b></li>
 *   <li>전송이 실패해도 <b>재시도하지 않는다</b>(상대 서버 로그에만 남는다). 즉 콜백 유실이 곧 결과
 *       유실이라, 콜백은 결과를 회수하는 유일한 수단이 아니다.</li>
 *   <li>{@code CANCELED} 발사 여부와 <b>본문의 {@code results} 포함 여부는 계약이 보장하지 않는다.</b>
 *       작업 결과와 미디어 메타데이터는 {@code GET /api/genai/jobs/{job_id}/results}(§4.3 작업 결과 조회)로
 *       조회하는 것이 v1.3 계약이다.</li>
 *   <li>{@code error_code}/{@code error_message} 는 실패 사유 자리다(§4.5 Webhook Sample).</li>
 * </ul>
 *
 * <h3>목서버가 실제로 보내는 것 (계약이 아니라 로컬 목의 동작)</h3>
 * <ul>
 *   <li>로컬 목({@code mock-server} 의 생성형 AI 시뮬레이터)은 <b>상태 전이마다</b> 발사한다 —
 *       {@code RUNNING}(progress 10 PREPROCESS / 50 INFERENCE / 90 POSTPROCESS) → {@code SUCCEEDED}
 *       또는 {@code FAILED}. {@code CANCELED} 는 발사하지 않는다.</li>
 *   <li>목은 성공 시 본문에 {@code results} 를 <b>실어서</b> 보내고, 전송이 실패하면 상한 안에서
 *       <b>재시도</b>한다.</li>
 *   <li>⚠ 이것은 <b>목의 동작이지 벤더 계약이 아니다.</b> 목이 그렇게 보낸다는 이유로 실벤더도 그렇다고
 *       읽지 말 것 — 위 「계약이 보장하는 것」이 벤더와의 계약면이다.</li>
 * </ul>
 *
 * <h3>그래서 수신부는 이렇게 설계돼 있다</h3>
 * <ul>
 *   <li><b>{@code status} 화이트리스트에서 {@code RUNNING} 을 빼지 말 것.</b> 계약이 보장하지 않는 것과
 *       받아 주면 안 되는 것은 다르다 — 목이 실제로 보내고 상대 배포본이 진행 알림을 함께 보낼 수도 있다.
 *       계약이 요구하는 최소 집합보다 관대하게 받아 멱등 흡수하는 편이 안전하다.</li>
 *   <li><b>{@code results} 가 안 와도 기능이 성립한다.</b> 콜백이 유실되거나 본문에 산출물이 없으면
 *       {@code AugmentResultRecoveryService} 가 {@code ExternalAugmentClient.fetchJobResults}
 *       ({@code GET /api/genai/jobs/{job_id}/results})로 회수해 종결시킨다. 이 폴백이 있어 계약이
 *       {@code results} 를 보장하지 않아도 산출물이 유실되지 않는다.</li>
 *   <li><b>같은 페이로드가 중복 도착하는 것을 정상으로 본다</b>(수신부는 멱등이어야 한다) — 목이 재시도하고,
 *       상대 배포본이 재시도할 가능성도 배제하지 않는다.</li>
 *   <li>미지의 필드는 무시한다({@link JsonIgnoreProperties}) — 외부가 필드를 추가해도 수신이 깨지지
 *       않아야 한다.</li>
 * </ul>
 *
 * <p><b>입력 검증(CWE-20)</b>: 모든 문자열에 길이 상한을, 코드성 필드에 화이트리스트를 건다.
 * {@code output_file_path} 는 <b>외부가 준 경로</b>라 형식 검증만으로 부족하며, 허용 루트 하위
 * 여부를 {@code GenAiCallbackService} 가 정규화 후 재검증한다(CWE-22).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenAiCallbackRequest(

        /** 우리가 발급한 job 단위 멱등키(= {@code LS_DATA_AUG_JOB.IDMP_KEY}) 의 echo. */
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$",
                message = "request_id 는 영숫자/대시/언더스코어만 허용됩니다.")
        @JsonProperty("request_id")
        String requestId,

        /**
         * 외부가 202 응답으로 발급한 job_id.
         *
         * <p>패턴은 {@code GenAiContract.JOB_ID} 와 <b>동일 규약</b>이다 — 이 값은 조회/취소에서 URL
         * <b>경로 세그먼트</b>로 다시 나가므로, 무서명 웹훅으로 들어오는 이 입구가 느슨하면 거기가
         * 오염된 식별자의 유입 경로가 된다. 선두 {@code (?!\.+$)} 는 {@code ".."} 같은 점-전용
         * 세그먼트 차단이다(CWE-22 — {@code .} 는 unreserved 라 인코딩되지 않는다).
         */
        @NotBlank
        @Size(max = 200)
        @Pattern(regexp = "^(?!\\.+$)[A-Za-z0-9_.:-]+$",
                message = "job_id 형식이 올바르지 않습니다.")
        @JsonProperty("job_id")
        String jobId,

        @NotBlank
        @Pattern(regexp = "^(RUNNING|SUCCEEDED|FAILED)$",
                message = "status 는 RUNNING|SUCCEEDED|FAILED 중 하나여야 합니다.")
        @JsonProperty("status")
        String status,

        @Min(0)
        @Max(100)
        @JsonProperty("progress")
        Integer progress,

        @Size(max = 20)
        @JsonProperty("current_step")
        String currentStep,

        @Size(max = 64)
        @JsonProperty("updated_at")
        String updatedAt,

        /**
         * SUCCEEDED 일 때만 실릴 수 있고 <b>아예 안 실릴 수도 있다</b>(§4.5 미보장 — 그때는 결과 조회로
         * 회수한다). 계약상 job 1건의 입력 상한이 100장이라 실릴 경우 결과도 100건을 넘지 않는다.
         */
        @Valid
        @Size(max = 100, message = "results 는 100건을 초과할 수 없습니다.")
        @JsonProperty("results")
        List<ResultItem> results,

        @Size(max = 50)
        @JsonProperty("error_code")
        String errorCode,

        @Size(max = 1000)
        @JsonProperty("error_message")
        String errorMessage
) {

    /** 결과 산출물 1건 — §4.3 작업 결과 조회의 {@code results[]} 항목과 같은 구조다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResultItem(

            @NotBlank
            @Size(max = 128)
            @JsonProperty("generated_data_id")
            String generatedDataId,

            @NotBlank
            @Pattern(regexp = "^(IMAGE|VIDEO)$",
                    message = "media_type 은 IMAGE|VIDEO 중 하나여야 합니다.")
            @JsonProperty("media_type")
            String mediaType,

            @NotBlank
            @Size(max = 500)
            @JsonProperty("output_file_path")
            String outputFilePath,

            @Size(max = 128)
            @JsonProperty("checksum")
            String checksum
    ) {
    }
}
