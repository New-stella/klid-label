package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.service.VideoDurationDbReader.DurationSource;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VideoDurationResolver 단위 테스트 — 자동 마킹 실패 결함(FIX A) 다단 폴백 검증.
 *
 * <p>durationSec → video.duration_ms 메타 → 직접 프로브 순으로 해석하고, 전부 실패 시 null 을
 * 반환(예외 미전파)하는지 검증한다. DB read({@link VideoDurationDbReader})와 {@link VideoProbe} 는
 * stub 으로 주입한다. (프로브가 트랜잭션/커넥션 밖에서 실행됨을 보장하는 구조적 회귀 테스트는
 * {@code VideoDurationResolverTxIsolationIT} 참조.)
 */
@ExtendWith(MockitoExtension.class)
class VideoDurationResolverTest {

    @Mock
    private VideoDurationDbReader dbReader;

    @Mock
    private VideoProbe videoProbe;

    @InjectMocks
    private VideoDurationResolver resolver;

    @Test
    @DisplayName("durationSec_존재하면_그값을_그대로_사용하고_프로브_안함")
    void durationSecPresent_returnsIt_noProbe() {
        // given — VDO_LEN_SEC=42 인 영상 (메타·경로는 폴백 대상이 아니므로 무시돼야 함)
        when(dbReader.read(1L)).thenReturn(new DurationSource(42, "99000", "/var/raw/clip.mp4"));

        // when
        Integer sec = resolver.resolveDurationSec(1L);

        // then — 그 값 사용 + 프로브 미호출
        assertThat(sec).isEqualTo(42);
        verify(videoProbe, never()).probe(any());
    }

    @Test
    @DisplayName("durationSec_null이면_video_duration_ms_메타를_초로_환산해_반환_프로브안함")
    void durationSecNull_resolvesFromDurationMsMeta_noProbe() {
        // given — durationSec null, 메타 video.duration_ms=30500(ms)
        when(dbReader.read(2L)).thenReturn(new DurationSource(null, "30500", "/var/raw/clip.mp4"));

        // when
        Integer sec = resolver.resolveDurationSec(2L);

        // then — 30500ms → 반올림 31초. 재프로브 없이 메타에서 해결.
        assertThat(sec).isEqualTo(31);
        verify(videoProbe, never()).probe(any());
    }

    @Test
    @DisplayName("durationSec_null이고_메타도_없으면_직접_프로브로_durationMs_초환산_반환")
    void durationSecNull_metaAbsent_resolvesFromProbe() {
        // given — durationSec null, 메타 없음, 프로브가 duration 60000ms 반환
        when(dbReader.read(3L)).thenReturn(new DurationSource(null, null, "/var/raw/clip.mp4"));
        VideoMeta probed = new VideoMeta(1920, 1080, "h264", 30.0, 5_000_000L, 60_000L, 100L);
        when(videoProbe.probe(Path.of("/var/raw/clip.mp4"))).thenReturn(probed);

        // when
        Integer sec = resolver.resolveDurationSec(3L);

        // then — 60000ms → 60초
        assertThat(sec).isEqualTo(60);
    }

    @Test
    @DisplayName("전부_실패하면_null_반환하고_프로브_예외는_전파하지_않는다")
    void allFail_returnsNull_swallowsProbeException() {
        // given — durationSec null, 메타 없음, 프로브가 예외 던짐(외부 ffprobe 실패)
        when(dbReader.read(4L)).thenReturn(new DurationSource(null, null, "/var/raw/clip.mp4"));
        when(videoProbe.probe(Path.of("/var/raw/clip.mp4")))
                .thenThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "ffprobe 실패"));

        // when / then — 예외 전파 없이 null (마킹 비파괴)
        assertThat(resolver.resolveDurationSec(4L)).isNull();
    }

    @Test
    @DisplayName("메타_ms값이_1초미만이면_프로브_폴백을_시도한다")
    void subSecondMeta_fallsThroughToProbe() {
        // given — 메타 400ms(<1초, 자동마킹 불가) → 프로브가 45000ms 반환
        when(dbReader.read(5L)).thenReturn(new DurationSource(null, "400", "/var/raw/clip.mp4"));
        VideoMeta probed = new VideoMeta(1280, 720, "h264", 25.0, null, 45_000L, null);
        when(videoProbe.probe(Path.of("/var/raw/clip.mp4"))).thenReturn(probed);

        // when
        Integer sec = resolver.resolveDurationSec(5L);

        // then — 메타 400ms 는 null 로 폐기되고 프로브 45초 채택
        assertThat(sec).isEqualTo(45);
    }

    @Test
    @DisplayName("파싱불가_메타값은_null로_폐기하고_프로브도_null이면_최종_null")
    void unparsableMeta_and_probeNullDuration_returnsNull() {
        // given — 메타 파싱불가, 프로브 durationMs=null(비디오 스트림 길이 미상)
        when(dbReader.read(6L)).thenReturn(new DurationSource(null, "N/A", "/var/raw/clip.mp4"));
        VideoMeta probed = new VideoMeta(0, 0, null, null, null, null, null);
        when(videoProbe.probe(Path.of("/var/raw/clip.mp4"))).thenReturn(probed);

        // when / then
        assertThat(resolver.resolveDurationSec(6L)).isNull();
    }

    @Test
    @DisplayName("원본경로가_blank이면_프로브를_시도하지_않고_null")
    void blankPath_noProbe_returnsNull() {
        // given — durationSec null, 메타 없음, 경로 blank
        when(dbReader.read(7L)).thenReturn(new DurationSource(null, null, "   "));

        // when / then — 프로브 미호출 + null
        assertThat(resolver.resolveDurationSec(7L)).isNull();
        verify(videoProbe, never()).probe(any());
    }

    @Test
    @DisplayName("영상이_없으면_DB리더가_null반환_최종_null_프로브안함")
    void videoAbsent_readerReturnsNull_returnsNull_noProbe() {
        // given — DB 리더가 영상 미존재로 null 반환
        when(dbReader.read(999L)).thenReturn(null);

        // when / then
        assertThat(resolver.resolveDurationSec(999L)).isNull();
        verify(videoProbe, never()).probe(any());
    }
}
