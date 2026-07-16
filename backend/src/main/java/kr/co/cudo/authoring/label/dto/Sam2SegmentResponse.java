package kr.co.cudo.authoring.label.dto;

import java.util.List;

/**
 * SAM2 클릭/박스 분할 결과 (Phase 4 — RQ-SFR-08-02).
 *
 * <ul>
 *   <li>polygon : [[x, y], ...] 폐곡선 좌표 (단순화 적용 후). ai-server 내부 mock(모델 미로드) 응답이면
 *                 <b>빈 리스트</b>로 반환되어 FE 가 자동 적용할 대상이 없으므로 차단된다.</li>
 *   <li>score   : 신뢰도 (0.0 ~ 1.0)</li>
 * </ul>
 *
 * <p><b>mock 안전장치(FE 계약에서 비노출)</b>: ai-server 가 mock 응답을 반환하면 서비스가
 * {@link #empty()} 로 <b>빈 폴리곤</b>을 반환하고, 컨트롤러가 {@code ApiResponse.message} 에
 * {@link #MOCK_UNAVAILABLE_MESSAGE} 안내를 세팅한다. 이로써 FE 는 별도 mock 플래그 없이도
 * 빈 결과 + 안내 메시지로 자동 적용을 차단하고 경고를 표시할 수 있다.
 */
public record Sam2SegmentResponse(
        List<List<Double>> polygon,
        double score
) {

    /** AI 모델 미로드(mock) 시 FE 표시용 안내 — 내부 경로/포털 경로 공통. */
    public static final String MOCK_UNAVAILABLE_MESSAGE = "AI 모델 미로드 — 결과 신뢰 불가";

    /** mock 응답 등으로 신뢰 불가할 때 반환하는 빈 결과(좌표 없음 → FE 자동적용 차단). */
    public static Sam2SegmentResponse empty() {
        return new Sam2SegmentResponse(List.of(), 0.0);
    }

    /** 자동적용 차단 신호 — 폴리곤이 비어있으면 true. */
    public boolean isEmpty() {
        return polygon == null || polygon.isEmpty();
    }
}
