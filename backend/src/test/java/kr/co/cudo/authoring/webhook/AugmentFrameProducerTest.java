package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.webhook.service.AugmentExtractPlan;
import kr.co.cudo.authoring.webhook.service.AugmentFrameProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase B(파일 I/O) 단위 테스트 — 커넥션-점유 분리 리팩터.
 *
 * <p>ffmpeg 재추출을 파일로만 산출하고, 실패 시 all-or-nothing + cleanup 을 검증한다. 또한 <b>리포지토리
 * 주입 0</b>(DB 커넥션 미보유)을 구조(필드 타입)로 보증한다 — 이 계약이 깨지면 리팩터 목적이 무너진다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AugmentFrameProducerTest {

    @Mock FfmpegFrameExtractor.FrameWriter frameWriter;

    @TempDir Path tempDir;

    private AugmentFrameProducer producer;

    @BeforeEach
    void setup() {
        producer = new AugmentFrameProducer(frameWriter);
        ReflectionTestUtils.setField(producer, "storageRawPath", tempDir.toString());
    }

    private AugmentExtractPlan plan(long newRawSn, Path source, List<AugmentExtractPlan.FrameSpec> frames) {
        Path framesDir = tempDir.resolve("frames/raw/" + newRawSn);
        return new AugmentExtractPlan(newRawSn, 100L, 20L, "rev1", source, framesDir, frames);
    }

    private AugmentExtractPlan.FrameSpec spec(long frameNo, long videoFrameNo, Path dst) {
        return new AugmentExtractPlan.FrameSpec(600L + frameNo, frameNo, videoFrameNo, LocalDateTime.now(), dst);
    }

    @Test
    @DisplayName("소스존재시_프레임별_videoFrameNo로_frameExact_추출한다")
    void produceWritesEachFrameByVideoFrameNo() throws IOException {
        Path source = tempDir.resolve("aug.mp4");
        Path framesDir = tempDir.resolve("frames/raw/9001");
        AugmentExtractPlan p = plan(9001L, source, List.of(
                spec(0, 100L, framesDir.resolve("frame-0.jpg")),
                spec(1, 250L, framesDir.resolve("frame-1.jpg"))));
        when(frameWriter.sourceExists(source)).thenReturn(true);

        producer.produce(p);

        // frame-exact — 디코더 프레임 번호(videoFrameNo)로 추출.
        verify(frameWriter).writeFrameByNumber(eq(source), eq(framesDir.resolve("frame-0.jpg")), eq(100));
        verify(frameWriter).writeFrameByNumber(eq(source), eq(framesDir.resolve("frame-1.jpg")), eq(250));
    }

    @Test
    @DisplayName("증강_소스파일_부재시_INVALID_INPUT")
    void sourceNotExist_rejected() throws IOException {
        Path source = tempDir.resolve("no-such.mp4");
        AugmentExtractPlan p = plan(9002L, source, List.of(
                spec(0, 0L, tempDir.resolve("frames/raw/9002/frame-0.jpg"))));
        when(frameWriter.sourceExists(source)).thenReturn(false);

        assertThatThrownBy(() -> producer.produce(p))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        // 소스 부재면 프레임 추출을 시도하지 않는다.
        verify(frameWriter, org.mockito.Mockito.never()).writeFrameByNumber(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("프레임추출_IOException시_all_or_nothing_INTERNAL_ERROR")
    void ioException_throwsInternalError() throws IOException {
        Path source = tempDir.resolve("aug.mp4");
        Path framesDir = tempDir.resolve("frames/raw/9003");
        AugmentExtractPlan p = plan(9003L, source, List.of(
                spec(0, 0L, framesDir.resolve("frame-0.jpg"))));
        when(frameWriter.sourceExists(source)).thenReturn(true);
        doThrow(new IOException("ffmpeg 실패")).when(frameWriter)
                .writeFrameByNumber(any(), any(), org.mockito.ArgumentMatchers.anyInt());

        assertThatThrownBy(() -> producer.produce(p))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("cleanup은_프레임디렉토리를_삭제하고_잔존없으면_true")
    void cleanupDeletesFramesDir() throws IOException {
        Path framesDir = tempDir.resolve("frames/raw/9004");
        Files.createDirectories(framesDir);
        Files.writeString(framesDir.resolve("frame-0.jpg"), "dummy");
        assertThat(Files.exists(framesDir)).isTrue();

        boolean clean = producer.cleanup(9004L);

        assertThat(clean).isTrue();
        assertThat(Files.exists(framesDir)).isFalse();
    }

    @Test
    @DisplayName("cleanup은_디렉토리가_없어도_true_멱등")
    void cleanupIdempotentWhenAbsent() {
        assertThat(producer.cleanup(9999L)).isTrue();
    }

    @Test
    @DisplayName("Phase_B는_리포지토리를_주입하지_않는다_무커넥션_구조보증")
    void hasNoRepositoryDependency() {
        for (Field f : AugmentFrameProducer.class.getDeclaredFields()) {
            String typeName = f.getType().getName();
            assertThat(typeName)
                    .as("Phase B 는 DB 커넥션을 잡지 않도록 리포지토리 의존이 0 이어야 한다: %s", f.getName())
                    .doesNotContain("Repository");
        }
    }
}
