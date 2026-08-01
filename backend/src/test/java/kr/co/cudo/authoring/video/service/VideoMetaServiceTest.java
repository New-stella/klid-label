package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VideoMetaService#upsertVideoMeta} 단위 테스트 — NIA export Phase 2.
 *
 * <p>ffprobe 결과(VideoMeta)를 {@code video.*} 키로 매핑하는 로직과, 각 키가 원자적
 * {@link LsDataMetaRepository#upsertMeta} 호출로 위임되는지를 검증한다(DB 비의존).
 * (RAW_SN, META_KEY) UK 충돌 처리는 PostgreSQL {@code ON CONFLICT} 가 DB 레벨에서 원자적으로
 * 담당하므로 여기서는 매핑·skip·절단·가드만 다루고, 실 DB 멱등/원자성은 Testcontainers 통합
 * 테스트({@code VideoMetaUpsertIT})가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class VideoMetaServiceTest {

    private static final Long RAW_SN = 100L;

    @Mock
    private LsDataMetaRepository metaRepository;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private LsDataIngestRepository ingestRepository;

    private VideoMetaService service;

    @BeforeEach
    void setUp() {
        service = new VideoMetaService(metaRepository, videoRepository, ingestRepository);
    }

    private VideoMeta fullMeta(Double fps) {
        // width=1920, height=1080, codec=h264, bitRate=4500000, durationMs=12500, fileSize=6789012
        return new VideoMeta(1920, 1080, "h264", fps, 4_500_000L, 12_500L, 6_789_012L);
    }

    /** width/height 만 지정하고 나머지 필드는 null(미상) 로 둔 resolution 격리용 meta. */
    private VideoMeta resolutionOnlyMeta(int width, int height) {
        return new VideoMeta(width, height, null, null, null, null, null);
    }

    @Test
    @DisplayName("적재_후_video메타_6키_upsert호출")
    void storesSixVideoKeys() {
        // when: 모든 필드가 채워진 probe 결과
        service.upsertVideoMeta(RAW_SN, fullMeta(29.97));

        // then: video.* 6개 키가 각각 upsertMeta 로 원자 저장된다
        verify(metaRepository).upsertMeta(eq(RAW_SN), eq("video.fps"), eq(String.valueOf(29.97)));
        verify(metaRepository).upsertMeta(eq(RAW_SN), eq("video.codec"), eq("h264"));
        verify(metaRepository).upsertMeta(eq(RAW_SN), eq("video.bit_rate"), eq("4500000"));
        verify(metaRepository).upsertMeta(eq(RAW_SN), eq("video.duration_ms"), eq("12500"));
        verify(metaRepository).upsertMeta(eq(RAW_SN), eq("video.filesize"), eq("6789012"));
        verify(metaRepository).upsertMeta(eq(RAW_SN), eq("video.resolution"), eq("1920x1080"));
        verify(metaRepository, times(6)).upsertMeta(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("멱등_재실행_동일키_원자upsert위임")
    void idempotentRerunDelegatesToAtomicUpsert() {
        // given: 동일 rawSn 으로 2회 실행 (fps 값만 변경)
        service.upsertVideoMeta(RAW_SN, fullMeta(29.97));

        // when: 2회차 — fps 30.0 으로 갱신
        service.upsertVideoMeta(RAW_SN, fullMeta(30.0));

        // then: 중복 여부는 DB ON CONFLICT 가 원자 처리 — 서비스는 두 값 모두 upsertMeta 로 위임한다
        verify(metaRepository).upsertMeta(eq(RAW_SN), eq("video.fps"), eq(String.valueOf(29.97)));
        verify(metaRepository).upsertMeta(eq(RAW_SN), eq("video.fps"), eq(String.valueOf(30.0)));
        // read-then-write 잔재(조회/saveAll)가 없어야 한다
        verify(metaRepository, never()).findByRawSnAndMetaKeyIn(anyLong(), any());
        verify(metaRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("null필드_해당키_미저장")
    void skipsNullFieldKey() {
        // when: fps 가 null(미상)
        service.upsertVideoMeta(RAW_SN, fullMeta(null));

        // then: video.fps 키는 upsert 되지 않고 나머지 5개는 저장된다
        verify(metaRepository, never()).upsertMeta(anyLong(), eq("video.fps"), anyString());
        verify(metaRepository, times(5)).upsertMeta(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("resolution_wh양수일때만")
    void resolutionOnlyWhenWidthHeightPositive() {
        // given: width/height 가 0(비디오 스트림 없음)
        VideoMeta noStream = new VideoMeta(0, 0, "h264", 29.97, 4_500_000L, 12_500L, 6_789_012L);

        // when
        service.upsertVideoMeta(RAW_SN, noStream);

        // then: video.resolution 미저장, 나머지 5개는 저장
        verify(metaRepository, never()).upsertMeta(anyLong(), eq("video.resolution"), anyString());
        verify(metaRepository, times(5)).upsertMeta(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("codec_META_VL초과_2000절단")
    void codecTruncatedToMaxLength() {
        // given: codec 문자열이 META_VL(2000) 초과
        String longCodec = "x".repeat(VideoMetaService.META_VL_MAX + 500);
        VideoMeta meta = new VideoMeta(0, 0, longCodec, null, null, null, null);

        // when
        service.upsertVideoMeta(RAW_SN, meta);

        // then: 2000 자로 절단되어 upsert 된다
        ArgumentCaptor<String> valCaptor = ArgumentCaptor.forClass(String.class);
        verify(metaRepository).upsertMeta(eq(RAW_SN), eq("video.codec"), valCaptor.capture());
        assertThat(valCaptor.getValue()).hasSize(VideoMetaService.META_VL_MAX);
    }

    @Test
    @DisplayName("모든필드null_desired빈맵_upsert호출없음")
    void allNullFieldsSkipsUpsertEntirely() {
        // given: 모든 필드 null + width/height 0 (추출 가능 필드 0)
        VideoMeta empty = new VideoMeta(0, 0, null, null, null, null, null);

        // when
        service.upsertVideoMeta(RAW_SN, empty);

        // then: upsert 호출 없음
        verify(metaRepository, never()).upsertMeta(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("rawSn_null이면_upsert호출없음")
    void nullRawSnGuardsEarlyReturn() {
        // when
        service.upsertVideoMeta(null, fullMeta(29.97));

        // then
        verify(metaRepository, never()).upsertMeta(any(), anyString(), any());
    }

    @Test
    @DisplayName("meta_null이면_upsert호출없음")
    void nullMetaGuardsEarlyReturn() {
        // when
        service.upsertVideoMeta(RAW_SN, null);

        // then
        verify(metaRepository, never()).upsertMeta(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("resolution_width만양수_height0_미저장")
    void resolutionSkippedWhenOnlyWidthPositive() {
        // given: width=1920, height=0, 나머지 null → 추출 가능 필드 0
        service.upsertVideoMeta(RAW_SN, resolutionOnlyMeta(1920, 0));

        // then: resolution 미구성 → upsert 자체 없음
        verify(metaRepository, never()).upsertMeta(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("resolution_height만양수_width0_미저장")
    void resolutionSkippedWhenOnlyHeightPositive() {
        // given: width=0, height=1080, 나머지 null
        service.upsertVideoMeta(RAW_SN, resolutionOnlyMeta(0, 1080));

        // then
        verify(metaRepository, never()).upsertMeta(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("codec_blank문자열_미저장")
    void blankCodecSkipped() {
        // given: codec 이 공백문자열(hasText=false), 나머지 null → 추출 가능 필드 0
        VideoMeta blankCodec = new VideoMeta(0, 0, "   ", null, null, null, null);

        // when
        service.upsertVideoMeta(RAW_SN, blankCodec);

        // then: video.codec 미구성 → upsert 자체 없음
        verify(metaRepository, never()).upsertMeta(anyLong(), anyString(), any());
    }

    /** 지정한 durationMs 만 담고 나머지는 null/0 인 back-fill 격리용 meta. */
    private VideoMeta durationOnlyMeta(Long durationMs) {
        return new VideoMeta(0, 0, null, null, null, durationMs, null);
    }

    @Test
    @DisplayName("durationMs_있으면_VDO_LEN_SEC를_초로_backfill_위임")
    void backfillsDurationSecFromProbe() {
        // given: ffprobe 길이 30000ms
        // when
        service.upsertVideoMeta(RAW_SN, durationOnlyMeta(30_000L));

        // then: 30초로 조건부 back-fill 위임(비었을 때만 채우는 판정은 리포지토리 WHERE 가 담당)
        verify(videoRepository).backfillDurationSecIfBlank(RAW_SN, 30);
    }

    @Test
    @DisplayName("durationMs_1초미만이어도_최소1초로_backfill")
    void backfillsAtLeastOneSecondForSubSecondDuration() {
        // given: 500ms(0.5초, 반올림 1) / 490ms(0.49초, 반올림 0 → 최소 1 보정)
        // when
        service.upsertVideoMeta(RAW_SN, durationOnlyMeta(500L));
        service.upsertVideoMeta(RAW_SN, durationOnlyMeta(490L));

        // then: ffprobe 가 길이를 알려준 이상 콘텐츠가 있으므로 최소 1초로 채운다(null 로 두지 않음)
        verify(videoRepository, times(2)).backfillDurationSecIfBlank(RAW_SN, 1);
    }

    @Test
    @DisplayName("durationMs_반올림_backfill")
    void backfillsRoundedSeconds() {
        // given: 12500ms → 12.5초 → 반올림 13
        // when
        service.upsertVideoMeta(RAW_SN, durationOnlyMeta(12_500L));

        // then
        verify(videoRepository).backfillDurationSecIfBlank(RAW_SN, 13);
    }

    @Test
    @DisplayName("durationMs_null이면_backfill_미호출")
    void skipsBackfillWhenDurationNull() {
        // given: durationMs 미상(null), 나머지도 null → 추출 필드 0
        // when
        service.upsertVideoMeta(RAW_SN, durationOnlyMeta(null));

        // then: back-fill 위임 없음
        verify(videoRepository, never()).backfillDurationSecIfBlank(anyLong(), anyInt());
    }

    @Test
    @DisplayName("durationMs_0이면_backfill_미호출")
    void skipsBackfillWhenDurationZero() {
        // given: durationMs=0(미상 취급)
        // when
        service.upsertVideoMeta(RAW_SN, durationOnlyMeta(0L));

        // then
        verify(videoRepository, never()).backfillDurationSecIfBlank(anyLong(), anyInt());
    }

    @Test
    @DisplayName("meta_null이면_backfill도_미호출")
    void skipsBackfillWhenMetaNull() {
        // when
        service.upsertVideoMeta(RAW_SN, null);

        // then: 조기 반환으로 meta·raw 어느 쪽도 건드리지 않는다
        verify(videoRepository, never()).backfillDurationSecIfBlank(anyLong(), anyInt());
    }

    // ================================================================= Phase 6 — 인입값 우선

    /**
     * 관제 인입 행 stub — 엔티티에 관제 수신 컬럼 setter 가 <b>없으므로</b>(설계 구속) mock 으로 값을 준다.
     *
     * @param fps       인입 {@code FPS}(문자열)
     * @param codec     인입 {@code VDO_CDC}
     * @param lenSec    인입 {@code VDO_LEN_SEC}(<b>초</b>)
     * @param fileSz    인입 {@code FILE_SZ}(byte)
     * @param resl      인입 {@code RESL}
     */
    private void stubIngest(String fps, String codec, BigDecimal lenSec, Long fileSz, String resl) {
        LsDataIngest ingest = Mockito.mock(LsDataIngest.class);
        Mockito.lenient().doReturn(fps).when(ingest).getFps();
        Mockito.lenient().doReturn(codec).when(ingest).getVdoCdc();
        Mockito.lenient().doReturn(lenSec).when(ingest).getVdoLenSec();
        Mockito.lenient().doReturn(fileSz).when(ingest).getFileSz();
        Mockito.lenient().doReturn(resl).when(ingest).getResl();
        when(ingestRepository.findLatestByRawSn(RAW_SN)).thenReturn(Optional.of(ingest));
    }

    /** 인입 5키가 모두 유효한 표준 stub. */
    private void stubFullIngest() {
        stubIngest("25", "hevc", new BigDecimal("30"), 1_000L, "1280x720");
    }

    @Test
    @DisplayName("인입값이_있으면_그_값을_적재하고_ffprobe값으로_덮지_않는다")
    void ingestValuesWinOverProbe() {
        // given: 인입 5키 + 서로 다른 probe 결과
        stubFullIngest();
        Map<String, String> ingestValues = service.loadIngestMeta(RAW_SN);

        // when
        service.upsertVideoMeta(RAW_SN, ingestValues, fullMeta(29.97));

        // then: 5키는 인입값(probe 값이 아님)
        verify(metaRepository).upsertMeta(RAW_SN, "video.fps", "25");
        verify(metaRepository).upsertMeta(RAW_SN, "video.codec", "hevc");
        verify(metaRepository).upsertMeta(RAW_SN, "video.duration_ms", "30000");
        verify(metaRepository).upsertMeta(RAW_SN, "video.filesize", "1000");
        verify(metaRepository).upsertMeta(RAW_SN, "video.resolution", "1280x720");
        // and: 인입이 못 주는 bit_rate 만 probe 값
        verify(metaRepository).upsertMeta(RAW_SN, "video.bit_rate", "4500000");
        verify(metaRepository, times(6)).upsertMeta(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("인입값이_없는_키만_ffprobe로_채운다")
    void fallsBackPerKeyNotWholesale() {
        // given: 인입이 codec·resolution 만 채웠다(fps·길이·파일크기 미상)
        stubIngest(null, "hevc", null, null, "1280x720");
        Map<String, String> ingestValues = service.loadIngestMeta(RAW_SN);

        // when
        service.upsertVideoMeta(RAW_SN, ingestValues, fullMeta(29.97));

        // then: 채운 2키는 인입값 유지
        verify(metaRepository).upsertMeta(RAW_SN, "video.codec", "hevc");
        verify(metaRepository).upsertMeta(RAW_SN, "video.resolution", "1280x720");
        // and: 비어 있던 키만 probe 값으로 채워진다("하나라도 없으면 전부 ffprobe" 가 아니다)
        verify(metaRepository).upsertMeta(RAW_SN, "video.fps", String.valueOf(29.97));
        verify(metaRepository).upsertMeta(RAW_SN, "video.duration_ms", "12500");
        verify(metaRepository).upsertMeta(RAW_SN, "video.filesize", "6789012");
        verify(metaRepository).upsertMeta(RAW_SN, "video.bit_rate", "4500000");
        verify(metaRepository, times(6)).upsertMeta(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("인입행이_없는_파생영상은_전부_ffprobe로_채운다")
    void derivativeWithoutIngestRowUsesProbeOnly() {
        // given: 파생영상(증강·해상도)은 저작도구가 만들어 인입 행이 없다
        when(ingestRepository.findLatestByRawSn(RAW_SN)).thenReturn(Optional.empty());

        // when
        Map<String, String> ingestValues = service.loadIngestMeta(RAW_SN);
        service.upsertVideoMeta(RAW_SN, ingestValues, fullMeta(29.97));

        // then: 인입 기여 0 → probe 6키 그대로(종전 동작과 동일)
        assertThat(ingestValues).isEmpty();
        verify(metaRepository).upsertMeta(RAW_SN, "video.fps", String.valueOf(29.97));
        verify(metaRepository).upsertMeta(RAW_SN, "video.resolution", "1920x1080");
        verify(metaRepository, times(6)).upsertMeta(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("영상길이는_초에서_ms로_변환되어_적재된다")
    void durationSecondsAreConvertedToMillis() {
        // given: 인입 VDO_LEN_SEC 는 <초>, video.duration_ms 는 <ms>
        stubIngest(null, null, new BigDecimal("71"), null, null);

        // when
        Map<String, String> ingestValues = service.loadIngestMeta(RAW_SN);

        // then: ×1000 (÷1000 이면 0, 그대로면 71 — 둘 다 오답)
        assertThat(ingestValues).containsEntry("video.duration_ms", "71000");
    }

    @Test
    @DisplayName("인입_영상길이로도_VDO_LEN_SEC_backfill이_동작한다")
    void backfillUsesMergedDurationFromIngest() {
        // given: probe 는 없고 인입만 있다(길이 30초)
        stubIngest(null, null, new BigDecimal("30"), null, null);
        Map<String, String> ingestValues = service.loadIngestMeta(RAW_SN);

        // when
        service.upsertVideoMeta(RAW_SN, ingestValues, null);

        // then: 병합 결과(30000ms)를 초로 환산해 back-fill 위임
        verify(videoRepository).backfillDurationSecIfBlank(RAW_SN, 30);
    }

    @Test
    @DisplayName("색심도_BIT은_video_bit_rate로_옮기지_않는다")
    void colorDepthBitIsNeverMappedToBitRate() {
        // given: 인입 BIT('24bit')은 색심도 표기이며 비트레이트가 아니다(V147 주석·설계 §10)
        stubFullIngest();

        // when
        Map<String, String> ingestValues = service.loadIngestMeta(RAW_SN);

        // then: bit_rate 는 인입에서 조달하지 않는다 — 소비처가 BIGINT(BIT_RT)라 '24bit' 는 값 유실이다
        assertThat(ingestValues).doesNotContainKey("video.bit_rate");
    }

    @Test
    @DisplayName("LS_DATA_META_키_6종이_이전과_동일하다")
    void metaKeySetIsUnchanged() {
        // given: 인입 5키 + probe(bit_rate 포함)
        stubFullIngest();
        Map<String, String> ingestValues = service.loadIngestMeta(RAW_SN);

        // when
        service.upsertVideoMeta(RAW_SN, ingestValues, fullMeta(29.97));

        // then: 키 이름·개수가 Phase 6 이전과 동일(EAV 구조·소비처 계약 무변경)
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(metaRepository, times(6)).upsertMeta(anyLong(), keys.capture(), anyString());
        assertThat(keys.getAllValues()).containsExactlyInAnyOrder(
                "video.fps", "video.codec", "video.bit_rate",
                "video.duration_ms", "video.filesize", "video.resolution");
        assertThat(keys.getAllValues()).allMatch(VideoMetaService::isTechnicalKey);
    }

    @Test
    @DisplayName("인입_형식위반값은_채택하지_않고_해당_키만_ffprobe로_넘긴다")
    void invalidIngestValuesAreRejectedPerKey() {
        // given: fps 비수치 / 길이 0 / 파일크기 음수 / 해상도 등급표기(WxH 아님) — 전부 형식 위반
        stubIngest("N/A", "  ", BigDecimal.ZERO, -1L, "FHD");

        // when
        Map<String, String> ingestValues = service.loadIngestMeta(RAW_SN);

        // then: 한 건도 채택하지 않는다(전부 probe 폴백 대상)
        assertThat(ingestValues).isEmpty();
    }

    @Test
    @DisplayName("인입_fps가_음수면_채택하지_않는다")
    void negativeIngestFpsIsRejected() {
        // given: 관제 수신값은 신뢰 경계 밖이다(CWE-20)
        stubIngest("-30", null, null, null, null);

        // when / then
        assertThat(service.loadIngestMeta(RAW_SN)).doesNotContainKey("video.fps");
    }

    @Test
    @DisplayName("인입_영상길이가_범위를_넘으면_채택하지_않는다")
    void overflowingIngestDurationIsRejected() {
        // given: ×1000 이 long 범위를 넘는 값(오버플로로 음수 길이가 되면 안 된다)
        stubIngest(null, null, new BigDecimal("99999999999999999999"), null, null);

        // when / then
        assertThat(service.loadIngestMeta(RAW_SN)).doesNotContainKey("video.duration_ms");
    }

    @Test
    @DisplayName("인입_해상도는_소문자_x로_정규화된다")
    void ingestResolutionIsNormalized() {
        // given: 대문자 X 표기 (소비처는 소문자 x 로 분해한다)
        stubIngest(null, null, null, null, "1920X1080");

        // when / then
        assertThat(service.loadIngestMeta(RAW_SN)).containsEntry("video.resolution", "1920x1080");
    }

    @Test
    @DisplayName("rawSn_null이면_인입조회를_하지_않는다")
    void nullRawSnSkipsIngestLookup() {
        // when / then
        assertThat(service.loadIngestMeta(null)).isEmpty();
        verify(ingestRepository, never()).findLatestByRawSn(any());
    }

    @Test
    @DisplayName("needsProbe는_전_키가_인입으로_채워졌을_때만_false다")
    void needsProbeOnlyFalseWhenAllKeysCovered() {
        // given: 6키 전부 / 5키만 / 빈 맵 / null
        Map<String, String> all = new LinkedHashMap<>();
        all.put("video.fps", "25");
        all.put("video.codec", "hevc");
        all.put("video.duration_ms", "30000");
        all.put("video.filesize", "1000");
        all.put("video.resolution", "1280x720");
        Map<String, String> withoutBitRate = new LinkedHashMap<>(all);
        all.put("video.bit_rate", "4500000");

        // when / then
        assertThat(VideoMetaService.needsProbe(all)).isFalse();
        assertThat(VideoMetaService.needsProbe(withoutBitRate)).isTrue();
        assertThat(VideoMetaService.needsProbe(Map.of())).isTrue();
        assertThat(VideoMetaService.needsProbe(null)).isTrue();
    }
}
