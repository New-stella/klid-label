package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * ai-server VLM 영상 단위 메타 추출 응답 (V2 Phase 1 신규).
 * <p>{@code meta_pairs} 는 K/V 매핑. 저장 시 META_KEY 는 "VLM_META." prefix 로 통일.
 * Phase 2 에서 META_TYPE_CD 컬럼 도입 시 prefix → 컬럼 분리 예정.
 */
public record VlmMetaResponse(
        @JsonProperty("meta_pairs") Map<String, String> metaPairs
) {
}
