package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

    private VideoMetaService service;

    @BeforeEach
    void setUp() {
        service = new VideoMetaService(metaRepository, videoRepository);
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
}
