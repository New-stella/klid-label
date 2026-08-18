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
 *
 * <h2>부분 결과 계약 (요청 단위 시간 예산)</h2>
 * <p>서버는 요청 하나에 <b>wall-clock 예산</b>({@code AiWaitBudgetPolicy.TRACK_BATCH_BUDGET})을 두고,
 * 예산이 다하면 남은 프레임을 처리하지 않고 <b>그때까지의 결과를 그대로 돌려준다</b>(200). 이때
 * {@code truncated=true} 이고 {@code resume} 에 <b>이어 보낼 요청의 값</b>이 들어 있다.
 *
 * <p>⚠ {@code tracked.size()} 로 «어디까지 했는지» 를 알 수 없다 — mock 프레임·퇴화 bbox 는
 * 처리했지만 결과에서 빠지기 때문이다. 반드시 {@code truncated}/{@code resume} 를 봐야 한다.
 * 이 두 필드를 무시하면 남은 프레임의 라벨이 <b>조용히 사라진다</b>.
 *
 * <p><b>기존 필드({@code tracked})의 이름·타입·의미는 그대로다</b> — 추가만 했다.
 *
 * @param tracked   자동 적용 가능한 추적 결과(처리한 프레임 중 신뢰 가능한 것만)
 * @param truncated 예산이 다해 <b>처리하지 못한 프레임이 남았는가</b>. 남지 않았으면 false
 * @param resume    이어 보낼 요청에 그대로 실을 값. {@code truncated=false} 면 null
 */
public record Sam2TrackResponseDto(List<TrackedItem> tracked, boolean truncated, Resume resume) {

    /** 하위호환 — 잘리지 않은(전량 처리) 응답의 1-arg 편의 생성자. */
    public Sam2TrackResponseDto(List<TrackedItem> tracked) {
        this(tracked, false, null);
    }

    /**
     * 이어 보내기용 값 — <b>다음 요청 본문에 그대로 옮겨 담으면 된다</b>.
     *
     * <p>화면이 스스로 만들지 않고 서버가 주는 이유: 이어붙일 시드는 <b>전파 중인 폴리곤</b>이라
     * 결과 목록에서 역산할 수 없다. mock 으로 제외된 프레임에는 결과 항목이 없고, {@code BBOX}
     * 형태로 요청하면 결과 좌표가 2점 외접박스라 다음 요청의 {@code prevPolygon}(최소 3점) 조건도
     * 만족하지 못한다. 서버가 주는 값은 항상 3점 이상인 실제 전파 폴리곤이다.
     *
     * <p>⚠ <b>진행이 0 일 수도 있다</b> — 예산이 한 프레임도 담지 못할 만큼 작게 설정된 경우
     * ({@code srcSn} 이 요청과 같고 {@code nextSrcSns} 도 요청 그대로다). 그대로 다시 보내면 같은
     * 결과가 되므로, 화면은 <b>진행이 있었는지</b>를 확인해 무한 재요청에 빠지지 않아야 한다.
     * 운영 기본 예산에서는 첫 프레임이 항상 시도되므로 이 상태가 나오지 않는다.
     *
     * @param srcSn       다음 요청의 {@code srcSn} — 마지막으로 처리한 프레임(= 이전 이미지)
     * @param prevPolygon 다음 요청의 {@code prevPolygon} — 그 프레임까지 전파된 폴리곤
     * @param nextSrcSns  다음 요청의 {@code nextSrcSns} — 아직 처리하지 않은 프레임(요청 순서 유지)
     */
    public record Resume(Long srcSn, List<List<Double>> prevPolygon, List<Long> nextSrcSns) {
    }

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
