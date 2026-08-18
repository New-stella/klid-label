package kr.co.cudo.authoring.label.dto;

import java.util.List;

/**
 * YOLO 객체 트랙 추론 결과 (순수 추론 프록시 — DB 저장 없음).
 *
 * <p>프레임별 검출 결과를 순서대로 반환한다. FE 가 결과를 받아
 * 기존 {@code PUT /v1/frames/{srcSn}/labels} 로 저장한다.
 *
 * <p>@design API-123, DFEAT-019
 *
 * <ul>
 *   <li>frames[].srcSn       : 프레임 PK</li>
 *   <li>frames[].frameIndex  : 시퀀스 내 순서(0-base, 0=리셋 프레임)</li>
 *   <li>detections[].points  : [x1, y1, x2, y2]</li>
 *   <li>detections[].trackId : ai-server 트래커 부여 객체 ID (null 허용)</li>
 *   <li>detections[].labelId : 라벨 마스터({@code LS_LABEL}) PK (null 허용 — 미매핑)</li>
 * </ul>
 */
public record YoloTrackResponseDto(List<FrameDetections> frames) {

    public record FrameDetections(
            Long srcSn,
            int frameIndex,
            List<Detected> detections
    ) {
    }

    /**
     * 프레임 1건의 검출 결과 1개.
     *
     * @param label   ai-server 가 돌려준 검출 클래스명(COCO 영문명)
     * @param points  [x1, y1, x2, y2]
     * @param score   신뢰도 [0.0, 1.0] (NaN → null)
     * @param trackId ai-server 트래커 부여 객체 ID (null 허용)
     * @param labelId 라벨 마스터({@code LS_LABEL}) PK — <b>AI 검출 클래스 축</b>
     *                ({@code DTCT_TYPE_CD})으로 해석한 값이다. 대응 마스터가 없거나 그 클래스에
     *                매핑이 지정돼 있지 않으면 <b>null</b> 이며 <b>값을 지어내지 않는다</b>.
     *                <p>이 필드가 필요한 이유: 화면이 {@code label}(COCO 영문명)만으로 라벨을 만들면
     *                저장 시 마스터 연결이 끊겨 표시 색뿐 아니라 <b>라벨명·속성 정의까지</b> 함께
     *                끊긴다. 저장 전에는 정상으로 보이다가 재조회 후에야 드러난다.
     */
    public record Detected(
            String label,
            List<Double> points,
            Double score,
            Integer trackId,
            Long labelId
    ) {
    }
}
