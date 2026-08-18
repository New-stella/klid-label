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
import java.util.concurrent.TimeUnit;

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
 * <p>
 * ★ 프로세스 대기에는 <b>상한</b>이 있다({@code authoring.ffmpeg.frame-timeout-sec}).
 *   상한이 없으면 ffmpeg 이 멈췄을 때 호출 스레드가 <b>영원히</b> 붙잡히고, 그 스레드가 포털 프레임
 *   추출이면 「무갱신 경과」 하트비트를 한 번도 치지 못해 <b>정상/멈춤을 구분하지 못한 채</b> 자산이
 *   방치로 판정된다({@code PortalFrameExtractRunner} 의 「남은 창」).
 */
@Slf4j
@Profile("!test")
@Component
public class BrampFfmpegFrameWriter implements FfmpegFrameExtractor.FrameWriter {

    /**
     * 프레임 1장 추출 프로세스의 대기 상한 기본값(초).
     *
     * <p>10분. {@link #writeFrameByNumber} 는 {@code select=eq(n,N)} 필터라 <b>입력 seek 없이</b>
     * 파일 처음부터 N번 프레임까지 전부 디코딩한다 — 즉 1장의 비용이 프레임 위치에 대략 비례해
     * 커진다. 그래서 seek 기반({@link #writeFrame}, 통상 수 초)의 상한으로는 넉넉하고, 프레임 정확
     * 추출의 뒤쪽 프레임에도 대개 충분한 값으로 잡았다.
     *
     * <p>상한을 넘으면 <b>실패로 끝낸다</b>(무한 대기로 매달리지 않는다). 더 긴 영상에서 정상 추출이
     * 이 상한에 걸린다면 설정으로 올릴 수 있으나, 올릴 때는 포털 고착 커트라인
     * ({@code portal.upload.stuck-timeout-minutes}) 과의 예산 관계를 함께 확인해야 한다.
     */
    public static final long DEFAULT_FRAME_TIMEOUT_SEC = 600L;

    private final String binary;
    private final long frameTimeoutSec;

    public BrampFfmpegFrameWriter(@Value("${authoring.ffmpeg.binary:ffmpeg}") String binary,
                                  @Value("${authoring.ffmpeg.frame-timeout-sec:600}") long frameTimeoutSec) {
        this.binary = binary;
        // ★ 여기서는 기본값으로 폴백한다 — 이 값은 <보호 장치>라 값이 이상하다고 꺼 버리면(무한 대기)
        //   막으려던 위험이 그대로 열린다. 「이상하면 그 회차를 건너뛴다」는 고착 스윕 규칙과 방향이
        //   반대인 것은 의도다: 그쪽은 <삭제의 예고>라 폴백이 곧 데이터 손실이지만, 이쪽은 폴백이
        //   곧 안전이다.
        if (frameTimeoutSec <= 0) {
            log.warn("[Batch][FrameWriter] authoring.ffmpeg.frame-timeout-sec 가 유효하지 않아 기본값을 "
                    + "사용한다(무한 대기로 두지 않는다) value={} default={}",
                    frameTimeoutSec, DEFAULT_FRAME_TIMEOUT_SEC);
            this.frameTimeoutSec = DEFAULT_FRAME_TIMEOUT_SEC;
        } else {
            this.frameTimeoutSec = frameTimeoutSec;
        }
    }

    /** 대기 상한(초) — 설정값 또는 기본값. */
    public long frameTimeoutSec() {
        return frameTimeoutSec;
    }

    /**
     * 프로세스 종료를 상한 안에서 기다린다.
     *
     * <p>상한을 넘으면 강제 종료 후 {@code false}. {@code Process#waitFor()}(무상한)를 직접 쓰지 않는
     * 유일한 통로이며, 테스트가 가짜 {@link Process} 로 상한 동작을 검증하는 seam 이다.
     *
     * @return 상한 안에 종료했으면 {@code true}
     */
    static boolean awaitExit(Process process, long timeoutSec) throws InterruptedException {
        if (process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            return true;
        }
        process.destroyForcibly();
        return false;
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
    private boolean runFfmpegByFrameNo(Path sourceVideo, Path outputFrame, int frameNo) throws IOException {
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
            if (!awaitExit(process, frameTimeoutSec)) {
                throw new FrameExtractTimeoutException(
                        "프레임 추출이 대기 상한을 넘겨 중단됐습니다: frameNo=" + frameNo
                                + " timeoutSec=" + frameTimeoutSec
                                + " src=" + sourceVideo.getFileName());
            }
            int exitCode = process.exitValue();
            return exitCode == 0 && Files.exists(outputFrame) && Files.size(outputFrame) >= 100;
        } catch (FrameExtractTimeoutException e) {
            // 상한 초과는 «실패»로 확정해 위로 올린다 — false 로 뭉개면 호출부가 일반 추출 실패와
            // 구분하지 못해 원인(느린 디코딩/멈춘 프로세스)이 로그에서 사라진다.
            log.error("[Batch][FrameWriter] ffmpeg frame-exact timeout frameNo={} timeoutSec={}",
                    frameNo, frameTimeoutSec);
            throw e;
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

    /** 프레임 추출 프로세스가 대기 상한을 넘긴 경우 — 일반 추출 실패와 구분하기 위한 전용 타입. */
    static class FrameExtractTimeoutException extends IOException {
        FrameExtractTimeoutException(String message) {
            super(message);
        }
    }

    private boolean runFfmpeg(Path sourceVideo, Path outputFrame, double seekSeconds) throws IOException {
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
            if (!awaitExit(process, frameTimeoutSec)) {
                throw new FrameExtractTimeoutException(
                        "프레임 추출이 대기 상한을 넘겨 중단됐습니다: seekSeconds=" + seekSeconds
                                + " timeoutSec=" + frameTimeoutSec
                                + " src=" + sourceVideo.getFileName());
            }
            int exitCode = process.exitValue();
            return exitCode == 0 && Files.exists(outputFrame) && Files.size(outputFrame) >= 100;
        } catch (FrameExtractTimeoutException e) {
            // 상한 초과면 0초 재시도도 하지 않는다 — 같은 파일을 같은 방식으로 다시 디코딩하는 것이라
            // 상한을 한 번 더 소진할 뿐이고, 그 사이 하트비트 창이 두 배로 벌어진다.
            log.error("[Batch][FrameWriter] ffmpeg timeout seekSeconds={} timeoutSec={}",
                    seekSeconds, frameTimeoutSec);
            throw e;
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
