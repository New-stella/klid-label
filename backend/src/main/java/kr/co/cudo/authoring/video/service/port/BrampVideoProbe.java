package kr.co.cudo.authoring.video.service.port;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ffprobe 바이너리 기반 {@link VideoProbe} 구현 — Phase 3.
 *
 * <p>{@code ffprobe -v error -select_streams v:0 -show_entries stream=width,height
 * -of csv=p=0:s=x <file>} 로 첫 비디오 스트림의 해상도를 추출한다.
 *
 * <p>보안:
 * <ul>
 *   <li>Command Injection (CWE-78): ProcessBuilder 리스트 인자 — 사용자 입력 문자열 조합 없음.
 *       파일 경로만 마지막 인자로 분리 전달.</li>
 *   <li>Privacy (CWE-209): 영상 경로는 hash 로 마스킹 후 로그 출력. 예외 메시지에 경로 미노출.</li>
 *   <li>좀비 프로세스 방지: process 를 try 밖 선언 + finally destroyForcibly.</li>
 * </ul>
 */
@Slf4j
@Profile("!test")
@Component
public class BrampVideoProbe implements VideoProbe {

    private static final int PROBE_TIMEOUT_SEC = 30;

    private final String binary;

    public BrampVideoProbe(@Value("${authoring.ffprobe.binary:ffprobe}") String binary) {
        this.binary = binary;
    }

    @Override
    public Dimensions probe(Path video) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(List.of(
                    binary, "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height",
                    "-of", "csv=p=0:s=x",
                    video.toAbsolutePath().toString()
            ));
            pb.redirectErrorStream(false);
            process = pb.start();

            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            boolean finished = process.waitFor(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("[Video][Resolution] ffprobe timeout path={}", mask(video));
                throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 해상도 추출이 지연되어 중단되었습니다.");
            }
            return parse(line, video);
        } catch (IOException e) {
            log.error("[Video][Resolution] ffprobe failed path={} err={}", mask(video), e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 해상도 추출에 실패했습니다.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 해상도 추출이 중단되었습니다.");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** "WIDTHxHEIGHT" 파싱. 비디오 스트림 없음/형식 오류 시 (0,0) — 서비스가 400 으로 변환. */
    private Dimensions parse(String line, Path video) {
        if (line == null || line.isBlank()) {
            log.error("[Video][Resolution] ffprobe empty output path={}", mask(video));
            return new Dimensions(0, 0);
        }
        String[] parts = line.trim().split("x");
        if (parts.length != 2) {
            log.error("[Video][Resolution] ffprobe unexpected output path={}", mask(video));
            return new Dimensions(0, 0);
        }
        try {
            return new Dimensions(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException e) {
            log.error("[Video][Resolution] ffprobe non-numeric output path={}", mask(video));
            return new Dimensions(0, 0);
        }
    }

    private String mask(Path video) {
        return video == null ? "null" : Integer.toHexString(video.toString().hashCode());
    }
}
