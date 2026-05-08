package kr.co.cudo.authoring.batch.step;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ffmpeg 바이너리 기반 FrameWriter — local 프로파일에서도 실제 추출 수행.
 * <p>
 * frameIndex N → 영상에서 N*60초 지점의 프레임을 추출. 시크 실패 시 0초로 fallback.
 * <p>
 * 보안:
 * - Command Injection (CWE-78): ProcessBuilder 리스트 방식으로 인자를 분리 전달.
 *   사용자 입력으로 문자열 조합하지 않음.
 */
@Slf4j
@Profile("!test")
@Component
public class BrampFfmpegFrameWriter implements FfmpegFrameExtractor.FrameWriter {

    private final String binary;

    public BrampFfmpegFrameWriter(@Value("${authoring.ffmpeg.binary:ffmpeg}") String binary) {
        this.binary = binary;
    }

    @Override
    public boolean sourceExists(Path sourceVideo) {
        return sourceVideo != null && Files.exists(sourceVideo);
    }

    @Override
    public void writeFrame(Path sourceVideo, Path outputFrame, int frameIndex) throws IOException {
        if (outputFrame.getParent() != null && !Files.exists(outputFrame.getParent())) {
            Files.createDirectories(outputFrame.getParent());
        }

        int seekSeconds = frameIndex * 60;
        boolean extracted = runFfmpeg(sourceVideo, outputFrame, seekSeconds);

        if (!extracted && seekSeconds > 0) {
            log.warn("[Batch][FrameWriter] seek={}s failed — retry at 0s", seekSeconds);
            extracted = runFfmpeg(sourceVideo, outputFrame, 0);
        }

        if (!extracted || !Files.exists(outputFrame) || Files.size(outputFrame) < 100) {
            throw new IOException("프레임 추출 실패: frameIndex=" + frameIndex + " src=" + sourceVideo.getFileName());
        }
        log.info("[Batch][FrameWriter] extracted frame index={} size={}B path={}",
                frameIndex, Files.size(outputFrame), outputFrame.getFileName());
    }

    private boolean runFfmpeg(Path sourceVideo, Path outputFrame, int seekSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(List.of(
                    binary, "-y",
                    "-ss", String.valueOf(seekSeconds),
                    "-i", sourceVideo.toAbsolutePath().toString(),
                    "-vframes", "1",
                    "-q:v", "2",
                    outputFrame.toAbsolutePath().toString()
            ));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            return exitCode == 0 && Files.exists(outputFrame) && Files.size(outputFrame) >= 100;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("[Batch][FrameWriter] ffmpeg error: {}", e.getMessage());
            return false;
        }
    }
}
