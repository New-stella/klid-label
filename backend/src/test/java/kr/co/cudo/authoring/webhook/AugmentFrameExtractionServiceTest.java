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
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.service.AugmentFrameExtractionService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 11 — 증강 프레임 재추출 + 라벨/메타 복사 + 비식별 완료 불변식 확정(async 트랜잭션) 단위 테스트.
 *
 * <p>부모 프레임을 <b>복사</b>하는 것이 아니라 증강 파일에서 <b>재추출</b>({@code extractByFrameNumbers})
 * 하며, 라벨은 videoFrameNo 기준으로 신규 SRC_SN 에 정확히 재매핑됨을 검증한다. 성공 시에만
 * DE_IDNTF_YN='Y' + MARKING_READY + SUCCESS procLog 를 확정하고, 개수 불일치 시 FAILED(예외)로 고아
 * 라벨을 원천 차단함을 검증한다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class AugmentFrameExtractionServiceTest {

    @Mock VideoRepository videoRepository;
    @Mock LsDataAugRepository augRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsDataLblRepository lblRepository;
    @Mock LsDataMetaRepository metaRepository;
    @Mock LsDataAugLblMapRepository augLblMapRepository;
    @Mock LsDeidentProcLogRepository deidentProcLogRepository;
    @Mock FfmpegFrameExtractor ffmpegFrameExtractor;

    private AugmentFrameExtractionService service;
    private final AtomicLong lblSnSeq = new AtomicLong(9000);

    @BeforeEach
    void setup() {
        service = new AugmentFrameExtractionService(videoRepository, augRepository, srcRepository,
                lblRepository, metaRepository, augLblMapRepository, deidentProcLogRepository,
                ffmpegFrameExtractor);
        when(lblRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<LsDataLbl> items = inv.getArgument(0);
            List<LsDataLbl> result = new ArrayList<>();
            if (items == null) return result; // 재-stubbing 시 any() 가 null 을 넘겨 이 answer 를 재실행하는 함정 방어
            for (LsDataLbl l : items) {
                setField(l, "lblSn", lblSnSeq.incrementAndGet());
                result.add(l);
            }
            return result;
        });
        when(metaRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<LsDataMeta> items = inv.getArgument(0);
            List<LsDataMeta> result = new ArrayList<>();
            if (items == null) return result; // 재-stubbing 함정 방어(위와 동일)
            items.forEach(result::add);
            return result;
        });
        when(augLblMapRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    /** 신규 증강 RAW — 기본 PENDING·deIdntfYn='N' (동기 handle 커밋 직후 상태). */
    private LsDataRaw newAugRaw(Long rawSn, Long parentRawSn, String filePath) {
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-" + parentRawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/" + parentRawSn + ".mp4", null, 60);
        setField(parent, "rawSn", parentRawSn);
        LsDataRaw aug = LsDataRaw.createFromAugment(parent, filePath, "WINTER");
        setField(aug, "rawSn", rawSn);
        return aug;
    }

    private LsDataSrc parentFrame(Long srcSn, Long rawSn, int frameNo, Long videoFrameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, videoFrameNo, rawSn + "/f" + frameNo + ".jpg", null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    /** 신규(재추출) 프레임 — videoFrameNo 를 부모와 동일하게 실어 반환(extractByFrameNumbers 계약). */
    private LsDataSrc newFrame(Long srcSn, Long rawSn, int frameNo, Long videoFrameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, videoFrameNo, "aug/" + rawSn + "/f" + frameNo + ".jpg", null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    private LsDataAug aug(Long augSn) {
        LsDataAug a = LsDataAug.createPending(600L, "WINTER", BigDecimal.valueOf(0.9), "registrar");
        setField(a, "dataAugSn", augSn);
        return a;
    }

    @Test
    @DisplayName("신규RAW_deIdntfYn_N_상태에서_extractByFrameNumbers_성공_순환의존_없음")
    void extractsWhenNewRawStillNotDeidentified() {
        LsDataRaw newRaw = newAugRaw(9001L, 100L, "/storage/augment/winter.mp4");
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("N"); // 시작 상태 검증

        LsDataSrc pf0 = parentFrame(600L, 100L, 0, 100L);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(20L)).thenReturn(Optional.of(aug(20L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(pf0));
        when(ffmpegFrameExtractor.extractByFrameNumbers(eq(newRaw), anyList()))
                .thenReturn(List.of(newFrame(8000L, 9001L, 0, 100L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(100L)).thenReturn(List.of());

        service.extractAndCopy(9001L, 20L);

        // deIdntfYn='N' 인 신규 RAW 도 재추출됨(게이트 재사용 없음 → 순환의존 없음).
        verify(ffmpegFrameExtractor, times(1)).extractByFrameNumbers(eq(newRaw), anyList());
        // 성공 후 비식별 완료 불변식 확정.
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(newRaw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
    }

    @Test
    @DisplayName("프레임추출_성공후에만_SUCCESS_procLog가_증강경로로_저장된다")
    void savesSuccessProcLogWithAugmentPath() {
        LsDataRaw newRaw = newAugRaw(9002L, 130L, "/storage/augment/70.mp4");
        LsDataSrc pf0 = parentFrame(700L, 130L, 0, 0L);
        when(videoRepository.findById(9002L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(70L)).thenReturn(Optional.of(aug(70L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(130L)).thenReturn(List.of(pf0));
        when(ffmpegFrameExtractor.extractByFrameNumbers(eq(newRaw), anyList()))
                .thenReturn(List.of(newFrame(8100L, 9002L, 0, 0L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(130L)).thenReturn(List.of());

        service.extractAndCopy(9002L, 70L);

        ArgumentCaptor<LsDeidentProcLog> logCaptor = ArgumentCaptor.forClass(LsDeidentProcLog.class);
        verify(deidentProcLogRepository, times(1)).save(logCaptor.capture());
        LsDeidentProcLog saved = logCaptor.getValue();
        assertThat(saved.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(saved.getDeIdntfFilePathNm()).isEqualTo("/storage/augment/70.mp4");
    }

    @Test
    @DisplayName("추출프레임수_부모와_다르면_라벨복사_안하고_FAILED")
    void frameCountMismatch_throwsAndDoesNotCopyLabels() {
        LsDataRaw newRaw = newAugRaw(9003L, 140L, "/storage/augment/mismatch.mp4");
        LsDataSrc pf0 = parentFrame(710L, 140L, 0, 100L);
        LsDataSrc pf1 = parentFrame(711L, 140L, 1, 250L);
        when(videoRepository.findById(9003L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(71L)).thenReturn(Optional.of(aug(71L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(140L)).thenReturn(List.of(pf0, pf1));
        // 부모 2건인데 재추출 1건만 반환 → 개수 불일치.
        when(ffmpegFrameExtractor.extractByFrameNumbers(eq(newRaw), anyList()))
                .thenReturn(List.of(newFrame(8200L, 9003L, 0, 100L)));

        assertThatThrownBy(() -> service.extractAndCopy(9003L, 71L))
                .isInstanceOf(CustomException.class);

        // 라벨/메타/procLog/MARKING_READY 모두 미수행(고아 라벨 원천 차단).
        verify(lblRepository, never()).saveAll(any());
        verify(deidentProcLogRepository, never()).save(any());
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("N"); // 확정 안 됨(FAILED 는 러너가 전이)
        assertThat(newRaw.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);
    }

    @Test
    @DisplayName("라벨이_videoFrameNo_기준_정확한_신규SRC_SN에_매핑된다")
    void labelsRemappedByVideoFrameNo() {
        LsDataRaw newRaw = newAugRaw(9004L, 104L, "/storage/augment/multi.mp4");
        // 부모 프레임 2건: videoFrameNo 100(srcSn 600), 250(srcSn 601).
        LsDataSrc pf0 = parentFrame(600L, 104L, 0, 100L);
        LsDataSrc pf1 = parentFrame(601L, 104L, 1, 250L);
        // 라벨: car@600(videoFrameNo 100), person@601(videoFrameNo 250).
        LsDataLbl carLbl = LsDataLbl.createAutoBbox(600L, null, "car", "[0,0,50,50]",
                BigDecimal.valueOf(0.8), null);
        setField(carLbl, "lblSn", 5001L);
        LsDataLbl personLbl = LsDataLbl.createAutoBbox(601L, null, "person", "[10,10,90,90]",
                BigDecimal.valueOf(0.7), null);
        setField(personLbl, "lblSn", 5002L);

        when(videoRepository.findById(9004L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(24L)).thenReturn(Optional.of(aug(24L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(104L)).thenReturn(List.of(pf0, pf1));
        // 재추출: 신규 프레임 videoFrameNo 100(srcSn 8300), 250(srcSn 8301).
        when(ffmpegFrameExtractor.extractByFrameNumbers(eq(newRaw), anyList()))
                .thenReturn(List.of(
                        newFrame(8300L, 9004L, 0, 100L),
                        newFrame(8301L, 9004L, 1, 250L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(carLbl, personLbl));
        when(metaRepository.findByRawSn(104L)).thenReturn(List.of());

        service.extractAndCopy(9004L, 24L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> lblCaptor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(lblCaptor.capture());
        List<LsDataLbl> copied = lblCaptor.getValue();
        assertThat(copied).hasSize(2);
        LsDataLbl copiedCar = copied.stream().filter(l -> "car".equals(l.getLabelNm())).findFirst().orElseThrow();
        LsDataLbl copiedPerson = copied.stream().filter(l -> "person".equals(l.getLabelNm())).findFirst().orElseThrow();
        // car(부모 videoFrameNo 100) → 신규 srcSn 8300, person(부모 videoFrameNo 250) → 신규 srcSn 8301.
        assertThat(copiedCar.getSrcSn()).isEqualTo(8300L);
        assertThat(copiedPerson.getSrcSn()).isEqualTo(8301L);
        // 좌표 그대로 보존.
        assertThat(copiedCar.getPointCn()).isEqualTo("[0,0,50,50]");
        assertThat(copiedPerson.getPointCn()).isEqualTo("[10,10,90,90]");
    }

    @Test
    @DisplayName("라벨_복사시_LS_DATA_AUG_LBL_MAP이_COORD_RECALC_N으로_저장된다")
    void augLblMapSavedWithRecalcN() {
        LsDataRaw newRaw = newAugRaw(9005L, 110L, "/storage/augment/map.mp4");
        LsDataSrc pf0 = parentFrame(500L, 110L, 0, 5L);
        LsDataLbl lbl = LsDataLbl.createAutoBbox(500L, null, "person", "[1,2,3,4]",
                BigDecimal.valueOf(0.9), null);
        setField(lbl, "lblSn", 6001L);

        when(videoRepository.findById(9005L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(40L)).thenReturn(Optional.of(aug(40L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(110L)).thenReturn(List.of(pf0));
        when(ffmpegFrameExtractor.extractByFrameNumbers(eq(newRaw), anyList()))
                .thenReturn(List.of(newFrame(8400L, 9005L, 0, 5L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(lbl));
        when(metaRepository.findByRawSn(110L)).thenReturn(List.of());

        service.extractAndCopy(9005L, 40L);

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
        LsDataSrc pf0 = parentFrame(300L, 101L, 0, 0L);
        LsDataMeta meta1 = LsDataMeta.create(101L, "weather", "sunny");
        LsDataMeta meta2 = LsDataMeta.create(101L, "time_of_day", "morning");

        when(videoRepository.findById(9006L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(21L)).thenReturn(Optional.of(aug(21L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(101L)).thenReturn(List.of(pf0));
        when(ffmpegFrameExtractor.extractByFrameNumbers(eq(newRaw), anyList()))
                .thenReturn(List.of(newFrame(8500L, 9006L, 0, 0L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(101L)).thenReturn(List.of(meta1, meta2));

        service.extractAndCopy(9006L, 21L);

        // 이슈1 — 콘텐츠 메타는 saveAll(INSERT) 대신 upsertMeta(ON CONFLICT)로 신규 RAW(9006)에 복사.
        verify(metaRepository, never()).saveAll(any());
        verify(metaRepository).upsertMeta(9006L, "weather", "sunny");
        verify(metaRepository).upsertMeta(9006L, "time_of_day", "morning");
    }

    /**
     * 이슈1 [CRITICAL] — 메타러너가 증강 파일에서 프로브한 {@code video.*} 기술메타를 먼저 upsert 한 뒤
     * extractAndCopy 가 실행돼도, 부모의 {@code video.*} 는 복사에서 제외되어 (RAW_SN, META_KEY) UNIQUE
     * 충돌/롤백 없이 프레임·라벨·비식별완료가 확정되어야 한다. 부모 콘텐츠메타(VLM 등)만 upsert 복사된다.
     *
     * <p>RED(수정 전): 부모 video.fps 를 그대로 saveAll(INSERT) 하면 메타러너 선행 upsert 와 UNIQUE
     * 충돌 → DataIntegrityViolationException → extractAndCopy 전체 롤백. 아래 saveAll 스텁이 그 충돌을
     * 재현한다. GREEN(수정 후): video.* 제외 + upsertMeta 사용으로 saveAll 미호출·충돌 없음.
     */
    @Test
    @DisplayName("증강메타복사_video기술메타는_제외되고_메타러너_upsert와_충돌하지않는다")
    void excludesVideoTechnicalMeta_andUsesUpsert_noConflict() {
        LsDataRaw newRaw = newAugRaw(9008L, 102L, "/storage/augment/tech.mp4");
        LsDataSrc pf0 = parentFrame(310L, 102L, 0, 0L);
        // 부모 메타: 기술메타 video.fps(=부모 파일 값) + 콘텐츠메타 weather.
        LsDataMeta parentTech = LsDataMeta.create(102L, "video.fps", "30"); // 부모 파일 고유값(오손 대상)
        LsDataMeta parentContent = LsDataMeta.create(102L, "weather", "snow");

        when(videoRepository.findById(9008L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(80L)).thenReturn(Optional.of(aug(80L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(102L)).thenReturn(List.of(pf0));
        when(ffmpegFrameExtractor.extractByFrameNumbers(eq(newRaw), anyList()))
                .thenReturn(List.of(newFrame(8600L, 9008L, 0, 0L)));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(102L)).thenReturn(List.of(parentTech, parentContent));

        // 메타러너 선행 시뮬 — 신규 RAW 에 video.* 가 이미 있어 그 키의 평범한 INSERT(saveAll)는 UNIQUE 위반.
        when(metaRepository.saveAll(any())).thenAnswer(inv -> {
            for (LsDataMeta m : (Iterable<LsDataMeta>) inv.getArgument(0)) {
                if (m.getMetaKey() != null && m.getMetaKey().startsWith("video.")) {
                    throw new DataIntegrityViolationException("UNIQUE(RAW_SN, META_KEY) 충돌: " + m.getMetaKey());
                }
            }
            return inv.getArgument(0);
        });

        // (a) UNIQUE 위반/롤백 없이 정상 확정.
        assertThatCode(() -> service.extractAndCopy(9008L, 80L)).doesNotThrowAnyException();
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(newRaw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);

        // (b) 부모 콘텐츠메타는 upsert 로 복사되고, video.* 부모값은 복사되지 않는다(메타러너 값 보존).
        verify(metaRepository).upsertMeta(9008L, "weather", "snow");
        verify(metaRepository, never()).upsertMeta(anyLong(), startsWith("video."), anyString());
        // video.* 를 saveAll(INSERT)로 밀어넣지 않는다(제외).
        verify(metaRepository, never()).saveAll(argThat(list -> {
            for (LsDataMeta m : (Iterable<LsDataMeta>) list) {
                if (m.getMetaKey() != null && m.getMetaKey().startsWith("video.")) return true;
            }
            return false;
        }));
    }

    /**
     * 이슈2 [LOW] — 부모 프레임에 중복 videoFrameNo 가 있으면 frameNoToNewSrc 키가 붕괴해 고아 프레임 +
     * 라벨 이중매핑이 발생한다. fail-fast(예외)로 고아 생성을 원천 차단한다.
     */
    @Test
    @DisplayName("부모_중복_videoFrameNo면_고아없이_실패처리")
    void duplicateParentVideoFrameNo_failsFast_noOrphan() {
        LsDataRaw newRaw = newAugRaw(9009L, 103L, "/storage/augment/dup.mp4");
        // 부모 프레임 2건이 같은 videoFrameNo(100) — 데이터 오손.
        LsDataSrc pf0 = parentFrame(320L, 103L, 0, 100L);
        LsDataSrc pf1 = parentFrame(321L, 103L, 1, 100L);
        when(videoRepository.findById(9009L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(81L)).thenReturn(Optional.of(aug(81L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(103L)).thenReturn(List.of(pf0, pf1));

        assertThatThrownBy(() -> service.extractAndCopy(9009L, 81L))
                .isInstanceOf(CustomException.class);

        // 고아 차단 — 추출/라벨/메타/procLog/MARKING_READY 모두 미수행.
        verify(ffmpegFrameExtractor, never()).extractByFrameNumbers(any(), anyList());
        verify(lblRepository, never()).saveAll(any());
        verify(deidentProcLogRepository, never()).save(any());
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("N");
        assertThat(newRaw.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);
    }

    /**
     * 이슈3 [LOW] — buildAugLabelMaps 가 saveAll 반환 순서에 의존하지 않고 원본 lblSn 을 동반한 명시적
     * 키 매핑으로 원본↔복사본을 짝짓는다. saveAll 이 순서를 뒤섞어 반환해도 정확히 매핑되어야 한다.
     */
    @Test
    @DisplayName("buildAugLabelMaps_saveAll반환순서_뒤섞여도_원본lblSn과_정확매핑")
    void augLblMap_correctMapping_evenWhenSaveAllReordersResult() {
        LsDataRaw newRaw = newAugRaw(9010L, 105L, "/storage/augment/order.mp4");
        LsDataSrc pf0 = parentFrame(600L, 105L, 0, 100L);
        LsDataSrc pf1 = parentFrame(601L, 105L, 1, 250L);
        // car@600(원본 lblSn 5001), person@601(원본 lblSn 5002).
        LsDataLbl carLbl = LsDataLbl.createAutoBbox(600L, null, "car", "[0,0,50,50]",
                BigDecimal.valueOf(0.8), null);
        setField(carLbl, "lblSn", 5001L);
        LsDataLbl personLbl = LsDataLbl.createAutoBbox(601L, null, "person", "[10,10,90,90]",
                BigDecimal.valueOf(0.7), null);
        setField(personLbl, "lblSn", 5002L);

        when(videoRepository.findById(9010L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(82L)).thenReturn(Optional.of(aug(82L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(105L)).thenReturn(List.of(pf0, pf1));
        when(ffmpegFrameExtractor.extractByFrameNumbers(eq(newRaw), anyList()))
                .thenReturn(List.of(
                        newFrame(8700L, 9010L, 0, 100L),   // car 의 신규 프레임
                        newFrame(8701L, 9010L, 1, 250L))); // person 의 신규 프레임
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(carLbl, personLbl));
        when(metaRepository.findByRawSn(105L)).thenReturn(List.of());
        // saveAll — 각 복사본에 신규 srcSn 기반 lblSn 을 부여하되 결과를 역순으로 반환(순서 의존 노출).
        when(lblRepository.saveAll(any())).thenAnswer(inv -> {
            List<LsDataLbl> copies = new ArrayList<>();
            ((Iterable<LsDataLbl>) inv.getArgument(0)).forEach(copies::add);
            for (LsDataLbl c : copies) {
                // 신규 srcSn 8700→lblSn 7700(car 복사본), 8701→7701(person 복사본).
                setField(c, "lblSn", 7000L + c.getSrcSn());
            }
            List<LsDataLbl> reversed = new ArrayList<>(copies);
            java.util.Collections.reverse(reversed); // 반환 순서를 입력과 다르게
            return reversed;
        });

        service.extractAndCopy(9010L, 82L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataAugLblMap>> mapCaptor = ArgumentCaptor.forClass(List.class);
        verify(augLblMapRepository).saveAll(mapCaptor.capture());
        List<LsDataAugLblMap> maps = mapCaptor.getValue();
        assertThat(maps).hasSize(2);
        // 원본 car(5001) → 복사본 car(신규 srcSn 8700 → lblSn 15700), person(5002) → srcSn 8701 → 15701.
        LsDataAugLblMap carMap = maps.stream()
                .filter(m -> m.getOrgnlDataLblSn() == 5001L).findFirst().orElseThrow();
        LsDataAugLblMap personMap = maps.stream()
                .filter(m -> m.getOrgnlDataLblSn() == 5002L).findFirst().orElseThrow();
        assertThat(carMap.getDataLblSn()).isEqualTo(7000L + 8700L);
        assertThat(personMap.getDataLblSn()).isEqualTo(7000L + 8701L);
    }

    @Test
    @DisplayName("이미_비식별완료된_신규RAW면_멱등_스킵_재추출_안함")
    void alreadyFinalized_skips() {
        LsDataRaw newRaw = newAugRaw(9007L, 160L, "/storage/augment/done.mp4");
        newRaw.markDeidentified("Y"); // 이미 처리됨
        when(videoRepository.findById(9007L)).thenReturn(Optional.of(newRaw));

        service.extractAndCopy(9007L, 72L);

        verify(ffmpegFrameExtractor, never()).extractByFrameNumbers(any(), anyList());
        verify(deidentProcLogRepository, never()).save(any());
    }
}
