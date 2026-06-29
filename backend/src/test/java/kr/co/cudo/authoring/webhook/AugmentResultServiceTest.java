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
import kr.co.cudo.authoring.webhook.idempotency.InMemoryWebhookIdempotencyLedger;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
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
import java.util.Collection;
import java.util.Collections;
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
    private final WebhookIdempotencyLedger ledger = new InMemoryWebhookIdempotencyLedger();

    private AugmentResultService service;
    private final java.util.concurrent.atomic.AtomicLong rawSnSeq = new java.util.concurrent.atomic.AtomicLong(9000);
    private final java.util.concurrent.atomic.AtomicLong srcSnSeq = new java.util.concurrent.atomic.AtomicLong(5000);
    private final java.util.concurrent.atomic.AtomicLong lblSnSeq = new java.util.concurrent.atomic.AtomicLong(7000);

    @BeforeEach
    void setup() {
        service = new AugmentResultService(augRepository, ledger,
                videoRepository, srcRepository, lblRepository, metaRepository, augLblMapRepository);
        ledger.clear();
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
    @DisplayName("POST_v1_augments_result_미발급_idempotencyKey_시_401")
    void unknownIdempotencyKey_throws401() {
        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-UNK", "EXT-1", "SUCCESS", 1L, "WINTER",
                "/storage/augment/1.mp4");

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(augRepository, never()).findById(any());
    }

    @Test
    @DisplayName("AugmentResultService_적재_시_LS_DATA_AUG_상태_ACCEPTED_갱신")
    void appliesAcceptedStatus() throws Exception {
        ledger.recordIssued("K-A-OK", "EXT-AO");
        LsDataAug aug = newAug(10L, "WINTER", LsDataAug.STTS_PENDING);
        when(augRepository.findById(10L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-OK", "EXT-AO", "SUCCESS", 10L, "WINTER",
                "/storage/augment/10.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(ledger.isProcessed("K-A-OK")).isTrue();
    }

    @Test
    @DisplayName("AugmentResultService_FAILED_status_시_LS_DATA_AUG_REJECTED")
    void failedStatus_mapsToRejected() throws Exception {
        ledger.recordIssued("K-A-FAIL", "EXT-AF");
        LsDataAug aug = newAug(11L, "NIGHT", LsDataAug.STTS_PENDING);
        when(augRepository.findById(11L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-FAIL", "EXT-AF", "FAILED", 11L, "NIGHT", null);

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
    }

    @Test
    @DisplayName("POST_v1_augments_result_idempotencyKey_재인계_시_200_OK_멱등")
    void replay_returnsFalse() throws Exception {
        ledger.recordIssued("K-A-DUP", "EXT-AD");
        LsDataAug aug = newAug(12L, "RAIN", LsDataAug.STTS_PENDING);
        when(augRepository.findById(12L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-DUP", "EXT-AD", "SUCCESS", 12L, "RAIN", null);

        assertThat(service.handle(req)).isTrue();
        assertThat(service.handle(req)).isFalse();
    }

    @Test
    @DisplayName("augType_불일치_시_409_CONFLICT")
    void augTypeMismatch_returns409() throws Exception {
        ledger.recordIssued("K-A-MISMATCH", "EXT-AM");
        LsDataAug aug = newAug(13L, "WINTER", LsDataAug.STTS_PENDING);
        when(augRepository.findById(13L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-MISMATCH", "EXT-AM", "SUCCESS", 13L, "NIGHT", null);

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("AugmentResultService_동시_webhook_인계_시_DataIntegrityViolation_멱등_흡수")
    void concurrentWebhookRace_absorbsDataIntegrityViolation() throws Exception {
        // Phase 4 — DeidentifyResultService / VlmResultService 와 동일한 race 흡수 패턴 적용.
        // 동시 webhook 인계 시 findByIdempotencyKey 모두 empty → 두 트랜잭션이 신규 save
        // 시도 → 한쪽이 DataIntegrityViolationException. 서비스는 catch 후 재조회하여 멱등 흡수해야 함.
        ledger.recordIssued("K-A-RACE", "EXT-A-RACE");

        LsDataAug raceWinner = newAug(50L, "WINTER", LsDataAug.STTS_PENDING);
        when(augRepository.findById(50L)).thenReturn(Optional.of(raceWinner));

        // 첫 호출: empty (둘 다 신규 갱신 시도)
        // 두 번째 호출: 다른 트랜잭션이 이미 적용한 row 반환 (race 흡수 경로)
        when(augRepository.findByIdempotencyKey("K-A-RACE"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(raceWinner));

        // 첫 save 호출 시 UNIQUE 위반 시뮬레이션, 두 번째 save 는 정상 (race 흡수 후)
        when(augRepository.save(any(LsDataAug.class)))
                .thenThrow(new DataIntegrityViolationException("UNIQUE violation"))
                .thenReturn(raceWinner);

        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-RACE", "EXT-A-RACE", "SUCCESS", 50L, "WINTER",
                "/storage/augment/50.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        // race 흡수 후 ACCEPTED 로 갱신
        assertThat(raceWinner.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        // save 두 번 호출 — 실패 + 재조회 후 갱신
        verify(augRepository, times(2)).save(any(LsDataAug.class));
        // 멱등 마킹 정상 수행
        assertThat(ledger.isProcessed("K-A-RACE")).isTrue();
    }

    @Test
    @DisplayName("AugmentResultService_동일_idempotencyKey_재인계_시_단일_LsDataAug_갱신")
    void sameIdempotencyKey_singleRowUpdate() throws Exception {
        // Phase 4 — findByIdempotencyKey 가 기존 row 를 반환하면 동일 row 갱신 (신규 save 없음).
        ledger.recordIssued("K-A-SAME", "EXT-A-SAME");

        LsDataAug existing = newAug(51L, "RAIN", LsDataAug.STTS_PENDING);
        existing.assignIdempotencyKey("K-A-SAME");
        when(augRepository.findById(51L)).thenReturn(Optional.of(existing));
        when(augRepository.findByIdempotencyKey("K-A-SAME")).thenReturn(Optional.of(existing));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));

        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-SAME", "EXT-A-SAME", "SUCCESS", 51L, "RAIN",
                "/storage/augment/51.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(existing.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        // 단일 save 호출 — UNIQUE 위반 없음
        verify(augRepository, times(1)).save(any(LsDataAug.class));
    }

    // ─── Phase 5 V2.0: 증강 = 새 영상 ───

    private LsDataRaw newRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/" + rawSn + ".mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        return raw;
    }

    private LsDataSrc newSrc(Long srcSn, Long rawSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, rawSn + "/frame-" + frameNo + ".jpg", null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    private LsDataSrc newSrcWithVideoFrameNo(Long srcSn, Long rawSn, int frameNo, Integer videoFrameNo) {
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
    @DisplayName("V2_증강_SUCCESS_시_새_RAW_SN_생성_PARENT_RAW_SN_참조")
    void successCreatesNewVideoWithParentRef() throws Exception {
        ledger.recordIssued("K-V2-NEW", "EXT-V2");
        LsDataRaw parentRaw = newRaw(100L);
        LsDataSrc originSrc = newSrc(200L, 100L, 0);
        LsDataAug aug = newAugWithSrc(20L, 200L, "WINTER");

        when(augRepository.findById(20L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(200L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findById(100L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(originSrc));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(100L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                "K-V2-NEW", "EXT-V2", "SUCCESS", 20L, "WINTER",
                "/storage/augment/winter.mp4");

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        ArgumentCaptor<LsDataRaw> rawCaptor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(rawCaptor.capture());
        LsDataRaw newRaw = rawCaptor.getValue();
        assertThat(newRaw.getParentRawSn()).isEqualTo(100L);
        assertThat(newRaw.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);
        assertThat(newRaw.getRawFilePathNm()).isEqualTo("/storage/augment/winter.mp4");
    }

    @Test
    @DisplayName("V2_증강_SUCCESS_시_원본_프레임_라벨_메타_복사")
    void successCopiesFramesLabelsAndMeta() throws Exception {
        ledger.recordIssued("K-V2-COPY", "EXT-V2C");
        LsDataRaw parentRaw = newRaw(101L);
        LsDataSrc frame0 = newSrc(300L, 101L, 0);
        LsDataSrc frame1 = newSrc(301L, 101L, 1);
        LsDataAug aug = newAugWithSrc(21L, 300L, "NIGHT");

        LsDataLbl lbl = LsDataLbl.createAutoBbox(300L, null, "person", "[1,2,3,4]",
                BigDecimal.valueOf(0.9), null);
        LsDataMeta meta = LsDataMeta.create(101L, "weather", "sunny");

        when(augRepository.findById(21L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(300L)).thenReturn(Optional.of(frame0));
        when(videoRepository.findById(101L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(101L)).thenReturn(List.of(frame0, frame1));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(lbl));
        when(metaRepository.findByRawSn(101L)).thenReturn(List.of(meta));

        AugmentResultRequest req = new AugmentResultRequest(
                "K-V2-COPY", "EXT-V2C", "SUCCESS", 21L, "NIGHT",
                "/storage/augment/night.mp4");

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
        ledger.recordIssued("K-V2-FAIL", "EXT-V2F");
        LsDataAug aug = newAug(22L, "RAIN", LsDataAug.STTS_PENDING);
        when(augRepository.findById(22L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                "K-V2-FAIL", "EXT-V2F", "FAILED", 22L, "RAIN", null);

        service.handle(req);

        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(srcRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("V2_증강_SUCCESS_originSrc_미존재_시_신규영상_미생성")
    void successButOriginSrcNotFound_skipsVideoCreation() throws Exception {
        // given
        ledger.recordIssued("K-V2-NOSRC", "EXT-V2NS");
        LsDataAug aug = newAugWithSrc(30L, 9999L, "WINTER");

        when(augRepository.findById(30L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(9999L)).thenReturn(Optional.empty()); // originSrc 미존재

        AugmentResultRequest req = new AugmentResultRequest(
                "K-V2-NOSRC", "EXT-V2NS", "SUCCESS", 30L, "WINTER",
                "/storage/augment/winter.mp4");

        // when
        boolean applied = service.handle(req);

        // then — 핸들 자체는 성공하지만 새 영상은 생성되지 않음
        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(srcRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("V2_증강_SUCCESS_parentRaw_미존재_시_신규영상_미생성")
    void successButParentRawNotFound_skipsVideoCreation() throws Exception {
        // given
        ledger.recordIssued("K-V2-NORAW", "EXT-V2NR");
        LsDataSrc originSrc = newSrc(400L, 8888L, 0);
        LsDataAug aug = newAugWithSrc(31L, 400L, "NIGHT");

        when(augRepository.findById(31L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(400L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findById(8888L)).thenReturn(Optional.empty()); // parentRaw 미존재

        AugmentResultRequest req = new AugmentResultRequest(
                "K-V2-NORAW", "EXT-V2NR", "SUCCESS", 31L, "NIGHT",
                "/storage/augment/night.mp4");

        // when
        boolean applied = service.handle(req);

        // then — 핸들 자체는 성공하지만 새 영상은 생성되지 않음
        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(srcRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("증강_결과_수신_시_라벨_복사와_함께_매핑이_저장된다")
    void copiesLabelsWithAugLblMap() throws Exception {
        // given — 원본 라벨 2건 (lblSn 부여), 증강 SUCCESS 수신
        ledger.recordIssued("K-MAP-OK", "EXT-MAP");
        LsDataRaw parentRaw = newRaw(110L);
        LsDataSrc frame0 = newSrc(500L, 110L, 0);
        LsDataAug aug = newAugWithSrc(40L, 500L, "WINTER");

        LsDataLbl lbl1 = LsDataLbl.createAutoBbox(500L, null, "person", "[1,2,3,4]",
                BigDecimal.valueOf(0.9), null);
        setField(lbl1, "lblSn", 6001L);
        LsDataLbl lbl2 = LsDataLbl.createAutoBbox(500L, null, "car", "[5,6,7,8]",
                BigDecimal.valueOf(0.8), null);
        setField(lbl2, "lblSn", 6002L);

        when(augRepository.findById(40L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(500L)).thenReturn(Optional.of(frame0));
        when(videoRepository.findById(110L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(110L)).thenReturn(List.of(frame0));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(lbl1, lbl2));
        when(metaRepository.findByRawSn(110L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                "K-MAP-OK", "EXT-MAP", "SUCCESS", 40L, "WINTER",
                "/storage/augment/winter.mp4");

        // when
        service.handle(req);

        // then — 라벨 2건당 매핑 2건 일괄 저장
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataAugLblMap>> mapCaptor = ArgumentCaptor.forClass(List.class);
        verify(augLblMapRepository, times(1)).saveAll(mapCaptor.capture());
        List<LsDataAugLblMap> maps = mapCaptor.getValue();
        assertThat(maps).hasSize(2);
        // 원본 SN 은 6001/6002, 복사본 SN 은 saveAll 에서 부여된 7000번대
        assertThat(maps).extracting(LsDataAugLblMap::getOrgnlDataLblSn)
                .containsExactlyInAnyOrder(6001L, 6002L);
        assertThat(maps).allSatisfy(m -> {
            assertThat(m.getDataAugSn()).isEqualTo(40L);
            assertThat(m.getDataLblSn()).isNotNull();
            assertThat(m.getDataLblSn()).isNotEqualTo(m.getOrgnlDataLblSn());
            // 해상도 동일 → 좌표 그대로 복사
            assertThat(m.getCoordRecalcYn()).isEqualTo(LsDataAugLblMap.RECALC_N);
            assertThat(m.getScaleX()).isNull();
            assertThat(m.getScaleY()).isNull();
        });
    }

    @Test
    @DisplayName("복사할_라벨이_없으면_매핑도_저장되지_않는다")
    void noLabels_noAugLblMapSaved() throws Exception {
        // given — 원본 라벨 0건
        ledger.recordIssued("K-MAP-EMPTY", "EXT-MAPE");
        LsDataRaw parentRaw = newRaw(111L);
        LsDataSrc frame0 = newSrc(510L, 111L, 0);
        LsDataAug aug = newAugWithSrc(41L, 510L, "NIGHT");

        when(augRepository.findById(41L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(510L)).thenReturn(Optional.of(frame0));
        when(videoRepository.findById(111L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(111L)).thenReturn(List.of(frame0));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(111L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                "K-MAP-EMPTY", "EXT-MAPE", "SUCCESS", 41L, "NIGHT",
                "/storage/augment/night.mp4");

        // when
        service.handle(req);

        // then — 매핑 저장 미호출
        verify(augLblMapRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("멱등_스킵_시_라벨_매핑도_저장되지_않는다")
    void idempotentReplay_noAugLblMapSaved() throws Exception {
        ledger.recordIssued("K-MAP-DUP", "EXT-MAPD");
        LsDataAug aug = newAug(42L, "RAIN", LsDataAug.STTS_PENDING);
        when(augRepository.findById(42L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                "K-MAP-DUP", "EXT-MAPD", "SUCCESS", 42L, "RAIN", null);

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
        ledger.recordIssued("K-VFN", "EXT-VFN");
        LsDataRaw parentRaw = newRaw(120L);
        LsDataSrc frame0 = newSrcWithVideoFrameNo(600L, 120L, 0, 100);
        LsDataSrc frame1 = newSrcWithVideoFrameNo(601L, 120L, 1, 250);
        LsDataAug aug = newAugWithSrc(45L, 600L, "WINTER");

        when(augRepository.findById(45L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(600L)).thenReturn(Optional.of(frame0));
        when(videoRepository.findById(120L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(120L)).thenReturn(List.of(frame0, frame1));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(120L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                "K-VFN", "EXT-VFN", "SUCCESS", 45L, "WINTER",
                "/storage/augment/winter.mp4");

        service.handle(req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataSrc>> captor = ArgumentCaptor.forClass(List.class);
        verify(srcRepository).saveAll(captor.capture());
        List<LsDataSrc> copied = captor.getValue();
        assertThat(copied).hasSize(2);
        // 부모의 videoFrameNo(실제 영상 위치) carry-over
        assertThat(copied.get(0).getVideoFrameNo()).isEqualTo(100);
        assertThat(copied.get(1).getVideoFrameNo()).isEqualTo(250);
    }

    @Test
    @DisplayName("부모_videoFrameNo가_null이면_증강복사본도_null")
    void augmentCopy_nullParentVideoFrameNo_staysNull() throws Exception {
        ledger.recordIssued("K-VFN-NULL", "EXT-VFNN");
        LsDataRaw parentRaw = newRaw(121L);
        // 4인자 create → videoFrameNo null (backfill 범위 외)
        LsDataSrc frame0 = newSrc(610L, 121L, 0);
        LsDataAug aug = newAugWithSrc(46L, 610L, "NIGHT");

        when(augRepository.findById(46L)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        when(srcRepository.findById(610L)).thenReturn(Optional.of(frame0));
        when(videoRepository.findById(121L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(121L)).thenReturn(List.of(frame0));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(121L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                "K-VFN-NULL", "EXT-VFNN", "SUCCESS", 46L, "NIGHT",
                "/storage/augment/night.mp4");

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
