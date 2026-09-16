package kr.co.cudo.authoring.batch.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg 바이너리로 재생 인덱스를 무손실 재배치한다 — {@code -c copy -movflags +faststart}.
 *
 * <p>호출 방식은 프레임 추출기({@code BrampFfmpegFrameWriter})와 같다 — 설정 {@code authoring.ffmpeg.binary}
 * 의 바이너리를 {@link ProcessBuilder} 리스트 인자로 실행하고 출력은 버리며, 대기에는 상한을 둔다.
 *
 * <ul>
 *   <li><b>명령 인자는 고정</b>이다(CWE-78) — 가변 값은 우리가 검증한 두 파일 경로와 고정 컨테이너
 *       이름뿐이고 셸을 거치지 않는다.</li>
 *   <li>{@code -c copy} — 재인코딩하지 않는다. {@code -map 0} — 입력의 모든 스트림을 옮긴다
 *       (기본 선택은 영상·음성 각 1개만 고른다).</li>
 *   <li>{@code -n} — 출력이 이미 있으면 덮어쓰지 않고 실패한다. {@code -nostdin} — 입력 대기로 멈추지 않는다.</li>
 *   <li>대기 상한 초과 시 프로세스를 강제 종료하고 실패로 끝낸다. 인터럽트(종료 중 등)도 강제 종료한다.</li>
 * </ul>
 *
 * @design ADR-072
 */
@Slf4j
@Component
public class FfmpegDeidentFaststartRemuxer implements DeidentFaststartRemuxer {

    /** 대기 상한 기본값(초) — 스트림 복사라 수백 MB 도 통상 수 초~수십 초다. */
    static final long DEFAULT_TIMEOUT_SEC = 600L;

    private final String binary;
    private final long timeoutSec;

    public FfmpegDeidentFaststartRemuxer(
            @Value("${authoring.ffmpeg.binary:ffmpeg}") String binary,
            @Value("${authoring.deidentify.faststart.timeout-sec:600}") long timeoutSec) {
        this.binary = binary;
        if (timeoutSec <= 0) {
            log.warn("[Deident] faststart timeout-sec invalid — default used value={} default={}",
                    timeoutSec, DEFAULT_TIMEOUT_SEC);
            this.timeoutSec = DEFAULT_TIMEOUT_SEC;
        } else {
            this.timeoutSec = timeoutSec;
        }
    }

    /** 실행할 명령 — 단위 시험이 인자 구성을 고정한다. */
    List<String> command(Path source, Path output, Container container) {
        return List.of(
                binary,
                "-hide_banner",
                "-nostdin",
                "-loglevel", "error",
                "-n",
                "-i", source.toAbsolutePath().toString(),
                "-map", "0",
                "-c", "copy",
                "-movflags", "+faststart",
                "-f", container.muxer(),
                output.toAbsolutePath().toString());
    }

    @Override
    public void remux(Path source, Path output, Container container) throws IOException {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command(source, output, container));
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                throw new IOException("faststart remux timed out timeoutSec=" + timeoutSec);
            }
            int exit = process.exitValue();
            if (exit != 0) {
                throw new IOException("faststart remux exited abnormally exit=" + exit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("faststart remux interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
