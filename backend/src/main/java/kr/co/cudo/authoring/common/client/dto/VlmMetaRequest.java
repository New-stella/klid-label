package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ai-server VLM 영상 단위 메타 추출 요청 (V2 Phase 1 신규).
 * <p>정합 대상: POST /infer/vlm/meta
 *  - raw_sn   : 영상 식별자
 *  - file_path: 영상 경로 (mock 모드에서는 무시 가능)
 */
public record VlmMetaRequest(
        @JsonProperty("raw_sn") long rawSn,
        @JsonProperty("file_path") String filePath
) {
}
