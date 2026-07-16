package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.interpolation.Bbox;
import kr.co.cudo.authoring.batch.interpolation.Keyframe;
import kr.co.cudo.authoring.batch.interpolation.PolyKeyframe;
import kr.co.cudo.authoring.batch.interpolation.PolyshapeMatcher;
import kr.co.cudo.authoring.batch.interpolation.TrackInterpolator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
public class TrackInterpolationStep implements BatchStep {

    private static final TrackInterpolator INTERPOLATOR = new TrackInterpolator();

    private final LsDataLblRepository lblRepository;
    private final LsDataLblAiInfoRepository aiInfoRepository;
    private final LsDataSrcRepository srcRepository;
    private final ObjectMapper objectMapper;

    public TrackInterpolationStep(LsDataLblRepository lblRepository,
                                  LsDataLblAiInfoRepository aiInfoRepository,
                                  LsDataSrcRepository srcRepository,
                                  ObjectMapper objectMapper) {
        this.lblRepository = lblRepository;
        this.aiInfoRepository = aiInfoRepository;
        this.srcRepository = srcRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public BatchStage stage() {
        return BatchStage.INTERPOLATE;
    }

    /**
     * 파이프라인 진입점 — 트랙 보간을 수행한다.
     * 동작 보존: 기존 orchestrator 의 {@code trackInterpolationStep.run(rawSn)} 와 동일.
     */
    @Override
    public void execute(BatchContext ctx) {
        run(ctx.getRawSn());
    }

    /**
     * 단일 영상의 모든 트랙을 보간하여 신규 INTERPOLATED BBOX row 를 저장한다.
     *
     * @param rawSn LS_DATA_RAW.RAW_SN
     * @return 저장된 보간 row 수 (0 이상). 영상 프레임이 없거나 보간 대상이 없으면 0.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int run(Long rawSn) {
        return interpolate(rawSn);
    }

    /**
     * 트랙 보간 본체 — <b>호출자의 트랜잭션에 참여</b>한다(별도 tx 경계 없음).
     * <p>파이프라인 진입점 {@link #run(Long)}(REQUIRES_NEW)이 위임하며, 트랙 병합
     * (TrackMergeService) 이 <b>trackId UPDATE 와 재보간을 같은 트랜잭션으로 묶어 원자성</b>을
     * 확보하기 위해 이 메서드를 직접 호출한다(재보간 실패 시 병합까지 함께 롤백). 자기호출이라
     * 트랜잭션 어드바이스가 없어 항상 caller tx 로 실행되며, 재보간 전 Hibernate auto-flush 로
     * 병합된 trackId 를 즉시 관측한다.
     *
     * @param rawSn LS_DATA_RAW.RAW_SN
     * @return 저장된 보간 row 수 (0 이상). 영상 프레임이 없거나 보간 대상이 없으면 0.
     */
    public int interpolate(Long rawSn) {
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
            frameToSrcSn.put(Math.toIntExact(s.getFrameNo()), s.getSrcSn());
            srcSnToFrame.put(s.getSrcSn(), Math.toIntExact(s.getFrameNo()));
        }

        // 재실행 idempotency — 기존 보간 생성 row 를 먼저 삭제(중복 INSERT 방지).
        // 자식(AI_INFO) → 부모(LS_DATA_LBL) 순서로 삭제해 FK 고아 방지.
        List<Long> staleInterpolated = lblRepository.findInterpolatedLblSnsByRawSn(rawSn);
        if (!staleInterpolated.isEmpty()) {
            aiInfoRepository.deleteByDataLblSnIn(staleInterpolated);
            lblRepository.deleteAllByIdInBatch(staleInterpolated);
            log.info("[Batch][Interpolation] cleared stale interpolated rows rawSn={} count={}",
                    rawSn, staleInterpolated.size());
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
            try {
                newRows.addAll(interpolateTrack(trackId, entry.getValue(),
                        srcSnToFrame, frameToSrcSn, totalFrames));
            } catch (Exception ex) {
                // 부분 실패 격리 — 한 트랙의 파싱/보간 예외가 같은 rawSn 의 다른 정상 트랙까지
                // 롤백하지 않도록 트랙 단위로 격리 후 skip. (예외 무시 아님 — WARN 로깅.)
                log.warn("[Batch][Interpolation] track skipped rawSn={} trackId={} reason={}",
                        rawSn, trackId, ex.getMessage());
            }
        }

        if (!newRows.isEmpty()) {
            Iterable<LsDataLbl> savedRows = lblRepository.saveAll(newRows);
            List<LsDataLblAiInfo> aiInfos = new ArrayList<>();
            for (LsDataLbl row : savedRows) {
                aiInfos.add(LsDataLblAiInfo.create(row.getLblSn(), rawSn, row.getSrcSn(),
                        LsDataLblAiInfo.SRC_INTERPOLATE, row.getConfScore(), "batch"));
            }
            aiInfoRepository.saveAll(aiInfos);
        }
        log.info("[Batch][Interpolation] saved rawSn={} tracks={} interpolatedRows={}",
                rawSn, byTrackId.size(), newRows.size());
        return newRows.size();
    }

    /**
     * 단일 트랙 재보간 — 트랙 병합(TrackMergeService) 후 <b>병합 트랙(toTrackId) 하나만</b> 재보간해
     * 락 유지시간을 단축한다. 결과 좌표는 {@link #interpolate}(영상 전체 재보간)와 <b>동일</b>하다
     * (동일한 <b>영상 전체 프레임</b> 매핑 + 동일한 트랙별 보간기 재사용 — 보간 폭 불변).
     *
     * <h2>이름은 toTrackId 재보간이나 stale 정리는 from+to 양쪽이다</h2>
     * {@code doMerge} 가 {@code reassignTrack(toTrackId)} 을 <b>원 키프레임에만</b> 적용하므로,
     * fromTrackId 로 생성됐던 기존 INTERPOLATE 산출물 row 는 trackId 가 여전히 fromTrackId 인 채 남는다.
     * toTrackId 만 지우면 이 fromTrackId 보간 산출물이 <b>고아로 영구 잔존</b>(유령 라벨·카운트 부풀림·
     * 검수 스냅샷 오염)하므로, stale 삭제 대상은 반드시 {@code {fromTrackId, toTrackId}} 양쪽이다.
     * 재보간 후보는 reassign 후 fromTrackId 가 0건이므로 toTrackId 만이다.
     *
     * <h2>트랜잭션 — caller(머지) tx 참여</h2>
     * {@link #interpolate} 와 동일하게 <b>트랜잭션 경계 없이</b> caller tx 에 참여한다(@Transactional
     * 미부착). 재보간 실패 시 머지 UPDATE(reassign)까지 롤백되어 <b>원자성</b>을 유지하기 위함이며,
     * 전체 경로의 per-track try/catch 격리와 달리 여기서는 예외를 삼키지 않는다(머지 롤백 유도).
     *
     * @param rawSn       LS_DATA_RAW.RAW_SN
     * @param toTrackId   병합 대상(재보간) 트랙 ID
     * @param fromTrackId 병합 소스 트랙 ID — stale 보간 산출물 정리 대상에만 포함(재보간 후보 아님)
     * @return 저장된 보간 row 수 (0 이상). 영상 프레임이 없거나 보간 후보가 없으면 0.
     */
    public int interpolateSingleTrack(Long rawSn, String toTrackId, String fromTrackId) {
        return interpolateSingleTrackTouched(rawSn, toTrackId, fromTrackId).newRowCount();
    }

    /**
     * {@link #interpolateSingleTrack} 와 동일 동작이되 <b>재보간이 실제로 건드린 프레임(srcSn) 집합</b>을
     * 함께 반환한다 — R4 트랙 삭제 / R5 split 이 APPROVED 통지({@code TASK_MODIFIED} "변경 프레임 목록")에
     * <b>재보간으로 삭제된 stale 보간 프레임 ∪ 새로 생성된 보간 프레임</b>을 union 해 데이터마트 드리프트를
     * 막기 위함(계약 정합). 좌표·row 수 산출 로직은 완전히 동일하다.
     *
     * @return {@link TouchedFrames}: 새로 생성된 보간 row 수 + 터치된 srcSn 집합(삭제 stale ∪ 신규)
     */
    public TouchedFrames interpolateSingleTrackTouched(Long rawSn, String toTrackId, String fromTrackId) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }

        Set<Long> touched = new HashSet<>();

        // stale 정리 — 후보 존재 여부와 무관하게 항상 선행. from+to 양쪽 보간 산출물 제거(고아 방지).
        // 삭제 전 대상 row 의 srcSn 을 touched 에 수집(통지 계약: 보간 제거된 프레임도 변경 프레임).
        // 자식(AI_INFO) → 부모(LS_DATA_LBL) 순서로 삭제해 FK 고아 방지.
        List<Long> staleInterpolated = lblRepository.findInterpolatedLblSnsByRawSnAndTrackId(
                rawSn, List.of(fromTrackId, toTrackId));
        if (!staleInterpolated.isEmpty()) {
            for (LsDataLbl stale : lblRepository.findAllById(staleInterpolated)) {
                touched.add(stale.getSrcSn());
            }
            aiInfoRepository.deleteByDataLblSnIn(staleInterpolated);
            lblRepository.deleteAllByIdInBatch(staleInterpolated);
            log.info("[Batch][Interpolation] cleared stale interpolated rows (single-track) rawSn={} from={} to={} count={}",
                    rawSn, fromTrackId, toTrackId, staleInterpolated.size());
        }

        // 프레임 매핑 — HIGH #2: 보간 폭 보존 위해 항상 영상 전체 프레임 사용(트랙만 필터). totalFrames 축소 금지.
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        if (frames.isEmpty()) {
            log.info("[Batch][Interpolation] no frames (single-track) rawSn={}", rawSn);
            return new TouchedFrames(0, touched);
        }
        int totalFrames = frames.size();
        Map<Integer, Long> frameToSrcSn = new HashMap<>(totalFrames);
        Map<Long, Integer> srcSnToFrame = new HashMap<>(totalFrames);
        for (LsDataSrc s : frames) {
            frameToSrcSn.put(Math.toIntExact(s.getFrameNo()), s.getSrcSn());
            srcSnToFrame.put(s.getSrcSn(), Math.toIntExact(s.getFrameNo()));
        }

        // 후보 — toTrackId 단건만(reassign 후 fromTrackId 후보 0건). 위 stale 삭제가 auto-flush 로 선반영됨.
        List<LsDataLbl> candidates = lblRepository.findAutoBboxByRawSnAndTrackId(rawSn, toTrackId);
        if (candidates.isEmpty()) {
            log.info("[Batch][Interpolation] no candidates (single-track) rawSn={} to={}", rawSn, toTrackId);
            return new TouchedFrames(0, touched);
        }

        // 공통부 재사용 — BBOX/POLYGON 라우팅 + 타입 혼재 skip 가드 포함. 전체 경로와 동일 로직(좌표 동일 보장).
        // 전체 경로의 per-track try/catch 격리와 달리 예외를 전파해 머지/삭제/split 원자성을 유지한다.
        List<LsDataLbl> newRows = interpolateTrack(toTrackId, candidates, srcSnToFrame, frameToSrcSn, totalFrames);

        if (!newRows.isEmpty()) {
            Iterable<LsDataLbl> savedRows = lblRepository.saveAll(newRows);
            List<LsDataLblAiInfo> aiInfos = new ArrayList<>();
            for (LsDataLbl row : savedRows) {
                touched.add(row.getSrcSn());
                aiInfos.add(LsDataLblAiInfo.create(row.getLblSn(), rawSn, row.getSrcSn(),
                        LsDataLblAiInfo.SRC_INTERPOLATE, row.getConfScore(), "batch"));
            }
            aiInfoRepository.saveAll(aiInfos);
        }
        log.info("[Batch][Interpolation] saved (single-track) rawSn={} to={} interpolatedRows={}",
                rawSn, toTrackId, newRows.size());
        return new TouchedFrames(newRows.size(), touched);
    }

    /**
     * 단일 트랙 재보간 결과 — 새로 생성된 보간 row 수({@code newRowCount})와 재보간이 삭제/생성으로
     * 실제 건드린 프레임(srcSn) 집합({@code touchedSrcSns}). 후자는 APPROVED 통지의 변경 프레임 union 용.
     */
    public record TouchedFrames(int newRowCount, Set<Long> touchedSrcSns) {
    }

    /**
     * 단일 트랙을 타입별로 라우팅하여 보간 row 를 산출한다.
     * <p>트랙 내 {@code LBL_TYPE_CD} 가 혼재(distinct &gt; 1)하면 안전하게 skip(WARN).
     * BBOX 는 선형 보간, POLYGON 은 polyshape 보간. (POLYLINE 은 현재 DB 코드값 미도입.)
     */
    private List<LsDataLbl> interpolateTrack(String trackId, List<LsDataLbl> labels,
                                             Map<Long, Integer> srcSnToFrame,
                                             Map<Integer, Long> frameToSrcSn,
                                             int totalFrames) {
        List<LsDataLbl> sorted = labels.stream()
                .filter(l -> srcSnToFrame.containsKey(l.getSrcSn()))
                .sorted(Comparator.comparingInt(l -> srcSnToFrame.get(l.getSrcSn())))
                .toList();
        if (sorted.isEmpty()) {
            return List.of();
        }
        List<String> types = sorted.stream().map(LsDataLbl::getLblTypeCd).distinct().toList();
        if (types.size() > 1) {
            log.warn("[Batch][Interpolation] mixed label types in track trackId={} types={} -> skip",
                    trackId, types);
            return List.of();
        }
        String type = types.get(0);
        String label = sorted.get(0).getLabelNm();
        if (LsDataLbl.TYPE_POLYGON.equals(type)) {
            return interpolatePolygonTrack(trackId, label, sorted, srcSnToFrame, frameToSrcSn, totalFrames);
        }
        return interpolateBboxTrack(trackId, label, sorted, srcSnToFrame, frameToSrcSn, totalFrames);
    }

    private List<LsDataLbl> interpolateBboxTrack(String trackId, String label, List<LsDataLbl> sorted,
                                                 Map<Long, Integer> srcSnToFrame,
                                                 Map<Integer, Long> frameToSrcSn, int totalFrames) {
        List<Keyframe> keyframes = sorted.stream()
                .map(l -> new Keyframe(srcSnToFrame.get(l.getSrcSn()), parseBbox(l.getPointCn()), false))
                .toList();
        Map<Integer, Bbox> interpolated = INTERPOLATOR.interpolate(keyframes, totalFrames);
        Set<Integer> existingFrames = keyframes.stream().map(Keyframe::frame).collect(Collectors.toSet());
        List<LsDataLbl> rows = new ArrayList<>();
        for (Map.Entry<Integer, Bbox> ie : interpolated.entrySet()) {
            int frame = ie.getKey();
            if (existingFrames.contains(frame)) {
                continue;  // 키프레임 자체는 skip
            }
            Long srcSn = frameToSrcSn.get(frame);
            if (srcSn == null) {
                continue;  // 안전망 — 매핑 안 되는 프레임은 skip
            }
            rows.add(LsDataLbl.createAutoInterpolatedBbox(
                    srcSn, null, label, serializeBbox(ie.getValue()), BigDecimal.ZERO, trackId));
        }
        return rows;
    }

    private List<LsDataLbl> interpolatePolygonTrack(String trackId, String label, List<LsDataLbl> sorted,
                                                    Map<Long, Integer> srcSnToFrame,
                                                    Map<Integer, Long> frameToSrcSn, int totalFrames) {
        List<PolyKeyframe> keyframes = sorted.stream()
                .map(l -> new PolyKeyframe(srcSnToFrame.get(l.getSrcSn()), parsePolygon(l.getPointCn()), false))
                .toList();
        // closed=true (폐곡선). 정점 개수/좌표 검증 실패 시 IllegalArgumentException → 호출부(run)가 트랙 단위 skip.
        Map<Integer, List<Point>> interpolated = INTERPOLATOR.interpolatePolyshape(keyframes, totalFrames, true);
        Set<Integer> existingFrames = keyframes.stream().map(PolyKeyframe::frame).collect(Collectors.toSet());
        List<LsDataLbl> rows = new ArrayList<>();
        for (Map.Entry<Integer, List<Point>> ie : interpolated.entrySet()) {
            int frame = ie.getKey();
            if (existingFrames.contains(frame)) {
                continue;  // 키프레임 자체는 skip
            }
            Long srcSn = frameToSrcSn.get(frame);
            if (srcSn == null) {
                continue;  // 안전망 — 매핑 안 되는 프레임은 skip
            }
            rows.add(LsDataLbl.createAutoInterpolatedPolygon(
                    srcSn, null, label, LabelPointSerializer.toJson(ie.getValue(), objectMapper),
                    BigDecimal.ZERO, trackId));
        }
        return rows;
    }

    /**
     * POLYGON pointsJson 을 정점 목록으로 파싱. 정규형/평탄/객체배열 모두 {@link LabelPointSerializer#fromJson} 로 흡수.
     * <p>빈 정점열이면 이후 {@link PolyshapeMatcher} 가 정점 개수 미달로 거부한다(호출부에서 트랙 skip).
     */
    private List<Point> parsePolygon(String pointsJson) {
        if (pointsJson == null || pointsJson.isBlank()) {
            throw new IllegalArgumentException("POLYGON pointsJson 이 비어있습니다.");
        }
        return LabelPointSerializer.fromJson(pointsJson, objectMapper);
    }

    /**
     * BBOX pointsJson 을 {@link Bbox} 로 파싱.
     *
     * <p>지원 포맷 (자체 시스템 산출물):
     * <ul>
     *   <li>{@code [[x1,y1],[x2,y2]]} — 2x2 nested. <b>현재 write 포맷</b> (Phase 1 좌표 정규화
     *       이후 YoloAutolabelStep·수동 라벨·{@link #serializeBbox} 가 모두 이 형식으로 저장).</li>
     *   <li>{@code [x1,y1,x2,y2]} — flat 4-double. <b>레거시 기존 데이터</b>(Phase 1 좌표 정규화 이전
     *       YOLO 가 저장한 row)와의 호환을 위해 계속 읽는다.</li>
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
            // CWE-117: 예외 메시지에 원본 pointsJson 전문 미노출.
            throw new IllegalArgumentException("BBOX 형식이 올바르지 않습니다");
        } catch (JsonProcessingException ex) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "BBOX 파싱 실패", ex);
        }
    }

    /**
     * 보간된 {@link Bbox} 를 수동 라벨과 동일한 정규형 nested 포맷으로 직렬화한다.
     * {@code [[x1, y1], [x2, y2]]} (Phase 1 좌표 정규화).
     *
     * <p>{@link #parseBbox} 는 flat/nested 양쪽을 계속 읽으므로 같은 배치 내 레거시 flat 과
     * 신규 nested 가 혼재해도 보간 사이클이 정상 동작한다.
     */
    private String serializeBbox(Bbox b) {
        return LabelPointSerializer.toJson(
                List.of(new Point(b.left(), b.top()), new Point(b.right(), b.bottom())),
                objectMapper);
    }
}
