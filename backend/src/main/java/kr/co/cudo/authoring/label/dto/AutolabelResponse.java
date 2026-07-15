package kr.co.cudo.authoring.label.dto;

import java.util.List;

/**
 * Phase 3 — YOLO 오토라벨 수동 트리거 응답.
 *
 * <p><b>mock 안전장치(FE 계약에서 비노출)</b>: ai-server 내부 mock 응답이면 서비스가 DB 저장을
 * 스킵하고 {@code savedCount=0} + 빈 {@code labels} 로 반환한다. FE 는 별도 mock 플래그 없이도
 * {@code savedCount=0} 을 미저장(자동적용 없음) 신호로 사용한다.
 *
 * @param srcSn      대상 프레임 PK
 * @param savedCount 저장/적용된 BBOX 라벨 개수 (mock 응답이면 0 — 미저장 신호)
 * @param labels     저장된 라벨 요약 (FE 즉시 반영용). 미저장(mock/검출없음) 이면 빈 리스트.
 */
public record AutolabelResponse(Long srcSn, int savedCount, List<Item> labels) {

    /**
     * AI 모델 미로드(mock) 시 FE 표시용 안내 — SAM2 세그({@code Sam2SegmentResponse.MOCK_UNAVAILABLE_MESSAGE})와
     * 대칭. 컨트롤러가 {@code ApiResponse.message} 에 세팅하며, FE 는 이 message 유무로 mock(경고) 과
     * 정상 "0건 검출" 을 구분한다. FE-facing DTO(record 컴포넌트)에는 mock 플래그를 두지 않는다.
     */
    public static final String MOCK_UNAVAILABLE_MESSAGE = "AI 모델 미로드 — 결과 신뢰 불가";

    /**
     * 저장된 라벨 요약.
     *
     * @param lblSn   라벨 PK
     * @param labelId LS_LABEL FK (미매칭 시 null)
     * @param label   라벨명
     * @param points  BBOX 평탄 좌표 [x1,y1,x2,y2]
     * @param score   신뢰도 (0.0~1.0)
     * @param trackId 트래커 객체 ID (단일 프레임 트리거이므로 연속성 미보장 — null 가능)
     */
    public record Item(Long lblSn, Long labelId, String label,
                       List<Double> points, Double score, Integer trackId) {
    }
}
