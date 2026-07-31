package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugLblMap;
import kr.co.cudo.authoring.augment.repository.LsDataAugLblMapRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.meta.service.DerivedMetaCopier;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase C(영속) 단위 테스트 — 커넥션-점유 분리 리팩터. 구 {@code AugmentFrameExtractionServiceTest} 의 DB
 * 파트 검증을 이관·회귀 보존한다.
 *
 * <p>Phase B 산출 파일(계획의 프레임 스펙) 기준 프레임 INSERT + videoFrameNo 기준 라벨 재매핑 +
 * LS_DATA_AUG_LBL_MAP(RECALC_N) + 메타 복사 위임({@link DerivedMetaCopier}) + 비식별 완료 불변식 확정을
 * 검증한다. 메타 전체복사(video.* 포함)·검수행 정책 자체는 {@code DerivedMetaCopierTest} 가 소유한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AugmentExtractPersistTest {

    @Mock VideoRepository videoRepository;
    @Mock LsDataAugRepository augRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsDataSrcHstryRepository hstryRepository;
    @Mock LsDataLblRepository lblRepository;
    @Mock LsDataAugLblMapRepository augLblMapRepository;
    @Mock LsDeidentProcLogRepository deidentProcLogRepository;
    @Mock DerivedMetaCopier derivedMetaCopier;

    private AugmentExtractPersist persist;
    private final AtomicLong lblSnSeq = new AtomicLong(9000);

    @BeforeEach
    void setup() {
        persist = new AugmentExtractPersist(videoRepository, augRepository, srcRepository, hstryRepository,
                lblRepository, augLblMapRepository, deidentProcLogRepository, derivedMetaCopier);
        // 메타 복사는 DerivedMetaCopier 로 위임 — 기본 스텁(로그가 결과 카운트를 읽으므로 non-null 반환).
        when(derivedMetaCopier.copyMetaAndReviews(anyLong(), anyLong()))
                .thenReturn(new DerivedMetaCopier.CopyResult(0, 0));
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
        LsDataRaw aug = LsDataRaw.createFromAugment(parent, filePath, "WINTER", rawSn);
        setField(aug, "rawSn", rawSn);
        return aug;
    }

    private LsDataAug aug(Long augSn) {
        LsDataAug a = LsDataAug.createPending(600L, "WINTER", BigDecimal.valueOf(0.9), "registrar");
        setField(a, "dataAugSn", augSn);
        return a;
    }

    private AugmentExtractPlan.FrameSpec spec(long parentSrcSn, long frameNo, long videoFrameNo) {
        Path dst = Paths.get("/tmp/deid/frames/deid/x/frame-" + frameNo + ".jpg");
        Path external = Paths.get("/storage/genai/job-1/out-" + frameNo + ".jpg");
        return new AugmentExtractPlan.FrameSpec(
                parentSrcSn, frameNo, videoFrameNo, LocalDateTime.now(), external, dst);
    }

    private AugmentExtractPlan plan(Long newRawSn, Long parentRawSn, Long dataAugSn,
                                    List<AugmentExtractPlan.FrameSpec> frames) {
        return new AugmentExtractPlan(newRawSn, parentRawSn, dataAugSn, "rev1",
                Paths.get("/tmp/deid/frames/deid/" + parentRawSn + "/frame-0.jpg"),
                Paths.get("/tmp/deid/videos/" + parentRawSn + "/deidentified.mp4"),
                Paths.get("/tmp/deid/videos/augment/" + parentRawSn + "/" + newRawSn + "/WINTER.mp4"),
                Paths.get("/tmp/deid/frames/deid/" + newRawSn), frames);
    }

    @Test
    @DisplayName("프레임_INSERT_이력기록_후_비식별완료불변식_확정_PERSISTED")
    void insertsFramesAndFinalizes() {
        LsDataRaw newRaw = newAugRaw(9001L, 100L, "/storage/augment/winter.mp4");
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(20L)).thenReturn(Optional.of(aug(20L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
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
    @DisplayName("기록된_경로가_실제_복사된_파일_파생비디오를_가리킨다")
    void savesSuccessProcLogWithCopiedDerivativeVideoPath() {
        // given — RAW_FILE_PATH_NM 은 부모 원본 경로로 폴백돼 있다(생성형 AI 는 영상을 안 준다).
        LsDataRaw newRaw = newAugRaw(9002L, 130L, "/storage/raw/130.mp4");
        when(videoRepository.findById(9002L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(70L)).thenReturn(Optional.of(aug(70L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        AugmentExtractPlan p = plan(9002L, 130L, 70L, List.of(spec(700L, 0, 0L)));

        persist.persist(p);

        ArgumentCaptor<LsDeidentProcLog> logCaptor = ArgumentCaptor.forClass(LsDeidentProcLog.class);
        verify(deidentProcLogRepository, times(1)).save(logCaptor.capture());
        LsDeidentProcLog saved = logCaptor.getValue();
        assertThat(saved.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        // 비식별 결과 경로 = Phase B 가 실제로 복사한 파생 비디오. 원본 경로를 기록하면 원본이 서빙된다.
        assertThat(saved.getDeIdntfFilePathNm()).isEqualTo(p.videoDst().toString());
        assertThat(saved.getDeIdntfFilePathNm()).isNotEqualTo(newRaw.getRawFilePathNm());
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
    @DisplayName("메타복사는_확정블록_이후_DerivedMetaCopier에_부모·파생_RAW로_위임된다")
    void delegatesMetaCopyToDerivedMetaCopier() {
        LsDataRaw newRaw = newAugRaw(9006L, 101L, "/storage/augment/meta.mp4");
        when(videoRepository.findById(9006L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(21L)).thenReturn(Optional.of(aug(21L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        // 위임 결과가 카운트를 담아 반환돼도 확정 상태·PERSISTED 는 그대로.
        when(derivedMetaCopier.copyMetaAndReviews(101L, 9006L))
                .thenReturn(new DerivedMetaCopier.CopyResult(3, 1));
        AugmentExtractPlan p = plan(9006L, 101L, 21L, List.of(spec(300L, 0, 0L)));

        AugmentExtractPersist.Result result = persist.persist(p);

        assertThat(result).isEqualTo(AugmentExtractPersist.Result.PERSISTED);
        // video.* 포함 전체복사·검수행 정책은 DerivedMetaCopier 단위 테스트가 소유. 여기선 부모·파생 RAW 로 위임만 검증.
        verify(derivedMetaCopier, times(1)).copyMetaAndReviews(101L, 9006L);
        // 확정 상태 유지 — 위임 호출이 확정 dirty 변경을 훼손하지 않는다(HIGH#4 순서).
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(newRaw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_COMPLETED);
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
    @DisplayName("파생프레임은_비식별경로컬럼에_적재되고_원본경로는_null이다_PII격리")
    void derivedFrameStoredAsDeidentifiedArtifact() {
        // given — 외부 증강 산출물은 <비식별 프레임>을 입력으로 만들어진 비식별 계열 산출물이다.
        LsDataRaw newRaw = newAugRaw(9030L, 140L, "/storage/augment/pii.mp4");
        when(videoRepository.findById(9030L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(61L)).thenReturn(Optional.of(aug(61L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        AugmentExtractPlan p = plan(9030L, 140L, 61L, List.of(spec(600L, 0, 100L)));

        // when
        persist.persist(p);

        // then — 산출물 1벌은 비식별 경로 컬럼에만 적재된다(V133 정책 A). 두 컬럼 동일 값 금지.
        ArgumentCaptor<LsDataSrc> childCaptor = ArgumentCaptor.forClass(LsDataSrc.class);
        verify(srcRepository).save(childCaptor.capture());
        LsDataSrc child = childCaptor.getValue();
        assertThat(child.getSrcFilePathNm()).as("파생영상은 원본 픽셀이 실재하지 않는다").isNull();
        assertThat(child.getDeidFilePath())
                .isEqualTo(p.frames().get(0).dst().toString());
    }

    @Test
    @DisplayName("부모프레임_개인정보3필드가_증강파생_프레임에_복사되어_INSERT된다")
    void copiesParentPrivacyMetaToDerivedFrames() {
        // given — 부모 프레임(srcSn 600)에 개인정보 3필드 세팅. 서비스 배선(loadParentSrcs→create 9-arg)이
        //         실제로 자식 프레임에 값을 흘려보내는지 서비스 레벨에서 검증(엔티티 팩토리 테스트만으로는 미보장).
        LsDataRaw newRaw = newAugRaw(9020L, 120L, "/storage/augment/priv.mp4");
        when(videoRepository.findById(9020L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(52L)).thenReturn(Optional.of(aug(52L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        LsDataSrc parent = LsDataSrc.create(120L, 0L, 100L, "/deid/f0.jpg", "/deid/f0.jpg",
                LocalDateTime.now(), "Y", "N", "Y");
        setField(parent, "srcSn", 600L);
        when(srcRepository.findAllById(any())).thenReturn(List.of(parent));
        AugmentExtractPlan p = plan(9020L, 120L, 52L, List.of(spec(600L, 0, 100L)));

        persist.persist(p);

        // then — INSERT 되는 자식 프레임에 부모의 3필드가 그대로 복사됨.
        ArgumentCaptor<LsDataSrc> childCaptor = ArgumentCaptor.forClass(LsDataSrc.class);
        verify(srcRepository).save(childCaptor.capture());
        LsDataSrc child = childCaptor.getValue();
        assertThat(child.getRawSn()).isEqualTo(9020L);
        assertThat(child.getAnonyInclYn()).isEqualTo("Y");
        assertThat(child.getPsdoInclYn()).isEqualTo("N");
        assertThat(child.getPrvcInclYn()).isEqualTo("Y");
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

    @Test
    @DisplayName("async확정_실패표기는_ACCEPTED_상태를_건드리지_않고_dead_letter만_찍는다")
    void markAugProcessingFailed_marksDeadLetterWithoutStatusChange() {
        // given: 콜백 동기 단계에서 이미 ACCEPTED 로 종결된 증강 행.
        LsDataAug a = aug(88L);
        a.applyReviewStatus(LsDataAug.STTS_ACCEPTED);
        when(augRepository.findById(88L)).thenReturn(Optional.of(a));

        // when: async 확정(A/B/C) 실패 인계.
        persist.markAugProcessingFailed(88L);

        // then: 집계 실패 판정축(DEAD_LETTER_AT)이 찍히고, 검수 결과 축(상태)은 그대로다
        //       — 상태를 바꾸면 재콜백 멱등 앵커/검수 의미가 흔들린다.
        assertThat(a.isProcessingFailed()).isTrue();
        assertThat(a.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(a.getRetryCount()).isEqualTo(1);
        verify(augRepository).save(a);
    }

    @Test
    @DisplayName("이미_dead_letter인_증강행은_재표기하지_않는다_재시도카운트_중복누적_방지")
    void markAugProcessingFailed_isIdempotent() {
        LsDataAug a = aug(89L);
        a.applyReviewStatus(LsDataAug.STTS_ACCEPTED);
        a.incrementRetryCount();
        a.markDeadLetter();
        when(augRepository.findById(89L)).thenReturn(Optional.of(a));

        persist.markAugProcessingFailed(89L);

        assertThat(a.getRetryCount()).isEqualTo(1);
        verify(augRepository, never()).save(a);
    }
}
