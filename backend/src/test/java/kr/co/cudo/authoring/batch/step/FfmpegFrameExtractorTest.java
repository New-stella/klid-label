package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FfmpegFrameExtractorTest {

    @TempDir
    Path tmp;

    private LsDataSrcRepository srcRepository;
    private LsDataSrcHstryRepository hstryRepository;
    private FfmpegFrameExtractor.FrameWriter frameWriter;
    private Path sourceVideo;

    @BeforeEach
    void setUp() throws IOException {
        srcRepository = mock(LsDataSrcRepository.class);
        hstryRepository = mock(LsDataSrcHstryRepository.class);

        AtomicLong seq = new AtomicLong(0);
        when(srcRepository.save(any(LsDataSrc.class))).thenAnswer(inv -> {
            LsDataSrc s = inv.getArgument(0);
            setField(s, "srcSn", seq.incrementAndGet());
            return s;
        });
        when(hstryRepository.save(any(LsDataSrcHstry.class))).thenAnswer(inv -> inv.getArgument(0));

        frameWriter = new FfmpegFrameExtractor.FrameWriter() {
            @Override
            public boolean sourceExists(Path sourceVideo) { return Files.exists(sourceVideo); }
            @Override
            public void writeFrame(Path sourceVideo, Path outputFrame, int frameIndex) throws IOException {
                if (outputFrame.getParent() != null && !Files.exists(outputFrame.getParent())) {
                    Files.createDirectories(outputFrame.getParent());
                }
                Files.write(outputFrame, ("frame-" + frameIndex).getBytes());
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
        return new FfmpegFrameExtractor(srcRepository, hstryRepository, frameWriter, tmp.toString());
    }

    private LsDataRaw newRaw(int durationSec) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, sourceVideo.toString(), null, durationSec);
        setField(raw, "rawSn", 9001L);
        return raw;
    }

    @Test
    @DisplayName("1분당_1프레임_추출_300초_영상은_5프레임")
    void extractsOneFramePerMinute() {
        FfmpegFrameExtractor extractor = newExtractor();
        List<LsDataSrc> frames = extractor.extract(newRaw(300));

        assertThat(frames).hasSize(5);
        assertThat(frames.get(0).getFrameNo()).isZero();
        assertThat(frames.get(4).getFrameNo()).isEqualTo(4);
    }

    @Test
    @DisplayName("60초_미만_영상도_최소_1프레임_추출")
    void shortVideoYieldsOneFrame() {
        FfmpegFrameExtractor extractor = newExtractor();
        List<LsDataSrc> frames = extractor.extract(newRaw(30));
        assertThat(frames).hasSize(1);
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
        FfmpegFrameExtractor extractor = newExtractor();
        extractor.extract(newRaw(120));

        Path manifest = tmp.resolve("frames").resolve("9001").resolve("manifest.jsonl");
        assertThat(Files.exists(manifest)).isTrue();
        long lineCount = Files.readAllLines(manifest).size();
        // header 3 + key frames 2 = 5
        assertThat(lineCount).isEqualTo(5);
    }

    @Test
    @DisplayName("computeFrameCount_경계값")
    void computeFrameCountBoundary() {
        assertThat(FfmpegFrameExtractor.computeFrameCount(0)).isEqualTo(1);
        assertThat(FfmpegFrameExtractor.computeFrameCount(59)).isEqualTo(1);
        assertThat(FfmpegFrameExtractor.computeFrameCount(60)).isEqualTo(1);
        assertThat(FfmpegFrameExtractor.computeFrameCount(61)).isEqualTo(1);
        assertThat(FfmpegFrameExtractor.computeFrameCount(120)).isEqualTo(2);
    }
}
