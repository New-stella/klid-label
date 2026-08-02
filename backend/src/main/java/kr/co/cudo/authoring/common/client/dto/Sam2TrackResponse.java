package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * ai-server SAM2 track 응답.
 *
 *  - track_id   : 입력과 동일한 트랙 ID (전파 유지)
 *  - polygon    : 다음 프레임에서의 폐곡선 좌표 [[x, y], ...]
 *  - score      : 신뢰도 (0.0 ~ 1.0)
 *  - mock       : ai-server 가 mock 응답을 반환했으면 true (모델 미로드/AI_MOCK_MODE/마스크 미검출).
 *  - source     : "mock" | "model"
 *  - mockReason : mock 인 경우 사유 ("env_mock" | "weights_missing" | "load_failed" | "empty_mask").
 *
 * <p>mock 3필드는 ai-server 스키마({@code app/schemas.py} 의 {@code Sam2TrackResponse})의
 * {@code mock}/{@code source}/{@code mock_reason} 과 1:1 이며 {@link Sam2Response}(segment)와 동형이다.
 *
 * <p><b>왜 필수인가 (C-ISSUE-81, CWE-345)</b>: ai-server 의 {@code _mock_track} 은 <b>시드 폴리곤을
 * 그대로 복사</b>하고 {@code score=0.9} 를 부여한다. BE 가 이 메타를 파싱하지 못하면 "N 프레임 추적"이
 * 조용히 "시드 폴리곤 N개 복제"로 둔갑하고, 점수가 높아 FE 의 저신뢰 분기에도 걸리지 않아 가짜 좌표가
 * 학습데이터로 확정된다. 파싱한 뒤에는 서비스가 해당 프레임을 결과에서 제외한다.
 */
public record Sam2TrackResponse(
        @JsonProperty("track_id") String trackId,
        List<List<Double>> polygon,
        double score,
        boolean mock,
        String source,
        @JsonProperty("mock_reason") String mockReason
) {

    /**
     * mock 메타가 없는 기존 호출부 호환용 — 비-mock(model) 응답으로 간주.
     * (실제 역직렬화는 6-arg 정준 생성자를 사용한다.)
     */
    public Sam2TrackResponse(String trackId, List<List<Double>> polygon, double score) {
        this(trackId, polygon, score, false, AiMockMeta.SOURCE_MODEL, null);
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
