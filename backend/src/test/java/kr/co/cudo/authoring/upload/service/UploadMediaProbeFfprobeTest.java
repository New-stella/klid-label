package kr.co.cudo.authoring.upload.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.upload.service.UploadMediaProbe.MediaMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link UploadMediaProbeFfprobe#parse} ffprobe 출력 파싱 단위 테스트.
 *
 * <p>프로세스 실행(ffprobe 바이너리)과 파싱을 분리해 바이너리 비의존으로 검증한다.
 * {@code [STREAM]}/{@code [FORMAT]} 동명 key({@code duration}·{@code bit_rate}) 구분, 미상값
 * graceful null 처리가 핵심이다.
 *
 * <p>{@link ProcessExecution} 중첩 클래스는 <b>프로세스 I/O 교착 회귀 가드</b>다 — 파싱이 아니라
 * "행(hang)하지 않는가"를 가짜 ffprobe 스크립트로 실제 실행해 검증한다.
 */
class UploadMediaProbeFfprobeTest {

    private static final Path VIDEO = Path.of("/nas-storage/upload/tmp/sample.mp4");
    /** 측정 실패·절단·비정상 종료 시의 "전량 미상" 기대값. */
    private static final MediaMeta UNKNOWN_META = new MediaMeta(0, 0, null, null, null, null, null, null);

    private List<String> output(List<String> streamLines, List<String> formatLines) {
        List<String> all = new ArrayList<>();
        all.add("[STREAM]");
        all.addAll(streamLines);
        all.add("[/STREAM]");
        all.add("[FORMAT]");
        all.addAll(formatLines);
        all.add("[/FORMAT]");
        return all;
    }

    @Test
    @DisplayName("STREAM과_FORMAT_구획의_동명키_duration을_구분해_파싱한다")
    void distinguishesDurationBySection() {
        // given: stream duration 12.5s / format duration 12.52s — 컨테이너(format) 값이 우선
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=30000/1001", "duration=12.500000"),
                List.of("duration=12.520000"));

        // when
        MediaMeta meta = UploadMediaProbeFfprobe.parse(lines, VIDEO);

        // then
        assertThat(meta.durationMs()).isEqualTo(12_520L);
    }

    @Test
    @DisplayName("FORMAT_duration이_없으면_STREAM_duration으로_폴백한다")
    void fallsBackToStreamDuration() {
        // given
        List<String> lines = output(
                List.of("width=1920", "height=1080", "duration=12.500000"),
                List.of());

        // when
        MediaMeta meta = UploadMediaProbeFfprobe.parse(lines, VIDEO);

        // then
        assertThat(meta.durationMs()).isEqualTo(12_500L);
    }

    @Test
    @DisplayName("nb_frames와_display_aspect_ratio를_파싱한다")
    void parsesFrameCountAndAspectRatio() {
        // given
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=h264",
                        "r_frame_rate=30000/1001", "nb_frames=375",
                        "display_aspect_ratio=16:9", "duration=12.500000"),
                List.of("duration=12.520000"));

        // when
        MediaMeta meta = UploadMediaProbeFfprobe.parse(lines, VIDEO);

        // then
        assertThat(meta.width()).isEqualTo(1920);
        assertThat(meta.height()).isEqualTo(1080);
        assertThat(meta.codecName()).isEqualTo("h264");
        assertThat(meta.fps()).isCloseTo(29.97, within(0.01));
        assertThat(meta.nbFrames()).isEqualTo(375L);
        assertThat(meta.displayAspectRatio()).isEqualTo("16:9");
    }

    @Test
    @DisplayName("FORMAT_bit_rate를_우선_채택하고_STREAM_값은_폴백이다")
    void prefersFormatBitRateOverStream() {
        // given: stream 1,800,000bps / format 2,050,627bps — 컨테이너(format) 값이 우선
        List<String> lines = output(
                List.of("width=1920", "height=1080", "bit_rate=1800000"),
                List.of("duration=12.520000", "bit_rate=2050627"));

        // when
        MediaMeta meta = UploadMediaProbeFfprobe.parse(lines, VIDEO);

        // then
        assertThat(meta.bitRate()).isEqualTo(2_050_627L);
    }

    @Test
    @DisplayName("FORMAT_bit_rate가_없으면_STREAM_bit_rate로_폴백한다")
    void fallsBackToStreamBitRate() {
        // given: 컨테이너가 총 비트레이트를 신고하지 않는 형식
        List<String> lines = output(
                List.of("width=1920", "height=1080", "bit_rate=1800000"),
                List.of("duration=12.520000"));

        // when
        MediaMeta meta = UploadMediaProbeFfprobe.parse(lines, VIDEO);

        // then
        assertThat(meta.bitRate()).isEqualTo(1_800_000L);
    }

    @Test
    @DisplayName("bit_rate가_N_A거나_0이거나_파싱불가면_null이다 — 0bps는_형식상_정상인_틀린_값이다")
    void rejectsUnusableBitRate() {
        // given: 미상(N/A) · 0 · 음수 · 숫자 아님 — 어느 것도 유효한 비트레이트가 아니다
        assertThat(bitRateOf("N/A")).isNull();
        assertThat(bitRateOf("0")).isNull();
        assertThat(bitRateOf("-1")).isNull();
        assertThat(bitRateOf("abc")).isNull();
        // 대조군 — 정상 값은 통과한다(게이트가 전부를 막는 것이 아니다)
        assertThat(bitRateOf("2050627")).isEqualTo(2_050_627L);
    }

    /** {@code format.bit_rate} 한 값만 바꿔 파싱 결과를 얻는다. */
    private Long bitRateOf(String rawValue) {
        return UploadMediaProbeFfprobe.parse(output(
                List.of("width=1920", "height=1080"),
                List.of("duration=12.520000", "bit_rate=" + rawValue)), VIDEO).bitRate();
    }

    @Test
    @DisplayName("미상값_N_A는_null로_정규화된다")
    void normalizesNotAvailable() {
        // given
        List<String> lines = output(
                List.of("width=1920", "height=1080", "codec_name=N/A",
                        "r_frame_rate=0/0", "nb_frames=N/A", "display_aspect_ratio=N/A"),
                List.of());

        // when
        MediaMeta meta = UploadMediaProbeFfprobe.parse(lines, VIDEO);

        // then
        assertThat(meta.codecName()).isNull();
        assertThat(meta.fps()).isNull();
        assertThat(meta.nbFrames()).isNull();
        assertThat(meta.displayAspectRatio()).isNull();
    }

    @Test
    @DisplayName("출력이_비어있으면_예외없이_전량_미상으로_돌아온다")
    void handlesEmptyOutput() {
        // given / when
        MediaMeta empty = UploadMediaProbeFfprobe.parse(List.of(), VIDEO);
        MediaMeta nullLines = UploadMediaProbeFfprobe.parse(null, VIDEO);

        // then
        assertThat(empty).isEqualTo(new MediaMeta(0, 0, null, null, null, null, null, null));
        assertThat(nullLines).isEqualTo(new MediaMeta(0, 0, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("STREAM_구획이_없으면_너비높이가_0이고_나머지가_null이다")
    void handlesMissingStreamSection() {
        // given: 비디오 스트림이 없어 FORMAT 만 출력된 경우(관심 밖 key 만 존재)
        List<String> lines = List.of("[FORMAT]", "size=6789012", "[/FORMAT]");

        // when
        MediaMeta meta = UploadMediaProbeFfprobe.parse(lines, VIDEO);

        // then
        assertThat(meta.width()).isZero();
        assertThat(meta.height()).isZero();
        assertThat(meta.codecName()).isNull();
        assertThat(meta.fps()).isNull();
        assertThat(meta.durationMs()).isNull();
        assertThat(meta.nbFrames()).isNull();
        assertThat(meta.displayAspectRatio()).isNull();
    }

    @Test
    @DisplayName("알_수_없는_키는_무시한다")
    void ignoresUnknownKeys() {
        // given
        List<String> lines = output(
                List.of("pix_fmt=yuv420p", "width=1280", "height=720", "level=31"),
                List.of("nb_streams=2"));

        // when
        MediaMeta meta = UploadMediaProbeFfprobe.parse(lines, VIDEO);

        // then
        assertThat(meta.width()).isEqualTo(1280);
        assertThat(meta.height()).isEqualTo(720);
    }

    @Test
    @DisplayName("값에_등호가_포함돼도_첫_등호로만_분리한다")
    void splitsOnFirstEqualsOnly() {
        // given: 값 안에 '=' 가 들어간 비정상 출력
        List<String> lines = output(
                List.of("codec_name=h264=main", "width=1280", "height=720"),
                List.of());

        // when
        MediaMeta meta = UploadMediaProbeFfprobe.parse(lines, VIDEO);

        // then
        assertThat(meta.codecName()).isEqualTo("h264=main");
    }

    @Test
    @DisplayName("구획_밖_라인은_무시한다")
    void ignoresLinesOutsideSections() {
        // given: 구획이 닫힌 뒤의 잔여 라인
        List<String> lines = List.of("[STREAM]", "width=1280", "[/STREAM]", "height=720");

        // when
        MediaMeta meta = UploadMediaProbeFfprobe.parse(lines, VIDEO);

        // then
        assertThat(meta.width()).isEqualTo(1280);
        assertThat(meta.height()).isZero();
    }

    // ==================== 포트 계약: NaN/Infinity 반출 금지 (CWE-20/681) ====================

    @Test
    @DisplayName("r_frame_rate가_NaN이나_Infinity면_fps를_null로_돌려준다")
    void neverEmitsNonFiniteFps() {
        // given: Double.parseDouble 은 "NaN"·"Infinity" 를 정상 파싱하고 NaN 은 모든 부호 비교를
        //        통과하므로, 부호 검사만으로는 이 값들이 그대로 반출된다
        MediaMeta nan = UploadMediaProbeFfprobe.parse(
                output(List.of("width=1920", "height=1080", "r_frame_rate=NaN/1"), List.of()), VIDEO);
        MediaMeta infinity = UploadMediaProbeFfprobe.parse(
                output(List.of("width=1920", "height=1080", "r_frame_rate=Infinity/1"), List.of()), VIDEO);
        MediaMeta singleNan = UploadMediaProbeFfprobe.parse(
                output(List.of("width=1920", "height=1080", "r_frame_rate=NaN"), List.of()), VIDEO);
        MediaMeta divideByZero = UploadMediaProbeFfprobe.parse(
                output(List.of("width=1920", "height=1080", "r_frame_rate=1/0"), List.of()), VIDEO);

        // then: 포트 계약("fps 는 유한 양수만 유효")대로 전부 미상
        assertThat(nan.fps()).isNull();
        assertThat(infinity.fps()).isNull();
        assertThat(singleNan.fps()).isNull();
        assertThat(divideByZero.fps()).isNull();
    }

    @Test
    @DisplayName("r_frame_rate가_슬래시_없는_Infinity_단일토큰이면_fps를_null로_돌려준다")
    void neverEmitsInfiniteFpsFromSingleToken() {
        // given: 분수가 아닌 단일 토큰 — 분수 경로의 isFinite 게이트를 타지 않으므로
        //        positiveFinite 의 isFinite 가 "유일하게" 이 값을 막는다.
        //        (NaN 은 NaN > 0.0 이 false 라 부호 검사만으로도 걸리므로 이 케이스가 그 게이트의
        //         단독 회귀 가드다 — 값을 NaN 으로 바꾸면 검출력이 사라진다)
        MediaMeta meta = UploadMediaProbeFfprobe.parse(
                output(List.of("width=1920", "height=1080", "r_frame_rate=Infinity"), List.of()), VIDEO);

        // then
        assertThat(meta.fps()).isNull();
    }

    @Test
    @DisplayName("r_frame_rate의_분모만_비유한이어도_fps를_null로_돌려준다")
    void neverEmitsFpsWhenDenominatorNonFinite() {
        // given: 분모만 비유한한 경우
        MediaMeta infinityDen = UploadMediaProbeFfprobe.parse(
                output(List.of("width=1920", "height=1080", "r_frame_rate=1/Infinity"), List.of()), VIDEO);
        MediaMeta nanDen = UploadMediaProbeFfprobe.parse(
                output(List.of("width=1920", "height=1080", "r_frame_rate=1/NaN"), List.of()), VIDEO);

        // then
        assertThat(infinityDen.fps()).isNull();
        assertThat(nanDen.fps()).isNull();
    }

    @Test
    @DisplayName("r_frame_rate의_분자와_분모가_모두_음수여도_fps를_채택하지_않는다")
    void neverEmitsFpsFromNegativeFraction() {
        // given: -1/-1 은 몫이 1.0(유한 양수)이라 결과만 보는 검사는 통과한다 —
        //        분수 경로의 부호 검사(num <= 0 || den <= 0)가 단독으로 막아야 하는 입력이다
        MediaMeta meta = UploadMediaProbeFfprobe.parse(
                output(List.of("width=1920", "height=1080", "r_frame_rate=-1/-1"), List.of()), VIDEO);

        // then
        assertThat(meta.fps()).isNull();
    }

    @Test
    @DisplayName("duration이_1e300이면_밀리초_포화_검사가_단독으로_길이를_막는다")
    void neverEmitsSaturatedDurationFromFiniteMillis() {
        // given: 1e300 × 1000 = 1e303 — 유한이라 isFinite 게이트로는 걸리지 않는다.
        //        포화 경계 검사가 없으면 Math.round 가 Long.MAX_VALUE 라는 "그럴듯한 정수"를 만든다.
        MediaMeta meta = UploadMediaProbeFfprobe.parse(
                output(List.of("width=1920", "height=1080"), List.of("duration=1e300")), VIDEO);

        // then
        assertThat(meta.durationMs()).isNull();
    }

    @Test
    @DisplayName("duration이_반올림해_0이_되는_미세값이면_길이를_null로_돌려준다")
    void neverEmitsZeroDuration() {
        // given: 0.0001초 → 0.1ms → 반올림 0. "길이 0"이 아니라 미상으로 둔다
        MediaMeta meta = UploadMediaProbeFfprobe.parse(
                output(List.of("width=1920", "height=1080"), List.of("duration=0.0001")), VIDEO);

        // then
        assertThat(meta.durationMs()).isNull();
    }

    @Test
    @DisplayName("STREAM_구획이_두_번_나오면_나중_값으로_덮어쓴다")
    void lastStreamSectionWins() {
        // given: 다중 스트림 컨테이너에서 -select_streams 가 무력화된 비정상 출력.
        //        현재 동작(관찰 확정)은 "key 단위 last-wins" 이며 앞 구획에만 있던 key 는 남는다.
        //        이 테스트는 그 동작을 고정해 향후 파서 변경의 드리프트를 잡는 것이 목적이다.
        List<String> lines = List.of(
                "[STREAM]", "width=1280", "[/STREAM]",
                "[STREAM]", "width=1920", "height=1080", "[/STREAM]");

        // when
        MediaMeta meta = UploadMediaProbeFfprobe.parse(lines, VIDEO);

        // then
        assertThat(meta.width()).isEqualTo(1920);
        assertThat(meta.height()).isEqualTo(1080);
    }

    @Test
    @DisplayName("명령_인자에_프로토콜_화이트리스트_pin과_i_옵션이_유지된다")
    void buildsHardenedCommandArguments() {
        // given: 두 인자는 제거해도 정상 파일의 측정 결과가 그대로라 동작 기반 테스트로는
        //        지워진 것을 알 수 없다(적대적 검증에서 둘 다 mutation 생존). 인자 구성을 직접 단언한다.
        UploadMediaProbeFfprobe probe = new UploadMediaProbeFfprobe("ffprobe", 1);

        // when
        List<String> command = probe.buildCommand(VIDEO);

        // then: SSRF 심층방어 pin (CWE-918)
        assertThat(command).containsSequence("-protocol_whitelist", "file,crypto,data");
        // 비트레이트를 stream·format 양쪽에서 요청한다 — show_entries 에서 빠지면 ffprobe 가 값을
        //   출력하지 않아 파서가 아무리 옳아도 BIT 이 영원히 null 이 된다(파싱 단위 테스트는 입력을
        //   직접 만들어 넣으므로 이 누락을 드러내지 못한다 — 인자 리스트를 직접 단언한다).
        assertThat(command).anySatisfy(arg -> {
            assertThat(arg).contains("stream=");
            assertThat(arg).contains(",bit_rate:format=");
            assertThat(arg).endsWith(",bit_rate");
        });
        // 경로는 위치 인자가 아니라 -i 의 옵션 값이어야 한다 (CWE-78 하드닝)
        assertThat(command).containsSequence("-i", VIDEO.toAbsolutePath().toString());
        assertThat(command.get(0)).isEqualTo("ffprobe");
    }

    @Test
    @DisplayName("duration이_1e308이거나_음수_포화값이면_길이를_null로_돌려준다")
    void neverEmitsSaturatedDuration() {
        // given: 1e308 은 그 자체는 유한이지만 ×1000 에서 Infinity 로 넘쳐 Math.round 가
        //        Long.MAX_VALUE 로 포화한다(음수는 Long.MIN_VALUE)
        MediaMeta positive = UploadMediaProbeFfprobe.parse(
                output(List.of("width=1920", "height=1080"), List.of("duration=1e308")), VIDEO);
        MediaMeta negative = UploadMediaProbeFfprobe.parse(
                output(List.of("width=1920", "height=1080"), List.of("duration=-1e308")), VIDEO);
        MediaMeta nan = UploadMediaProbeFfprobe.parse(
                output(List.of("width=1920", "height=1080"), List.of("duration=NaN")), VIDEO);

        // then
        assertThat(positive.durationMs()).isNull();
        assertThat(negative.durationMs()).isNull();
        assertThat(nan.durationMs()).isNull();
    }

    /**
     * 프로세스 실행 경로 회귀 가드 — 실증된 교착 결함(stderr 파이프 미배수)과 종료코드 미검사가
     * 재발하지 않음을 <b>실제 프로세스 실행으로</b> 검증한다.
     *
     * <p>가짜 ffprobe 셸 스크립트를 임시 디렉터리에 만들어 바이너리 경로로 주입한다(패키지-프라이빗
     * 타임아웃 주입 생성자 사용). 셸이 없는 Windows 에서는 건너뛴다.
     *
     * <p>모든 케이스를 {@code assertTimeoutPreemptively} 로 감싼다 — 가드가 무너지면 테스트가
     * <b>영원히 매달려</b> CI 를 멈추게 하므로, 매달림을 실패로 바꾸기 위한 장치다.
     */
    @Nested
    @DisplayName("프로세스 실행 — 교착·종료코드 회귀 가드")
    class ProcessExecution {

        /**
         * 테스트 전용 타임아웃 — 프로덕션 기본값(30초)을 좁혀 교착 시 이 값이 상한이 되게 한다.
         *
         * <p>⚠ 구 값은 1초였고 근거는 <i>"가짜 ffprobe 는 즉시 응답하므로 1초면 충분하다"</i> 였다.
         * <b>병렬 실행에서 그 전제가 깨진다.</b> 전체 회귀(다른 Gradle 테스트 워커와 동시 실행)에서만
         * 이 중첩 클래스가 2회 연속 실패했는데, 실패한 것은 전부 <b>빨리 끝나기를 기대하는</b> 케이스였고
         * 소요가 하나같이 1.01초대(= 타임아웃 벽)였다. 반대로 타임아웃 발동을 기대하는 케이스는 같은
         * 실행에서 정상 통과했다. 같은 클래스를 <b>격리 실행하면 3회 연속 전건 통과</b>하고 머신 부하
         * (load average 7.9~9.5)는 양쪽이 같았다 — 가르는 것은 부하가 아니라 워커 간 자원 경합이며,
         * 그 구간에서 가짜 스크립트가 1초 안에 응답하지 못한 것이다. 제품 결함이 아니라 하네스 취약성이다.
         *
         * <p>그래서 관측된 벽(1.01초)의 5배로 잡는다. 동시에 {@link #NO_HANG_LIMIT}(15초)의 1/3 이라
         * 교착 시에는 여전히 <b>이 타임아웃이 먼저</b> 발동한다 — 이 값을 매달림 상한 쪽으로 더 올리면
         * 그 상한에 먼저 걸려, 이 가드가 검증하려던 것(타임아웃 자체)을 못 잡게 된다.
         *
         * <p>대가: 타임아웃 발동을 기대하는 2건이 그만큼 느려진다(대기 1회 약 1.0초 → 5초,
         * 대기 4회 약 4.1초 → 20초). 되돌리기 전에 위 관측을 먼저 재현할 것.
         */
        private static final int PROBE_TIMEOUT_SEC = 5;
        /** 매달림을 "실패"로 바꾸기 위한 테스트측 상한. 정상 경로는 수백 ms 안에 끝난다. */
        private static final Duration NO_HANG_LIMIT = Duration.ofSeconds(15);

        /** 프로덕션 {@code MAX_OUTPUT_BYTES} 와 같은 값 — 절단 경계를 값 중간에 맞추기 위해 안다. */
        private static final int MAX_OUTPUT_BYTES = 64 * 1024;
        /** 바이트 초과 케이스 패딩 — 라인길이 512·라인수 200 이내라 바이트 상한만 트리거한다. */
        private static final int BYTE_PAD_LINE_LENGTH = 400;
        private static final int BYTE_PAD_LINE_COUNT = 180;
        /**
         * 경계 정밀 제어용 패딩 — {@code "[STREAM]\n"}(9) + {@code 159 × 412} + {@code "width=1920\n"}(11)
         * = 65,528 이라 바로 뒤 {@code "height=1080"} 의 8번째 문자에서 64KiB 경계가 걸린다.
         */
        private static final int BOUNDARY_PAD_LINE_LENGTH = 411;
        private static final int BOUNDARY_PAD_LINE_COUNT = 159;
        /** 라인수 상한(200) 초과분 — 바이트·라인길이는 이내라 라인수 상한만 트리거한다. */
        private static final int EXCESS_LINE_COUNT = 250;
        /** {@code "codec_name=" + 공백 + "h264"} 가 512자를 넘게 만드는 공백 수(11+500+4=515). */
        private static final int CODEC_PAD_LENGTH = 500;
        /** 누수를 누적시켜 관측하기 위한 반복 호출 횟수. */
        private static final int CLEANUP_PROBE_COUNT = 4;

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("ffprobe가_응답하지_않아도_타임아웃_내에_예외로_끝난다")
        void doesNotHangWhenChildNeverExits() throws IOException {
            // given: 인자를 무시하고 30초 자는 스크립트 — 구 구현은 readLine() 에서 무기한 대기했다
            assumeShellAvailable();
            Path script = writeScript("hang.sh", """
                    #!/bin/sh
                    sleep 30
                    """);
            UploadMediaProbeFfprobe probe = new UploadMediaProbeFfprobe(script.toString(), PROBE_TIMEOUT_SEC);

            // when / then: 타임아웃(PROBE_TIMEOUT_SEC) 후 예외 — 매달리면 15초에 실패로 잡힌다
            assertTimeoutPreemptively(NO_HANG_LIMIT, () ->
                    assertThatThrownBy(() -> probe.probe(VIDEO))
                            .isInstanceOf(CustomException.class));
        }

        @Test
        @DisplayName("stderr로_100KB를_쏟아도_교착없이_stdout을_정상_파싱한다")
        void doesNotHangWhenChildFloodsStderr() throws IOException {
            // given: 손상 mp4 재현 — ffprobe 는 -v error 로도 stderr 76KB(1,200여 줄)를 쏟는다(실측).
            //        파이프 버퍼(약 64KiB)를 넘기면 자식이 write 에서 막혀 stdout 을 닫지 못하고,
            //        stderr 를 읽지 않는 부모의 readLine() 과 함께 영구 교착한다.
            //        ★ 이것이 실증된 결함의 직접 회귀 가드다.
            assumeShellAvailable();
            Path script = writeScript("flood.sh", """
                    #!/bin/sh
                    LINE=$(printf '%1000s' '')
                    i=0
                    while [ "$i" -lt 150 ]; do
                      printf '%s\\n' "$LINE" >&2
                      i=$((i + 1))
                    done
                    printf '[STREAM]\\nwidth=1920\\nheight=1080\\ncodec_name=h264\\n'
                    printf 'r_frame_rate=25/1\\nnb_frames=250\\n[/STREAM]\\n'
                    printf '[FORMAT]\\nduration=10.000000\\n[/FORMAT]\\n'
                    """);
            UploadMediaProbeFfprobe probe = new UploadMediaProbeFfprobe(script.toString(), PROBE_TIMEOUT_SEC);

            // when
            MediaMeta meta = assertTimeoutPreemptively(NO_HANG_LIMIT, () -> probe.probe(VIDEO));

            // then: 교착 없이 정상 종료 + stdout 파싱 결과 유지
            assertThat(meta.width()).isEqualTo(1920);
            assertThat(meta.height()).isEqualTo(1080);
            assertThat(meta.codecName()).isEqualTo("h264");
            assertThat(meta.nbFrames()).isEqualTo(250L);
            assertThat(meta.durationMs()).isEqualTo(10_000L);
        }

        @Test
        @DisplayName("종료코드가_0이_아니면_부분_출력을_채택하지_않고_전량_미상을_돌려준다")
        void discardsPartialOutputOnNonZeroExit() throws IOException {
            // given: 손상 컨테이너 실측 재현 — 일부 stdout 을 흘리고 exit 1 로 끝난다.
            //        부분 값을 채택하면 "틀린 메타"가 확정 저장되고 실패는 조용히 사라진다(CWE-754).
            assumeShellAvailable();
            Path script = writeScript("fail.sh", """
                    #!/bin/sh
                    printf '[STREAM]\\nwidth=1920\\nheight=1080\\ncodec_name=h264\\n[/STREAM]\\n'
                    exit 1
                    """);
            UploadMediaProbeFfprobe probe = new UploadMediaProbeFfprobe(script.toString(), PROBE_TIMEOUT_SEC);

            // when
            MediaMeta meta = assertTimeoutPreemptively(NO_HANG_LIMIT, () -> probe.probe(VIDEO));

            // then
            assertThat(meta).isEqualTo(new MediaMeta(0, 0, null, null, null, null, null, null));
        }

        @Test
        @DisplayName("stdout이_비어도_예외없이_전량_미상을_돌려준다")
        void returnsUnknownWhenStdoutEmpty() throws IOException {
            // given: 정상 종료(exit 0)인데 출력이 없는 경우 — 비디오 스트림이 없는 파일 등
            assumeShellAvailable();
            Path script = writeScript("silent.sh", """
                    #!/bin/sh
                    exit 0
                    """);
            UploadMediaProbeFfprobe probe = new UploadMediaProbeFfprobe(script.toString(), PROBE_TIMEOUT_SEC);

            // when
            MediaMeta meta = assertTimeoutPreemptively(NO_HANG_LIMIT, () -> probe.probe(VIDEO));

            // then
            assertThat(meta).isEqualTo(new MediaMeta(0, 0, null, null, null, null, null, null));
        }

        // ============ 출력 절단 = 전량 미상 (fail-closed, 이슈 A) ============
        //
        // ⚠ 아래 4건의 입력값은 "어느 상한을 어떻게 넘는가"로 정밀하게 고른 것이다. 값을 임의로
        //    바꾸면 다른 상한이 먼저 걸려 그 상한의 mutation 검출력이 사라진다(예: 바이트 초과
        //    케이스의 패딩 라인을 512자보다 길게 만들면 라인길이 상한이 먼저 걸린다).

        @Test
        @DisplayName("출력_바이트가_상한을_넘으면_부분_파싱하지_않고_전량_미상을_돌려준다")
        void discardsOutputExceedingByteLimit() throws IOException {
            // given: 정상 STREAM → 64KiB 초과 패딩 → FORMAT. 구 구현은 앞부분만 채택해
            //        frmeCnt=250 · resl=1920x1080 을 확정 저장하고 길이만 비웠다(부분 채택).
            //        패딩은 라인수 200·라인길이 512 이내라 "바이트 상한"만 트리거한다.
            assumeShellAvailable();
            String payload = "[STREAM]\nwidth=1920\nheight=1080\ncodec_name=h264\n"
                    + "r_frame_rate=25/1\nnb_frames=250\n[/STREAM]\n"
                    + (" ".repeat(BYTE_PAD_LINE_LENGTH) + "\n").repeat(BYTE_PAD_LINE_COUNT)
                    + "[FORMAT]\nduration=10.000000\n[/FORMAT]\n";
            assertThat(payload.length()).isGreaterThan(MAX_OUTPUT_BYTES);
            UploadMediaProbeFfprobe probe = catProbe("bytes", payload);

            // when
            MediaMeta meta = assertTimeoutPreemptively(NO_HANG_LIMIT, () -> probe.probe(VIDEO));

            // then: exit != 0 과 동일한 fail-closed
            assertThat(meta).isEqualTo(UNKNOWN_META);
        }

        @Test
        @DisplayName("절단_경계가_값_중간에_걸려도_잘린_값을_채택하지_않는다")
        void discardsValueTruncatedMidToken() throws IOException {
            // given: 64KiB 경계가 정확히 "height=1080" 의 중간(height=1 직후)에 오도록 맞춘 입력.
            //        구 구현은 height=1 을 채택해 resl="1920x1" · vrtc=1 · asprtRt="1920:1" 이라는
            //        "형식상 완벽한 틀린 값"을 만들었다 — 하류 검증을 전부 통과하므로 정상값과
            //        구분할 수단이 없다. 이것이 절단을 실패로 취급하는 이유다.
            assumeShellAvailable();
            String payload = "[STREAM]\n"
                    + (" ".repeat(BOUNDARY_PAD_LINE_LENGTH) + "\n").repeat(BOUNDARY_PAD_LINE_COUNT)
                    + "width=1920\nheight=1080\n[/STREAM]\n[FORMAT]\nduration=10.000000\n[/FORMAT]\n";
            // 자기점검: 경계가 실제로 "height=1" 직후인지 확인(상수를 바꾸면 여기서 먼저 알려준다)
            assertThat(payload.indexOf("height=1080") + "height=1".length()).isEqualTo(MAX_OUTPUT_BYTES);
            UploadMediaProbeFfprobe probe = catProbe("boundary", payload);

            // when
            MediaMeta meta = assertTimeoutPreemptively(NO_HANG_LIMIT, () -> probe.probe(VIDEO));

            // then
            assertThat(meta.height()).isNotEqualTo(1);
            assertThat(meta).isEqualTo(UNKNOWN_META);
        }

        @Test
        @DisplayName("출력_라인수가_상한을_넘으면_전량_미상을_돌려준다")
        void discardsOutputExceedingLineLimit() throws IOException {
            // given: 라인 수만 초과(바이트·라인길이는 이내)
            assumeShellAvailable();
            String payload = "[STREAM]\nwidth=1920\nheight=1080\n[/STREAM]\n"
                    + "x=1\n".repeat(EXCESS_LINE_COUNT);
            UploadMediaProbeFfprobe probe = catProbe("lines", payload);

            // when
            MediaMeta meta = assertTimeoutPreemptively(NO_HANG_LIMIT, () -> probe.probe(VIDEO));

            // then
            assertThat(meta).isEqualTo(UNKNOWN_META);
        }

        @Test
        @DisplayName("라인_길이가_상한을_넘으면_잘라서_파싱하지_않고_전량_미상을_돌려준다")
        void discardsOutputExceedingLineLengthLimit() throws IOException {
            // given: codec_name= + 공백500 + h264 = 515자 > 512.
            //        구 구현은 512자에서 "잘라서 파싱"해 codec_name 값이 "h" 가 됐다 —
            //        InternalUploadMetaResolver 는 "코덱명은 절단하지 않고 버린다"고 못 박았는데,
            //        probe 가 먼저 잘라 주면 짧고 정상 형식이라 resolver 가 구분할 수단이 없다.
            assumeShellAvailable();
            String payload = "[STREAM]\ncodec_name=" + " ".repeat(CODEC_PAD_LENGTH) + "h264\n"
                    + "width=1920\nheight=1080\n[/STREAM]\n";
            UploadMediaProbeFfprobe probe = catProbe("linelength", payload);

            // when
            MediaMeta meta = assertTimeoutPreemptively(NO_HANG_LIMIT, () -> probe.probe(VIDEO));

            // then: 회귀하면 정확히 "h" 가 돌아온다
            assertThat(meta.codecName()).isNotEqualTo("h");
            assertThat(meta).isEqualTo(UNKNOWN_META);
        }

        // ============ 임시파일 정리 (이슈 C) ============
        //
        // stdout 을 임시파일로 받는 구조라 finally 의 삭제가 유일한 정리 경로다. 삭제를 빼면
        // 업로드 완료마다 파일이 쌓이는데(적대적 검증 실측: 4회 호출 → 4개 잔존) 측정 결과는
        // 그대로라 동작 기반 테스트로는 드러나지 않는다.

        @Test
        @DisplayName("정상_경로에서_stdout_임시파일이_남지_않는다")
        void leavesNoTempFileOnSuccess() throws IOException {
            assumeShellAvailable();
            UploadMediaProbeFfprobe probe = catProbe("cleanup-ok",
                    "[STREAM]\nwidth=1920\nheight=1080\n[/STREAM]\n");
            Set<String> before = probeTempFiles();

            // when: 여러 번 호출해 누수를 누적시킨다
            for (int i = 0; i < CLEANUP_PROBE_COUNT; i++) {
                assertTimeoutPreemptively(NO_HANG_LIMIT, () -> probe.probe(VIDEO));
            }

            // then
            assertThat(newTempFilesSince(before)).isEmpty();
        }

        @Test
        @DisplayName("타임아웃_경로에서도_stdout_임시파일이_남지_않는다")
        void leavesNoTempFileOnTimeout() throws IOException {
            // given: 예외로 빠져나가는 경로 — finally 가 없으면 여기서 확실히 샌다
            assumeShellAvailable();
            Path script = writeScript("cleanup-timeout.sh", """
                    #!/bin/sh
                    sleep 30
                    """);
            UploadMediaProbeFfprobe probe = new UploadMediaProbeFfprobe(script.toString(), PROBE_TIMEOUT_SEC);
            Set<String> before = probeTempFiles();

            // when
            for (int i = 0; i < CLEANUP_PROBE_COUNT; i++) {
                assertTimeoutPreemptively(NO_HANG_LIMIT, () ->
                        assertThatThrownBy(() -> probe.probe(VIDEO)).isInstanceOf(CustomException.class));
            }

            // then
            assertThat(newTempFilesSince(before)).isEmpty();
        }

        // ⚠ {@code process.getOutputStream().close()}(자식 stdin EOF + FD 회수)는 회귀 가드를 두지
        //    않았다 — 가짜 ffprobe 든 실제 ffprobe 든 stdin 을 읽지 않으므로 닫히지 않아도 관측 가능한
        //    동작 차이가 없고, FD 는 프로세스 종료 시 어차피 회수된다. "왜 이것만 가드가 없나"로
        //    되돌아오지 않도록 남긴다. 검증하려면 stdin 을 read 하는 자식이 필요한데, 그 자식은
        //    실제 ffprobe 의 동작이 아니라 테스트가 지어낸 전제라 가드로서 의미가 약하다.

        /** {@code cat} 으로 지정 payload 를 그대로 뱉는 가짜 ffprobe — 바이트 경계를 정밀 제어한다. */
        private UploadMediaProbeFfprobe catProbe(String name, String payload) throws IOException {
            Path payloadFile = tempDir.resolve(name + ".payload");
            Files.writeString(payloadFile, payload, StandardCharsets.UTF_8);
            Path script = writeScript(name + ".sh", "#!/bin/sh\ncat '" + payloadFile + "'\n");
            return new UploadMediaProbeFfprobe(script.toString(), PROBE_TIMEOUT_SEC);
        }

        private Set<String> probeTempFiles() throws IOException {
            Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir"));
            try (Stream<Path> files = Files.list(tmpRoot)) {
                return files.map(p -> p.getFileName().toString())
                        .filter(name -> name.startsWith("upload-ffprobe-"))
                        .collect(Collectors.toCollection(HashSet::new));
            }
        }

        private Set<String> newTempFilesSince(Set<String> before) throws IOException {
            Set<String> remaining = probeTempFiles();
            remaining.removeAll(before);
            return remaining;
        }

        private Path writeScript(String name, String body) throws IOException {
            Path script = tempDir.resolve(name);
            Files.writeString(script, body);
            assertThat(script.toFile().setExecutable(true, true)).isTrue();
            return script;
        }

        /** 셸 스크립트를 실행할 수 없는 환경(Windows)에서는 건너뛴다. */
        private void assumeShellAvailable() {
            assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win"),
                    "셸 스크립트 기반 가드라 Windows 에서는 실행하지 않는다");
        }
    }
}
