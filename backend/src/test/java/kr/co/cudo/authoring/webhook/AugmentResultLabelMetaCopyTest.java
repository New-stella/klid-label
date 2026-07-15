package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.AugmentResultRequest;
import kr.co.cudo.authoring.webhook.service.AugmentResultService;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H2 — 증강 결과 수신 시 원본 라벨/메타 복사 내용 검증.
 *
 * <p>기존 AugmentResultServiceTest 는 saveAll 호출 횟수만 검증한다.
 * 이 테스트는 <b>복사된 내용(srcSn 매핑, 라벨 필드, 메타 필드)</b>을 정밀 검증한다.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class AugmentResultLabelMetaCopyTest {

    @Mock LsDataAugRepository augRepository;
    @Mock VideoRepository videoRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsDataLblRepository lblRepository;
    @Mock LsDataMetaRepository metaRepository;
    @Mock kr.co.cudo.authoring.augment.repository.LsDataAugLblMapRepository augLblMapRepository;
    @Mock kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository deidentProcLogRepository;
    @Mock kr.co.cudo.authoring.batch.runner.AsyncVideoMetaRunner asyncVideoMetaRunner;

    private AugmentResultService service;
    private final AtomicLong rawSnSeq = new AtomicLong(9000);
    private final AtomicLong srcSnSeq = new AtomicLong(5000);

    @BeforeEach
    void setup() {
        service = new AugmentResultService(augRepository,
                videoRepository, srcRepository, lblRepository, metaRepository, augLblMapRepository,
                deidentProcLogRepository, asyncVideoMetaRunner);

        // save mocks — ID 자동 채번
        when(videoRepository.save(any(LsDataRaw.class))).thenAnswer(inv -> {
            LsDataRaw r = inv.getArgument(0);
            setField(r, "rawSn", rawSnSeq.incrementAndGet());
            return r;
        });
        when(srcRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<LsDataSrc> items = inv.getArgument(0);
            List<LsDataSrc> result = new ArrayList<>();
            for (LsDataSrc s : items) {
                setField(s, "srcSn", srcSnSeq.incrementAndGet());
                result.add(s);
            }
            return result;
        });
        when(lblRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<LsDataLbl> items = inv.getArgument(0);
            List<LsDataLbl> result = new ArrayList<>();
            items.forEach(result::add);
            return result;
        });
        when(metaRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<LsDataMeta> items = inv.getArgument(0);
            List<LsDataMeta> result = new ArrayList<>();
            items.forEach(result::add);
            return result;
        });
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private LsDataRaw newRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + rawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/" + rawSn + ".mp4", null, 60);
        setField(raw, "rawSn", rawSn);
        // R8 — createAugmentedVideo 가 콜백 처리 시점에 부모 DE_IDNTF_YN='Y' 를 재확인하므로 부모를 비식별 완료로 둔다.
        raw.markDeidentified("Y");
        return raw;
    }

    private LsDataSrc newSrc(Long srcSn, Long rawSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, rawSn + "/frame-" + frameNo + ".jpg", null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    private LsDataAug newAugWithSrc(Long augSn, Long srcSn, String type) {
        LsDataAug aug = LsDataAug.createPending(srcSn, type, BigDecimal.valueOf(0.95), "registrar");
        setField(aug, "dataAugSn", augSn);
        return aug;
    }

    private void stubAugAccept(Long augSn, LsDataAug aug) {
        when(augRepository.findByDataAugSnForUpdate(augSn)).thenReturn(Optional.of(aug));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─────────────────────── H2: 라벨 복사 내용 검증 ───────────────────────

    @Test
    @DisplayName("증강_성공시_원본_라벨_내용이_새_프레임에_정확히_복사됨")
    void 증강_성공시_원본_라벨_내용이_새_프레임에_정확히_복사됨() {
        // given — 원본 영상(100L), 프레임 1개(srcSn=200L), 라벨 1건
        LsDataRaw parentRaw = newRaw(100L);
        LsDataSrc originSrc = newSrc(200L, 100L, 0);
        LsDataAug aug = newAugWithSrc(20L, 200L, "WINTER");

        LsDataLbl originalLabel = LsDataLbl.createAutoBbox(
                200L, 42L, "person", "[10,20,100,200]", BigDecimal.valueOf(0.92), "T-001");

        stubAugAccept(20L, aug);
        when(srcRepository.findById(200L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(100L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(originSrc));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(originalLabel));
        when(metaRepository.findByRawSn(100L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                20L, "EXT-LC", "WINTER", "SUCCESS",
                "/storage/augment/winter.mp4");

        // when
        service.handle(req);

        // then — 복사된 라벨의 내용을 캡처하여 검증
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> lblCaptor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(lblCaptor.capture());
        List<LsDataLbl> copiedLabels = lblCaptor.getValue();

        assertThat(copiedLabels).hasSize(1);
        LsDataLbl copied = copiedLabels.get(0);
        // srcSn 은 신규 프레임의 srcSn 이어야 함 (원본 200L 이 아님)
        assertThat(copied.getSrcSn()).isNotEqualTo(200L);
        // 라벨 속성은 원본과 동일
        assertThat(copied.getLblTypeCd()).isEqualTo("BBOX");
        assertThat(copied.getLabelId()).isEqualTo(42L);
        assertThat(copied.getLabelNm()).isEqualTo("person");
        assertThat(copied.getPointCn()).isEqualTo("[10,20,100,200]");
        assertThat(copied.getTrackId()).isEqualTo("T-001");
    }

    @Test
    @DisplayName("증강_성공시_원본_메타가_새_영상에_정확히_복사됨")
    void 증강_성공시_원본_메타가_새_영상에_정확히_복사됨() {
        // given — 원본 영상(101L), 메타 2건
        LsDataRaw parentRaw = newRaw(101L);
        LsDataSrc originSrc = newSrc(300L, 101L, 0);
        LsDataAug aug = newAugWithSrc(21L, 300L, "NIGHT");

        LsDataMeta meta1 = LsDataMeta.create(101L, "weather", "sunny");
        LsDataMeta meta2 = LsDataMeta.create(101L, "time_of_day", "morning");

        stubAugAccept(21L, aug);
        when(srcRepository.findById(300L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(101L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(101L)).thenReturn(List.of(originSrc));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(101L)).thenReturn(List.of(meta1, meta2));

        AugmentResultRequest req = new AugmentResultRequest(
                21L, "EXT-MC", "NIGHT", "SUCCESS",
                "/storage/augment/night.mp4");

        // when
        service.handle(req);

        // then — 복사된 메타 내용 캡처
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataMeta>> metaCaptor = ArgumentCaptor.forClass(List.class);
        verify(metaRepository).saveAll(metaCaptor.capture());
        List<LsDataMeta> copiedMetas = metaCaptor.getValue();

        assertThat(copiedMetas).hasSize(2);
        // rawSn 은 신규 영상 (원본 101L 이 아님)
        assertThat(copiedMetas.get(0).getRawSn()).isNotEqualTo(101L);
        assertThat(copiedMetas.get(1).getRawSn()).isNotEqualTo(101L);
        // 두 메타의 rawSn 은 동일 (같은 새 영상)
        assertThat(copiedMetas.get(0).getRawSn()).isEqualTo(copiedMetas.get(1).getRawSn());
        // metaKey, metaVal 원본과 동일
        assertThat(copiedMetas).extracting(LsDataMeta::getMetaKey)
                .containsExactlyInAnyOrder("weather", "time_of_day");
        assertThat(copiedMetas).extracting(LsDataMeta::getMetaVl)
                .containsExactlyInAnyOrder("sunny", "morning");
    }

    @Test
    @DisplayName("원본에_라벨_없으면_라벨_복사_스킵_정상_완료")
    void 원본에_라벨_없으면_라벨_복사_스킵_정상_완료() {
        // given — 원본에 라벨 0건
        LsDataRaw parentRaw = newRaw(102L);
        LsDataSrc originSrc = newSrc(400L, 102L, 0);
        LsDataAug aug = newAugWithSrc(22L, 400L, "RAIN");

        stubAugAccept(22L, aug);
        when(srcRepository.findById(400L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(102L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(102L)).thenReturn(List.of(originSrc));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(102L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                22L, "EXT-NL", "RAIN", "SUCCESS",
                "/storage/augment/rain.mp4");

        // when
        boolean applied = service.handle(req);

        // then — 정상 완료, saveAll 에 빈 리스트 전달됨
        assertThat(applied).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> lblCaptor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(lblCaptor.capture());
        assertThat(lblCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("원본에_메타_없으면_메타_복사_스킵_정상_완료")
    void 원본에_메타_없으면_메타_복사_스킵_정상_완료() {
        // given — 원본에 메타 0건
        LsDataRaw parentRaw = newRaw(103L);
        LsDataSrc originSrc = newSrc(500L, 103L, 0);
        LsDataAug aug = newAugWithSrc(23L, 500L, "RAIN");

        stubAugAccept(23L, aug);
        when(srcRepository.findById(500L)).thenReturn(Optional.of(originSrc));
        when(videoRepository.findByRawSnForUpdate(103L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(103L)).thenReturn(List.of(originSrc));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());
        when(metaRepository.findByRawSn(103L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                23L, "EXT-NM", "RAIN", "SUCCESS",
                "/storage/augment/rain-no-meta.mp4");

        // when
        boolean applied = service.handle(req);

        // then — 빈 메타 리스트로 saveAll 호출
        assertThat(applied).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataMeta>> metaCaptor = ArgumentCaptor.forClass(List.class);
        verify(metaRepository).saveAll(metaCaptor.capture());
        assertThat(metaCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("복수_프레임_라벨이_올바른_신규_프레임에_매핑됨")
    void 복수_프레임_라벨이_올바른_신규_프레임에_매핑됨() {
        // given — 원본 2프레임(srcSn 600,601), 각 프레임에 라벨 1건씩
        LsDataRaw parentRaw = newRaw(104L);
        LsDataSrc frame0 = newSrc(600L, 104L, 0);
        LsDataSrc frame1 = newSrc(601L, 104L, 1);
        LsDataAug aug = newAugWithSrc(24L, 600L, "WINTER");

        LsDataLbl lbl0 = LsDataLbl.createAutoBbox(600L, null, "car", "[0,0,50,50]",
                BigDecimal.valueOf(0.8), null);
        LsDataLbl lbl1 = LsDataLbl.createAutoBbox(601L, null, "person", "[10,10,90,90]",
                BigDecimal.valueOf(0.7), null);

        stubAugAccept(24L, aug);
        when(srcRepository.findById(600L)).thenReturn(Optional.of(frame0));
        when(videoRepository.findByRawSnForUpdate(104L)).thenReturn(Optional.of(parentRaw));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(104L)).thenReturn(List.of(frame0, frame1));
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(lbl0, lbl1));
        when(metaRepository.findByRawSn(104L)).thenReturn(List.of());

        AugmentResultRequest req = new AugmentResultRequest(
                24L, "EXT-MF", "WINTER", "SUCCESS",
                "/storage/augment/winter-multi.mp4");

        // when
        service.handle(req);

        // then — 2건 복사, 각 라벨의 srcSn 이 서로 다른 신규 프레임에 매핑
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> lblCaptor = ArgumentCaptor.forClass(List.class);
        verify(lblRepository).saveAll(lblCaptor.capture());
        List<LsDataLbl> copied = lblCaptor.getValue();

        assertThat(copied).hasSize(2);
        // 두 라벨의 srcSn 이 서로 다름 (각각 다른 신규 프레임에 매핑)
        assertThat(copied.get(0).getSrcSn()).isNotEqualTo(copied.get(1).getSrcSn());
        // car 라벨은 frame0 의 신규 srcSn, person 라벨은 frame1 의 신규 srcSn
        LsDataLbl copiedCar = copied.stream().filter(l -> "car".equals(l.getLabelNm())).findFirst().orElseThrow();
        LsDataLbl copiedPerson = copied.stream().filter(l -> "person".equals(l.getLabelNm())).findFirst().orElseThrow();
        // srcSn 값이 원본(600, 601)이 아닌 새 값
        assertThat(copiedCar.getSrcSn()).isNotEqualTo(600L);
        assertThat(copiedPerson.getSrcSn()).isNotEqualTo(601L);
        // 좌표 내용 보존
        assertThat(copiedCar.getPointCn()).isEqualTo("[0,0,50,50]");
        assertThat(copiedPerson.getPointCn()).isEqualTo("[10,10,90,90]");
    }
}
