package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
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
        // 기본 1 fps — 기존 케이스(60초 미만 1프레임, 300초 5프레임 등)와 호환되지 않으므로
        // 개별 테스트에서 stubbing 을 덮어쓰거나 1 fps 기준으로 가정값을 재설정한다.
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
        // Phase 2: deidentified-path 별도 base 디렉토리 주입 — extractBoth 가 DEID 프레임을 별도 base 에 작성
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

    /** Phase 2: deidFilePath 가 설정된 raw (영상 단위 비식별 영상이 별도 보관됨) */
    private LsDataRaw newRawWithDeidVideo(int durationSec, Path deidVideoPath) {
        LsDataRaw raw = newRaw(durationSec);
        setField(raw, "deidFilePath", deidVideoPath.toString());
        return raw;
    }

    @Test
    @DisplayName("1fps_설정_113초_영상은_113프레임_FRM_TYPE_CD_RAW")
    void extractsOneFramePerSecondAtDefaultFps() {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        FfmpegFrameExtractor extractor = newExtractor();
        List<LsDataSrc> frames = extractor.extract(newRaw(113));

        assertThat(frames).hasSize(113);
        assertThat(frames.get(0).getFrameNo()).isZero();
        assertThat(frames.get(112).getFrameNo()).isEqualTo(112);
        // Phase 2: 단일 extract() 는 모든 프레임이 RAW 타입
        assertThat(frames).allMatch(f -> LsDataSrc.FRM_TYPE_RAW.equals(f.getFrmTypeCd()));
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

        // 1 fps 폴백 → 45 frames
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
        // 짧은 duration 으로 manifest 라인 수를 예측 가능하게 유지 (1 fps · 2 sec → 2 frames)
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
        // 31 fps 는 범위 밖 → 기본 1 fps 로 폴백 → 10 sec * 1 fps = 10
        assertThat(FfmpegFrameExtractor.computeFrameCount(10, 31)).isEqualTo(10);
        // 정확히 상한 30 fps 는 그대로 사용 → 10 sec * 30 fps = 300
        assertThat(FfmpegFrameExtractor.computeFrameCount(10, 30)).isEqualTo(300);
    }

    @Test
    @DisplayName("computeFrameCount_outputFps_0_이하_기본1")
    void computeFrameCount_outputFps_0_이하_기본1() {
        // 0 / 음수는 범위 밖 → 1 fps 폴백
        assertThat(FfmpegFrameExtractor.computeFrameCount(10, 0)).isEqualTo(10);
        assertThat(FfmpegFrameExtractor.computeFrameCount(10, -5)).isEqualTo(10);
    }

    @Test
    @DisplayName("computeFrameCount_최소_1프레임_보장")
    void computeFrameCount_최소_1프레임_보장() {
        // durationSec=0 이라도 최소 1프레임 (extract() 진입 단계 가드 별개로 메서드 자체 보장)
        assertThat(FfmpegFrameExtractor.computeFrameCount(0, 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("1fps_113초_영상_seekMillis_는_i초_균등간격")
    void seekMillis_at_1fps_is_one_second_per_frame() {
        // 단위 미스매치 회귀 방지: 1fps · 113초 → frameIndex=i 의 seek 위치가 i*1000ms 인지 확인.
        // 옛 버그: frameIndex * 60 (초) 로 시크 → 검정 프레임 출력.
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
    // Phase 2: extractBoth — 원본/비식별 영상 2벌 추출
    // ============================================================

    @Test
    @DisplayName("Phase2_extractBoth_deidFilePath_있을_때_원본_+_비식별_2벌_저장_FRM_TYPE_CD_RAW_DEID")
    void extractBoth_savesRawAndDeidFrames() throws IOException {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        // 비식별 영상 파일 작성
        Path deidVideo = tmp.resolve("clip-deid.mp4");
        Files.write(deidVideo, new byte[]{0, 0, 0});

        FfmpegFrameExtractor extractor = newExtractor();
        List<LsDataSrc> frames = extractor.extractBoth(newRawWithDeidVideo(3, deidVideo));

        // 1fps · 3초 = RAW 3 프레임 + DEID 3 프레임 = 6 row
        assertThat(frames).hasSize(6);
        long rawCount = frames.stream().filter(f -> LsDataSrc.FRM_TYPE_RAW.equals(f.getFrmTypeCd())).count();
        long deidCount = frames.stream().filter(f -> LsDataSrc.FRM_TYPE_DEID.equals(f.getFrmTypeCd())).count();
        assertThat(rawCount).isEqualTo(3);
        assertThat(deidCount).isEqualTo(3);
    }

    @Test
    @DisplayName("Phase2_extractBoth_deidFilePath_NULL_일_때_원본만_추출_V1_호환")
    void extractBoth_nullDeidPath_rawOnly() {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        FfmpegFrameExtractor extractor = newExtractor();
        // deidFilePath 가 null 인 raw (V1 호환)
        List<LsDataSrc> frames = extractor.extractBoth(newRaw(3));

        assertThat(frames).hasSize(3);
        assertThat(frames).allMatch(f -> LsDataSrc.FRM_TYPE_RAW.equals(f.getFrmTypeCd()));
    }

    @Test
    @DisplayName("Phase2_extractBoth_비식별_영상_파일_없으면_경고_로그_+_RAW_만")
    void extractBoth_deidVideoMissing_rawOnly() {
        when(systemConfigService.getInt(eq(ConfigKeys.FFMPEG_OUTPUT_FPS))).thenReturn(1);
        FfmpegFrameExtractor extractor = newExtractor();
        // deidFilePath 가 설정되어 있으나 실제 파일은 존재하지 않음
        Path missingDeidVideo = tmp.resolve("missing-deid.mp4");
        LsDataRaw raw = newRawWithDeidVideo(3, missingDeidVideo);

        List<LsDataSrc> frames = extractor.extractBoth(raw);

        // RAW 만 추출됨 — DEID 프레임 미생성, 예외 미발생 (graceful fallback)
        assertThat(frames).hasSize(3);
        assertThat(frames).allMatch(f -> LsDataSrc.FRM_TYPE_RAW.equals(f.getFrmTypeCd()));
    }
}
