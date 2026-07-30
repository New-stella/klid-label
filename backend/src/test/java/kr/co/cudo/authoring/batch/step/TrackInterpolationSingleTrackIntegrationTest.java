package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.support.RawVideoFixture;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 — 트랙 병합 후 <b>단일 트랙(toTrackId) 재보간</b> 통합 테스트 (실제 DB).
 *
 * <p>{@link TrackInterpolationStep#interpolateSingleTrack} 이 병합 트랙만 재생성하되 stale 보간
 * 산출물은 {@code {fromTrackId, toTrackId}} 양쪽을 정리함을 검증한다. 학습데이터 정합은 비가역이므로
 * 골든 테스트로 전체 재보간({@link TrackInterpolationStep#interpolate})과 좌표 동일성을 보장한다.
 *
 * <p>{@code @Transactional} — 각 테스트가 caller tx 에서 실행되고 종료 시 롤백된다. step 의 두 진입
 * 메서드({@code interpolate}/{@code interpolateSingleTrack})는 트랜잭션 경계가 없어 이 tx 에 참여한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class TrackInterpolationSingleTrackIntegrationTest {

    @Autowired private TrackInterpolationStep step;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblAiInfoRepository aiInfoRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager em;

    /** 벌크 삭제(deleteAllByIdInBatch)는 DB 는 반영하나 L1 영속성 컨텍스트를 동기화하지 않으므로,
     * findById 재조회 전 flush+clear 로 L1 캐시를 비워 실제 DB 상태를 읽게 한다. */
    private void syncDb() {
        em.flush();
        em.clear();
    }

    // 테스트 간 rawSn 충돌 방지용 유니크 시드.
    private static final AtomicLong RAW_SEQ = new AtomicLong(970_700_000L);

    /**
     * 격리된 새 영상 1건을 <b>실제로 적재</b>하고 rawSn 을 반환한다.
     * V146(DB-ISSUE-01) 이후 프레임·AI_INFO 가 {@code LS_DATA_RAW} 를 FK 로 참조한다.
     */
    private long nextRaw() {
        return RawVideoFixture.seedRaw(jdbcTemplate, RAW_SEQ.incrementAndGet());
    }

    /** frameNo 0..count-1 프레임 생성 → frameNo→srcSn 매핑 반환. */
    private Map<Integer, Long> frames(long rawSn, int count) {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < count; i++) {
            LsDataSrc s = srcRepository.saveAndFlush(
                    LsDataSrc.create(rawSn, i, "raw/" + rawSn + "/" + i + ".png", null));
            map.put(i, s.getSrcSn());
        }
        return map;
    }

    /** 자동 BBOX 키프레임(AUTO_LBL_YN='Y', trackId 보유) 저장. */
    private Long autoBbox(long rawSn, long srcSn, String trackId, String pts) {
        LsDataLbl lbl = lblRepository.saveAndFlush(
                LsDataLbl.createAutoBbox(srcSn, null, "person", pts, BigDecimal.valueOf(0.9), trackId));
        aiInfoRepository.saveAndFlush(LsDataLblAiInfo.create(
                lbl.getLblSn(), rawSn, srcSn, LsDataLblAiInfo.SRC_YOLO, BigDecimal.valueOf(0.9), "batch"));
        return lbl.getLblSn();
    }

    /** 자동 POLYGON 키프레임 저장 (타입 혼재 skip 가드 검증용). */
    private Long autoPolygon(long rawSn, long srcSn, String trackId, String pts) {
        LsDataLbl lbl = LsDataLbl.createManual(srcSn, "POLYGON", null, "person", pts, null);
        lbl = lblRepository.saveAndFlush(lbl);
        aiInfoRepository.saveAndFlush(LsDataLblAiInfo.create(
                lbl.getLblSn(), rawSn, srcSn, LsDataLblAiInfo.SRC_YOLO, BigDecimal.valueOf(0.9), "batch"));
        setTrackId(lbl, trackId);
        lblRepository.saveAndFlush(lbl);
        return lbl.getLblSn();
    }

    /** 기존(선행 전체 재보간) INTERPOLATE 산출물 row 저장 — stale 정리 대상. */
    private Long interpolatedRow(long rawSn, long srcSn, String trackId, String pts) {
        LsDataLbl lbl = lblRepository.saveAndFlush(
                LsDataLbl.createAutoInterpolatedBbox(srcSn, null, "person", pts, BigDecimal.ZERO, trackId));
        aiInfoRepository.saveAndFlush(LsDataLblAiInfo.create(
                lbl.getLblSn(), rawSn, srcSn, LsDataLblAiInfo.SRC_INTERPOLATE, BigDecimal.ZERO, "batch"));
        return lbl.getLblSn();
    }

    private static void setTrackId(LsDataLbl lbl, String trackId) {
        try {
            var f = LsDataLbl.class.getDeclaredField("trackId");
            f.setAccessible(true);
            f.set(lbl, trackId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** 영상의 INTERPOLATE 산출물 중 trackId 일치 row 를 frameNo→POINT_CN 정렬 맵으로. */
    private Map<Integer, String> interpolatedByFrame(long rawSn, String trackId, Map<Long, Integer> srcToFrame) {
        List<Long> interpSns = lblRepository.findInterpolatedLblSnsByRawSn(rawSn);
        Map<Integer, String> byFrame = new TreeMap<>();
        for (LsDataLbl l : lblRepository.findAllById(interpSns)) {
            if (trackId.equals(l.getTrackId())) {
                byFrame.put(srcToFrame.get(l.getSrcSn()), l.getPointCn());
            }
        }
        return byFrame;
    }

    private static Map<Long, Integer> invert(Map<Integer, Long> frameToSrc) {
        Map<Long, Integer> inv = new HashMap<>();
        frameToSrc.forEach((f, s) -> inv.put(s, f));
        return inv;
    }

    // ─── 골든: 단일 트랙 재보간 좌표가 전체 재보간과 동일 (HIGH #2 — 학습데이터 정합 비가역) ───

    @Test
    @DisplayName("단일트랙_재보간_좌표가_전체재보간과_동일")
    void singleTrackCoordinatesEqualFullReinterpolation() {
        // rawA: 전체 재보간. to(frame0,10) + other(frame0,10).
        long rawA = nextRaw();
        Map<Integer, Long> fA = frames(rawA, 11);
        autoBbox(rawA, fA.get(0), "to", "[[0,0],[100,100]]");
        autoBbox(rawA, fA.get(10), "to", "[[100,100],[200,200]]");
        autoBbox(rawA, fA.get(0), "other", "[[0,0],[10,10]]");
        autoBbox(rawA, fA.get(10), "other", "[[50,50],[60,60]]");
        step.interpolate(rawA);
        Map<Integer, String> fullTo = interpolatedByFrame(rawA, "to", invert(fA));

        // rawB: 동일 데이터, 단일 트랙 재보간(to 만).
        long rawB = nextRaw();
        Map<Integer, Long> fB = frames(rawB, 11);
        autoBbox(rawB, fB.get(0), "to", "[[0,0],[100,100]]");
        autoBbox(rawB, fB.get(10), "to", "[[100,100],[200,200]]");
        autoBbox(rawB, fB.get(0), "other", "[[0,0],[10,10]]");
        autoBbox(rawB, fB.get(10), "other", "[[50,50],[60,60]]");
        step.interpolateSingleTrack(rawB, "to", "from-none");
        Map<Integer, String> singleTo = interpolatedByFrame(rawB, "to", invert(fB));

        // 보간 폭(1..9, 9건) + 좌표가 프레임별로 정확히 일치해야 한다.
        assertThat(singleTo.keySet()).containsExactlyInAnyOrderElementsOf(fullTo.keySet());
        assertThat(singleTo).hasSize(9);
        assertThat(singleTo).isEqualTo(fullTo);
    }

    // ─── 병합 트랙만 재보간, 다른 트랙 보간은 불변 ───

    @Test
    @DisplayName("머지후_toTrackId만_재보간되고_다른_트랙_보간은_불변")
    void onlyToTrackReinterpolated() {
        long raw = nextRaw();
        Map<Integer, Long> f = frames(raw, 11);
        autoBbox(raw, f.get(0), "to", "[[0,0],[100,100]]");
        autoBbox(raw, f.get(10), "to", "[[100,100],[200,200]]");
        autoBbox(raw, f.get(0), "other", "[[0,0],[10,10]]");
        autoBbox(raw, f.get(10), "other", "[[50,50],[60,60]]");

        int rows = step.interpolateSingleTrack(raw, "to", "from-none");

        assertThat(rows).isEqualTo(9);
        Map<Long, Integer> inv = invert(f);
        assertThat(interpolatedByFrame(raw, "to", inv)).hasSize(9);
        // other 트랙은 단일 경로에서 재보간되지 않는다 → 보간 산출물 0.
        assertThat(interpolatedByFrame(raw, "other", inv)).isEmpty();
    }

    // ─── HIGH #1: fromTrackId 기존 보간 산출물 고아 0 ───

    @Test
    @DisplayName("머지후_fromTrackId_기존보간산출물_고아_0")
    void fromTrackOrphanInterpolatedCleared() {
        long raw = nextRaw();
        Map<Integer, Long> f = frames(raw, 11);
        // 선행 전체 재보간이 fromTrackId 로 만들어 둔 보간 산출물(reassign 후 trackId 그대로 잔존 위험).
        Long fromOrphan = interpolatedRow(raw, f.get(5), "from", "[[5,5],[6,6]]");
        // 병합 후 toTrackId 키프레임(원래 to + reassign 된 from 키프레임 모두 to 로 관측되는 상태).
        autoBbox(raw, f.get(0), "to", "[[0,0],[100,100]]");
        autoBbox(raw, f.get(10), "to", "[[100,100],[200,200]]");

        step.interpolateSingleTrack(raw, "to", "from");
        syncDb();

        // 전체 조회에 fromTrackId 보간 잔재 0건 — from+to 양쪽 stale 삭제가 고아를 제거했다.
        List<LsDataLbl> interps = lblRepository.findAllById(lblRepository.findInterpolatedLblSnsByRawSn(raw));
        assertThat(interps).noneMatch(l -> "from".equals(l.getTrackId()));
        assertThat(lblRepository.findById(fromOrphan)).isEmpty();
    }

    @Test
    @DisplayName("머지후_고아_프레임_0")
    void noOrphanFrames() {
        long raw = nextRaw();
        Map<Integer, Long> f = frames(raw, 11);
        autoBbox(raw, f.get(0), "to", "[[0,0],[100,100]]");
        autoBbox(raw, f.get(10), "to", "[[100,100],[200,200]]");

        step.interpolateSingleTrack(raw, "to", "from-none");

        List<Long> validSrcSns = f.values().stream().toList();
        List<LsDataLbl> interps = lblRepository.findAllById(lblRepository.findInterpolatedLblSnsByRawSn(raw));
        assertThat(interps).allMatch(l -> validSrcSns.contains(l.getSrcSn()));
    }

    // ─── stale 삭제는 from+to 만, 타 트랙 보간 산출물 불변 ───

    @Test
    @DisplayName("보간산출물_stale_삭제가_from_to만_적용_타트랙_불변")
    void staleDeleteAppliesToFromAndToOnly() {
        long raw = nextRaw();
        Map<Integer, Long> f = frames(raw, 11);
        Long fromInterp = interpolatedRow(raw, f.get(3), "from", "[[3,3],[4,4]]");
        Long toInterp = interpolatedRow(raw, f.get(4), "to", "[[4,4],[5,5]]");
        Long otherInterp = interpolatedRow(raw, f.get(6), "other", "[[6,6],[7,7]]");
        autoBbox(raw, f.get(0), "to", "[[0,0],[100,100]]");
        autoBbox(raw, f.get(10), "to", "[[100,100],[200,200]]");

        step.interpolateSingleTrack(raw, "to", "from");
        syncDb();

        // from/to 보간 산출물은 삭제, other 는 불변.
        assertThat(lblRepository.findById(fromInterp)).isEmpty();
        assertThat(lblRepository.findById(toInterp)).isEmpty();
        assertThat(lblRepository.findById(otherInterp)).isPresent();
    }

    // ─── HIGH #4: 타입 혼재 skip 가드가 단일 경로에서도 동작 ───

    @Test
    @DisplayName("트랙_타입혼재시_skip_가드_단일경로에서도_동작")
    void mixedTypeTrackSkippedInSinglePath() {
        long raw = nextRaw();
        Map<Integer, Long> f = frames(raw, 5);
        // toTrackId "to" 에 BBOX + POLYGON 혼재 → interpolateTrack 이 안전 skip → 0 rows.
        autoBbox(raw, f.get(0), "to", "[[0,0],[10,10]]");
        autoPolygon(raw, f.get(4), "to", "[[0,0],[10,0],[5,10]]");

        int rows = step.interpolateSingleTrack(raw, "to", "from-none");

        assertThat(rows).isZero();
        assertThat(interpolatedByFrame(raw, "to", invert(f))).isEmpty();
    }

    // ─── 후보 없어도 stale 삭제는 항상 선행 ───

    @Test
    @DisplayName("후보없어도_stale삭제_선행_실행")
    void staleDeletePrecedesEvenWithoutCandidates() {
        long raw = nextRaw();
        Map<Integer, Long> f = frames(raw, 11);
        // 재보간 후보(to auto 키프레임) 없음. from/to 보간 산출물만 존재.
        Long fromInterp = interpolatedRow(raw, f.get(3), "from", "[[3,3],[4,4]]");
        Long toInterp = interpolatedRow(raw, f.get(4), "to", "[[4,4],[5,5]]");

        int rows = step.interpolateSingleTrack(raw, "to", "from");
        syncDb();

        assertThat(rows).isZero();
        assertThat(lblRepository.findById(fromInterp)).isEmpty();
        assertThat(lblRepository.findById(toInterp)).isEmpty();
    }

    // ─── 신규 repository 오버로드 DB 레벨 필터 검증 ───

    @Test
    @DisplayName("findAutoBboxByRawSnAndTrackId_지정트랙만_DB레벨_반환")
    void findAutoBboxByTrackIdReturnsOnlyRequestedTrack() {
        long raw = nextRaw();
        Map<Integer, Long> f = frames(raw, 3);
        Long toSn = autoBbox(raw, f.get(0), "to", "[[0,0],[10,10]]");
        autoBbox(raw, f.get(1), "other", "[[0,0],[10,10]]");

        List<LsDataLbl> found = lblRepository.findAutoBboxByRawSnAndTrackId(raw, "to");

        assertThat(found).extracting(LsDataLbl::getLblSn).containsExactly(toSn);
    }

    @Test
    @DisplayName("findInterpolatedLblSnsByRawSnAndTrackId_from_to만_반환_타트랙제외")
    void findInterpolatedByTrackIdsReturnsOnlyGivenTracks() {
        long raw = nextRaw();
        Map<Integer, Long> f = frames(raw, 3);
        Long fromSn = interpolatedRow(raw, f.get(0), "from", "[[0,0],[1,1]]");
        Long toSn = interpolatedRow(raw, f.get(1), "to", "[[1,1],[2,2]]");
        Long otherSn = interpolatedRow(raw, f.get(2), "other", "[[2,2],[3,3]]");

        List<Long> found = lblRepository.findInterpolatedLblSnsByRawSnAndTrackId(raw, List.of("from", "to"));

        assertThat(found).containsExactlyInAnyOrder(fromSn, toSn).doesNotContain(otherSn);
    }
}
