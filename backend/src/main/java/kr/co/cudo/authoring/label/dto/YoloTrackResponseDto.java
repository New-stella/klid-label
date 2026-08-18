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
 *
 * <h2>부분 결과 계약 (요청 단위 시간 예산)</h2>
 * <p>서버는 요청 하나에 <b>wall-clock 예산</b>({@code AiWaitBudgetPolicy.TRACK_BATCH_BUDGET})을 두고,
 * 예산이 다하면 남은 프레임을 처리하지 않고 <b>그때까지의 프레임 결과를 그대로 돌려준다</b>(200).
 * 이때 {@code truncated=true} 이고 {@code resume} 에 <b>이어 보낼 요청의 값</b>이 들어 있다.
 * 이 두 필드를 무시하면 남은 프레임의 검출이 <b>조용히 사라진다</b>.
 *
 * <p>⚠ <b>이어 보내면 트래커가 리셋된다</b> — 요청마다 격리된 트래커 세션을 쓰므로
 * ({@code clipId} 는 서버가 요청 단위로 발급한다) 이어 보낸 요청의 {@code trackId} 는 앞 요청과
 * 이어지지 않는다. 그래서 화면은 <b>가능한 한 한 요청에 다 실어 보내야 한다</b> — 예산이 프레임
 * 수를 흡수하므로 대기 시간은 프레임 수에 비례하지 않는다({@code AiWaitBudget.perFrameSec = 0}).
 *
 * <p><b>기존 필드({@code frames})의 이름·타입·의미는 그대로다</b> — 추가만 했다.
 *
 * @param frames    처리한 프레임의 검출 결과(요청 시퀀스 순서, 앞에서부터)
 * @param truncated 예산이 다해 <b>처리하지 못한 프레임이 남았는가</b>. 남지 않았으면 false
 * @param resume    이어 보낼 요청에 그대로 실을 값. {@code truncated=false} 면 null
 */
public record YoloTrackResponseDto(List<FrameDetections> frames, boolean truncated, Resume resume) {

    /** 하위호환 — 잘리지 않은(전량 처리) 응답의 1-arg 편의 생성자. */
    public YoloTrackResponseDto(List<FrameDetections> frames) {
        this(frames, false, null);
    }

    /**
     * 이어 보내기용 값 — <b>다음 요청 본문에 그대로 옮겨 담으면 된다</b>.
     *
     * <p>⚠ <b>진행이 0 일 수도 있다</b>(예산이 한 프레임도 담지 못할 만큼 작게 설정된 경우) — 그때는
     * {@code srcSn}·{@code nextSrcSns} 가 요청 그대로라 다시 보내도 같은 결과다. 화면은 <b>진행이
     * 있었는지</b>를 확인해 무한 재요청에 빠지지 않아야 한다. 운영 기본 예산에서는 나오지 않는다.
     *
     * @param srcSn      다음 요청의 {@code srcSn} — 아직 처리하지 않은 <b>첫</b> 프레임
     *                   (그 요청에서 트래커 리셋 프레임이 된다)
     * @param nextSrcSns 다음 요청의 {@code nextSrcSns} — 그 뒤로 남은 프레임(요청 순서 유지)
     */
    public record Resume(Long srcSn, List<Long> nextSrcSns) {
    }

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
