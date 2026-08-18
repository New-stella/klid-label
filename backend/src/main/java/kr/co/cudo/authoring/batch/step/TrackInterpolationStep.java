package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.interpolation.Bbox;
import kr.co.cudo.authoring.batch.interpolation.Keyframe;
import kr.co.cudo.authoring.batch.interpolation.PolyKeyframe;
import kr.co.cudo.authoring.batch.interpolation.PolyshapeMatcher;
import kr.co.cudo.authoring.batch.interpolation.TrackInterpolator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
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
    private final LsDataSrcRepository srcRepository;
    private final ObjectMapper objectMapper;

    public TrackInterpolationStep(LsDataLblRepository lblRepository,
                                  LsDataSrcRepository srcRepository,
                                  ObjectMapper objectMapper) {
        this.lblRepository = lblRepository;
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
     *
     * <p><b>트랜잭션 경계는 여기에 있다</b>(DEV_FIX — self-invocation 트랜잭션 부재). 오케스트레이터가
     * 빈(프록시)의 {@code execute} 를 호출하므로 애노테이션이 발효되고, 아래 {@code this.run(...)} 은
     * 자기호출이라 어드바이스가 걸리지 않아 본 트랜잭션에 참여한다(REQUIRES_NEW 중첩 없음 —
     * 스텝 1건 = 트랜잭션 1건). {@code run()} 을 프록시 경유로 바꾸면 중첩되므로 바꾸지 말 것.
     */
    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
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

        // DEV_FIX(H4) — 프레임 락을 트랜잭션 맨 앞에서 1회·SRC_SN 오름차순으로 선점한다. 본 경로도
        //   "stale 보간 프레임 bump → 신규 보간 프레임 bump" 로 한 트랜잭션에서 서로 다른 집합을 2회
        //   잠갔다. 트랙 편집 경로만 단일화하고 여기를 두면, 편집이 모든 프레임을 쥔 채 라벨 락을
        //   기다리고 이쪽이 프레임 락을 기다리는 순환이 그대로 남는다(양쪽 모두 단일 지점이어야 한다).
        //   상세 근거는 LsDataSrcRepository#lockFramesByRawSn Javadoc.
        srcRepository.lockFramesByRawSn(rawSn);

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
        // V6 흡수로 생산이력이 같은 행이라 자식(AI_INFO) 선삭제 단계가 사라졌다.
        List<Long> staleInterpolated = lblRepository.findInterpolatedLblSnsByRawSn(rawSn);
        // C-ISSUE-21 / DEV_FIX(H2① 락 순서 · H11 범위) — 삭제 대상 프레임 집합을 먼저 확정하고, 라벨 행을
        //   지우기 <b>전에</b> 그 프레임들의 라벨셋 버전을 +1 한다(= 프레임 락 선점). 라벨 삭제 후 bump 하면
        //   "프레임 락 → 라벨 락" 인 라벨 저장 경로와 역순이 되어 ABBA 데드락이 열린다.
        if (!staleInterpolated.isEmpty()) {
            Set<Long> staleFrames = new HashSet<>();
            for (LsDataLbl stale : lblRepository.findAllById(staleInterpolated)) {
                staleFrames.add(stale.getSrcSn());
            }
            srcRepository.bumpLabelVersionIn(staleFrames);
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
            // V6 — LBL_SRC_CD='INTERPOLATE' 는 팩토리(createAutoInterpolated*)가 이미 실 컬럼에 넣어
            //   저장되므로, 저장 후 AI 메타를 따로 적재하던 단계가 사라졌다.
            Iterable<LsDataLbl> savedRows = lblRepository.saveAll(newRows);
            Set<Long> insertedFrames = new HashSet<>();
            for (LsDataLbl row : savedRows) {
                insertedFrames.add(row.getSrcSn());
            }
            // C-ISSUE-21 — 보간 row 가 <b>생성된 프레임</b>의 라벨셋 버전 +1. 배치 재실행(수동 재처리·오토라벨
            //   재실행)은 라벨링 중 영상에도 일어날 수 있어, 편집 화면이 보유한 버전을 무효화해 낡은
            //   full-replace 저장이 방금 만든 보간 산출물을 지우는 lost update 를 막는다.
            // DEV_FIX(H11 범위) — 구현은 영상 전 프레임(bumpLabelVersionByRawSn)을 올렸으나, 실제 변경
            //   프레임만 올린다(삭제분은 위에서 선반영). 손대지 않은 프레임을 편집 중인 작업자가 409 를 받는
            //   과잉 무효화와, 영상 전 프레임에 대한 광역 쓰기 락을 함께 제거한다.
            // DEV_FIX(H4 주석 정정) — 구 주석은 "삽입은 락을 잡지 않으므로 규약 대상이 아니다"라고 했으나
            //   부정확하다. bump 자체가 프레임 행에 쓰기 락을 잡으므로 이 문장도 락 획득이다. 이 위치가
            //   안전한 진짜 이유는 <b>같은 트랜잭션이 이미 상단에서 영상 전 프레임 락을 선점</b>했기 때문이다
            //   (lockFramesByRawSn) — 여기서 새로 얻는 락이 없다.
            srcRepository.bumpLabelVersionIn(insertedFrames);
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

        // DEV_FIX(H4) — 프레임 락 단일 선점. 호출부(트랙 삭제/분할/병합)가 이미 같은 문장으로 선점했다면
        //   같은 트랜잭션이 이미 보유한 행이라 새 락을 얻지 않는다(멱등). 이 메서드가 caller tx 에 참여하는
        //   구조라 여기서 다시 호출해도 락 획득 지점이 늘지 않는다.
        srcRepository.lockFramesByRawSn(rawSn);

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
            // DEV_FIX(H2① 락 순서) — 라벨 행 삭제 前 프레임 락 선점(bump). 호출부(트랙 삭제/분할/병합)가
            //   자기 변경 프레임을 이미 올렸더라도 stale 보간 프레임은 그 집합 밖일 수 있으므로 여기서 올린다.
            //   버전은 단조 증가라 호출부와 중복되어도 무해하다.
            srcRepository.bumpLabelVersionIn(touched);
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
            // V6 — 전체 경로와 같은 이유로 AI 메타 동반 적재가 사라졌다(팩토리가 실 컬럼에 넣는다).
            Iterable<LsDataLbl> savedRows = lblRepository.saveAll(newRows);
            for (LsDataLbl row : savedRows) {
                touched.add(row.getSrcSn());
            }
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
     *
     * <h3>분류 축 승계</h3>
     * 보간 row 는 키프레임의 <b>라벨명({@code LABEL_NM}) 과 라벨 마스터 FK({@code LBL_ID}) 를 함께</b>
     * 승계한다. 두 값은 반드시 <b>같은 키프레임</b>(정렬 후 선두 = {@code anchor})에서 취한다 —
     * 서로 다른 키프레임에서 가져오면 이름과 분류가 어긋나는 divergence 가 새로 생긴다.
     * <p>{@code LBL_ID} 가 없는(마스터 미연결) 키프레임이면 보간 row 도 {@code null} 이 정답이다.
     * 라벨명으로 마스터를 역추적해 채우지 않는다 — 라벨명에는 유일성 제약이 없어 동명이인·비활성
     * 마스터로 오매칭되면 <b>다른 분류로 저장</b>된다.
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
        // 분류 축은 한 키프레임(anchor)에서 라벨명·labelId 를 함께 취한다 (위 javadoc「분류 축 승계」).
        LsDataLbl anchor = sorted.get(0);
        String label = anchor.getLabelNm();
        Long labelId = anchor.getLabelId();
        if (LsDataLbl.TYPE_POLYGON.equals(type)) {
            return interpolatePolygonTrack(trackId, labelId, label, sorted, srcSnToFrame, frameToSrcSn, totalFrames);
        }
        return interpolateBboxTrack(trackId, labelId, label, sorted, srcSnToFrame, frameToSrcSn, totalFrames);
    }

    private List<LsDataLbl> interpolateBboxTrack(String trackId, Long labelId, String label, List<LsDataLbl> sorted,
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
                    srcSn, labelId, label, serializeBbox(ie.getValue()), BigDecimal.ZERO, trackId));
        }
        return rows;
    }

    private List<LsDataLbl> interpolatePolygonTrack(String trackId, Long labelId, String label, List<LsDataLbl> sorted,
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
                    srcSn, labelId, label, LabelPointSerializer.toJson(ie.getValue(), objectMapper),
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
