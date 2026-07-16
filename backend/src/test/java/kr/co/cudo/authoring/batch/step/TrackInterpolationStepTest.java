package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 TrackInterpolationStep 단위 테스트.
 *
 * <p>Repository 는 mock — DB 라운드트립 없음. 알고리즘 정확성은 {@link
 * kr.co.cudo.authoring.batch.interpolation.TrackInterpolatorTest} 가 보장하고,
 * 여기서는 그 결과를 DB 로 어떻게 INSERT 하는지 (factory 선택, 좌표/trackId/label
 * 보존, 키프레임 skip, 멀티 트랙 독립성 등) 만 검증한다.</p>
 */
class TrackInterpolationStepTest {

    private LsDataLblRepository lblRepository;
    private LsDataLblAiInfoRepository aiInfoRepository;
    private LsDataSrcRepository srcRepository;
    private ObjectMapper objectMapper;
    private TrackInterpolationStep step;

    @BeforeEach
    void setUp() {
        lblRepository = mock(LsDataLblRepository.class);
        aiInfoRepository = mock(LsDataLblAiInfoRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        objectMapper = new ObjectMapper();
        step = new TrackInterpolationStep(lblRepository, aiInfoRepository, srcRepository, objectMapper);

        // saveAll 기본 동작 — 입력 그대로 반환하되 lblSn 부여 (AI Info INSERT 시 row.getLblSn() 사용)
        when(lblRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<?> arg = inv.getArgument(0);
            List<LsDataLbl> ret = new ArrayList<>();
            long id = 10000L;
            for (Object o : arg) {
                LsDataLbl l = (LsDataLbl) o;
                setField(l, "lblSn", id++);
                ret.add(l);
            }
            return ret;
        });
    }

    /** 헬퍼 — frameNo=N, srcSn=offset+N 형태로 10 개 프레임 생성. */
    private List<LsDataSrc> framesOf(long rawSn, long srcSnOffset, int count) {
        List<LsDataSrc> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LsDataSrc s = LsDataSrc.create(rawSn, i, "raw/" + rawSn + "/" + i + ".png", null);
            setField(s, "srcSn", srcSnOffset + i);
            list.add(s);
        }
        return list;
    }

    /** 헬퍼 — bbox flat 4-double JSON 으로 라벨 생성 (autoLblYn=Y, BBOX, lblSrcCd=null). */
    private LsDataLbl autoBboxAt(long srcSn, String label, String trackId,
                                 double x1, double y1, double x2, double y2) {
        String pts = "[" + x1 + "," + y1 + "," + x2 + "," + y2 + "]";
        LsDataLbl lbl = LsDataLbl.createAutoBbox(srcSn, null, label, pts, BigDecimal.valueOf(0.9), trackId);
        return lbl;
    }

    /** 헬퍼 — POLYGON 라벨 생성 (autoLblYn=Y, trackId 보유). pointsJson 은 nested [[x,y],...]. */
    private LsDataLbl autoPolygonAt(long srcSn, String label, String trackId, String pointsJson) {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(srcSn, null, label, pointsJson, BigDecimal.valueOf(0.9), trackId);
        setField(lbl, "lblTypeCd", "POLYGON");
        return lbl;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("rawSn_null이면_INVALID_INPUT")
    void nullRawSnRejected() {
        assertThatThrownBy(() -> step.run(null))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("프레임_없는_영상은_no_op_saveAll_미호출")
    void noFramesIsNoOp() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(900L)).thenReturn(List.of());

        int saved = step.run(900L);

        assertThat(saved).isEqualTo(0);
        verify(lblRepository, never()).saveAll(any());
        verify(lblRepository, never()).findAutoBboxWithTrackId(anyLong());
    }

    @Test
    @DisplayName("trackId_있는_BBOX_없으면_no_op")
    void noCandidatesIsNoOp() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(901L)).thenReturn(framesOf(901L, 1000L, 10));
        when(lblRepository.findAutoBboxWithTrackId(901L)).thenReturn(List.of());

        int saved = step.run(901L);

        assertThat(saved).isEqualTo(0);
        verify(lblRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("trackId_없는_라벨은_보간_대상_외_(repository_쿼리가_이미_필터)")
    void noTrackIdLabelIsExcludedByRepository() {
        // findAutoBboxWithTrackId 가 NOT NULL 조건이므로 step 입장에서는 이미 필터된 결과만 받음.
        // 따라서 trackId NULL 인 라벨이 candidates 에 섞여 있지 않음 → no_op 동일.
        when(srcRepository.findByRawSnOrderByFrameNoAsc(902L)).thenReturn(framesOf(902L, 2000L, 10));
        when(lblRepository.findAutoBboxWithTrackId(902L)).thenReturn(List.of());

        int saved = step.run(902L);

        assertThat(saved).isEqualTo(0);
    }

    @Test
    @DisplayName("같은_trackId_5_10_프레임_BBOX_2개_있으면_6_7_8_9_프레임_보간_4건_저장")
    void interpolatesBetweenTwoKeyframes() {
        // 10 프레임 영상, frame 5 와 frame 10 에 같은 trackId="7" 의 BBOX → 6,7,8,9 보간
        // srcSn = frameNo + 3000
        List<LsDataSrc> frames = framesOf(903L, 3000L, 11);  // 0..10
        when(srcRepository.findByRawSnOrderByFrameNoAsc(903L)).thenReturn(frames);

        LsDataLbl at5 = autoBboxAt(3005L, "person", "7", 0, 0, 100, 100);
        LsDataLbl at10 = autoBboxAt(3010L, "person", "7", 100, 100, 200, 200);
        when(lblRepository.findAutoBboxWithTrackId(903L)).thenReturn(List.of(at5, at10));

        int saved = step.run(903L);

        assertThat(saved).isEqualTo(4);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> captor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(captor.capture());
        List<LsDataLbl> savedRows = captor.getValue();
        assertThat(savedRows).hasSize(4);

        // 모두 LBL_SRC_CD=INTERPOLATED, AUTO_LBL_YN=Y, BBOX, trackId=7, label=person
        assertThat(savedRows)
                .allMatch(r -> "INTERPOLATED".equals(r.getLblSrcCd()))
                .allMatch(r -> "Y".equals(r.getAutoLblYn()))
                .allMatch(r -> "BBOX".equals(r.getLblTypeCd()))
                .allMatch(r -> "7".equals(r.getTrackId()))
                .allMatch(r -> "person".equals(r.getLabelNm()));

        // srcSn 매핑 확인: frame 6=3006, 7=3007, 8=3008, 9=3009
        assertThat(savedRows).extracting(LsDataLbl::getSrcSn)
                .containsExactlyInAnyOrder(3006L, 3007L, 3008L, 3009L);
    }

    @Test
    @DisplayName("보간_BBOX_좌표가_선형보간_결과와_일치")
    void interpolatedCoordinatesAreLinear() throws Exception {
        // frame 0 (0,0,100,100) → frame 4 (40,40,140,140). frame 2 의 좌표는 (20,20,120,120)
        List<LsDataSrc> frames = framesOf(904L, 4000L, 5);  // 0..4
        when(srcRepository.findByRawSnOrderByFrameNoAsc(904L)).thenReturn(frames);

        LsDataLbl at0 = autoBboxAt(4000L, "car", "1", 0, 0, 100, 100);
        LsDataLbl at4 = autoBboxAt(4004L, "car", "1", 40, 40, 140, 140);
        when(lblRepository.findAutoBboxWithTrackId(904L)).thenReturn(List.of(at0, at4));

        int saved = step.run(904L);

        assertThat(saved).isEqualTo(3);  // frames 1, 2, 3

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> captor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(captor.capture());

        // frame 2 (srcSn=4002) 의 좌표 검증 — 정규형 nested [[x1,y1],[x2,y2]] 포맷
        LsDataLbl mid = captor.getValue().stream()
                .filter(r -> r.getSrcSn() == 4002L)
                .findFirst()
                .orElseThrow();
        List<List<Double>> pts = objectMapper.readValue(mid.getPointCn(),
                new com.fasterxml.jackson.core.type.TypeReference<List<List<Double>>>() {});
        assertThat(pts).hasSize(2);
        assertThat(pts.get(0).get(0)).isCloseTo(20.0, within(1e-6));
        assertThat(pts.get(0).get(1)).isCloseTo(20.0, within(1e-6));
        assertThat(pts.get(1).get(0)).isCloseTo(120.0, within(1e-6));
        assertThat(pts.get(1).get(1)).isCloseTo(120.0, within(1e-6));
    }

    @Test
    @DisplayName("트랙보간_serializeBbox가_nested형식_반환")
    void interpolatedRowsAreNestedFormat() {
        // frame 0, 4 — 사이 1,2,3 보간 → 모든 보간 row 가 nested [[x,y],[x,y]] 로 저장
        List<LsDataSrc> frames = framesOf(909L, 9500L, 5);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(909L)).thenReturn(frames);
        LsDataLbl at0 = autoBboxAt(9500L, "car", "1", 0, 0, 100, 100);
        LsDataLbl at4 = autoBboxAt(9504L, "car", "1", 40, 40, 140, 140);
        when(lblRepository.findAutoBboxWithTrackId(909L)).thenReturn(List.of(at0, at4));

        int saved = step.run(909L);

        assertThat(saved).isEqualTo(3);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> captor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(captor.capture());
        // 모든 보간 row 의 POINT_CN 이 nested 배열 형식이어야 한다.
        assertThat(captor.getValue())
                .allMatch(r -> r.getPointCn().startsWith("[[") && r.getPointCn().endsWith("]]"));
    }

    @Test
    @DisplayName("여러_trackId_가_각각_독립_보간")
    void multipleTracksInterpolatedIndependently() {
        List<LsDataSrc> frames = framesOf(905L, 5000L, 11);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(905L)).thenReturn(frames);

        // 트랙 1: frame 0,2 — 사이 1 프레임만 보간 (1건)
        LsDataLbl t1_0 = autoBboxAt(5000L, "person", "1", 0, 0, 10, 10);
        LsDataLbl t1_2 = autoBboxAt(5002L, "person", "1", 20, 20, 30, 30);
        // 트랙 2: frame 5,10 — 사이 4 프레임 보간 (4건)
        LsDataLbl t2_5 = autoBboxAt(5005L, "car", "2", 100, 100, 200, 200);
        LsDataLbl t2_10 = autoBboxAt(5010L, "car", "2", 300, 300, 400, 400);

        when(lblRepository.findAutoBboxWithTrackId(905L))
                .thenReturn(List.of(t1_0, t1_2, t2_5, t2_10));

        int saved = step.run(905L);

        assertThat(saved).isEqualTo(5);  // 1 + 4

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> captor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(captor.capture());
        List<LsDataLbl> savedRows = captor.getValue();

        // 트랙 1 보간 — 1 건, label=person, trackId=1
        long track1Count = savedRows.stream()
                .filter(r -> "1".equals(r.getTrackId()))
                .count();
        assertThat(track1Count).isEqualTo(1);

        // 트랙 2 보간 — 4 건, label=car, trackId=2
        long track2Count = savedRows.stream()
                .filter(r -> "2".equals(r.getTrackId()))
                .count();
        assertThat(track2Count).isEqualTo(4);

        // 라벨 보존 확인
        assertThat(savedRows.stream()
                .filter(r -> "1".equals(r.getTrackId()))
                .allMatch(r -> "person".equals(r.getLabelNm()))).isTrue();
        assertThat(savedRows.stream()
                .filter(r -> "2".equals(r.getTrackId()))
                .allMatch(r -> "car".equals(r.getLabelNm()))).isTrue();
    }

    @Test
    @DisplayName("키프레임_자체는_skip_(원본_라벨_덮어쓰기_없음)")
    void keyframeItselfIsSkippedFromInsert() {
        // 0,5 키프레임 — saveAll 결과에 frame 0 또는 5 의 srcSn 이 없어야 함
        List<LsDataSrc> frames = framesOf(906L, 6000L, 11);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(906L)).thenReturn(frames);

        LsDataLbl at0 = autoBboxAt(6000L, "person", "9", 0, 0, 10, 10);
        LsDataLbl at5 = autoBboxAt(6005L, "person", "9", 50, 50, 60, 60);
        when(lblRepository.findAutoBboxWithTrackId(906L)).thenReturn(List.of(at0, at5));

        step.run(906L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> captor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(captor.capture());
        List<LsDataLbl> savedRows = captor.getValue();

        assertThat(savedRows).extracting(LsDataLbl::getSrcSn)
                .doesNotContain(6000L, 6005L)
                .containsExactlyInAnyOrder(6001L, 6002L, 6003L, 6004L);
    }

    @Test
    @DisplayName("단일_키프레임만_있는_트랙은_보간_row_0건")
    void singleKeyframeProducesNoInterpolation() {
        List<LsDataSrc> frames = framesOf(907L, 7000L, 5);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(907L)).thenReturn(frames);

        LsDataLbl only = autoBboxAt(7002L, "person", "11", 0, 0, 10, 10);
        when(lblRepository.findAutoBboxWithTrackId(907L)).thenReturn(List.of(only));

        int saved = step.run(907L);

        assertThat(saved).isEqualTo(0);
        verify(lblRepository, never()).saveAll(any());
    }

    // ─── Phase 6: LS_DATA_LBL_AI_INFO 분리 ───

    @Test
    @DisplayName("Phase6_보간_라벨_저장_후_LsDataLblAiInfo_SRC_INTERPOLATE_도_동시_저장")
    void aiInfoSavedForEveryInterpolatedRow() {
        // frame 0, 4 — 사이 1,2,3 보간 → 3건 AI Info
        List<LsDataSrc> frames = framesOf(910L, 9000L, 5);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(910L)).thenReturn(frames);
        LsDataLbl at0 = autoBboxAt(9000L, "car", "1", 0, 0, 100, 100);
        LsDataLbl at4 = autoBboxAt(9004L, "car", "1", 40, 40, 140, 140);
        when(lblRepository.findAutoBboxWithTrackId(910L)).thenReturn(List.of(at0, at4));

        int saved = step.run(910L);

        assertThat(saved).isEqualTo(3);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<LsDataLblAiInfo>> aiCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(aiInfoRepository).saveAll(aiCaptor.capture());
        List<LsDataLblAiInfo> aiInfos = new ArrayList<>();
        aiCaptor.getValue().forEach(aiInfos::add);
        assertThat(aiInfos).hasSize(3);
        assertThat(aiInfos)
                .allMatch(i -> LsDataLblAiInfo.SRC_INTERPOLATE.equals(i.getLblSrcCd()))
                .allMatch(i -> "Y".equals(i.getAutoLblYn()))
                .allMatch(i -> 910L == i.getDataRawSn())
                .allMatch(i -> i.getDataLblSn() != null);
    }

    @Test
    @DisplayName("Phase6_보간_대상_없으면_LsDataLblAiInfo_도_saveAll_미호출")
    void aiInfoNotSavedWhenNoInterpolation() {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(911L)).thenReturn(framesOf(911L, 1000L, 5));
        when(lblRepository.findAutoBboxWithTrackId(911L)).thenReturn(List.of());

        step.run(911L);

        verify(aiInfoRepository, never()).saveAll(any());
    }

    // ─── 폴리곤 트랙 보간 (R1 SFR-08-01) ───

    @Test
    @DisplayName("POLYGON_트랙_두_키프레임_사이_폴리곤_보간_저장")
    void polygonTrackInterpolated() {
        // frame 0 삼각형 → frame 4 로 (12,0) 평행이동. 사이 1,2,3 보간 → 3건
        List<LsDataSrc> frames = framesOf(920L, 12000L, 5);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(920L)).thenReturn(frames);

        LsDataLbl at0 = autoPolygonAt(12000L, "person", "p1", "[[0,0],[10,0],[5,10]]");
        LsDataLbl at4 = autoPolygonAt(12004L, "person", "p1", "[[12,0],[22,0],[17,10]]");
        when(lblRepository.findAutoBboxWithTrackId(920L)).thenReturn(List.of(at0, at4));

        int saved = step.run(920L);

        assertThat(saved).isEqualTo(3);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> captor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(captor.capture());
        List<LsDataLbl> rows = captor.getValue();
        assertThat(rows)
                .hasSize(3)
                .allMatch(r -> "POLYGON".equals(r.getLblTypeCd()))
                .allMatch(r -> "INTERPOLATED".equals(r.getLblSrcCd()))
                .allMatch(r -> "p1".equals(r.getTrackId()))
                .allMatch(r -> r.getPointCn().startsWith("[[") && r.getPointCn().endsWith("]]"));
        assertThat(rows).extracting(LsDataLbl::getSrcSn)
                .containsExactlyInAnyOrder(12001L, 12002L, 12003L);
    }

    @Test
    @DisplayName("POLYGON_정점개수_상이_두_키프레임도_보간_완료_(좌표꼬임없음)")
    void polygonVariableVertexInterpolated() {
        // frame 0 삼각형(3) → frame 2 사각형(4). 중간 프레임 정점 max=4
        List<LsDataSrc> frames = framesOf(921L, 12100L, 3);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(921L)).thenReturn(frames);

        LsDataLbl at0 = autoPolygonAt(12100L, "car", "p2", "[[0,0],[10,0],[5,10]]");
        LsDataLbl at2 = autoPolygonAt(12102L, "car", "p2", "[[0,0],[10,0],[10,10],[0,10]]");
        when(lblRepository.findAutoBboxWithTrackId(921L)).thenReturn(List.of(at0, at2));

        int saved = step.run(921L);

        assertThat(saved).isEqualTo(1);  // frame 1
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> captor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getLblTypeCd()).isEqualTo("POLYGON");
    }

    @Test
    @DisplayName("POLYGON_트랙_시작전_종료후_propagate_없음")
    void polygonNoPropagateOutsideTrackSpan() {
        // 10 프레임 영상, POLYGON 키프레임 frame 3, 6 → 4,5 만 보간, 0..2 / 7..9 propagate 없음
        List<LsDataSrc> frames = framesOf(922L, 12200L, 10);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(922L)).thenReturn(frames);

        LsDataLbl at3 = autoPolygonAt(12203L, "person", "p3", "[[0,0],[10,0],[5,10]]");
        LsDataLbl at6 = autoPolygonAt(12206L, "person", "p3", "[[6,0],[16,0],[11,10]]");
        when(lblRepository.findAutoBboxWithTrackId(922L)).thenReturn(List.of(at3, at6));

        int saved = step.run(922L);

        assertThat(saved).isEqualTo(2);  // frames 4,5
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> captor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(LsDataLbl::getSrcSn)
                .containsExactlyInAnyOrder(12204L, 12205L)
                .doesNotContain(12200L, 12201L, 12202L, 12207L, 12208L, 12209L);
    }

    @Test
    @DisplayName("트랙내_타입혼재_(BBOX+POLYGON)_안전_skip")
    void mixedTypeTrackSkipped() {
        List<LsDataSrc> frames = framesOf(923L, 12300L, 5);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(923L)).thenReturn(frames);

        // 같은 trackId "mix" 에 BBOX + POLYGON 혼재 → skip
        LsDataLbl bbox0 = autoBboxAt(12300L, "person", "mix", 0, 0, 10, 10);
        LsDataLbl poly4 = autoPolygonAt(12304L, "person", "mix", "[[0,0],[10,0],[5,10]]");
        when(lblRepository.findAutoBboxWithTrackId(923L)).thenReturn(List.of(bbox0, poly4));

        int saved = step.run(923L);

        assertThat(saved).isEqualTo(0);
        verify(lblRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("한트랙_예외_다른트랙_보간은_저장됨_(부분실패_격리)")
    void oneTrackFailsOthersStillSaved() {
        List<LsDataSrc> frames = framesOf(924L, 12400L, 5);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(924L)).thenReturn(frames);

        // 정상 BBOX 트랙 "ok": frame 0,2 → 1 보간
        LsDataLbl ok0 = autoBboxAt(12400L, "car", "ok", 0, 0, 10, 10);
        LsDataLbl ok2 = autoBboxAt(12402L, "car", "ok", 20, 20, 30, 30);
        // 손상 POLYGON 트랙 "bad": 정점 2개(면적없음) → PolyshapeMatcher 예외 → 트랙 skip
        LsDataLbl bad0 = autoPolygonAt(12400L, "person", "bad", "[[0,0],[10,0]]");
        LsDataLbl bad2 = autoPolygonAt(12402L, "person", "bad", "[[5,5],[15,5]]");
        when(lblRepository.findAutoBboxWithTrackId(924L))
                .thenReturn(List.of(ok0, ok2, bad0, bad2));

        int saved = step.run(924L);

        // 정상 트랙 1건만 저장, 손상 트랙은 격리되어 전체 롤백 없음
        assertThat(saved).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> captor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getTrackId()).isEqualTo("ok");
    }

    @Test
    @DisplayName("재실행_idempotency_기존_보간row_삭제후_재삽입")
    void idempotentClearsStaleInterpolatedRows() {
        List<LsDataSrc> frames = framesOf(925L, 12500L, 5);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(925L)).thenReturn(frames);
        when(lblRepository.findInterpolatedLblSnsByRawSn(925L)).thenReturn(List.of(111L, 222L));

        LsDataLbl at0 = autoBboxAt(12500L, "car", "1", 0, 0, 10, 10);
        LsDataLbl at2 = autoBboxAt(12502L, "car", "1", 20, 20, 30, 30);
        when(lblRepository.findAutoBboxWithTrackId(925L)).thenReturn(List.of(at0, at2));

        step.run(925L);

        // 자식(AI_INFO) → 부모(LS_DATA_LBL) 순 삭제 검증
        verify(aiInfoRepository).deleteByDataLblSnIn(List.of(111L, 222L));
        verify(lblRepository).deleteAllByIdInBatch(List.of(111L, 222L));
    }

    @Test
    @DisplayName("nested_BBOX_좌표_포맷도_파싱_지원_(수동_라벨_호환)")
    void parsesNestedBboxFormat() {
        // 일부 라벨은 [[x1,y1],[x2,y2]] nested 포맷일 수 있음 (Phase 6 수동 입력)
        List<LsDataSrc> frames = framesOf(908L, 8000L, 5);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(908L)).thenReturn(frames);

        LsDataLbl nested0 = LsDataLbl.createAutoBbox(
                8000L, null, "person", "[[0,0],[100,100]]", BigDecimal.valueOf(0.9), "1");
        LsDataLbl nested4 = LsDataLbl.createAutoBbox(
                8004L, null, "person", "[[40,40],[140,140]]", BigDecimal.valueOf(0.9), "1");
        when(lblRepository.findAutoBboxWithTrackId(908L)).thenReturn(List.of(nested0, nested4));

        int saved = step.run(908L);

        assertThat(saved).isEqualTo(3);  // frames 1,2,3
    }
}
