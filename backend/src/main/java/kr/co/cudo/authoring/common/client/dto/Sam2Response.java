package kr.co.cudo.authoring.common.client.dto;

import java.util.List;

/**
 * ai-server SAM2 segment 응답.
 *
 *  - polygon    : [[x, y], ...] 폐곡선 좌표
 *  - score      : 신뢰도 (0.0 ~ 1.0)
 *  - mock       : ai-server 가 mock 응답을 반환했으면 true (모델 미로드/AI_MOCK_MODE).
 *  - source     : "mock" | "model"
 *  - mockReason : mock 인 경우 사유 ("env_mock" | "weights_missing" | "load_failed").
 *
 * <p>mock 필드는 ai-server 스키마(Sam2SegmentResponse)의 mock/source/mock_reason 과 1:1.
 * BE 는 이를 그대로 클라이언트 응답으로 전파해 FE 가 "AI 모델 미로드 — 결과 신뢰 불가" 경고 +
 * 자동 적용 차단을 수행하도록 한다.
 */
public record Sam2Response(
        List<List<Double>> polygon,
        double score,
        boolean mock,
        String source,
        @com.fasterxml.jackson.annotation.JsonProperty("mock_reason") String mockReason
) {

    /**
     * mock 메타가 없는 기존 호출부(배치 Sam2SegmentStep 등) 호환용 — 비-mock(model) 응답으로 간주.
     */
    public Sam2Response(List<List<Double>> polygon, double score) {
        this(polygon, score, false, AiMockMeta.SOURCE_MODEL, null);
    }

    /**
     * 자동 적용 대상으로 신뢰할 수 없는 응답인지 — <b>호출부는 {@code mock()} 대신 이 메서드를 쓴다</b>.
     *
     * <p>{@code mock()} 만 보면 ai-server 가 mock 메타를 <b>생략</b>했을 때 primitive 기본값 {@code false}
     * 때문에 정상 응답으로 오인한다(fail-open). 판정 규약은 {@link AiMockMeta} 참조.
     */
    public boolean untrusted() {
        return AiMockMeta.untrusted(mock, source);
    }
}
