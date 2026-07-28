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
 * 생성형 AI(증강) 결과 웹훅 페이로드 — 「생성형 AI API 연동명세서 v1.1」 §4.2/§4.3 정합.
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
 * <h3>계약 사실 (수신부 설계 근거)</h3>
 * <ul>
 *   <li>상태 전이마다 발사된다 — {@code RUNNING}(progress 10/50/90) → {@code SUCCEEDED}
 *       또는 {@code FAILED}. {@code CANCELED} 는 발사되지 않는다.</li>
 *   <li>전송 실패 시 재시도하므로 <b>같은 페이로드가 중복 도착하는 것이 정상</b>이다
 *       (수신부는 멱등이어야 한다).</li>
 *   <li>{@code results} 는 SUCCEEDED, {@code error_code}/{@code error_message} 는 FAILED 에만
 *       실린다. 미지의 필드는 무시한다({@link JsonIgnoreProperties}) — 외부가 필드를 추가해도
 *       수신이 깨지지 않아야 한다.</li>
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

        /** 외부가 202 응답으로 발급한 job_id. */
        @NotBlank
        @Size(max = 200)
        @Pattern(regexp = "^[A-Za-z0-9_.:-]+$",
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

        /** SUCCEEDED 에만 존재. 계약상 job 1건의 입력 상한이 100장이라 결과도 100건을 넘지 않는다. */
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

    /** 결과 산출물 1건 — §4.2 {@code results[]} 항목. */
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
