package kr.co.cudo.authoring.batch.step;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * ffmpeg 바이너리 기반 FrameWriter — local 프로파일에서도 실제 추출 수행.
 * <p>
 * 호출자(FfmpegFrameExtractor)가 outputFps 와 frameIndex 로부터 계산한 seekMillis
 * (영상 시작점부터의 시크 위치, 밀리초)를 그대로 받아 ffmpeg {@code -ss} 옵션에
 * 초 단위(소수점 3자리) 로 전달한다. 시크 실패 시 0초로 fallback.
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
    public void writeFrame(Path sourceVideo, Path outputFrame, long seekMillis) throws IOException {
        if (outputFrame.getParent() != null && !Files.exists(outputFrame.getParent())) {
            Files.createDirectories(outputFrame.getParent());
        }

        double seekSeconds = seekMillis / 1000.0;
        boolean extracted = runFfmpeg(sourceVideo, outputFrame, seekSeconds);

        if (!extracted && seekSeconds > 0.0) {
            log.warn("[Batch][FrameWriter] seek={}s failed — retry at 0s", seekSeconds);
            extracted = runFfmpeg(sourceVideo, outputFrame, 0.0);
        }

        if (!extracted || !Files.exists(outputFrame) || Files.size(outputFrame) < 100) {
            throw new IOException("프레임 추출 실패: seekMillis=" + seekMillis + " src=" + sourceVideo.getFileName());
        }
        log.info("[Batch][FrameWriter] extracted frame seekMillis={} size={}B path={}",
                seekMillis, Files.size(outputFrame), outputFrame.getFileName());
    }

    @Override
    public void writeFrameByNumber(Path sourceVideo, Path outputFrame, int frameNo) throws IOException {
        if (frameNo < 0) {
            throw new IOException("프레임 번호가 음수입니다: frameNo=" + frameNo);
        }
        if (outputFrame.getParent() != null && !Files.exists(outputFrame.getParent())) {
            Files.createDirectories(outputFrame.getParent());
        }

        boolean extracted = runFfmpegByFrameNo(sourceVideo, outputFrame, frameNo);

        if (!extracted || !Files.exists(outputFrame) || Files.size(outputFrame) < 100) {
            throw new IOException("프레임 추출 실패(frame-exact): frameNo=" + frameNo
                    + " src=" + sourceVideo.getFileName());
        }
        log.info("[Batch][FrameWriter] extracted frame-exact frameNo={} size={}B path={}",
                frameNo, Files.size(outputFrame), outputFrame.getFileName());
    }

    /**
     * 프레임 번호 직접 추출. {@code select=eq(n\,<frameNo>)} 필터로 디코더 프레임 인덱스에
     * 정확히 해당하는 프레임 1장만 통과시키고 {@code -frames:v 1 -vsync 0} 로 1장 저장한다.
     * fps 변환·seek 가정 없음 → 프레임 시퀀스 동일한 비식별 영상에서 좌표 정합 보장.
     * <p>
     * CWE-78: ProcessBuilder 리스트 방식으로 인자를 분리 전달, 문자열 조합 없음.
     */
    private boolean runFfmpegByFrameNo(Path sourceVideo, Path outputFrame, int frameNo) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(List.of(
                    binary, "-y",
                    "-i", sourceVideo.toAbsolutePath().toString(),
                    "-vf", "select=eq(n\\," + frameNo + ")",
                    "-frames:v", "1",
                    "-vsync", "0",
                    "-q:v", "2",
                    outputFrame.toAbsolutePath().toString()
            ));
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0 && Files.exists(outputFrame) && Files.size(outputFrame) >= 100;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("[Batch][FrameWriter] ffmpeg frame-exact error: {}", e.getMessage());
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private boolean runFfmpeg(Path sourceVideo, Path outputFrame, double seekSeconds) {
        // MEDIUM-4 fix: InterruptedException 등 중도 종료 시 ffmpeg 좀비 프로세스 방지 →
        // process 를 try 밖에 선언하고 finally 에서 destroyForcibly() 보장.
        //
        // MEDIUM-1 fix (OOM 잠재 위험): redirectErrorStream(true) 후 stdout 을
        // readAllBytes() 로 메모리에 적재하면 ffmpeg progress 로그 누적으로 OOM 가능.
        // ffmpeg -vframes 1 -q:v 2 는 결과를 outputFrame 파일로 직접 저장하므로
        // stdout/stderr 는 모두 DISCARD 하여 메모리 적재를 차단한다.
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(List.of(
                    binary, "-y",
                    "-ss", String.format(Locale.ROOT, "%.3f", seekSeconds),
                    "-i", sourceVideo.toAbsolutePath().toString(),
                    "-vframes", "1",
                    "-q:v", "2",
                    outputFrame.toAbsolutePath().toString()
            ));
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0 && Files.exists(outputFrame) && Files.size(outputFrame) >= 100;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("[Batch][FrameWriter] ffmpeg error: {}", e.getMessage());
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
