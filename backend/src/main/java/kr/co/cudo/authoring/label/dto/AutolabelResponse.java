package kr.co.cudo.authoring.label.dto;

import java.util.List;

/**
 * Phase 3/4 — YOLO 오토라벨 수동 트리거 응답 (<b>DB 미저장 — 좌표만 반환</b>).
 *
 * <p><b>미저장 정책(Phase 1 전환)</b>: 온라인(캔버스 버튼) 오토라벨은 더 이상 DB 에 저장하지 않고
 * ai-server 가 검출한 좌표만 반환한다(SAM2 분할 {@link Sam2SegmentResponse} 와 동일한 stateless
 * 프록시 패턴). 클라이언트가 결과를 캔버스 작업본에 반영한 뒤 기존 라벨 저장 API(PUT /labels)로
 * 확정한다. 따라서 {@code labels[].lblSn} 은 항상 {@code null}(미저장 신호)이다.
 *
 * <p><b>필드 의미 전환 + 과도기 mirror</b>: 저장 개수({@code savedCount}) 의미가 검출 개수
 * ({@code detectedCount}) 로 바뀌었다. FE 미파손을 위해 과도기 동안 두 필드를 <b>동일 값</b>으로
 * 함께 노출한다. Phase 4 에서 FE 를 {@code detectedCount} 로 전환한 뒤 {@code savedCount} 는 제거 예정.
 *
 * <p><b>mock 안전장치(FE 계약에서 비노출)</b>: ai-server 내부 mock 응답이면 서비스가
 * {@code detectedCount=0} + 빈 {@code labels} 로 반환하고, 컨트롤러가 {@code ApiResponse.message}
 * 에 안내를 세팅한다. FE-facing DTO(record 컴포넌트)에는 mock 플래그를 두지 않아 계약을 불변으로 유지한다.
 *
 * @param srcSn         대상 프레임 PK
 * @param detectedCount ai-server 가 검출한 BBOX 개수 (mock/검출없음 이면 0)
 * @param savedCount    (deprecated, Phase 4 제거 예정) {@code detectedCount} 와 동일 값 mirror — FE 하위호환용
 * @param labels        검출된 라벨 요약 (FE 가 작업본에 반영). 미검출/mock 이면 빈 리스트.
 */
public record AutolabelResponse(Long srcSn, int detectedCount, int savedCount, List<Item> labels) {

    /**
     * 과도기 편의 생성자 — {@code detectedCount} 와 {@code savedCount} 를 동일 값으로 mirror 한다.
     * Phase 4 에서 FE 전환 완료 후 {@code savedCount} 제거 시 본 생성자도 함께 정리한다.
     */
    public AutolabelResponse(Long srcSn, int count, List<Item> labels) {
        this(srcSn, count, count, labels);
    }

    /**
     * AI 모델 미로드(mock) 시 FE 표시용 안내 — SAM2 세그({@code Sam2SegmentResponse.MOCK_UNAVAILABLE_MESSAGE})와
     * 대칭. 컨트롤러가 {@code ApiResponse.message} 에 세팅하며, FE 는 이 message 유무로 mock(경고) 과
     * 정상 "0건 검출" 을 구분한다. FE-facing DTO(record 컴포넌트)에는 mock 플래그를 두지 않는다.
     */
    public static final String MOCK_UNAVAILABLE_MESSAGE = "AI 모델 미로드 — 결과 신뢰 불가";

    /**
     * 폴리곤 오토라벨(R12) 형태 안내 템플릿.
     * <ul>
     *   <li>{@code POLYGON_TRUNCATED_TEMPLATE} : 검출 박스가 상한을 초과해 일부만 처리한 경우(HIGH #1/MED #5).</li>
     *   <li>{@code POLYGON_PARTIAL_MOCK_MESSAGE} : 일부 박스가 mock/실패로 제외돼 신뢰 불가한 결과가 섞인 경우(HIGH #4/#9).</li>
     * </ul>
     */
    public static String polygonTruncatedMessage(int detected, int processed) {
        return "검출 " + detected + "건 중 상한 " + processed + "건만 처리했습니다.";
    }

    public static final String POLYGON_PARTIAL_MOCK_MESSAGE = "일부 결과의 신뢰도를 보장할 수 없습니다.";

    /**
     * 폴리곤 오토라벨 <b>전량</b> 실패/mock(반환 0, 검출은 있었음) 안내 — 일부 실패(위 {@link #POLYGON_PARTIAL_MOCK_MESSAGE})와
     * 구분해 "모든 결과"가 신뢰 불가임을 명시한다(code-reviewer LOW: 전량/일부 문구 구분).
     */
    public static final String POLYGON_ALL_UNRELIABLE_MESSAGE = "모든 결과의 신뢰도를 보장할 수 없습니다.";

    /**
     * 검출된 라벨 요약 (<b>미저장</b> — 클라이언트가 작업본에 반영 후 PUT /labels 로 저장).
     *
     * @param lblSn     라벨 PK — <b>항상 null</b>(미저장 신호). DB 에 저장하지 않으므로 발급되지 않는다.
     * @param labelId   LS_LABEL FK (미매칭 시 null)
     * @param label     라벨명
     * @param points    BBOX 평탄 좌표 [x1,y1,x2,y2] ({@code shapeType=BBOX} 일 때). POLYGON 이면 null.
     * @param score     신뢰도 (0.0~1.0)
     * @param trackId   트래커 객체 ID (단일 프레임 트리거이므로 연속성 미보장 — null 가능)
     * @param shapeType 형태 구분 "BBOX" | "POLYGON" (FE 가 points/polygon 중 무엇을 읽을지 판단)
     * @param polygon   폴리곤 정점 좌표 [[x,y],...] ({@code shapeType=POLYGON} 일 때). BBOX 이면 null.
     */
    public record Item(Long lblSn, Long labelId, String label,
                       List<Double> points, Double score, Integer trackId,
                       String shapeType, List<List<Double>> polygon) {

        /** 하위호환 — BBOX 형태(6-arg) 편의 생성자. shapeType="BBOX", polygon=null. */
        public Item(Long lblSn, Long labelId, String label,
                    List<Double> points, Double score, Integer trackId) {
            this(lblSn, labelId, label, points, score, trackId, "BBOX", null);
        }
    }
}
