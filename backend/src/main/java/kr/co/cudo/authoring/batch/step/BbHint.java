package kr.co.cudo.authoring.batch.step;

import java.util.List;

/**
 * YOLO → SAM2 단계 간 인메모리 BBOX 힌트 전달용 (Phase 2 — 토글 분기).
 *
 * <p>{@link YoloAutolabelStep} 가 라벨별 {@code polygonEnabled=true} 인 검출 결과를
 * 누적해서 {@link Sam2SegmentStep#run(Long, List)} 의 두 번째 인자로 넘긴다.
 * BBOX_ONLY 라벨은 DB INSERT 만 수행되고 본 record 에는 포함되지 않는다.
 * POLYGON_ONLY 라벨은 DB INSERT 없이 본 record 로만 흐른다.
 *
 * <p>스레드 안전:
 *  - 본 record 는 불변. 정적 필드/싱글톤에 누적 금지 — 메서드 파라미터/지역 변수로만 전달.
 *  - srcSn, label, points, score 는 입력 검증 후 호출자가 채움.
 *
 * @param srcSn  프레임 식별자 (LS_DATA_SRC.SRC_SN)
 * @param label  라벨 코드 (소문자 정규화는 호출자 책임)
 * @param points YOLO 검출 좌표 (BBOX: [x1, y1, x2, y2])
 * @param score  YOLO 신뢰도 (0.0~1.0)
 */
public record BbHint(long srcSn, String label, List<Double> points, double score) {
}
