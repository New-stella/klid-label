package kr.co.cudo.authoring.video.service.port;

import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BrampVideoProbe#parse} ffprobe 출력 파싱 단위 테스트.
 *
 * <p>프로세스 실행(ffprobe 바이너리)과 파싱 로직을 분리해, 바이너리 비의존으로 key=value 구획
 * 파싱·분수 프레임레이트 계산·폴백·graceful null 처리를 검증한다.
 */
class BrampVideoProbeTest {

    /** [STREAM]/[FORMAT] 구획 + key=value 라인을 구성하는 헬퍼. */
    private List<String> output(List<String> streamLines, List<String> formatLines) {
        java.util.List<String> all = new java.util.ArrayList<>();
        all.add("[STREAM]");
        all.addAll(streamLines);
        all.add("[/STREAM]");
        all.add("[FORMAT]");
        all.addAll(formatLines);
        all.add("[/FORMAT]");
        return all;
    }

    @Test
    @DisplayName("정상_모든필드_파싱")
    void parsesAllFields() {
        // given: h264, 29.97fps(30000/1001), 정상 bit_rate·duration·size
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=30000/1001", "bit_rate=4500000", "duration=12.500000"),
                List.of("size=6789012", "bit_rate=6800000", "duration=12.520000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then
        assertThat(meta.width()).isEqualTo(1920);
        assertThat(meta.height()).isEqualTo(1080);
        assertThat(meta.codecName()).isEqualTo("h264");
        assertThat(meta.fps()).isCloseTo(29.97, org.assertj.core.data.Offset.offset(0.01));
        assertThat(meta.bitRate()).isEqualTo(4500000L);
        assertThat(meta.durationMs()).isEqualTo(12500L);
        assertThat(meta.fileSize()).isEqualTo(6789012L);
    }

    @Test
    @DisplayName("정수프레임레이트_파싱")
    void parsesIntegerFrameRate() {
        // given: 25/1 → 25.0
        List<String> lines = output(
                List.of("width=1280", "height=720", "codec_name=hevc",
                        "r_frame_rate=25/1", "bit_rate=3000000", "duration=10.000000"),
                List.of("size=3750000", "bit_rate=3000000", "duration=10.000000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then
        assertThat(meta.fps()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("분모0_프레임레이트_널")
    void zeroDenominatorFrameRateIsNull() {
        // given: 0/0 (ffprobe 가 프레임레이트 미상 시 출력)
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=0/0", "bit_rate=4500000", "duration=12.500000"),
                List.of("size=6789012", "bit_rate=6800000", "duration=12.500000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then
        assertThat(meta.fps()).isNull();
        assertThat(meta.width()).isEqualTo(1920);
    }

    @Test
    @DisplayName("비트레이트_스트림누락_포맷폴백")
    void streamBitRateNaFallsBackToFormat() {
        // given: stream bit_rate=N/A, format bit_rate 존재
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=30/1", "bit_rate=N/A", "duration=8.000000"),
                List.of("size=1234567", "bit_rate=5500000", "duration=8.000000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then
        assertThat(meta.bitRate()).isEqualTo(5500000L);
    }

    @Test
    @DisplayName("필드일부누락_graceful")
    void missingFieldsAreGraceful() {
        // given: codec_name 없음, duration 없음(stream/format 모두). width/height/fps 는 정상
        List<String> lines = output(
                List.of("width=1920", "height=1080", "r_frame_rate=30/1"),
                List.of("size=1000000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then
        assertThat(meta.codecName()).isNull();
        assertThat(meta.durationMs()).isNull();
        assertThat(meta.width()).isEqualTo(1920);
        assertThat(meta.height()).isEqualTo(1080);
        assertThat(meta.fps()).isEqualTo(30.0);
        assertThat(meta.fileSize()).isEqualTo(1000000L);
    }

    @Test
    @DisplayName("비디오스트림_없음")
    void noVideoStream() {
        // given: 오디오 전용 등으로 비디오 스트림 없음 — STREAM 구획 비고 FORMAT 만 존재
        List<String> lines = output(
                List.of(),
                List.of("size=500000", "bit_rate=128000", "duration=30.000000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then: 예외 없이 width/height=0, codec/fps null
        assertThat(meta.width()).isEqualTo(0);
        assertThat(meta.height()).isEqualTo(0);
        assertThat(meta.codecName()).isNull();
        assertThat(meta.fps()).isNull();
        assertThat(meta.fileSize()).isEqualTo(500000L);
        // then: 비디오 스트림이 없어도 format 폴백이 실제로 채워짐
        assertThat(meta.bitRate()).isEqualTo(128000L);
        assertThat(meta.durationMs()).isEqualTo(30000L);
    }

    @Test
    @DisplayName("기존_해상도만_사용처_무회귀")
    void backwardCompatibleDimensions() {
        // given
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=30/1", "bit_rate=4500000", "duration=12.500000"),
                List.of("size=6789012", "bit_rate=6800000", "duration=12.500000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);
        VideoProbe.Dimensions dim = meta.dimensions();

        // then: Dimensions 접근이 그대로 동작
        assertThat(dim.width()).isEqualTo(1920);
        assertThat(dim.height()).isEqualTo(1080);
    }

    @Test
    @DisplayName("입력_null_전필드_기본값")
    void nullInputReturnsDefaults() {
        // when
        VideoMeta meta = BrampVideoProbe.parse(null, null);

        // then: 예외 없이 VideoMeta(0,0,null,null,null,null,null)
        assertThat(meta.width()).isEqualTo(0);
        assertThat(meta.height()).isEqualTo(0);
        assertThat(meta.codecName()).isNull();
        assertThat(meta.fps()).isNull();
        assertThat(meta.bitRate()).isNull();
        assertThat(meta.durationMs()).isNull();
        assertThat(meta.fileSize()).isNull();
    }

    @Test
    @DisplayName("입력_빈리스트_전필드_기본값")
    void emptyInputReturnsDefaults() {
        // when
        VideoMeta meta = BrampVideoProbe.parse(List.of(), null);

        // then
        assertThat(meta.width()).isEqualTo(0);
        assertThat(meta.height()).isEqualTo(0);
        assertThat(meta.codecName()).isNull();
        assertThat(meta.fps()).isNull();
        assertThat(meta.bitRate()).isNull();
        assertThat(meta.durationMs()).isNull();
        assertThat(meta.fileSize()).isNull();
    }

    @Test
    @DisplayName("duration_비수치_graceful_널")
    void nonNumericDurationIsNull() {
        // given: stream/format duration 이 비수치("abc")
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=30/1", "duration=abc"),
                List.of("size=1000000", "duration=abc"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then: 예외 없이 durationMs null, 나머지 정상
        assertThat(meta.durationMs()).isNull();
        assertThat(meta.width()).isEqualTo(1920);
        assertThat(meta.fps()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("bitrate_비수치_graceful_널")
    void nonNumericBitRateIsNull() {
        // given: stream/format bit_rate 가 비수치("1.2.3")
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=30/1", "bit_rate=1.2.3"),
                List.of("size=1000000", "bit_rate=1.2.3"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then
        assertThat(meta.bitRate()).isNull();
        assertThat(meta.fileSize()).isEqualTo(1000000L);
    }

    @Test
    @DisplayName("size_비수치_graceful_널")
    void nonNumericSizeIsNull() {
        // given: format size 가 비수치("abc")
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=30/1", "bit_rate=4500000", "duration=8.000000"),
                List.of("size=abc", "bit_rate=5000000", "duration=8.000000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then
        assertThat(meta.fileSize()).isNull();
        assertThat(meta.bitRate()).isEqualTo(4500000L);
    }

    @Test
    @DisplayName("단일정수_프레임레이트_슬래시없음")
    void bareIntegerFrameRate() {
        // given: 슬래시 없는 단일 정수 형식 "25" → 25.0
        List<String> lines = output(
                List.of("width=1280", "height=720", "codec_name=h264",
                        "r_frame_rate=25", "bit_rate=3000000", "duration=10.000000"),
                List.of("size=3750000", "bit_rate=3000000", "duration=10.000000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then
        assertThat(meta.fps()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("음수_분자_프레임레이트_널")
    void negativeNumeratorFrameRateIsNull() {
        // given: -30/1 (음수 분자) → fps null
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=-30/1", "bit_rate=4500000", "duration=8.000000"),
                List.of("size=1000000", "bit_rate=4500000", "duration=8.000000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then
        assertThat(meta.fps()).isNull();
        assertThat(meta.width()).isEqualTo(1920);
    }

    @Test
    @DisplayName("음수_분모_프레임레이트_널")
    void negativeDenominatorFrameRateIsNull() {
        // given: 30/-1 (음수 분모) → fps null
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=30/-1", "bit_rate=4500000", "duration=8.000000"),
                List.of("size=1000000", "bit_rate=4500000", "duration=8.000000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then
        assertThat(meta.fps()).isNull();
    }

    @Test
    @DisplayName("음수_단일정수_프레임레이트_널")
    void negativeBareFrameRateIsNull() {
        // given: 슬래시 없는 음수 "-25" → fps null
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=-25", "bit_rate=4500000", "duration=8.000000"),
                List.of("size=1000000", "bit_rate=4500000", "duration=8.000000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then
        assertThat(meta.fps()).isNull();
    }

    @Test
    @DisplayName("width_height_비수치_0폴백")
    void nonNumericDimensionsFallBackToZero() {
        // given: width/height 가 비수치 → 0 폴백
        List<String> lines = output(
                List.of("width=abc", "height=xyz", "codec_name=h264",
                        "r_frame_rate=30/1", "bit_rate=4500000", "duration=8.000000"),
                List.of("size=1000000", "bit_rate=4500000", "duration=8.000000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then
        assertThat(meta.width()).isEqualTo(0);
        assertThat(meta.height()).isEqualTo(0);
        assertThat(meta.fps()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("비트레이트만_누락_타필드_독립유지")
    void onlyBitRateMissingOthersIntact() {
        // given: stream/format 모두 bit_rate 만 누락, 나머지(fps/duration/codec)는 정상
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=30/1", "duration=8.000000"),
                List.of("size=1000000", "duration=8.000000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then: bit_rate 만 null, 나머지는 상호 독립적으로 유지
        assertThat(meta.bitRate()).isNull();
        assertThat(meta.fps()).isEqualTo(30.0);
        assertThat(meta.durationMs()).isEqualTo(8000L);
        assertThat(meta.codecName()).isEqualTo("h264");
    }

    @Test
    @DisplayName("비수치_프레임레이트_슬래시없음_예외캐치_널")
    void nonNumericBareFrameRateIsNull() {
        // given: 슬래시 없는 비수치 "abc" → Double.parseDouble 예외 → catch → null
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=abc", "bit_rate=4500000", "duration=8.000000"),
                List.of("size=1000000", "bit_rate=4500000", "duration=8.000000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then: fps null, 다른 필드는 정상
        assertThat(meta.fps()).isNull();
        assertThat(meta.width()).isEqualTo(1920);
        assertThat(meta.height()).isEqualTo(1080);
        assertThat(meta.bitRate()).isEqualTo(4500000L);
    }

    @Test
    @DisplayName("비수치_프레임레이트_분수형식_예외캐치_널")
    void nonNumericFractionFrameRateIsNull() {
        // given: 분수형식이나 분모가 비수치 "30/abc" → Double.parseDouble 예외 → catch → null
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=30/abc", "bit_rate=4500000", "duration=8.000000"),
                List.of("size=1000000", "bit_rate=4500000", "duration=8.000000"));

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then
        assertThat(meta.fps()).isNull();
        assertThat(meta.width()).isEqualTo(1920);
    }

    @Test
    @DisplayName("malformed_라인_구획밖_라인_스킵")
    void malformedAndOutOfSectionLinesAreSkipped() {
        // given: '=' 없는 라인, 구획 밖 잡음 라인이 섞여도 정상 파싱
        List<String> lines = new java.util.ArrayList<>();
        lines.add("garbage-line-no-eq");        // 구획 밖 + '=' 없음
        lines.add("stray=value");                // 구획 밖 key=value (무시돼야 함)
        lines.add("[STREAM]");
        lines.add("width=1920");
        lines.add("no-equals-here");             // 구획 안 '=' 없음 → 스킵
        lines.add("=leadingEquals");             // eq==0 → 스킵
        lines.add("height=1080");
        lines.add("codec_name=h264");
        lines.add("r_frame_rate=30/1");
        lines.add("[/STREAM]");
        lines.add("orphan-line");                // 구획 밖
        lines.add("[FORMAT]");
        lines.add("size=1000000");
        lines.add("[/FORMAT]");

        // when
        VideoMeta meta = BrampVideoProbe.parse(lines, null);

        // then: malformed 라인 무시하고 정상 값만 파싱
        assertThat(meta.width()).isEqualTo(1920);
        assertThat(meta.height()).isEqualTo(1080);
        assertThat(meta.codecName()).isEqualTo("h264");
        assertThat(meta.fps()).isEqualTo(30.0);
        assertThat(meta.fileSize()).isEqualTo(1000000L);
    }
}
