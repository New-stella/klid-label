package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * YOLO detection → LS_DATA_LBL(BBOX) + LS_DATA_LBL_AI_INFO 저장 공용 헬퍼.
 *
 * <p><b>배치({@link YoloAutolabelStep}) 전용</b> 저장 헬퍼다. 온라인 수동 트리거
 * ({@code AutolabelOnlineService})는 Phase 1 에서 <b>미저장(좌표만 반환)</b>으로 전환되어 더 이상 본
 * Persister 를 사용하지 않는다 — 온라인 결과는 클라이언트가 작업본에 반영 후 저장 API(PUT /labels)로
 * 확정한다. 상태 없는 정적 유틸이며, 저장은 <b>호출자가 주입한</b> 리포지토리 인스턴스로 수행한다.
 * 따라서 호출자는 자신의 트랜잭션(@Transactional qualifier)/모킹 컨텍스트를 그대로 유지한다.
 *
 * <p>보안:
 * <ul>
 *   <li>입력 검증(CWE-20): 평탄 좌표 홀수/ null 원소는 {@link LabelPointSerializer#flatToPoints} 가
 *       거부 → INVALID_INPUT 으로 통일 래핑(내부 좌표 원문 미노출, CWE-209).</li>
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
     * 단일 detection 을 BBOX 라벨 + AI 메타로 저장한다.
     *
     * @param lblRepository    호출자 주입 라벨 리포지토리
     * @param aiInfoRepository 호출자 주입 AI 정보 리포지토리
     * @param objectMapper     좌표 직렬화용 Jackson 매퍼
     * @param srcSn            프레임 PK
     * @param rawSn            영상 PK
     * @param label            detection 라벨명 (예: person)
     * @param labelId          LS_LABEL FK (미매칭 시 null — 호출자가 사전 조회)
     * @param points           평탄 좌표 [x1,y1,x2,y2]
     * @param score            신뢰도 (0.0~1.0; 엔티티에서 clamp)
     * @param trackId          트래커 객체 ID (null 허용)
     * @param source           AI_INFO.REG_ID 출처 마커 ({@link #SOURCE_BATCH}/{@link #SOURCE_ONLINE})
     * @return 저장된 라벨 (PK 부여됨)
     * @throws CustomException INVALID_INPUT — 좌표 형식 오류
     */
    public static LsDataLbl persistBbox(LsDataLblRepository lblRepository,
                                        LsDataLblAiInfoRepository aiInfoRepository,
                                        ObjectMapper objectMapper,
                                        Long srcSn, Long rawSn, String label, Long labelId,
                                        List<Double> points, double score, Integer trackId,
                                        String source) {
        BigDecimal scoreBd = BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
        String pointCn;
        try {
            pointCn = LabelPointSerializer.toJson(
                    LabelPointSerializer.flatToPoints(points), objectMapper);
        } catch (IllegalArgumentException e) {
            // CWE-209: 좌표 원문·스택트레이스 미노출.
            throw new CustomException(ErrorCode.INVALID_INPUT, "YOLO bbox 좌표 형식 오류", e);
        }
        String trackIdStr = trackId == null ? null : String.valueOf(trackId);
        LsDataLbl saved = lblRepository.save(
                LsDataLbl.createAutoBbox(srcSn, labelId, label, pointCn, scoreBd, trackIdStr));
        aiInfoRepository.save(LsDataLblAiInfo.create(
                saved.getLblSn(), rawSn, srcSn, LsDataLblAiInfo.SRC_YOLO, scoreBd, source));
        return saved;
    }
}
