package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugLblMap;
import kr.co.cudo.authoring.augment.repository.LsDataAugLblMapRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.service.AugmentExtractPersist;
import kr.co.cudo.authoring.webhook.service.AugmentExtractPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase C(영속) 단위 테스트 — 커넥션-점유 분리 리팩터. 구 {@code AugmentFrameExtractionServiceTest} 의 DB
 * 파트 검증을 이관·회귀 보존한다.
 *
 * <p>Phase B 산출 파일(계획의 프레임 스펙) 기준 프레임 INSERT + videoFrameNo 기준 라벨 재매핑 +
 * LS_DATA_AUG_LBL_MAP(RECALC_N) + video.* 제외 메타 upsert + 비식별 완료 불변식 확정을 검증한다.
 * 결과물이 리팩터 전과 동일함을 보증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AugmentExtractPersistTest {

    @Mock VideoRepository videoRepository;
    @Mock LsDataAugRepository augRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsDataSrcHstryRepository hstryRepository;
    @Mock LsDataLblRepository lblRepository;
    @Mock LsDataMetaRepository metaRepository;
    @Mock LsDataAugLblMapRepository augLblMapRepository;
    @Mock LsDeidentProcLogRepository deidentProcLogRepository;

    private AugmentExtractPersist persist;
    private final AtomicLong lblSnSeq = new AtomicLong(9000);

    @BeforeEach
    void setup() {
        persist = new AugmentExtractPersist(videoRepository, augRepository, srcRepository, hstryRepository,
                lblRepository, metaRepository, augLblMapRepository, deidentProcLogRepository);
        // srcRepository.save — 신규 프레임에 videoFrameNo 기반 결정적 srcSn(8000+vfn) 부여 후 반환.
        when(srcRepository.save(any(LsDataSrc.class))).thenAnswer(inv -> {
            LsDataSrc s = inv.getArgument(0);
            setField(s, "srcSn", 8000L + s.getVideoFrameNo());
            return s;
        });
        when(lblRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<LsDataLbl> items = inv.getArgument(0);
            List<LsDataLbl> result = new ArrayList<>();
            if (items == null) return result;
            for (LsDataLbl l : items) {
                setField(l, "lblSn", lblSnSeq.incrementAndGet());
                result.add(l);
            }
            return result;
        });
        when(augLblMapRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
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

    private LsDataRaw newAugRaw(Long rawSn, Long parentRawSn, String filePath) {
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-" + parentRawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/" + parentRawSn + ".mp4", null, 60);
        setField(parent, "rawSn", parentRawSn);
        LsDataRaw aug = LsDataRaw.createFromAugment(parent, filePath, "WINTER");
        setField(aug, "rawSn", rawSn);
        return aug;
    }

    private LsDataAug aug(Long augSn) {
        LsDataAug a = LsDataAug.createPending(600L, "WINTER", BigDecimal.valueOf(0.9), "registrar");
        setField(a, "dataAugSn", augSn);
        return a;
    }

    private AugmentExtractPlan.FrameSpec spec(long parentSrcSn, long frameNo, long videoFrameNo) {
        Path dst = Paths.get("/tmp/store/frames/raw/x/frame-" + frameNo + ".jpg");
        return new AugmentExtractPlan.FrameSpec(parentSrcSn, frameNo, videoFrameNo, LocalDateTime.now(), dst);
    }

    private AugmentExtractPlan plan(Long newRawSn, Long parentRawSn, Long dataAugSn,
                                    List<AugmentExtractPlan.FrameSpec> frames) {
        return new AugmentExtractPlan(newRawSn, parentRawSn, dataAugSn, "rev1",
                Paths.get("/storage/augment/x.mp4"),
                Paths.get("/tmp/store/frames/raw/" + newRawSn), frames);
    }

    @Test
    @DisplayName("프레임_INSERT_이력기록_후_비식별완료불변식_확정_PERSISTED")
    void insertsFramesAndFinalizes() {
        LsDataRaw newRaw = newAugRaw(9001L, 100L, "/storage/augment/winter.mp4");
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(20L)).thenReturn(Optional.of(aug(20L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(100L)).thenReturn(List.of());
        AugmentExtractPlan p = plan(9001L, 100L, 20L, List.of(spec(600L, 0, 100L)));

        AugmentExtractPersist.Result result = persist.persist(p);

        assertThat(result).isEqualTo(AugmentExtractPersist.Result.PERSISTED);
        verify(srcRepository, times(1)).save(any(LsDataSrc.class));
        verify(hstryRepository, times(1)).save(any());
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("Y");
        // 파생본은 라벨 복사로 마킹·배치 불필요 → 배치 단계 COMPLETED 로 마감(작업보드 노출).
        assertThat(newRaw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
    }

    @Test
    @DisplayName("프레임추출_성공후_SUCCESS_procLog가_증강경로로_저장된다")
    void savesSuccessProcLogWithAugmentPath() {
        LsDataRaw newRaw = newAugRaw(9002L, 130L, "/storage/augment/70.mp4");
        when(videoRepository.findById(9002L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(70L)).thenReturn(Optional.of(aug(70L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(130L)).thenReturn(List.of());
        AugmentExtractPlan p = plan(9002L, 130L, 70L, List.of(spec(700L, 0, 0L)));

        persist.persist(p);

        ArgumentCaptor<LsDeidentProcLog> logCaptor = ArgumentCaptor.forClass(LsDeidentProcLog.class);
        verify(deidentProcLogRepository, times(1)).save(logCaptor.capture());
        LsDeidentProcLog saved = logCaptor.getValue();
        assertThat(saved.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(saved.getDeIdntfFilePathNm()).isEqualTo("/storage/augment/70.mp4");
    }

    @Test
    @DisplayName("라벨이_videoFrameNo_기준_정확한_신규SRC_SN에_매핑되고_좌표보존")
    void labelsRemappedByVideoFrameNo() {
        LsDataRaw newRaw = newAugRaw(9004L, 104L, "/storage/augment/multi.mp4");
        LsDataLbl carLbl = LsDataLbl.createAutoBbox(600L, null, "car", "[0,0,50,50]",
                BigDecimal.valueOf(0.8), null);
        setField(carLbl, "lblSn", 5001L);
        LsDataLbl personLbl = LsDataLbl.createAutoBbox(601L, null, "person", "[10,10,90,90]",
                BigDecimal.valueOf(0.7), null);
        setField(personLbl, "lblSn", 5002L);
        when(videoRepository.findById(9004L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(24L)).thenReturn(Optional.of(aug(24L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(carLbl, personLbl));
        when(metaRepository.findByRawSn(104L)).thenReturn(List.of());
        // 부모 pf0(srcSn600, vfn100), pf1(srcSn601, vfn250) → 신규 srcSn 8100, 8250.
        AugmentExtractPlan p = plan(9004L, 104L, 24L, List.of(spec(600L, 0, 100L), spec(601L, 1, 250L)));

        persist.persist(p);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> lblCaptor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(lblCaptor.capture());
        List<LsDataLbl> copied = lblCaptor.getValue();
        assertThat(copied).hasSize(2);
        LsDataLbl copiedCar = copied.stream().filter(l -> "car".equals(l.getLabelNm())).findFirst().orElseThrow();
        LsDataLbl copiedPerson = copied.stream().filter(l -> "person".equals(l.getLabelNm())).findFirst().orElseThrow();
        assertThat(copiedCar.getSrcSn()).isEqualTo(8100L);   // vfn 100 → 8000+100
        assertThat(copiedPerson.getSrcSn()).isEqualTo(8250L); // vfn 250 → 8000+250
        assertThat(copiedCar.getPointCn()).isEqualTo("[0,0,50,50]");
        assertThat(copiedPerson.getPointCn()).isEqualTo("[10,10,90,90]");
    }

    @Test
    @DisplayName("라벨_복사시_LS_DATA_AUG_LBL_MAP이_COORD_RECALC_N으로_저장된다")
    void augLblMapSavedWithRecalcN() {
        LsDataRaw newRaw = newAugRaw(9005L, 110L, "/storage/augment/map.mp4");
        LsDataLbl lbl = LsDataLbl.createAutoBbox(500L, null, "person", "[1,2,3,4]",
                BigDecimal.valueOf(0.9), null);
        setField(lbl, "lblSn", 6001L);
        when(videoRepository.findById(9005L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(40L)).thenReturn(Optional.of(aug(40L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(lbl));
        when(metaRepository.findByRawSn(110L)).thenReturn(List.of());
        AugmentExtractPlan p = plan(9005L, 110L, 40L, List.of(spec(500L, 0, 5L)));

        persist.persist(p);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataAugLblMap>> mapCaptor = ArgumentCaptor.forClass(List.class);
        verify(augLblMapRepository, times(1)).saveAll(mapCaptor.capture());
        List<LsDataAugLblMap> maps = mapCaptor.getValue();
        assertThat(maps).hasSize(1);
        assertThat(maps.get(0).getDataAugSn()).isEqualTo(40L);
        assertThat(maps.get(0).getOrgnlDataLblSn()).isEqualTo(6001L);
        assertThat(maps.get(0).getCoordRecalcYn()).isEqualTo(LsDataAugLblMap.RECALC_N);
        assertThat(maps.get(0).getScaleX()).isNull();
    }

    @Test
    @DisplayName("원본_콘텐츠메타가_새_영상에_upsert로_정확히_복사된다")
    void copiesMeta() {
        LsDataRaw newRaw = newAugRaw(9006L, 101L, "/storage/augment/meta.mp4");
        LsDataMeta meta1 = LsDataMeta.create(101L, "weather", "sunny");
        LsDataMeta meta2 = LsDataMeta.create(101L, "time_of_day", "morning");
        when(videoRepository.findById(9006L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(21L)).thenReturn(Optional.of(aug(21L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(101L)).thenReturn(List.of(meta1, meta2));
        AugmentExtractPlan p = plan(9006L, 101L, 21L, List.of(spec(300L, 0, 0L)));

        persist.persist(p);

        verify(metaRepository, never()).saveAll(any());
        verify(metaRepository).upsertMeta(9006L, "weather", "sunny");
        verify(metaRepository).upsertMeta(9006L, "time_of_day", "morning");
    }

    @Test
    @DisplayName("증강메타복사_video기술메타는_제외되고_메타러너_upsert와_충돌하지않는다")
    void excludesVideoTechnicalMeta() {
        LsDataRaw newRaw = newAugRaw(9008L, 102L, "/storage/augment/tech.mp4");
        LsDataMeta parentTech = LsDataMeta.create(102L, "video.fps", "30");
        LsDataMeta parentContent = LsDataMeta.create(102L, "weather", "snow");
        when(videoRepository.findById(9008L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(80L)).thenReturn(Optional.of(aug(80L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(102L)).thenReturn(List.of(parentTech, parentContent));
        // 메타러너 선행 시뮬 — video.* 를 saveAll(INSERT)로 밀면 UNIQUE 위반.
        when(metaRepository.saveAll(any())).thenAnswer(inv -> {
            for (LsDataMeta m : (Iterable<LsDataMeta>) inv.getArgument(0)) {
                if (m.getMetaKey() != null && m.getMetaKey().startsWith("video.")) {
                    throw new DataIntegrityViolationException("UNIQUE 충돌: " + m.getMetaKey());
                }
            }
            return inv.getArgument(0);
        });
        AugmentExtractPlan p = plan(9008L, 102L, 80L, List.of(spec(310L, 0, 0L)));

        assertThatCode(() -> persist.persist(p)).doesNotThrowAnyException();
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(newRaw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
        verify(metaRepository).upsertMeta(9008L, "weather", "snow");
        verify(metaRepository, never()).upsertMeta(anyLong(), startsWith("video."), anyString());
        verify(metaRepository, never()).saveAll(argThat(list -> {
            for (LsDataMeta m : (Iterable<LsDataMeta>) list) {
                if (m.getMetaKey() != null && m.getMetaKey().startsWith("video.")) return true;
            }
            return false;
        }));
    }

    @Test
    @DisplayName("buildAugLabelMaps_saveAll반환순서_뒤섞여도_원본lblSn과_정확매핑")
    void augLblMap_correctMapping_evenWhenSaveAllReordersResult() {
        LsDataRaw newRaw = newAugRaw(9010L, 105L, "/storage/augment/order.mp4");
        LsDataLbl carLbl = LsDataLbl.createAutoBbox(600L, null, "car", "[0,0,50,50]",
                BigDecimal.valueOf(0.8), null);
        setField(carLbl, "lblSn", 5001L);
        LsDataLbl personLbl = LsDataLbl.createAutoBbox(601L, null, "person", "[10,10,90,90]",
                BigDecimal.valueOf(0.7), null);
        setField(personLbl, "lblSn", 5002L);
        when(videoRepository.findById(9010L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(82L)).thenReturn(Optional.of(aug(82L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(carLbl, personLbl));
        when(metaRepository.findByRawSn(105L)).thenReturn(List.of());
        // 신규 srcSn: vfn100→8100(car), vfn250→8250(person).
        when(lblRepository.saveAll(any())).thenAnswer(inv -> {
            List<LsDataLbl> copies = new ArrayList<>();
            ((Iterable<LsDataLbl>) inv.getArgument(0)).forEach(copies::add);
            for (LsDataLbl c : copies) {
                setField(c, "lblSn", 7000L + c.getSrcSn());
            }
            List<LsDataLbl> reversed = new ArrayList<>(copies);
            java.util.Collections.reverse(reversed);
            return reversed;
        });
        AugmentExtractPlan p = plan(9010L, 105L, 82L, List.of(spec(600L, 0, 100L), spec(601L, 1, 250L)));

        persist.persist(p);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataAugLblMap>> mapCaptor = ArgumentCaptor.forClass(List.class);
        verify(augLblMapRepository).saveAll(mapCaptor.capture());
        List<LsDataAugLblMap> maps = mapCaptor.getValue();
        assertThat(maps).hasSize(2);
        LsDataAugLblMap carMap = maps.stream()
                .filter(m -> m.getOrgnlDataLblSn() == 5001L).findFirst().orElseThrow();
        LsDataAugLblMap personMap = maps.stream()
                .filter(m -> m.getOrgnlDataLblSn() == 5002L).findFirst().orElseThrow();
        assertThat(carMap.getDataLblSn()).isEqualTo(7000L + 8100L);
        assertThat(personMap.getDataLblSn()).isEqualTo(7000L + 8250L);
    }

    @Test
    @DisplayName("이미_비식별완료된_신규RAW면_멱등_SKIPPED_프레임재삽입_안함")
    void alreadyFinalized_skips() {
        LsDataRaw newRaw = newAugRaw(9007L, 160L, "/storage/augment/done.mp4");
        newRaw.markDeidentified("Y");
        when(videoRepository.findById(9007L)).thenReturn(Optional.of(newRaw));
        AugmentExtractPlan p = plan(9007L, 160L, 72L, List.of(spec(800L, 0, 0L)));

        AugmentExtractPersist.Result result = persist.persist(p);

        assertThat(result).isEqualTo(AugmentExtractPersist.Result.SKIPPED);
        verify(srcRepository, never()).save(any(LsDataSrc.class));
        verify(deidentProcLogRepository, never()).save(any());
    }
}
