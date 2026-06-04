package kr.co.cudo.authoring.label.dto;

import java.util.List;

/**
 * SAM2 클릭/박스 분할 결과 (Phase 4 — RQ-SFR-08-02).
 *
 * <ul>
 *   <li>polygon : [[x, y], ...] 폐곡선 좌표 (단순화 적용 후)</li>
 *   <li>score   : 신뢰도 (0.0 ~ 1.0)</li>
 *   <li>mock    : ai-server 가 mock 응답(모델 미로드/AI_MOCK_MODE)을 반환했으면 true.
 *                 FE 는 mock=true 시 "AI 모델 미로드 — 결과 신뢰 불가" 경고 + 자동 적용 차단.</li>
 * </ul>
 */
public record Sam2SegmentResponse(
        List<List<Double>> polygon,
        double score,
        boolean mock
) {
}
