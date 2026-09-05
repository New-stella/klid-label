package kr.co.cudo.authoring.augment.integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「생성형 AI API 연동명세서 v1.3」 §4.3 결과 조회 응답(200) — INT-030.
 *
 * <p>{@code GET /api/genai/jobs/{job_id}/results} 의 본문이며 <b>{@code SUCCEEDED} 에서만</b> 200 이다
 * (그 외는 409 {@code STATE_CONFLICT}). 따라서 {@code status} 는 항상 {@code SUCCEEDED} 여야 하고
 * {@code results} 가 비어 있으면 <b>계약 위반</b>이다 — 웹훅 경로({@code GenAiCallbackService})가 쓰는
 * 규칙과 동일하다(빈 산출물을 성공으로 접수하면 빈 증강본이 확정된다).
 *
 * <h3>⚠ {@code output_file_path} 는 신뢰 영역 밖이다 (CWE-22)</h3>
 * <p>여기 담긴 경로는 <b>외부가 준 NAS 절대경로</b>다. 파일시스템 API 에 넘기기 전에 반드시
 * {@code VideoArtifactRootResolver#verifyExternalReadablePath} 로 정규화·허용 루트 검증을 거쳐야 한다
 * (웹훅 경로가 이미 그렇게 하고 있다). 본 DTO 는 <b>파싱까지만</b> 하며 아무 파일도 열지 않는다.
 *
 * @param requestId 우리가 발급한 request_id echo (필수)
 * @param jobId     외부가 발급한 job_id echo (필수)
 * @param status    항상 {@code SUCCEEDED} (필수)
 * @param results   산출물 목록 (필수, 1~100건 — job 1건의 입력 상한이 100장이라 결과도 100을 넘지 않는다)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenAiJobResultsResponse(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("job_id") String jobId,
        @JsonProperty("status") String status,
        @JsonProperty("results") List<ResultItem> results) {

    /** 계약상 결과 상한(= {@code input_files} 상한). 초과는 계약 위반이자 자원 방어선이다(CWE-770). */
    public static final int MAX_RESULTS = 100;

    public GenAiJobResultsResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /**
     * 산출물 1건 — §4.3 {@code results[]} 항목.
     *
     * @param outputFilePath 외부가 준 NAS 절대경로(≤500). <b>검증 전에는 파일시스템에 넘기지 말 것.</b>
     * @param mediaMetadata  벤더 자유 메타(mime_type/size_bytes 등). 값에 null 이 섞일 수 있어
     *                       {@code Map.copyOf}(null 금지) 대신 순서 보존 복사를 쓴다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResultItem(
            @JsonProperty("generated_data_id") String generatedDataId,
            @JsonProperty("media_type") String mediaType,
            @JsonProperty("output_file_path") String outputFilePath,
            @JsonProperty("checksum") String checksum,
            @JsonProperty("media_metadata") Map<String, Object> mediaMetadata) {

        /** {@code output_file_path} 계약 상한(§4.3). */
        public static final int OUTPUT_PATH_MAX = 500;

        public ResultItem {
            mediaMetadata = mediaMetadata == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(mediaMetadata));
        }
    }
}
