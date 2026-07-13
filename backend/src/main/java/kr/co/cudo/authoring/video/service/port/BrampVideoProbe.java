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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ffprobe 바이너리 기반 {@link VideoProbe} 구현 — Phase 3 + NIA export Phase 1.
 *
 * <p>{@code ffprobe -v error -select_streams v:0
 * -show_entries stream=width,height,codec_name,r_frame_rate,bit_rate,duration:format=size,bit_rate,duration
 * -of default=noprint_wrappers=0:nokey=0 <file>} 로 첫 비디오 스트림 + 컨테이너(format) 기술메타를
 * 1회 호출로 추출한다. 출력은 {@code [STREAM]/[FORMAT]} 구획 + {@code key=value} 라인이며 구획을
 * 추적해 stream/format 값을 구분 파싱한다.
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
    public VideoMeta probe(Path video) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(List.of(
                    binary, "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries",
                    "stream=width,height,codec_name,r_frame_rate,bit_rate,duration:format=size,bit_rate,duration",
                    "-of", "default=noprint_wrappers=0:nokey=0",
                    video.toAbsolutePath().toString()
            ));
            pb.redirectErrorStream(false);
            process = pb.start();

            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            boolean finished = process.waitFor(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("[Video][Probe] ffprobe timeout path={}", mask(video));
                throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 기술메타 추출이 지연되어 중단되었습니다.");
            }
            return parse(lines, video);
        } catch (IOException e) {
            log.error("[Video][Probe] ffprobe failed path={} err={}", mask(video), e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 기술메타 추출에 실패했습니다.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 기술메타 추출이 중단되었습니다.");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * ffprobe {@code default=noprint_wrappers=0:nokey=0} 출력을 파싱한다.
     *
     * <p>{@code [STREAM]/[FORMAT]} 구획을 추적해 stream/format 동명 key(bit_rate, duration)를
     * 구분한다. 개별 필드 누락/파싱실패는 예외 없이 해당 필드 null(원시 width/height 는 0)로 둔다.
     */
    static VideoMeta parse(List<String> lines, Path video) {
        if (lines == null || lines.isEmpty()) {
            log.error("[Video][Probe] ffprobe empty output path={}", mask(video));
            return new VideoMeta(0, 0, null, null, null, null, null);
        }

        int width = 0;
        int height = 0;
        String codecName = null;
        String rFrameRate = null;
        Long streamBitRate = null;
        Long formatBitRate = null;
        Long streamDurationMs = null;
        Long formatDurationMs = null;
        Long fileSize = null;

        String section = null; // "STREAM" | "FORMAT"
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.equals("[STREAM]")) {
                section = "STREAM";
                continue;
            }
            if (line.equals("[FORMAT]")) {
                section = "FORMAT";
                continue;
            }
            if (line.startsWith("[/")) {
                section = null;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();

            if ("STREAM".equals(section)) {
                switch (key) {
                    case "width" -> width = parseIntOrDefault(value, 0);
                    case "height" -> height = parseIntOrDefault(value, 0);
                    case "codec_name" -> codecName = normalize(value);
                    case "r_frame_rate" -> rFrameRate = normalize(value);
                    case "bit_rate" -> streamBitRate = parseLongOrNull(value);
                    case "duration" -> streamDurationMs = parseDurationMsOrNull(value);
                    default -> { /* 관심 밖 key 무시 */ }
                }
            } else if ("FORMAT".equals(section)) {
                switch (key) {
                    case "size" -> fileSize = parseLongOrNull(value);
                    case "bit_rate" -> formatBitRate = parseLongOrNull(value);
                    case "duration" -> formatDurationMs = parseDurationMsOrNull(value);
                    default -> { /* 관심 밖 key 무시 */ }
                }
            }
        }

        Double fps = parseFps(rFrameRate);
        Long bitRate = streamBitRate != null ? streamBitRate : formatBitRate;
        Long durationMs = streamDurationMs != null ? streamDurationMs : formatDurationMs;

        return new VideoMeta(width, height, codecName, fps, bitRate, durationMs, fileSize);
    }

    /**
     * r_frame_rate 분수("30000/1001") 또는 단일 정수("25")를 double fps 로 변환한다.
     *
     * <p>fps 는 <b>양수만 유효</b>하다. 분자·분모 중 하나라도 0 이하(≤0)이거나, 단일 정수가
     * 0 이하이거나, 파싱 불가/미상("N/A")이면 모두 null(미상)을 반환한다 — 음수 fps 가
     * 결과로 새어나가지 않도록 방어한다.
     */
    private static Double parseFps(String rFrameRate) {
        String v = normalize(rFrameRate);
        if (v == null) {
            return null;
        }
        try {
            int slash = v.indexOf('/');
            if (slash < 0) {
                double d = Double.parseDouble(v);
                return d > 0 ? d : null;
            }
            double num = Double.parseDouble(v.substring(0, slash).trim());
            double den = Double.parseDouble(v.substring(slash + 1).trim());
            if (num <= 0.0 || den <= 0.0) {
                return null;
            }
            return num / den;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 초 실수 문자열 → ms(Long, ×1000 반올림). 미상/파싱불가 → null. */
    private static Long parseDurationMsOrNull(String value) {
        String v = normalize(value);
        if (v == null) {
            return null;
        }
        try {
            return Math.round(Double.parseDouble(v) * 1000.0);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLongOrNull(String value) {
        String v = normalize(value);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseIntOrDefault(String value, int fallback) {
        String v = normalize(value);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** ffprobe 미상값("N/A")·공백을 null 로 정규화. */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("N/A")) {
            return null;
        }
        return v;
    }

    private static String mask(Path video) {
        return video == null ? "null" : Integer.toHexString(video.toString().hashCode());
    }
}
