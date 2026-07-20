package kr.co.cudo.authoring.label.dto;

import java.util.List;

/**
 * SAM2 Track 결과 (<b>DB 미저장 — 좌표만 반환</b>, R12).
 *
 * <p><b>미저장 정책</b>: 내부 온라인 추적은 더 이상 propagation 결과를 {@code LS_DATA_LBL} 에 저장하지 않고
 * 좌표만 반환한다(AI 탐지/포털 추적과 동일 stateless 프록시). 클라이언트가 작업본에 병합 후 PUT /labels 로
 * 확정한다.
 *
 * <p><b>형태(shapeType)</b>:
 * <ul>
 *   <li>{@code POLYGON} : {@code points} = 폴리곤 정점 [[x,y],...].</li>
 *   <li>{@code BBOX}    : {@code points} = 외접 bbox 두 꼭짓점 [[minX,minY],[maxX,maxY]].</li>
 * </ul>
 * FE 가 {@code shapeType} 으로 두 형태를 구분한다.
 */
public record Sam2TrackResponseDto(List<TrackedItem> tracked) {

    public record TrackedItem(
            Long srcSn,
            String trackId,
            String label,
            List<List<Double>> points,
            double score,
            String shapeType
    ) {

        /** 하위호환 — POLYGON 형태(5-arg) 편의 생성자. 포털 추적 경로/기존 호출자 유지. */
        public TrackedItem(Long srcSn, String trackId, String label,
                           List<List<Double>> points, double score) {
            this(srcSn, trackId, label, points, score, "POLYGON");
        }
    }
}
