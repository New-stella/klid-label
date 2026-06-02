package kr.co.cudo.authoring.video.service.port;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg 바이너리 기반 {@link VideoResizer} 구현 — Phase 3.
 *
 * <p>{@code ffmpeg -y -i <src> -vf scale=W:H -c:a copy <dst>} 로 리사이즈한다.
 * targetW/targetH 는 서비스가 짝수로 계산해 전달한다(yuv420p 요구사항 충족).
 *
 * <p>보안/안정성:
 * <ul>
 *   <li>Command Injection (CWE-78): ProcessBuilder 리스트 인자 — 계산된 정수 W/H 와 파일 경로만
 *       분리 전달, 자유 입력 문자열 조합 없음.</li>
 *   <li>타임아웃 강제 (HIGH-⑨): {@code process.waitFor(timeout, SECONDS)} — 초과 시 destroyForcibly
 *       + 예외. 무한 hang 으로 인한 스레드/프로세스 점유 차단.</li>
 *   <li>좀비 프로세스 방지: process 를 try 밖 선언 + finally destroyForcibly.</li>
 *   <li>Privacy (CWE-209): 경로 hash 마스킹.</li>
 * </ul>
 */
@Slf4j
@Profile("!test")
@Component
public class BrampVideoResizer implements VideoResizer {

    private final String binary;
    private final long timeoutSec;

    public BrampVideoResizer(@Value("${authoring.ffmpeg.binary:ffmpeg}") String binary,
                             @Value("${authoring.ffmpeg.resize-timeout-sec:600}") long timeoutSec) {
        this.binary = binary;
        this.timeoutSec = timeoutSec;
    }

    @Override
    public void resize(Path src, Path dst, double factor, int targetW, int targetH) {
        Process process = null;
        try {
            if (dst.getParent() != null && !Files.exists(dst.getParent())) {
                Files.createDirectories(dst.getParent());
            }
            ProcessBuilder pb = new ProcessBuilder(List.of(
                    binary, "-y",
                    "-i", src.toAbsolutePath().toString(),
                    "-vf", String.format(Locale.ROOT, "scale=%d:%d", targetW, targetH),
                    "-c:a", "copy",
                    dst.toAbsolutePath().toString()
            ));
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("[Video][Resolution] ffmpeg resize timeout factor={} path={}", factor, mask(src));
                throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 리사이즈가 지연되어 중단되었습니다.");
            }
            int exit = process.exitValue();
            if (exit != 0 || !Files.exists(dst) || Files.size(dst) < 100) {
                log.error("[Video][Resolution] ffmpeg resize failed exit={} path={}", exit, mask(src));
                throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 리사이즈에 실패했습니다.");
            }
            log.info("[Video][Resolution] resized factor={} target={}x{} path={}",
                    factor, targetW, targetH, mask(src));
        } catch (IOException e) {
            log.error("[Video][Resolution] ffmpeg resize io error path={} err={}", mask(src), e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 리사이즈에 실패했습니다.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 리사이즈가 중단되었습니다.");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String mask(Path video) {
        return video == null ? "null" : Integer.toHexString(video.toString().hashCode());
    }
}
