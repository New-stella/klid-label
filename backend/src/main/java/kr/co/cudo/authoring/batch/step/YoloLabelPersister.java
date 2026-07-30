package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.step.AutoLabelBatchPersister.PendingLabel;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.DetectionBoxNormalizer;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * YOLO detection → LS_DATA_LBL(BBOX) 엔티티 생성 공용 헬퍼.
 *
 * <p><b>배치({@link YoloAutolabelStep}) 전용</b>이다. 온라인 수동 트리거
 * ({@code AutolabelOnlineService})는 Phase 1 에서 <b>미저장(좌표만 반환)</b>으로 전환되어 더 이상 본
 * 헬퍼를 사용하지 않는다 — 온라인 결과는 클라이언트가 작업본에 반영 후 저장 API(PUT /labels)로 확정한다.
 *
 * <p><b>B-ISSUE-42</b>: 실제 영속화는 프레임 단위 일괄 저장({@link AutoLabelBatchPersister#saveAll})이
 * 담당하고, 본 헬퍼는 <b>검증·정규화·엔티티 생성</b>까지만 책임진다(리포지토리 의존 없음). 검출 1건마다
 * 리포지토리를 왕복하던 구조를 제거하되, 저장 직전 정규화 방어는 그대로 유지한다.
 *
 * <p>보안:
 * <ul>
 *   <li>입력 검증(CWE-20 · C-ISSUE-41): 좌표는 {@link DetectionBoxNormalizer} <b>공용 규칙</b>으로
 *       정규화한다 — 온라인 경로({@code AutolabelOnlineService})와 같은 함수다. 구 구현은 배치만
 *       무검증이라 {@code LS_DATA_LBL} 에 음수 좌표가 적재됐고, 같은 응답을 온라인은 400 으로 거부하는
 *       비대칭이 있었다. 형식 위반(개수·NaN/Infinity·null)은 INVALID_INPUT 으로 통일 래핑한다
 *       (내부 좌표 원문 미노출, CWE-209).</li>
 *   <li>Insecure Deserialization(CWE-502): Jackson 표준 ObjectMapper 만 사용. enableDefaultTyping 없음.</li>
 * </ul>
 */
public final class YoloLabelPersister {

    /** AI_INFO.REG_ID 출처 마커 — 배치 파이프라인 자동 실행. */
    public static final String SOURCE_BATCH = "batch";
    /** AI_INFO.REG_ID 출처 마커 — 라벨링 화면 수동 트리거(온라인). */
    public static final String SOURCE_ONLINE = "MANUAL_TRIGGER";

    private YoloLabelPersister() {}

    /**
     * 단일 detection 을 <b>이미지 경계로 clamp 한 뒤</b> BBOX 라벨 엔티티 + AI 메타 신뢰도로 만든다.
     *
     * <p>영속화는 하지 않는다 — 호출자가 프레임 단위로 모아
     * {@link AutoLabelBatchPersister#saveAll} 로 일괄 저장한다(B-ISSUE-42).
     *
     * @param objectMapper 좌표 직렬화용 Jackson 매퍼
     * @param srcSn        프레임 PK
     * @param label        detection 라벨명 (예: person)
     * @param labelId      LS_LABEL FK (미매칭 시 null — 호출자가 사전 조회)
     * @param points       평탄 좌표 [x1,y1,x2,y2]
     * @param bounds       프레임 실측 [width, height]. null 이면 상한 clamp 생략(하한은 유지)
     * @param score        신뢰도 (0.0~1.0; 엔티티에서 clamp)
     * @param trackId      트래커 객체 ID (null 허용)
     * @return 저장 대기 라벨. clamp 후 퇴화(이미지 밖·좌표 역전)면 {@link Optional#empty()}
     *         — 영상 1건의 배치를 통째로 실패시키지 않고 그 검출만 건너뛴다
     * @throws CustomException INVALID_INPUT — 좌표 형식 오류(개수·NaN/Infinity·null 원소)
     */
    public static Optional<PendingLabel> buildBbox(ObjectMapper objectMapper,
                                                   Long srcSn, String label, Long labelId,
                                                   List<Double> points, int[] bounds, double score,
                                                   Integer trackId) {
        BigDecimal scoreBd = BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
        String pointCn;
        try {
            // C-ISSUE-41 — 온라인 경로와 동일한 공용 규칙으로 정규화(경계 clamp / 형식 위반만 거부).
            Optional<List<Double>> normalized = DetectionBoxNormalizer.normalizeBbox(points, bounds);
            if (normalized.isEmpty()) {
                return Optional.empty();
            }
            pointCn = LabelPointSerializer.toJson(
                    LabelPointSerializer.flatToPoints(normalized.get()), objectMapper);
        } catch (IllegalArgumentException e) {
            // CWE-209: 좌표 원문·스택트레이스 미노출.
            throw new CustomException(ErrorCode.INVALID_INPUT, "YOLO bbox 좌표 형식 오류", e);
        }
        String trackIdStr = trackId == null ? null : String.valueOf(trackId);
        return Optional.of(new PendingLabel(
                LsDataLbl.createAutoBbox(srcSn, labelId, label, pointCn, scoreBd, trackIdStr), scoreBd));
    }
}
