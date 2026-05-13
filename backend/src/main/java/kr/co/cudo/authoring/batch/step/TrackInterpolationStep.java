package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.interpolation.Bbox;
import kr.co.cudo.authoring.batch.interpolation.Keyframe;
import kr.co.cudo.authoring.batch.interpolation.TrackInterpolator;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 트랙 보간 단계 — Phase 3.
 * <p>
 * SAM2 단계가 끝난 직후 호출되어, 같은 영상(rawSn) 내에서 같은 {@code TRACK_ID} 를 가진
 * 자동 BBOX 라벨 사이의 누락 프레임을 선형 보간으로 채워 LS_DATA_LBL 에 INSERT 한다.
 *
 * <h2>처리 흐름</h2>
 * <ol>
 *   <li>{@link LsDataSrcRepository#findByRawSnOrderByFrameNoAsc} 로 영상의 모든 프레임 srcSn ↔ frameNo
 *       매핑을 메모리 캐싱 (N+1 회피).</li>
 *   <li>{@link LsDataLblRepository#findAutoBboxWithTrackId} 로 보간 대상 라벨 일괄 조회.
 *       자동 + BBOX + trackId NOT NULL 만.</li>
 *   <li>trackId 별 그룹 → frameNo 오름차순 정렬 → {@link Keyframe} 리스트로 변환.</li>
 *   <li>{@link TrackInterpolator#interpolate} 호출 → frame → Bbox 매핑 획득.</li>
 *   <li>키프레임 자체는 skip 하고 사이 프레임만 신규 row 로 생성. {@link LsDataLbl#createAutoInterpolatedBbox} 사용.</li>
 *   <li>{@link LsDataLblRepository#saveAll} 일괄 INSERT — N+1 회피.</li>
 * </ol>
 *
 * <h2>트랜잭션</h2>
 * 다른 step 과 동일하게 {@link Propagation#REQUIRES_NEW} — 한 영상의 보간을 단일 트랜잭션으로 commit.
 * SAM2 등 이전 step 의 트랜잭션과 분리되어, 본 step 실패가 SAM2 결과에 영향 없음.
 *
 * <h2>안전</h2>
 * <ul>
 *   <li>BBOX 좌표는 자체 시스템 산출물(YOLO → DB) — 외부 입력 아님. 그래도 size&lt;4 면 IllegalArgumentException.</li>
 *   <li>ObjectMapper 는 Spring 빈 주입 — Jackson 표준 모드 (enableDefaultTyping 미사용, CWE-502 안전).</li>
 *   <li>frame 매핑 안 되는 row 는 안전망(continue)으로 skip.</li>
 * </ul>
 */
@Slf4j
@Component
public class TrackInterpolationStep {

    private static final TrackInterpolator INTERPOLATOR = new TrackInterpolator();

    private final LsDataLblRepository lblRepository;
    private final LsDataSrcRepository srcRepository;
    private final ObjectMapper objectMapper;

    public TrackInterpolationStep(LsDataLblRepository lblRepository,
                                  LsDataSrcRepository srcRepository,
                                  ObjectMapper objectMapper) {
        this.lblRepository = lblRepository;
        this.srcRepository = srcRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 단일 영상의 모든 트랙을 보간하여 신규 INTERPOLATED BBOX row 를 저장한다.
     *
     * @param rawSn LS_DATA_RAW.RAW_SN
     * @return 저장된 보간 row 수 (0 이상). 영상 프레임이 없거나 보간 대상이 없으면 0.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int run(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }

        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        if (frames.isEmpty()) {
            log.info("[Batch][Interpolation] no frames rawSn={}", rawSn);
            return 0;
        }
        int totalFrames = frames.size();
        Map<Integer, Long> frameToSrcSn = new HashMap<>(totalFrames);
        Map<Long, Integer> srcSnToFrame = new HashMap<>(totalFrames);
        for (LsDataSrc s : frames) {
            frameToSrcSn.put(s.getFrameNo(), s.getSrcSn());
            srcSnToFrame.put(s.getSrcSn(), s.getFrameNo());
        }

        List<LsDataLbl> candidates = lblRepository.findAutoBboxWithTrackId(rawSn);
        if (candidates.isEmpty()) {
            log.info("[Batch][Interpolation] no interpolation candidates rawSn={}", rawSn);
            return 0;
        }

        // trackId 별 그룹 — 각 트랙 안에서 frame_no 오름차순 정렬
        Map<String, List<LsDataLbl>> byTrackId = candidates.stream()
                .collect(Collectors.groupingBy(LsDataLbl::getTrackId));

        List<LsDataLbl> newRows = new ArrayList<>();
        for (Map.Entry<String, List<LsDataLbl>> entry : byTrackId.entrySet()) {
            String trackId = entry.getKey();
            List<LsDataLbl> sorted = entry.getValue().stream()
                    .filter(l -> srcSnToFrame.containsKey(l.getSrcSn()))
                    .sorted(Comparator.comparingInt(l -> srcSnToFrame.get(l.getSrcSn())))
                    .toList();
            if (sorted.isEmpty()) {
                continue;
            }
            String label = sorted.get(0).getLabel();
            List<Keyframe> keyframes = sorted.stream()
                    .map(l -> new Keyframe(
                            srcSnToFrame.get(l.getSrcSn()),
                            parseBbox(l.getPointsJson()),
                            false))
                    .toList();
            Map<Integer, Bbox> interpolated = INTERPOLATOR.interpolate(keyframes, totalFrames);
            Set<Integer> existingFrames = keyframes.stream()
                    .map(Keyframe::frame)
                    .collect(Collectors.toSet());
            for (Map.Entry<Integer, Bbox> ie : interpolated.entrySet()) {
                int frame = ie.getKey();
                if (existingFrames.contains(frame)) {
                    continue;  // 키프레임 자체는 skip
                }
                Long srcSn = frameToSrcSn.get(frame);
                if (srcSn == null) {
                    continue;  // 안전망 — 매핑 안 되는 프레임은 skip
                }
                newRows.add(LsDataLbl.createAutoInterpolatedBbox(
                        srcSn, label, serializeBbox(ie.getValue()), BigDecimal.ZERO, trackId));
            }
        }

        if (!newRows.isEmpty()) {
            lblRepository.saveAll(newRows);
        }
        log.info("[Batch][Interpolation] saved rawSn={} tracks={} interpolatedRows={}",
                rawSn, byTrackId.size(), newRows.size());
        return newRows.size();
    }

    /**
     * BBOX pointsJson 을 {@link Bbox} 로 파싱.
     *
     * <p>지원 포맷 (자체 시스템 산출물):
     * <ul>
     *   <li>{@code [x1,y1,x2,y2]} — flat 4-double (YoloAutolabelStep 의 serialize(d.points()) 결과)</li>
     *   <li>{@code [[x1,y1],[x2,y2]]} — 2x2 nested (수동 라벨 호환 — Phase 6 LabelPointSerializer 산출)</li>
     * </ul>
     *
     * @throws IllegalArgumentException 포맷이 위 둘 중 어느 것도 아닐 때 (size&lt;4)
     */
    private Bbox parseBbox(String pointsJson) {
        if (pointsJson == null || pointsJson.isBlank()) {
            throw new IllegalArgumentException("BBOX pointsJson 이 비어있습니다.");
        }
        try {
            Object root = objectMapper.readValue(pointsJson, Object.class);
            if (root instanceof List<?> raw && !raw.isEmpty()) {
                if (raw.get(0) instanceof Number) {
                    // flat: [x1, y1, x2, y2]
                    if (raw.size() < 4) {
                        throw new IllegalArgumentException("BBOX 는 최소 4개 좌표가 필요합니다.");
                    }
                    double x1 = ((Number) raw.get(0)).doubleValue();
                    double y1 = ((Number) raw.get(1)).doubleValue();
                    double x2 = ((Number) raw.get(2)).doubleValue();
                    double y2 = ((Number) raw.get(3)).doubleValue();
                    return new Bbox(x1, y1, x2, y2);
                }
                if (raw.get(0) instanceof List<?>) {
                    // nested: [[x1,y1],[x2,y2]]
                    if (raw.size() < 2) {
                        throw new IllegalArgumentException("BBOX nested 는 최소 2개 점이 필요합니다.");
                    }
                    List<?> p1 = (List<?>) raw.get(0);
                    List<?> p2 = (List<?>) raw.get(1);
                    if (p1.size() < 2 || p2.size() < 2) {
                        throw new IllegalArgumentException("BBOX 각 점은 [x,y] 2개 값이 필요합니다.");
                    }
                    double x1 = ((Number) p1.get(0)).doubleValue();
                    double y1 = ((Number) p1.get(1)).doubleValue();
                    double x2 = ((Number) p2.get(0)).doubleValue();
                    double y2 = ((Number) p2.get(1)).doubleValue();
                    return new Bbox(x1, y1, x2, y2);
                }
            }
            throw new IllegalArgumentException("BBOX 형식이 올바르지 않습니다: " + pointsJson);
        } catch (JsonProcessingException ex) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "BBOX 파싱 실패", ex);
        }
    }

    /**
     * 보간된 {@link Bbox} 를 YoloAutolabelStep 과 동일한 flat 4-double 포맷으로 직렬화한다.
     * {@code [x1, y1, x2, y2]}.
     */
    private String serializeBbox(Bbox b) {
        try {
            return objectMapper.writeValueAsString(List.of(b.left(), b.top(), b.right(), b.bottom()));
        } catch (JsonProcessingException ex) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "BBOX 직렬화 실패", ex);
        }
    }
}
