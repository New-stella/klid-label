package kr.co.cudo.authoring.upload.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * ffprobe 바이너리 기반 {@link UploadMediaProbe} 구현.
 *
 * <p>{@code ffprobe -v error -protocol_whitelist file,crypto,data -select_streams v:0 -show_entries
 * stream=width,height,codec_name,r_frame_rate,nb_frames,display_aspect_ratio,duration,bit_rate
 * :format=duration,bit_rate -of default=noprint_wrappers=0:nokey=0 -i <file>} 로 첫 비디오 스트림 + 컨테이너(format) 값을
 * <b>1회 호출</b>로 추출한다(완료 경로의 ffprobe 호출 횟수를 늘리지 않는다는 비기능 요건).
 * 출력은 {@code [STREAM]/[FORMAT]} 구획 + {@code key=value} 라인이며 구획을 추적해 동명 key
 * ({@code duration})를 구분한다.
 *
 * <p><b>{@code @Profile("!test")} 를 붙이지 않는 이유</b> — 자매 구현
 * {@code video.service.port.BrampVideoProbe} 와 달리 이 빈은 Phase 2 에서
 * {@code TusUploadService} 가 <b>생성자 주입</b>으로 받는다. 테스트 프로파일에서 빈이 사라지면
 * {@code InternalUploadIngestFlowIT} 등 기존 Spring 통합 테스트가 "빈 부재"로 기동조차 못 한다.
 * 단위 테스트는 프로파일이 아니라 stub 직접 주입으로 바이너리를 격리한다
 * ({@code DurationProbeFfprobe} 와 동일한 패턴).
 *
 * <p><b>프로세스 I/O 규약 (교착 방지 — 되돌리지 말 것)</b>
 * <ul>
 *   <li><b>stdout 은 파이프가 아니라 임시파일로 받는다</b>({@code redirectOutput(Redirect.to(file))}).
 *       파이프로 받아 {@code readLine()} 루프를 돌면 그 루프가 EOF 까지 <b>무기한</b> 블로킹해
 *       뒤에 오는 {@code waitFor(timeout)} 과 {@code finally} 의 {@code destroyForcibly()} 가
 *       <b>영원히 도달하지 못한다</b>. 파일로 받으면 {@code waitFor(timeout)} 이 <b>첫 대기 지점</b>이
 *       되어 전체 벽시계 시간이 타임아웃으로 상한된다. 별도 배수 스레드(방식 a)를 쓰지 않은 이유는
 *       이 경로가 업로드 완료마다 호출돼 스레드·executor 수명 관리가 새로운 누수 표면이 되기
 *       때문이다 — 임시파일은 {@code finally} 삭제 하나로 끝난다.</li>
 *   <li><b>stderr 은 {@code Redirect.DISCARD} 로 버린다.</b> 손상 mp4 를 {@code -v error} 로 probe 하면
 *       stderr 로 76KB(1,200여 줄)가 쏟아지는데(실측), 리눅스 파이프 버퍼는 약 64KiB 다. 읽지 않는
 *       파이프로 두면 자식이 write 에서 막혀 stdout 을 닫지 못하고 부모와 함께 교착한다.
 *       {@code BrampFfmpegFrameWriter} 의 "MEDIUM-1 fix" 와 같은 관례다.</li>
 *   <li>⚠ <b>{@code redirectErrorStream(true)} 로 고치지 말 것.</b> stderr 가 stdout 에 합류하면 그
 *       76KB 가 그대로 파싱 대상에 적재돼 결함이 <b>교착에서 OOM 으로 옮겨갈 뿐</b>이다(이 저장소가
 *       과거 그 방식을 쓰다 DISCARD 로 되돌린 이력이 있다). "stderr 를 왜 안 읽지?"는 결함이 아니라
 *       의도다.</li>
 *   <li>stdin 은 시작 직후 파이프를 닫아 자식에 EOF 를 주고 FD 를 회수한다.
 *       {@code redirectInput(Redirect.DISCARD)} 는 쓸 수 없다 — DISCARD 는 WRITE 타입이라
 *       {@code IllegalArgumentException: Redirect invalid for reading: WRITE} 가 난다.</li>
 * </ul>
 *
 * <p>보안:
 * <ul>
 *   <li>Command Injection (CWE-78): {@code ProcessBuilder} 리스트 인자만 사용한다 — 문자열 조합·셸
 *       호출 없음. 파일 경로는 마지막 <b>위치</b> 인자가 아니라 {@code -i} 의 <b>옵션 값</b>으로
 *       전달해, 경로가 {@code -} 로 시작해도 옵션으로 해석될 여지를 없앤다
 *       ({@code BrampFfmpegFrameWriter} 와 동일 형태).</li>
 *   <li>SSRF 심층방어 (CWE-918): {@code -protocol_whitelist file,crypto,data} 로 프로토콜을
 *       <b>고정(pin)</b> 한다. 이 값은 현재 ffmpeg 기본값과 동일해 동작을 바꾸지 않으며, 목적은
 *       기본값이 넓어지거나 다운그레이드될 때 조용히 열리지 않게 못 박는 것이다.
 *       <b>더 좁히지 말 것</b> — 정상 파일이 열리지 않을 수 있다.</li>
 *   <li>Resource Consumption (CWE-400/770): 타임아웃 + {@code finally} 의 {@code destroyForcibly} +
 *       임시파일 삭제로 <b>수명</b>을 상한하고, {@link #MAX_OUTPUT_BYTES} 등 3종 상한으로
 *       <b>파싱 대상</b>의 크기를 상한한다.
 *       <p>⚠ <b>디스크 기록량은 상한되지 않는다(인지·수용된 잔여 위험).</b> stdout 을 파이프가 아니라
 *       임시파일로 받으면 64KiB 커널 파이프 버퍼가 주던 <b>역압이 사라져</b> 자식이 타임아웃까지
 *       마음껏 쓴다 — 실측: 자식이 20MB 를 출력하면 20,020,000 B, 무한 출력 + 2초 타임아웃이면
 *       81,952,871 B(~78 MiB)가 임시파일에 적재된다. {@link #MAX_OUTPUT_BYTES} 는
 *       {@code waitFor} <b>이후 읽는 시점</b>에만 적용되므로 기록 자체를 막지 못한다. 실질 상한은
 *       {@code probeTimeoutSec × 자식 쓰기 속도}(기본 30초 기준 외삽 ~1.2GB)이며, 파일은
 *       {@code finally} 에서 삭제되므로 잔존하지 않는다. 신뢰 경계 안의 바이너리(ffprobe)를
 *       우리가 만든 인자로 부르는 경로라 이 노출을 수용한다.
 *       <p><b>그럼에도 배수 스레드 방식으로 되돌리지 않는 이유</b> — 그 방식은 기록량을 상한하는
 *       대신 업로드 완료마다 스레드·executor 를 만들어 <b>수명 관리라는 새 누수 표면</b>을 연다.
 *       반면 지금 구조는 실증된 교착(stderr 150KB·무응답 자식 모두 15초 초과 → 377ms 완주)을
 *       없앴고 정리가 {@code finally} 삭제 하나로 끝난다. 교착 제거의 이득이 더 크다는 판단이며,
 *       바꾸려면 이 잔여 위험이 실제로 문제가 됐다는 근거부터 확보할 것.</li>
 *   <li>Improper Check for Unusual Conditions (CWE-754): 종료코드가 0 이 아니거나 <b>출력이 상한에
 *       걸려 절단되면</b> 부분 파싱 결과를 채택하지 않고 전량 미상을 돌려준다(아래 {@code probe} ·
 *       {@link #readBoundedLines} 참조).</li>
 *   <li>Privacy / Information Leak (CWE-209): 영상 경로는 해시로 마스킹해 로그에 남기고 예외
 *       메시지에는 넣지 않는다. ffprobe 원문 값도 로그에 싣지 않는다(파일명·메타가 PII 를 담을 수
 *       있다).</li>
 * </ul>
 */
@Slf4j
@Component
public class UploadMediaProbeFfprobe implements UploadMediaProbe {

    /** 측정 1회의 벽시계 상한(초). 이 시간을 넘기면 프로세스를 강제 종료한다. */
    private static final int DEFAULT_PROBE_TIMEOUT_SEC = 30;
    /**
     * 프로토콜 고정값(pin). ffmpeg 기본값과 동일 — 좁히기가 아니라 "조용히 넓어지지 않게" 하는 장치다.
     */
    private static final String PROTOCOL_WHITELIST = "file,crypto,data";
    /** stdout 적재 상한(바이트). 현재 명령 출력은 ~13줄이라 도달하지 않는 방어선이다. */
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    /** stdout 적재 상한(라인 수). */
    private static final int MAX_OUTPUT_LINES = 200;
    /** 라인 1개 적재 상한(문자). ffprobe {@code key=value} 라인은 수십 자 수준이다. */
    private static final int MAX_LINE_LENGTH = 512;

    private static final String SECTION_STREAM = "STREAM";
    private static final String SECTION_FORMAT = "FORMAT";
    private static final double MILLIS_PER_SECOND = 1000.0;
    /** {@code Math.round} 가 {@code Long.MAX_VALUE} 로 포화하기 시작하는 경계 — 이 이상은 미상 처리. */
    private static final double LONG_SATURATION_BOUND = (double) Long.MAX_VALUE;

    /** 측정 실패·비정상 종료 시 돌려주는 "전량 미상" 값. */
    private static final MediaMeta UNKNOWN = new MediaMeta(0, 0, null, null, null, null, null, null);

    private final String binary;
    private final int probeTimeoutSec;

    @Autowired
    public UploadMediaProbeFfprobe(@Value("${authoring.ffmpeg.ffprobe-binary:ffprobe}") String binary) {
        this(binary, DEFAULT_PROBE_TIMEOUT_SEC);
    }

    /**
     * 타임아웃 주입 생성자 — <b>교착 회귀 테스트 전용</b>(가짜 ffprobe 스크립트를 짧은 타임아웃으로
     * 돌린다). 프로덕션 빈은 위 {@code @Autowired} 생성자로만 생성된다.
     */
    UploadMediaProbeFfprobe(String binary, int probeTimeoutSec) {
        this.binary = binary;
        this.probeTimeoutSec = probeTimeoutSec;
    }

    /**
     * {@inheritDoc}
     *
     * <p>실패 처리 규약:
     * <ul>
     *   <li><b>타임아웃</b> — 강제 종료 후 ERROR 로그 + 예외. 측정이 끝나지 않은 것과 "메타가 없는 것"은
     *       다르다.</li>
     *   <li><b>비정상 종료(exit != 0)</b> — WARN 로그 후 <b>전량 미상</b> 반환. 부분 출력만 그럴듯하게
     *       채택되면 잘못된 메타가 확정 저장되기 때문이다. 로그의 {@code exit} 로 타임아웃과 구분된다
     *       (CWE-754: 실패가 "성공(전량 미상)"으로 조용히 축퇴하지 않게 흔적을 남긴다).</li>
     *   <li><b>출력 절단</b> — 바이트·라인수·라인길이 상한 중 하나라도 걸리면 WARN 로그 후
     *       <b>전량 미상</b> 반환. 비정상 종료와 <b>동일한</b> fail-closed 정책이다
     *       ({@link #readBoundedLines} 의 근거 참조).</li>
     * </ul>
     */
    @Override
    public MediaMeta probe(Path filePath) {
        Process process = null;
        Path stdoutFile = null;
        try {
            stdoutFile = Files.createTempFile("upload-ffprobe-", ".out");
            ProcessBuilder pb = new ProcessBuilder(buildCommand(filePath));
            // stdout=임시파일 / stderr=버림 — 위 "프로세스 I/O 규약" 참조. 파이프로 되돌리면 교착한다.
            pb.redirectOutput(ProcessBuilder.Redirect.to(stdoutFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            process.getOutputStream().close(); // 자식 stdin 에 EOF + FD 회수

            boolean finished = process.waitFor(probeTimeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("[Upload][Probe] ffprobe timeout timeoutSec={} path={}",
                        probeTimeoutSec, mask(filePath));
                throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 기술메타 추출이 지연되어 중단되었습니다.");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("[Upload][Probe] ffprobe exited abnormally exit={} path={} — 전량 미상 처리",
                        exitCode, mask(filePath));
                return UNKNOWN;
            }
            // 절단(Optional.empty)은 exit!=0 과 동일하게 전량 미상 — readBoundedLines Javadoc 참조
            return readBoundedLines(stdoutFile, filePath)
                    .map(lines -> parse(lines, filePath))
                    .orElse(UNKNOWN);
        } catch (IOException e) {
            log.error("[Upload][Probe] ffprobe failed path={} causeType={}",
                    mask(filePath), e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 기술메타 추출에 실패했습니다.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 기술메타 추출이 중단되었습니다.");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteQuietly(stdoutFile);
        }
    }

    /**
     * ffprobe 실행 인자를 조립한다.
     *
     * <p>테스트가 인자 구성을 <b>관측</b>할 수 있도록 패키지-프라이빗으로 분리했다 — 여기 인자 중
     * {@code -protocol_whitelist}(SSRF 심층방어 pin)와 {@code -i}(경로를 위치 인자로 두지 않는
     * CWE-78 하드닝)는 <b>제거해도 정상 파일 측정 결과가 그대로라</b> 동작 기반 테스트로는 지워진
     * 것을 알 수 없다. 인자 리스트 자체를 단언해야 회귀가 잡힌다.
     */
    List<String> buildCommand(Path filePath) {
        return List.of(
                binary, "-v", "error",
                "-protocol_whitelist", PROTOCOL_WHITELIST,
                "-select_streams", "v:0",
                "-show_entries",
                "stream=width,height,codec_name,r_frame_rate,nb_frames,display_aspect_ratio,duration"
                        + ",bit_rate:format=duration,bit_rate",
                "-of", "default=noprint_wrappers=0:nokey=0",
                "-i", filePath.toAbsolutePath().toString()
        );
    }

    /**
     * ffprobe stdout 임시파일을 <b>상한과 함께</b> 읽는다 (CWE-400/770).
     *
     * <p>현재 명령의 출력은 {@code [STREAM]/[FORMAT]} 포함 ~13줄이라 상한에 도달하지 않는다. 그럼에도
     * 상한을 박는 이유는 stdout 을 stderr 와 합치는 변경(금지 사항)이 들어오면 <b>즉시</b> 76KB 가
     * 이 리스트로 들어오기 때문이다.
     *
     * <p><b>절단은 "잘라서 파싱"이 아니라 실패다</b>(fail-closed — 되돌리지 말 것). 어느 상한
     * (바이트 총량·라인 수·라인 길이)에 걸리든 {@link Optional#empty()} 를 돌려주고 호출자는
     * 전량 미상으로 처리한다. 근거: <b>절단된 출력은 형식상 정상인 틀린 값을 만든다</b> — 경계가
     * 하필 값 중간에 걸리면 {@code height=1080} 이 {@code height=1} 로, {@code codec_name=h264} 가
     * {@code codec_name=h} 로 <b>멀쩡한 형태</b>가 되어 나온다. 그 값은 하류
     * {@link InternalUploadMetaResolver} 의 형식·길이·범위 검증을 <b>전부 통과</b>하므로
     * (실측: {@code resl="1920x1"} · {@code vrtc=1} · {@code asprtRt="1920:1"} 채택) 정상값과
     * 구분할 수단이 아예 없다. 따라서 절단 = 실패로 취급하며, 이는 {@code exit != 0} 처리와 동일한
     * 정책이다(같은 위험에 다른 정책을 두면 절단 경로만 예외가 된다).
     *
     * <p>⚠ 라인 길이 초과를 <b>"그 라인만 버리기"로 처리하지 말 것</b> — 어느 라인이 잘렸는지에 따라
     * 결과가 달라지는 미묘한 동작이 생기고, "절단된 값과 정상 값을 구분 못 한다"는 근본이 남는다.
     */
    private static Optional<List<String>> readBoundedLines(Path stdoutFile, Path video) throws IOException {
        // 거대 파일을 다 읽고 나서 버리지 않도록 크기부터 본다
        if (Files.size(stdoutFile) > MAX_OUTPUT_BYTES) {
            return warnTruncated(video, "bytes");
        }

        byte[] buffer = new byte[MAX_OUTPUT_BYTES];
        int read;
        boolean overflow;
        try (InputStream in = Files.newInputStream(stdoutFile)) {
            read = in.readNBytes(buffer, 0, MAX_OUTPUT_BYTES);
            overflow = in.read() >= 0; // 상한 뒤에 남은 바이트가 있으면 절단된 것
        }
        if (overflow) {
            return warnTruncated(video, "bytes");
        }

        String[] raw = new String(buffer, 0, read, StandardCharsets.UTF_8).split("\n", -1);
        int count = raw.length;
        if (count > 0 && raw[count - 1].isEmpty()) {
            count--; // 파일 끝 개행이 만드는 빈 토큰은 라인이 아니다
        }
        if (count > MAX_OUTPUT_LINES) {
            return warnTruncated(video, "lines");
        }

        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (raw[i].length() > MAX_LINE_LENGTH) {
                return warnTruncated(video, "lineLength");
            }
            lines.add(raw[i]);
        }
        return Optional.of(lines);
    }

    /** 절단 사유만 남긴다 — 출력 원문은 로그에 싣지 않는다(CWE-209). */
    private static Optional<List<String>> warnTruncated(Path video, String limit) {
        log.warn("[Upload][Probe] ffprobe output exceeded limit={} maxBytes={} maxLines={} maxLineLength={} "
                        + "path={} — 전량 미상 처리",
                limit, MAX_OUTPUT_BYTES, MAX_OUTPUT_LINES, MAX_LINE_LENGTH, mask(video));
        return Optional.empty();
    }

    /** 임시파일 정리 — 삭제 실패가 측정 결과를 뒤집지 않도록 삼키되 흔적은 남긴다. */
    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("[Upload][Probe] temp file delete failed causeType={}", e.getClass().getSimpleName());
        }
    }

    /**
     * ffprobe {@code default=noprint_wrappers=0:nokey=0} 출력을 파싱한다.
     *
     * <p>구획을 추적해 stream/format 동명 key({@code duration}·{@code bit_rate})를 구분하며, 길이와
     * 비트레이트는 <b>컨테이너({@code format.*}) 값을 우선</b>하고 없을 때만 스트림 값으로 폴백한다 —
     * 스트림 duration 은 컨테이너마다 누락·불일치가 잦고, 비트레이트도 스트림 값이 비어 있는
     * 컨테이너가 흔하다(같은 우선순위를 쓰는 것이 두 축의 해석을 일치시킨다).
     *
     * <p>개별 필드의 누락·파싱실패는 예외를 던지지 않고 해당 필드만 null(원시 width/height 는 0)로
     * 둔다. <b>여기서는 값의 타당성을 판정하지 않는다</b> — 채택 여부는 전적으로
     * {@link InternalUploadMetaResolver} 책임이다(측정과 정책의 분리).
     */
    static MediaMeta parse(List<String> lines, Path video) {
        if (lines == null || lines.isEmpty()) {
            log.warn("[Upload][Probe] ffprobe empty output path={}", mask(video));
            return UNKNOWN;
        }

        int width = 0;
        int height = 0;
        String codecName = null;
        String rFrameRate = null;
        Long nbFrames = null;
        String displayAspectRatio = null;
        Long streamDurationMs = null;
        Long formatDurationMs = null;
        Long streamBitRate = null;
        Long formatBitRate = null;

        String section = null; // "STREAM" | "FORMAT" | null(구획 밖)
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.equals("[" + SECTION_STREAM + "]")) {
                section = SECTION_STREAM;
                continue;
            }
            if (line.equals("[" + SECTION_FORMAT + "]")) {
                section = SECTION_FORMAT;
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

            if (SECTION_STREAM.equals(section)) {
                switch (key) {
                    case "width" -> width = parseIntOrDefault(value);
                    case "height" -> height = parseIntOrDefault(value);
                    case "codec_name" -> codecName = normalize(value);
                    case "r_frame_rate" -> rFrameRate = normalize(value);
                    case "nb_frames" -> nbFrames = parseLongOrNull(value);
                    case "display_aspect_ratio" -> displayAspectRatio = normalize(value);
                    case "duration" -> streamDurationMs = parseDurationMsOrNull(value);
                    case "bit_rate" -> streamBitRate = parsePositiveLongOrNull(value);
                    default -> { /* 관심 밖 key 무시 */ }
                }
            } else if (SECTION_FORMAT.equals(section)) {
                if ("duration".equals(key)) {
                    formatDurationMs = parseDurationMsOrNull(value);
                } else if ("bit_rate".equals(key)) {
                    formatBitRate = parsePositiveLongOrNull(value);
                }
            }
        }

        Long durationMs = formatDurationMs != null ? formatDurationMs : streamDurationMs;
        Long bitRate = formatBitRate != null ? formatBitRate : streamBitRate;
        return new MediaMeta(width, height, codecName, parseFps(rFrameRate),
                durationMs, nbFrames, displayAspectRatio, bitRate);
    }

    /**
     * {@code r_frame_rate} 분수("30000/1001") 또는 단일 정수("25")를 double fps 로 변환한다.
     *
     * <p>fps 는 <b>양수(유한)만 유효</b>하다 — 분자·분모 중 하나라도 0 이하이거나(무비디오 스트림의
     * {@code 0/0} 포함) 파싱 불가/미상이면 null(미상)로 둔다.
     *
     * <p><b>{@code NaN}/{@code Infinity} 를 반출하지 않는다</b> (CWE-20/681): {@code Double.parseDouble}
     * 은 {@code "NaN"}·{@code "Infinity"} 를 <b>정상 파싱</b>하고, {@code NaN} 은 모든 비교가 false 라
     * {@code num <= 0.0} 류의 부호 검사를 그대로 통과한다. 그래서 부호 검사가 아니라
     * {@link Double#isFinite} 게이트를 <b>분수 경로·단일 정수 경로 양쪽</b>에 건다 — 지금은 하류
     * {@code InternalUploadMetaResolver} 가 다시 막지만, 포트 계약("양수만 유효")이 거짓이면 이
     * 레코드를 문서대로 믿는 다음 소비자에게 NaN 이 그대로 흘러간다.
     *
     * <p><b>가드 중복에 대한 사실 고지</b>(mutation 검출력 판정용 — 다음 검토자가 "왜 이 가드만
     * 테스트가 없나"로 되돌아오지 않게 남긴다): 분수 경로의
     * {@code !isFinite(num) || !isFinite(den)} 는 {@link #positiveFinite} 와 <b>증명적으로 중복</b>
     * 이라 <b>단독으로는 어떤 입력으로도 죽일 수 없다</b>. num·den 중 하나라도 비유한이면 몫은
     * 반드시 {@code ±Infinity}(유한/±Inf → ±0, ±Inf/유한 → ±Inf, NaN 개입 → NaN) 이거나 0·NaN 이
     * 되어 {@code positiveFinite} 가 전부 잡기 때문이다(실측: 이 가드만 지우면 스위트가 초록,
     * {@code positiveFinite} 와 <b>함께</b> 지우면 {@code Infinity/1} 이 새어 2건이 FAIL). 그럼에도
     * 남겨 두는 이유는 "몫을 만들기 전에 입력을 본다"는 의도를 코드로 못 박기 위해서이며,
     * {@code positiveFinite} 쪽이 먼저 사라지는 변경이 오면 이 가드가 유일한 방어선이 된다.
     * 반대로 {@code positiveFinite} 의
     * {@code isFinite} 는 <b>단일 토큰</b> {@code r_frame_rate=Infinity}(슬래시 없음)가 유일하게
     * 필요로 하는 가드라 회귀 테스트가 그 입력으로 고정한다.
     */
    private static Double parseFps(String rFrameRate) {
        String v = normalize(rFrameRate);
        if (v == null) {
            return null;
        }
        try {
            int slash = v.indexOf('/');
            if (slash < 0) {
                return positiveFinite(Double.parseDouble(v));
            }
            double num = Double.parseDouble(v.substring(0, slash).trim());
            double den = Double.parseDouble(v.substring(slash + 1).trim());
            if (!Double.isFinite(num) || !Double.isFinite(den) || num <= 0.0 || den <= 0.0) {
                return null;
            }
            return positiveFinite(num / den);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 유한 양수만 통과시킨다. {@code NaN} 은 모든 비교가 false 이므로 부호 검사보다 먼저 걸러야 한다. */
    private static Double positiveFinite(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : null;
    }

    /**
     * 초 실수 문자열 → ms(Long, ×1000 반올림). 미상/파싱불가/비유한/0 이하 → null.
     *
     * <p>{@code Math.round} 는 {@code NaN} 을 0 으로, {@code ±Infinity} 를
     * {@code Long.MIN_VALUE}/{@code MAX_VALUE} 로 <b>조용히 바꿔</b> 그럴듯한 정수를 만들어낸다.
     * 그래서 반올림 <b>전에</b> ①입력값 ②×1000 결과 양쪽의 유한성과 long 표현범위를 확인한다 —
     * {@code 1e308} 은 그 자체는 유한이지만 ×1000 에서 {@code Infinity} 로 넘친다. 반올림 결과가
     * 0 이하면 "길이 미상"으로 둔다.
     *
     * <p><b>가드 중복에 대한 사실 고지</b>(위 {@link #parseFps} 와 같은 취지): 세 게이트의 검출력은
     * 대칭이 아니다.
     * <ul>
     *   <li>{@code Math.abs(millis) >= LONG_SATURATION_BOUND} — <b>단독으로 죽는다</b>. 입력
     *       {@code 1e300}(→ millis {@code 1e303}, 유한)은 이 검사만이 잡으며, 없으면
     *       {@code Math.round} 가 {@code Long.MAX_VALUE} 라는 그럴듯한 정수를 만든다. 회귀 테스트가
     *       이 입력으로 고정한다.</li>
     *   <li>{@code !isFinite(seconds)} · {@code !isFinite(millis)} — <b>개별로도, 둘을 동시에
     *       지워도 죽지 않는다</b>(실측). {@code ±Infinity} 는 위 포화 검사가, {@code NaN} 은
     *       {@code Math.round(NaN) == 0} → {@code rounded > 0} 이 각각 잡기 때문이다. 즉 이 둘은
     *       "무보호 가드"가 아니라 <b>증명적으로 중복인 백스톱</b>이다. 의도(반올림 <b>전에</b>
     *       유한성을 본다)를 명시하기 위해 남겨 두며, 커버리지·mutation 점수만 보고 지우지 말 것 —
     *       지우면 위 두 후행 가드 중 하나만 약해져도 {@code Long.MAX_VALUE} 가 새는 경로가 열린다.</li>
     * </ul>
     */
    private static Long parseDurationMsOrNull(String value) {
        String v = normalize(value);
        if (v == null) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(v);
            if (!Double.isFinite(seconds)) {
                return null;
            }
            double millis = seconds * MILLIS_PER_SECOND;
            if (!Double.isFinite(millis) || Math.abs(millis) >= LONG_SATURATION_BOUND) {
                return null;
            }
            long rounded = Math.round(millis);
            return rounded > 0L ? rounded : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * bps 정수 문자열 → {@code Long}. 미상/파싱불가/0 이하는 null.
     *
     * <p>{@code nb_frames} 용 {@link #parseLongOrNull} 과 <b>부호 처리가 다르다</b> — 그쪽은 포트 계약이
     * "컨테이너가 신고한 값 그대로"라 0·음수도 그대로 나르고 채택 판정을 하류에 맡기지만, 비트레이트는
     * 포트 계약 자체가 "양수만 유효"({@link MediaMeta#bitRate})라 여기서 거른다. ffprobe 는 값을 모를 때
     * {@code N/A} 뿐 아니라 {@code 0} 을 신고하는 컨테이너가 있어, 0 을 그대로 나르면 하류가 "0 bps"라는
     * 형식상 정상인 틀린 값을 볼 여지가 생긴다.
     */
    private static Long parsePositiveLongOrNull(String value) {
        Long parsed = parseLongOrNull(value);
        return parsed != null && parsed > 0L ? parsed : null;
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

    private static int parseIntOrDefault(String value) {
        String v = normalize(value);
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
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

    /** 경로 원문을 로그에 남기지 않기 위한 해시 마스킹 (CWE-209). */
    private static String mask(Path video) {
        return video == null ? "null" : Integer.toHexString(video.toString().hashCode());
    }
}
