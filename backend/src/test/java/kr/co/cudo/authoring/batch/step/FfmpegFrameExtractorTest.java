package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4 — FfmpegFrameExtractor 단위 테스트.
 *
 * <p>V2 정책:
 * <ul>
 *   <li>frmTypeCd 컬럼 폐기. 원본/비식별 프레임을 별도 row 로 만들지 않고
 *       단일 row 의 FILE_PATH(원본) + SRC_BKUP_FILE_PATH(비식별) 컬럼에 저장.</li>
 *   <li>{@link FfmpegFrameExtractor#extractBoth(LsDataRaw, String)} 는 비식별 영상 경로를
 *       호출자(BatchOrchestrator)가 전달하면 동일 row 에 attach.</li>
 * </ul>
 */
class FfmpegFrameExtractorTest {

    @TempDir
    Path tmp;

    private LsDataSrcRepository srcRepository;
    private LsDataSrcHstryRepository hstryRepository;
    private FfmpegFrameExtractor.FrameWriter frameWriter;
    private SystemConfigService systemConfigService;
    private Path sourceVideo;
    private List<Long> recordedSeekMillis;

    @BeforeEach
    void setUp() throws IOException {
        srcRepository = mock(LsDataSrcRepository.class);
        hstryRepository = mock(LsDataSrcHstryRepository.class);
        systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);

        AtomicLong seq = new AtomicLong(0);
        when(srcRepository.save(any(LsDataSrc.class))).thenAnswer(inv -> {
            LsDataSrc s = inv.getArgument(0);
            setField(s, "srcSn", seq.incrementAndGet());
            return s;
        });
        when(hstryRepository.save(any(LsDataSrcHstry.class))).thenAnswer(inv -> inv.getArgument(0));

        recordedSeekMillis = new ArrayList<>();
        frameWriter = new FfmpegFrameExtractor.FrameWriter() {
            @Override
            public boolean sourceExists(Path sourceVideo) { return Files.exists(sourceVideo); }
            @Override
            public void writeFrame(Path sourceVideo, Path outputFrame, long seekMillis) throws IOException {
                if (outputFrame.getParent() != null && !Files.exists(outputFrame.getParent())) {
                    Files.createDirectories(outputFrame.getParent());
                }
                recordedSeekMillis.add(seekMillis);
                Files.write(outputFrame, ("frame-seek-" + seekMillis).getBytes());
            }
        };

        sourceVideo = tmp.resolve("clip.mp4");
        Files.write(sourceVideo, new byte[]{0, 0, 0});
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private FfmpegFrameExtractor newExtractor() {
        Path deidBase = tmp.resolve("deid");
        return new FfmpegFrameExtractor(srcRepository, hstryRepository, frameWriter,
                systemConfigService, tmp.toString(), deidBase.toString());
    }

    private LsDataRaw newRaw(int durationSec) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, sourceVideo.toString(), null, durationSec);
        setField(raw, "rawSn", 9001L);
        return raw;
    }

    // ============================================================
    // 기본 extract — RAW 프레임 추출
    // ============================================================

    @Test
    @DisplayName("1fps_설정_113초_영상은_113_RAW_프레임_row")
    void extractsOneFramePerSecondAtDefaultFps() {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        FfmpegFrameExtractor extractor = newExtractor();
        List<LsDataSrc> frames = extractor.extract(newRaw(113));

        assertThat(frames).hasSize(113);
        assertThat(frames.get(0).getFrameNo()).isZero();
        assertThat(frames.get(112).getFrameNo()).isEqualTo(112);
        // Phase 4: 단일 extract() 는 비식별 경로 미할당 (srcBkupFilePath == null)
        assertThat(frames).allMatch(f -> f.getSrcBkupFilePath() == null);
    }

    @Test
    @DisplayName("2fps_설정_60초_영상은_120프레임")
    void extractsTwoFramesPerSecond() {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(2);
        FfmpegFrameExtractor extractor = newExtractor();
        List<LsDataSrc> frames = extractor.extract(newRaw(60));

        assertThat(frames).hasSize(120);
    }

    @Test
    @DisplayName("durationSec_0_초과면_최소_1프레임_추출")
    void shortVideoYieldsAtLeastOneFrame() {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        FfmpegFrameExtractor extractor = newExtractor();
        List<LsDataSrc> frames = extractor.extract(newRaw(1));
        assertThat(frames).hasSize(1);
    }

    @Test
    @DisplayName("시스템_설정_조회_실패_시_기본_1fps_폴백")
    void fallsBackToDefaultFpsWhenConfigLookupFails() {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS)))
                .thenThrow(new RuntimeException("DB down"));
        FfmpegFrameExtractor extractor = newExtractor();
        List<LsDataSrc> frames = extractor.extract(newRaw(45));

        assertThat(frames).hasSize(45);
    }

    @Test
    @DisplayName("durationSec_0_이하면_INVALID_INPUT")
    void invalidDurationRejected() {
        FfmpegFrameExtractor extractor = newExtractor();
        assertThatThrownBy(() -> extractor.extract(newRaw(0)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("존재하지_않는_파일_경로면_INVALID_INPUT")
    void missingFileRejected() throws IOException {
        Files.delete(sourceVideo);
        FfmpegFrameExtractor extractor = newExtractor();
        assertThatThrownBy(() -> extractor.extract(newRaw(60)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("manifest_jsonl_파일이_생성됨")
    void manifestFileWritten() throws IOException {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        FfmpegFrameExtractor extractor = newExtractor();
        extractor.extract(newRaw(2));

        Path manifest = tmp.resolve("frames").resolve("9001").resolve("manifest.jsonl");
        assertThat(Files.exists(manifest)).isTrue();
        long lineCount = Files.readAllLines(manifest).size();
        // header 3 + key frames 2 = 5
        assertThat(lineCount).isEqualTo(5);
    }

    @Test
    @DisplayName("computeFrameCount_1fps_113sec_113frame")
    void computeFrameCount_1fps_113sec_113frame() {
        assertThat(FfmpegFrameExtractor.computeFrameCount(113, 1)).isEqualTo(113);
    }

    @Test
    @DisplayName("computeFrameCount_2fps_60sec_120frame")
    void computeFrameCount_2fps_60sec_120frame() {
        assertThat(FfmpegFrameExtractor.computeFrameCount(60, 2)).isEqualTo(120);
    }

    @Test
    @DisplayName("computeFrameCount_outputFps_30_상한_클램프")
    void computeFrameCount_outputFps_상한_클램프() {
        assertThat(FfmpegFrameExtractor.computeFrameCount(10, 31)).isEqualTo(10);
        assertThat(FfmpegFrameExtractor.computeFrameCount(10, 30)).isEqualTo(300);
    }

    @Test
    @DisplayName("computeFrameCount_outputFps_0_이하_기본1")
    void computeFrameCount_outputFps_0_이하_기본1() {
        assertThat(FfmpegFrameExtractor.computeFrameCount(10, 0)).isEqualTo(10);
        assertThat(FfmpegFrameExtractor.computeFrameCount(10, -5)).isEqualTo(10);
    }

    @Test
    @DisplayName("computeFrameCount_최소_1프레임_보장")
    void computeFrameCount_최소_1프레임_보장() {
        assertThat(FfmpegFrameExtractor.computeFrameCount(0, 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("1fps_113초_영상_seekMillis_는_i초_균등간격")
    void seekMillis_at_1fps_is_one_second_per_frame() {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        FfmpegFrameExtractor extractor = newExtractor();
        extractor.extract(newRaw(113));

        assertThat(recordedSeekMillis).hasSize(113);
        assertThat(recordedSeekMillis.get(0)).isEqualTo(0L);
        assertThat(recordedSeekMillis.get(2)).isEqualTo(2_000L);
        assertThat(recordedSeekMillis.get(112)).isEqualTo(112_000L);
    }

    @Test
    @DisplayName("2fps_seekMillis_는_500ms_간격")
    void seekMillis_at_2fps_is_500ms_per_frame() {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(2);
        FfmpegFrameExtractor extractor = newExtractor();
        extractor.extract(newRaw(3));

        // 3초 · 2fps = 6 프레임, seek: 0, 500, 1000, 1500, 2000, 2500 (ms)
        assertThat(recordedSeekMillis).containsExactly(0L, 500L, 1000L, 1500L, 2000L, 2500L);
    }

    // ============================================================
    // Phase 4: extractBoth — 동일 row 의 srcBkupFilePath 에 비식별 프레임 attach
    // ============================================================

    @Test
    @DisplayName("Phase4_extractBoth_비식별영상_경로_있을_때_RAW_프레임_+_srcBkupFilePath_attach")
    void extractBoth_attachesDeidPathToSameRow() throws IOException {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        Path deidVideo = tmp.resolve("clip-deid.mp4");
        Files.write(deidVideo, new byte[]{0, 0, 0});

        FfmpegFrameExtractor extractor = newExtractor();
        List<LsDataSrc> frames = extractor.extractBoth(newRaw(3), deidVideo.toString());

        // 1fps · 3초 = 3 프레임 row — 추가 row 없음 (frmTypeCd 폐기, 동일 row 의 srcBkupFilePath 사용)
        assertThat(frames).hasSize(3);
        assertThat(frames).allMatch(f -> f.getSrcBkupFilePath() != null);
        // 원본 FILE_PATH 는 raw base 하위
        assertThat(frames).allMatch(f -> f.getFilePath().contains("frames"));
    }

    @Test
    @DisplayName("Phase4_extractBoth_deidVideoPath_NULL_일_때_RAW_프레임_만_attach_없음_V1_호환")
    void extractBoth_nullDeidPath_rawOnly() {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        FfmpegFrameExtractor extractor = newExtractor();
        List<LsDataSrc> frames = extractor.extractBoth(newRaw(3), null);

        assertThat(frames).hasSize(3);
        assertThat(frames).allMatch(f -> f.getSrcBkupFilePath() == null);
    }

    @Test
    @DisplayName("Phase4_extractBoth_비식별_영상_파일_없으면_경고_로그_+_RAW_만_graceful_fallback")
    void extractBoth_deidVideoMissing_rawOnly() {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        FfmpegFrameExtractor extractor = newExtractor();
        Path missingDeidVideo = tmp.resolve("missing-deid.mp4");

        List<LsDataSrc> frames = extractor.extractBoth(newRaw(3), missingDeidVideo.toString());

        // RAW 만 추출됨 — DEID 프레임 미생성, 예외 미발생 (graceful fallback)
        assertThat(frames).hasSize(3);
        assertThat(frames).allMatch(f -> f.getSrcBkupFilePath() == null);
    }

    // ============================================================
    // Phase 4 V2.0: extractByMarks — 마킹 위치 기반 프레임 추출
    // ============================================================

    @Test
    @DisplayName("V2_마킹_위치_기반_프레임_추출_정확한_frameIndex")
    void extractByMarks_extractsAtMarkPositions() {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        FfmpegFrameExtractor extractor = newExtractor();

        // 마킹: frameIndex 0, 150, 300 (30fps 기준 0초, 5초, 10초)
        List<MarkItem> marks = List.of(
                new MarkItem(0, "00:00"),
                new MarkItem(150, "00:05"),
                new MarkItem(300, "00:10")
        );
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), null, marks);

        assertThat(frames).hasSize(3);
        // frameNo 는 순차 인덱스 (0, 1, 2)
        assertThat(frames.get(0).getFrameNo()).isZero();
        assertThat(frames.get(1).getFrameNo()).isEqualTo(1);
        assertThat(frames.get(2).getFrameNo()).isEqualTo(2);
        // seekMillis: frameIndex * 1000 / 30 → 0ms, 5000ms, 10000ms
        assertThat(recordedSeekMillis).containsExactly(0L, 5000L, 10000L);
    }

    @Test
    @DisplayName("V2_마킹_기반_추출_비식별_영상_포함_2벌")
    void extractByMarks_withDeidVideo_attachesBothPaths() throws IOException {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        Path deidVideo = tmp.resolve("clip-deid.mp4");
        Files.write(deidVideo, new byte[]{0, 0, 0});

        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(
                new MarkItem(0, "00:00"),
                new MarkItem(150, "00:05")
        );
        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), deidVideo.toString(), marks);

        assertThat(frames).hasSize(2);
        // 원본 + 비식별 2벌 — 같은 row 의 srcBkupFilePath 에 비식별 경로
        assertThat(frames).allMatch(f -> f.getSrcBkupFilePath() != null);
        assertThat(frames).allMatch(f -> f.getFilePath().contains("frames"));
        // raw 2회 + deid 2회 = 총 4회 writeFrame 호출
        assertThat(recordedSeekMillis).hasSize(4);
    }

    @Test
    @DisplayName("V2_마킹_비식별_영상_없으면_RAW_만_graceful")
    void extractByMarks_noDeidVideo_rawOnly() {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(new MarkItem(0, "00:00"));

        List<LsDataSrc> frames = extractor.extractByMarks(newRaw(60), null, marks);

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).getSrcBkupFilePath()).isNull();
    }

    @Test
    @DisplayName("V2_마킹_빈_배열_시_INVALID_INPUT")
    void extractByMarks_emptyMarks_rejected() {
        FfmpegFrameExtractor extractor = newExtractor();

        assertThatThrownBy(() -> extractor.extractByMarks(newRaw(60), null, List.of()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("V2_마킹_null_시_INVALID_INPUT")
    void extractByMarks_nullMarks_rejected() {
        FfmpegFrameExtractor extractor = newExtractor();

        assertThatThrownBy(() -> extractor.extractByMarks(newRaw(60), null, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("V2_마킹_기반_추출_manifest_jsonl_생성")
    void extractByMarks_writesManifest() throws IOException {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        FfmpegFrameExtractor extractor = newExtractor();
        List<MarkItem> marks = List.of(
                new MarkItem(0, "00:00"),
                new MarkItem(150, "00:05")
        );

        extractor.extractByMarks(newRaw(60), null, marks);

        Path manifest = tmp.resolve("frames").resolve("9001").resolve("manifest.jsonl");
        assertThat(Files.exists(manifest)).isTrue();
        long lineCount = Files.readAllLines(manifest).size();
        // header 3 + key frames 2 = 5
        assertThat(lineCount).isEqualTo(5);
    }
}
