package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugLblMap;
import kr.co.cudo.authoring.augment.repository.LsDataAugLblMapRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.AugmentResultRequest;
import kr.co.cudo.authoring.webhook.service.AugmentResultService;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class AugmentResultServiceTest {

    @Mock LsDataAugRepository augRepository;
    @Mock VideoRepository videoRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsDataLblRepository lblRepository;
    @Mock LsDataMetaRepository metaRepository;
    @Mock LsDataAugLblMapRepository augLblMapRepository;
    @Mock kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository deidentProcLogRepository;
    @Mock kr.co.cudo.authoring.batch.runner.AsyncVideoMetaRunner asyncVideoMetaRunner;

    private AugmentResultService service;
    private final java.util.concurrent.atomic.AtomicLong rawSnSeq = new java.util.concurrent.atomic.AtomicLong(9000);
    private final java.util.concurrent.atomic.AtomicLong srcSnSeq = new java.util.concurrent.atomic.AtomicLong(5000);
    private final java.util.concurrent.atomic.AtomicLong lblSnSeq = new java.util.concurrent.atomic.AtomicLong(7000);

    @BeforeEach
    void setup() {
        service = new AugmentResultService(augRepository,
                videoRepository, srcRepository, lblRepository, metaRepository, augLblMapRepository,
                deidentProcLogRepository, asyncVideoMetaRunner);
        // V2.0: save mocks for new video creation
        when(videoRepository.save(any(LsDataRaw.class))).thenAnswer(inv -> {
            LsDataRaw r = inv.getArgument(0);
            setField(r, "rawSn", rawSnSeq.incrementAndGet());
            return r;
        });
        when(srcRepository.save(any(LsDataSrc.class))).thenAnswer(inv -> {
            LsDataSrc s = inv.getArgument(0);
            setField(s, "srcSn", srcSnSeq.incrementAndGet());
            return s;
        });
        when(srcRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<LsDataSrc> items = inv.getArgument(0);
            List<LsDataSrc> result = new java.util.ArrayList<>();
            for (LsDataSrc s : items) {
                setField(s, "srcSn", srcSnSeq.incrementAndGet());
                result.add(s);
            }
            return result;
        });
        when(lblRepository.save(any(LsDataLbl.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lblRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<LsDataLbl> items = inv.getArgument(0);
            List<LsDataLbl> result = new java.util.ArrayList<>();
            for (LsDataLbl l : items) {
                setField(l, "lblSn", lblSnSeq.incrementAndGet());
                result.add(l);
            }
            return result;
        });
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(augLblMapRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<LsDataAugLblMap> items = inv.getArgument(0);
            List<LsDataAugLblMap> result = new java.util.ArrayList<>();
            items.forEach(result::add);
            return result;
        });
        when(metaRepository.save(any(LsDataMeta.class))).thenAnswer(inv -> inv.getArgument(0));
        when(metaRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<LsDataMeta> items = inv.getArgument(0);
            List<LsDataMeta> result = new java.util.ArrayList<>();
            items.forEach(result::add);
            return result;
        });
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    @Test
    @DisplayName("POST_v1_aug_callback_미존재_dataAugSn_시_404")
    void unknownDataAugSn_throws404() {
        when(augRepository.findByDataAugSnForUpdate(1L)).thenReturn(Optional.empty());
        AugmentResultRequest req = new AugmentResultRequest(
                1L, "aug_001", "WINTER", "SUCCESS", "/storage/augment/1.mp4");

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(videoRepository, never()).save(any(LsDataRaw.class));
    }

    @Test
    @DisplayName("AugmentResultService_적재_시_LS_DATA_AUG_상태_ACCEPTED_갱신")
    void appliesAcceptedStatus() throws Exception {
        LsDataAug aug = newAug(10L, "WINTER", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(10L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                10L, "aug_010", "WINTER", "SUCCESS", "/storage/augment/10.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        // otsd_job_id 를 재전송 멱등 앵커(externalJobId)에 적재
        assertThat(aug.getExternalJobId()).isEqualTo("aug_010");
    }

    @Test
    @DisplayName("AugmentResultService_FAILED_status_시_LS_DATA_AUG_REJECTED")
    void failedStatus_mapsToRejected() throws Exception {
        LsDataAug aug = newAug(11L, "NIGHT", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(11L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                11L, "aug_011", "NIGHT", "FAILED", null);

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
    }

    @Test
    @DisplayName("POST_v1_aug_callback_종결행_재전송_시_200_OK_멱등스킵")
    void replay_returnsFalse() throws Exception {
        LsDataAug aug = newAug(12L, "RAIN", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(12L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                12L, "aug_012", "RAIN", "SUCCESS", null);

        assertThat(service.handle(req)).isTrue();
        // 2차 콜백 — 행이 이미 ACCEPTED(종결) → 재전송 멱등 스킵
        assertThat(service.handle(req)).isFalse();
    }

    @Test
    @DisplayName("동일_otsdJobId_재전송_시_두번째_영상은_생성되지_않는다")
    void duplicateOtsdJobId_doesNotCreateSecondVideo() throws Exception {
        LsDataRaw parentRaw = newRaw(150L);
        LsDataSrc originSrc = newSrc(250L, 150L, 0);
        LsDataAug aug = newAugWithSrc(60L, 250L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(60L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(250L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(150L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(150L)).thenReturn(List.of(originSrc));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(150L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                60L, "aug_060", "WINTER", "SUCCESS", "/storage/augment/60.mp4");

        assertThat(service.handle(req)).isTrue();   // 최초 콜백 — 영상 생성
        assertThat(service.handle(req)).isFalse();  // 재전송 — 종결 행 → 스킵

        // 신규 영상은 정확히 1회만 생성되어야 함
        verify(videoRepository, times(1)).save(any(LsDataRaw.class));
    }

    @Test
    @DisplayName("augType_불일치_시_409_CONFLICT")
    void augTypeMismatch_returns409() throws Exception {
        LsDataAug aug = newAug(13L, "WINTER", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(13L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                13L, "aug_013", "NIGHT", "SUCCESS", null);

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("AugmentResultService_동시_콜백_UNIQUE위반_시_externalJobId_재조회_멱등흡수")
    void concurrentWebhookRace_absorbsDataIntegrityViolation() throws Exception {
        // 동시/오배송 재전송 — save 시 uk_aug_external_job_id UNIQUE 위반.
        // 서비스는 catch 후 findByExternalJobId 로 선점 행을 재조회하여 멱등 흡수(false, 신규 영상 미생성)해야 함.
        LsDataAug target = newAug(50L, "WINTER", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(50L)).thenReturn(Optional.of(target));

        LsDataAug raceWinner = newAug(50L, "WINTER", LsDataAug.STTS_ACCEPTED);
        when(augRepository.findByExternalJobId("aug_050")).thenReturn(Optional.of(raceWinner));

        when(augRepository.save(any(LsDataAug.class)))
                .thenThrow(new DataIntegrityViolationException("UNIQUE violation"));

        AugmentResultRequest req = new AugmentResultRequest(
                50L, "aug_050", "WINTER", "SUCCESS", "/storage/augment/50.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isFalse();
        // 멱등 흡수 — 신규 영상 미생성
        verify(videoRepository, never()).save(any(LsDataRaw.class));
    }

    // ─── Phase 5 V2.0: 증강 = 새 영상 ───

    private LsDataRaw newRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/" + rawSn + ".mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        // R8 — 부모는 비식별 완료 영상. createAugmentedVideo 가 콜백 처리 시점에 부모 DE_IDNTF_YN='Y' 를
        // 재확인하므로, 정상 파생 시나리오에서는 부모를 'Y' 로 둔다.
        raw.markDeidentified("Y");
        return raw;
    }

    /** R8 — 부모 비식별 미완료(신고 'F' 등) 시나리오용 헬퍼. */
    private LsDataRaw newNonDeidentRaw(Long rawSn, String deIdntfYn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/" + rawSn + ".mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        raw.markDeidentified(deIdntfYn);
        return raw;
    }

    private LsDataSrc newSrc(Long srcSn, Long rawSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, rawSn + "/frame-" + frameNo + ".jpg", null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    private LsDataSrc newSrcWithVideoFrameNo(Long srcSn, Long rawSn, int frameNo, Long videoFrameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, videoFrameNo, rawSn + "/frame-" + frameNo + ".jpg", null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    private LsDataAug newAugWithSrc(Long augSn, Long srcSn, String type) throws Exception {
        LsDataAug aug = LsDataAug.createPending(srcSn, type, BigDecimal.valueOf(0.95), "registrar");
        Field f = LsDataAug.class.getDeclaredField("dataAugSn");
        f.setAccessible(true);
        f.set(aug, augSn);
        return aug;
    }

    @Test
    @DisplayName("V2_증강_SUCCESS_시_새_RAW_SN_생성_ORGNL_RAW_SN_참조")
    void successCreatesNewVideoWithParentRef() throws Exception {
        LsDataRaw parentRaw = newRaw(100L);
        LsDataSrc originSrc = newSrc(200L, 100L, 0);
        LsDataAug aug = newAugWithSrc(20L, 200L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(20L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(200L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(100L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(originSrc));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(100L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                20L, "aug_020", "WINTER", "SUCCESS", "/storage/augment/winter.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        ArgumentCaptor<LsDataRaw> rawCaptor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(rawCaptor.capture());
        LsDataRaw newRaw = rawCaptor.getValue();
        assertThat(newRaw.getOrgnlRawSn()).isEqualTo(100L);
        // R8 — 재비식별 skip 대신 비식별 완료 불변식 재현: 새 영상은 MARKING_READY 로 진입한다(PENDING 아님).
        assertThat(newRaw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(newRaw.getRawFilePathNm()).isEqualTo("/storage/augment/winter.mp4");
    }

    @Test
    @DisplayName("증강콜백_처리시_증강행과_부모RAW를_비관적락으로_조회한다")
    void handle_usesPessimisticLockFinders() throws Exception {
        // MED #2 / HIGH #1 — 동시 콜백·비식별 신고 직렬화를 위해 잠금 없는 findById 가 아닌
        // findByDataAugSnForUpdate(증강 행) / findByRawSnForUpdate(부모 RAW) 를 사용해야 한다.
        LsDataRaw parentRaw = newRaw(180L);
        LsDataSrc originSrc = newSrc(740L, 180L, 0);
        LsDataAug aug = newAugWithSrc(74L, 740L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(74L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(740L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(180L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(180L)).thenReturn(List.of(originSrc));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(180L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                74L, "aug_074", "WINTER", "SUCCESS", "/storage/augment/74.mp4");

        service.handle(req);

        verify(augRepository, times(1)).findByDataAugSnForUpdate(74L);
        verify(videoRepository, times(1)).findByRawSnForUpdate(180L);
        // 잠금 없는 findById 는 사용하지 않는다(락 우회 회귀 가드).
        verify(augRepository, never()).findById(any());
        verify(videoRepository, never()).findById(any());
    }

    // ─── R8: 증강본 재비식별 skip + 상태전이 정합 (HIGH 시나리오) ───

    @Test
    @DisplayName("증강콜백_성공후_SUCCESS_procLog가_같은_트랜잭션에_생성된다")
    void success_createsSuccessDeidentProcLog() throws Exception {
        LsDataRaw parentRaw = newRaw(130L);
        LsDataSrc originSrc = newSrc(700L, 130L, 0);
        LsDataAug aug = newAugWithSrc(70L, 700L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(70L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(700L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(130L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(130L)).thenReturn(List.of(originSrc));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(130L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                70L, "aug_070", "WINTER", "SUCCESS", "/storage/augment/70.mp4");

        service.handle(req);

        // resolveDeidPath 가 요구하는 SUCCESS procLog 가 신규 RAW_SN 에 대해 비식별 결과경로와 함께 저장됨.
        ArgumentCaptor<kr.co.cudo.authoring.batch.entity.LsDeidentProcLog> logCaptor =
                ArgumentCaptor.forClass(kr.co.cudo.authoring.batch.entity.LsDeidentProcLog.class);
        verify(deidentProcLogRepository, times(1)).save(logCaptor.capture());
        kr.co.cudo.authoring.batch.entity.LsDeidentProcLog saved = logCaptor.getValue();
        assertThat(saved.getProcSttsCd())
                .isEqualTo(kr.co.cudo.authoring.batch.entity.LsDeidentProcLog.SUCCEEDED);
        assertThat(saved.getDeIdntfFilePathNm()).isEqualTo("/storage/augment/70.mp4");
    }

    @Test
    @DisplayName("부모_deIdntfYn_F면_증강본_생성보류_영상과_procLog_미생성")
    void parentDeidentReported_blocksAugmentedVideo() throws Exception {
        // 신고로 부모가 'F' 로 되돌아간 경우 — 요청 시점이 아닌 콜백 처리 시점 재검증으로 PII 노출 차단.
        LsDataRaw parentRaw = newNonDeidentRaw(140L, "F");
        LsDataSrc originSrc = newSrc(710L, 140L, 0);
        LsDataAug aug = newAugWithSrc(71L, 710L, "NIGHT");

        when(augRepository.findByDataAugSnForUpdate(71L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(710L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(140L)).thenReturn(Optional.of(parentRaw));

        AugmentResultRequest req = new AugmentResultRequest(
                71L, "aug_071", "NIGHT", "SUCCESS", "/storage/augment/71.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue(); // 콜백 자체는 처리(상태 전이)됨
        // 증강본 영상·프레임·procLog·메타추출 모두 미생성 (PII 노출 차단)
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(srcRepository, never()).saveAll(any());
        verify(deidentProcLogRepository, never()).save(any());
        verify(asyncVideoMetaRunner, never()).runAsync(any());
    }

    @Test
    @DisplayName("증강본_생성후_메타추출이_새_RAW_SN으로_트리거된다")
    void success_triggersMetaExtraction() throws Exception {
        LsDataRaw parentRaw = newRaw(160L);
        LsDataSrc originSrc = newSrc(720L, 160L, 0);
        LsDataAug aug = newAugWithSrc(72L, 720L, "RAIN");

        when(augRepository.findByDataAugSnForUpdate(72L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(720L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(160L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(160L)).thenReturn(List.of(originSrc));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(160L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                72L, "aug_072", "RAIN", "SUCCESS", "/storage/augment/72.mp4");

        service.handle(req);

        // 신규 RAW_SN(mock 채번 9001+) 으로 메타추출 1회 트리거 (비식별은 트리거하지 않음).
        ArgumentCaptor<Long> rawSnCaptor = ArgumentCaptor.forClass(Long.class);
        verify(asyncVideoMetaRunner, times(1)).runAsync(rawSnCaptor.capture());
        assertThat(rawSnCaptor.getValue()).isNotNull();
        assertThat(rawSnCaptor.getValue()).isNotEqualTo(160L); // 부모가 아닌 신규 영상 SN
    }

    @Test
    @DisplayName("부모_프레임이_없으면_증강본_생성보류")
    void parentNoFrames_blocksAugmentedVideo() throws Exception {
        LsDataRaw parentRaw = newRaw(170L);
        LsDataSrc originSrc = newSrc(730L, 170L, 0);
        LsDataAug aug = newAugWithSrc(73L, 730L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(73L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(730L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(170L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(170L)).thenReturn(List.of()); // 프레임 없음

        AugmentResultRequest req = new AugmentResultRequest(
                73L, "aug_073", "WINTER", "SUCCESS", "/storage/augment/73.mp4");

        service.handle(req);

        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(deidentProcLogRepository, never()).save(any());
        verify(asyncVideoMetaRunner, never()).runAsync(any());
    }

    @Test
    @DisplayName("V2_증강_SUCCESS_시_원본_프레임_라벨_메타_복사")
    void successCopiesFramesLabelsAndMeta() throws Exception {
        LsDataRaw parentRaw = newRaw(101L);
        LsDataSrc frame0 = newSrc(300L, 101L, 0);
        LsDataSrc frame1 = newSrc(301L, 101L, 1);
        LsDataAug aug = newAugWithSrc(21L, 300L, "NIGHT");

        LsDataLbl lbl = LsDataLbl.createAutoBbox(300L, null, "person", "[1,2,3,4]",
                BigDecimal.valueOf(0.9), null);
        LsDataMeta meta = LsDataMeta.create(101L, "weather", "sunny");

        when(augRepository.findByDataAugSnForUpdate(21L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(300L)).thenReturn(Optional.of(frame0));
        when(videoRepository.findByRawSnForUpdate(101L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(101L)).thenReturn(List.of(frame0, frame1));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(lbl));
        when(metaRepository.findByRawSn(101L)).thenReturn(List.of(meta));

        AugmentResultRequest req = new AugmentResultRequest(
                21L, "aug_021", "NIGHT", "SUCCESS", "/storage/augment/night.mp4");

        service.handle(req);

        // 프레임 2건 일괄 복사 (saveAll 1회)
        verify(srcRepository, times(1)).saveAll(any());
        // 라벨 1건 일괄 복사 (findBySrcSnIn 1회 + saveAll 1회)
        verify(lblRepository, times(1)).findBySrcSnIn(anyCollection());
        verify(lblRepository, times(1)).saveAll(any());
        // 메타 1건 일괄 복사 (saveAll 1회)
        verify(metaRepository, times(1)).saveAll(any());
    }

    @Test
    @DisplayName("V2_증강_FAILED_시_새_영상_미생성")
    void failedDoesNotCreateNewVideo() throws Exception {
        LsDataAug aug = newAug(22L, "RAIN", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(22L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                22L, "aug_022", "RAIN", "FAILED", null);

        service.handle(req);

        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(srcRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("V2_증강_SUCCESS_originSrc_미존재_시_신규영상_미생성")
    void successButOriginSrcNotFound_skipsVideoCreation() throws Exception {
        LsDataAug aug = newAugWithSrc(30L, 9999L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(30L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(9999L)).thenReturn(Optional.empty()); // originSrc 미존재

        AugmentResultRequest req = new AugmentResultRequest(
                30L, "aug_030", "WINTER", "SUCCESS", "/storage/augment/winter.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(srcRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("V2_증강_SUCCESS_parentRaw_미존재_시_신규영상_미생성")
    void successButParentRawNotFound_skipsVideoCreation() throws Exception {
        LsDataSrc originSrc = newSrc(400L, 8888L, 0);
        LsDataAug aug = newAugWithSrc(31L, 400L, "NIGHT");

        when(augRepository.findByDataAugSnForUpdate(31L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(400L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(8888L)).thenReturn(Optional.empty()); // parentRaw 미존재

        AugmentResultRequest req = new AugmentResultRequest(
                31L, "aug_031", "NIGHT", "SUCCESS", "/storage/augment/night.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(srcRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("증강_결과_수신_시_라벨_복사와_함께_매핑이_저장된다")
    void copiesLabelsWithAugLblMap() throws Exception {
        LsDataRaw parentRaw = newRaw(110L);
        LsDataSrc frame0 = newSrc(500L, 110L, 0);
        LsDataAug aug = newAugWithSrc(40L, 500L, "WINTER");

        LsDataLbl lbl1 = LsDataLbl.createAutoBbox(500L, null, "person", "[1,2,3,4]",
                BigDecimal.valueOf(0.9), null);
        setField(lbl1, "lblSn", 6001L);
        LsDataLbl lbl2 = LsDataLbl.createAutoBbox(500L, null, "car", "[5,6,7,8]",
                BigDecimal.valueOf(0.8), null);
        setField(lbl2, "lblSn", 6002L);

        when(augRepository.findByDataAugSnForUpdate(40L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(500L)).thenReturn(Optional.of(frame0));
        when(videoRepository.findByRawSnForUpdate(110L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(110L)).thenReturn(List.of(frame0));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(lbl1, lbl2));
        when(metaRepository.findByRawSn(110L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                40L, "aug_040", "WINTER", "SUCCESS", "/storage/augment/winter.mp4");

        service.handle(req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataAugLblMap>> mapCaptor = ArgumentCaptor.forClass(List.class);
        verify(augLblMapRepository, times(1)).saveAll(mapCaptor.capture());
        List<LsDataAugLblMap> maps = mapCaptor.getValue();
        assertThat(maps).hasSize(2);
        assertThat(maps).extracting(LsDataAugLblMap::getOrgnlDataLblSn)
                .containsExactlyInAnyOrder(6001L, 6002L);
        assertThat(maps).allSatisfy(m -> {
            assertThat(m.getDataAugSn()).isEqualTo(40L);
            assertThat(m.getDataLblSn()).isNotNull();
            assertThat(m.getDataLblSn()).isNotEqualTo(m.getOrgnlDataLblSn());
            assertThat(m.getCoordRecalcYn()).isEqualTo(LsDataAugLblMap.RECALC_N);
            assertThat(m.getScaleX()).isNull();
            assertThat(m.getScaleY()).isNull();
        });
    }

    @Test
    @DisplayName("복사할_라벨이_없으면_매핑도_저장되지_않는다")
    void noLabels_noAugLblMapSaved() throws Exception {
        LsDataRaw parentRaw = newRaw(111L);
        LsDataSrc frame0 = newSrc(510L, 111L, 0);
        LsDataAug aug = newAugWithSrc(41L, 510L, "NIGHT");

        when(augRepository.findByDataAugSnForUpdate(41L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(510L)).thenReturn(Optional.of(frame0));
        when(videoRepository.findByRawSnForUpdate(111L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(111L)).thenReturn(List.of(frame0));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(111L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                41L, "aug_041", "NIGHT", "SUCCESS", "/storage/augment/night.mp4");

        service.handle(req);

        verify(augLblMapRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("멱등_스킵_시_라벨_매핑도_저장되지_않는다")
    void idempotentReplay_noAugLblMapSaved() throws Exception {
        LsDataAug aug = newAug(42L, "RAIN", LsDataAug.STTS_PENDING);
        when(augRepository.findByDataAugSnForUpdate(42L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                42L, "aug_042", "RAIN", "SUCCESS", null);

        // 1차 인계 후 2차 멱등 스킵
        service.handle(req);
        boolean second = service.handle(req);

        assertThat(second).isFalse();
        // 멱등 스킵된 2차 호출에서는 추가 매핑 저장 없음 (1차에서만 0건 — 라벨 없음)
        verify(augLblMapRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("증강복사_프레임은_부모의_videoFrameNo를_그대로_갖는다")
    void augmentCopy_carriesParentVideoFrameNo() throws Exception {
        LsDataRaw parentRaw = newRaw(120L);
        LsDataSrc frame0 = newSrcWithVideoFrameNo(600L, 120L, 0, 100L);
        LsDataSrc frame1 = newSrcWithVideoFrameNo(601L, 120L, 1, 250L);
        LsDataAug aug = newAugWithSrc(45L, 600L, "WINTER");

        when(augRepository.findByDataAugSnForUpdate(45L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(600L)).thenReturn(Optional.of(frame0));
        when(videoRepository.findByRawSnForUpdate(120L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(120L)).thenReturn(List.of(frame0, frame1));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(120L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                45L, "aug_045", "WINTER", "SUCCESS", "/storage/augment/winter.mp4");

        service.handle(req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataSrc>> captor = ArgumentCaptor.forClass(List.class);
        verify(srcRepository).saveAll(captor.capture());
        List<LsDataSrc> copied = captor.getValue();
        assertThat(copied).hasSize(2);
        assertThat(copied.get(0).getVideoFrameNo()).isEqualTo(100);
        assertThat(copied.get(1).getVideoFrameNo()).isEqualTo(250);
    }

    @Test
    @DisplayName("부모_videoFrameNo가_null이면_증강복사본도_null")
    void augmentCopy_nullParentVideoFrameNo_staysNull() throws Exception {
        LsDataRaw parentRaw = newRaw(121L);
        LsDataSrc frame0 = newSrc(610L, 121L, 0);
        LsDataAug aug = newAugWithSrc(46L, 610L, "NIGHT");

        when(augRepository.findByDataAugSnForUpdate(46L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(610L)).thenReturn(Optional.of(frame0));
        when(videoRepository.findByRawSnForUpdate(121L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(121L)).thenReturn(List.of(frame0));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(121L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                46L, "aug_046", "NIGHT", "SUCCESS", "/storage/augment/night.mp4");

        service.handle(req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataSrc>> captor = ArgumentCaptor.forClass(List.class);
        verify(srcRepository).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getVideoFrameNo()).isNull();
    }

    private LsDataAug newAug(Long sn, String type, String status) throws Exception {
        LsDataAug aug = LsDataAug.createPending(1L, type, BigDecimal.valueOf(0.95), "registrar");
        Field f = LsDataAug.class.getDeclaredField("dataAugSn");
        f.setAccessible(true);
        f.set(aug, sn);
        if (!LsDataAug.STTS_PENDING.equals(status)) {
            // forcibly override
            Field s = LsDataAug.class.getDeclaredField("augProcSttsCd");
            s.setAccessible(true);
            s.set(aug, status);
        }
        return aug;
    }
}
