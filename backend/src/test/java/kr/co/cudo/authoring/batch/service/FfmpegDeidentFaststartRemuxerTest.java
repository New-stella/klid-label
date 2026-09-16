package kr.co.cudo.authoring.batch.service;

import io.micrometer.core.instrument.MeterRegistry;
import kr.co.cudo.authoring.batch.service.DeidentFaststartInspector.Layout;
import kr.co.cudo.authoring.batch.service.DeidentFaststartService.Outcome;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.support.TestVideoFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ffmpeg 실행기 — 고정 인자 구성, 비정상 종료·대기 상한, 그리고 ffmpeg 가 있는 환경에서의 실제 무손실 재배치.
 *
 * <p>실제 재배치 시험은 {@code ffmpeg}·{@code ffprobe} 가 실행 가능할 때만 돈다(없으면 건너뛴다).
 *
 * @design ADR-072
 * @design AC-1063
 */
class FfmpegDeidentFaststartRemuxerTest {

    private static final long RAW_SN = 77L;

    @TempDir
    Path tmp;

    @Test
    @DisplayName("명령은_재인코딩_없는_고정_인자다")
    void commandIsFixedStreamCopy() {
        FfmpegDeidentFaststartRemuxer remuxer = new FfmpegDeidentFaststartRemuxer("ffmpeg", 5);

        List<String> cmd = remuxer.command(Path.of("/a/in.mp4"), Path.of("/b/out.mp4"),
                DeidentFaststartRemuxer.Container.MP4);

        assertThat(cmd).containsExactly("ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error", "-n",
                "-i", "/a/in.mp4", "-map", "0", "-c", "copy", "-movflags", "+faststart",
                "-f", "mp4", "/b/out.mp4");
    }

    @Test
    @DisplayName("비정상_종료는_예외다")
    void nonZeroExitThrows() throws Exception {
        Path fake = script("exit 3");
        FfmpegDeidentFaststartRemuxer remuxer = new FfmpegDeidentFaststartRemuxer(fake.toString(), 5);

        assertThatThrownBy(() -> remuxer.remux(tmp.resolve("in.mp4"), tmp.resolve("out.mp4"),
                DeidentFaststartRemuxer.Container.MP4)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("대기_상한을_넘기면_강제종료하고_예외다")
    void timeoutThrows() throws Exception {
        Path fake = script("sleep 30");
        FfmpegDeidentFaststartRemuxer remuxer = new FfmpegDeidentFaststartRemuxer(fake.toString(), 1);

        long start = System.nanoTime();
        assertThatThrownBy(() -> remuxer.remux(tmp.resolve("in.mp4"), tmp.resolve("out.mp4"),
                DeidentFaststartRemuxer.Container.MP4)).isInstanceOf(IOException.class);
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(20);
    }

    @Test
    @DisplayName("대기_상한_설정이_0_이하면_기본값을_쓴다")
    void invalidTimeoutFallsBack() {
        assertThatThrownBy(() -> new FfmpegDeidentFaststartRemuxer("definitely-missing-ffmpeg-bin", 0)
                .remux(tmp.resolve("in.mp4"), tmp.resolve("out.mp4"), DeidentFaststartRemuxer.Container.MOV))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("실행기가_실패해_반쯤_쓴_출력을_남겨도_서비스가_정리하고_원본을_지킨다")
    void failingBinaryLeavesNoWorkFile() throws Exception {
        // 마지막 인자(출력 경로)에 일부를 쓰고 실패하는 가짜 ffmpeg.
        Path fake = script("for a in \"$@\"; do last=\"$a\"; done\nprintf 'partial' > \"$last\"\nexit 1");
        Fixture fx = fixture();
        byte[] before = Files.readAllBytes(fx.artifact());

        Outcome outcome = fx.service(new FfmpegDeidentFaststartRemuxer(fake.toString(), 5))
                .relocate(RAW_SN, fx.raw().toString(), fx.artifact().toString());

        assertThat(outcome).isEqualTo(Outcome.FAILED);
        assertThat(Files.readAllBytes(fx.artifact())).isEqualTo(before);
        assertThat(listNames(fx.workDir())).isEmpty();
        assertThat(listNames(fx.deidDir())).containsExactly("clip-mask.mp4");
    }

    @Test
    @DisplayName("실제_ffmpeg로_재배치하면_인덱스가_앞이고_경로_길이_해상도_코덱이_같다")
    void realFfmpegRelocationIsLossless() throws Exception {
        assumeTrue(runnable("ffmpeg") && runnable("ffprobe"), "ffmpeg/ffprobe 미설치 — 건너뜀");
        Fixture fx = fixture();
        assertThat(DeidentFaststartInspector.inspect(fx.artifact())).isEqualTo(Layout.MOOV_AT_END);
        String probeBefore = probe(fx.artifact());

        Outcome outcome = fx.service(new FfmpegDeidentFaststartRemuxer("ffmpeg", 60))
                .relocate(RAW_SN, fx.raw().toString(), fx.artifact().toString());

        assertThat(outcome).isEqualTo(Outcome.RELOCATED);
        assertThat(DeidentFaststartInspector.inspect(fx.artifact())).isEqualTo(Layout.FASTSTART);
        assertThat(listNames(fx.deidDir())).containsExactly("clip-mask.mp4");
        assertThat(listNames(fx.workDir())).isEmpty();
        assertThat(probe(fx.artifact())).isNotBlank().isEqualTo(probeBefore);

        // 두 번째 실행은 멱등 — 이미 앞이라 아무것도 하지 않는다.
        assertThat(fx.service(new FfmpegDeidentFaststartRemuxer("ffmpeg", 60))
                .relocate(RAW_SN, fx.raw().toString(), fx.artifact().toString()))
                .isEqualTo(Outcome.ALREADY_FASTSTART);
    }

    // ── helpers ──

    private record Fixture(Path raw, Path deidDir, Path artifact, VideoArtifactRootResolver resolver) {

        Path workDir() {
            return deidDir.getParent().resolve(DeidentFaststartService.WORK_DIR_NAME);
        }

        @SuppressWarnings("unchecked")
        DeidentFaststartService service(DeidentFaststartRemuxer remuxer) {
            return new DeidentFaststartService(resolver, remuxer, mock(StreamMetaCacheEvictor.class),
                    (ObjectProvider<MeterRegistry>) mock(ObjectProvider.class), true, Runnable::run);
        }
    }

    private Fixture fixture() throws IOException {
        Path nas = Files.createDirectories(tmp.resolve("nas"));
        Path raw = Files.writeString(nas.resolve("clip.mp4"), "raw");
        Path deidDir = Files.createDirectories(nas.resolve(String.valueOf(RAW_SN)).resolve("deid"));
        Path artifact = TestVideoFixtures.writeTinyMp4(deidDir.resolve("clip-mask.mp4"));
        VideoArtifactRootResolver resolver = mock(VideoArtifactRootResolver.class);
        when(resolver.readableDeidVideoDirs(RAW_SN, raw.toString())).thenReturn(List.of(deidDir));
        return new Fixture(raw, deidDir, artifact, resolver);
    }

    private Path script(String body) throws IOException {
        Path f = tmp.resolve("fake-ffmpeg-" + System.nanoTime() + ".sh");
        Files.writeString(f, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwx------"));
        return f;
    }

    private static List<String> listNames(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).toList();
        }
    }

    private static boolean runnable(String bin) {
        try {
            Process p = new ProcessBuilder(bin, "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** 길이·해상도·코덱 — 스트림 복사면 전후가 같아야 한다. */
    private static String probe(Path file) throws Exception {
        Process p = new ProcessBuilder("ffprobe", "-v", "error",
                "-show_entries", "format=duration:stream=codec_type,codec_name,width,height,duration",
                "-of", "default=noprint_wrappers=1", file.toString())
                .redirectErrorStream(true)
                .start();
        byte[] out = p.getInputStream().readAllBytes();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
        return new String(out, StandardCharsets.UTF_8).trim();
    }
}
