package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import net.bramp.ffmpeg.probe.FFmpegStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ffprobe 바이너리 기반 포털 영상 프로브 — 운영 기본 구현({@code @Profile("!test")}).
 *
 * <p>{@link PortalVideoProbe} 구현체. 비디오 스트림 존재 여부 + 길이(초) + fps 를 반환한다.
 * 테스트는 stub 함수를 직접 주입해 바이너리 의존을 격리한다.
 *
 * <p><b>타임아웃 필수(시나리오 #6)</b>: ffprobe 가 손상/미지원 파일에서 무한 대기하는 것을 막기 위해
 * 단일 스레드 executor + {@link Future#get(long, TimeUnit)} 로 하드 타임아웃을 강제한다. 초과 시
 * {@link RuntimeException} 을 던져(호출자 러너가 markFailed 처리) 영구 PROCESSING 을 예방한다.
 */
@Slf4j
@Profile("!test")
@Component
public class PortalVideoProbeFfprobe implements PortalVideoProbe {

    private final String ffprobePath;
    private final long timeoutSec;

    public PortalVideoProbeFfprobe(
            @Value("${authoring.ffmpeg.ffprobe-binary:ffprobe}") String ffprobePath,
            PortalUploadProperties properties) {
        this.ffprobePath = ffprobePath;
        long timeoutSec = properties.probeTimeoutSec();
        this.timeoutSec = timeoutSec > 0 ? timeoutSec : 30L;
    }

    @Override
    public Result probe(Path filePath) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "portal-ffprobe");
            t.setDaemon(true);
            return t;
        });
        Future<Result> future = executor.submit(probeTask(filePath));
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("ffprobe 타임아웃(" + timeoutSec + "s)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IllegalStateException("ffprobe 실패: "
                    + (cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName()), cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ffprobe 중단");
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Result> probeTask(Path filePath) {
        return () -> {
            FFprobe ffprobe = new FFprobe(ffprobePath);
            FFmpegProbeResult probe = ffprobe.probe(filePath.toString());
            if (probe == null || probe.getFormat() == null) {
                throw new IllegalStateException("ffprobe 응답에 format 정보가 없습니다.");
            }
            List<FFmpegStream> streams = probe.getStreams() == null ? List.of() : probe.getStreams();
            FFmpegStream video = streams.stream()
                    .filter(s -> s.codec_type == FFmpegStream.CodecType.VIDEO)
                    .findFirst()
                    .orElse(null);
            double durationSec = probe.getFormat().duration;
            if (video == null) {
                // 오디오 전용/비디오 스트림 부재 — hasVideoStream=false 로 호출자가 거부(#6).
                return new Result(false, durationSec, 0.0);
            }
            double fps = fpsOf(video);
            // 스트림에 duration 이 있으면 우선(포맷 duration 이 0 인 컨테이너 방어).
            double effectiveDuration = durationSec > 0 ? durationSec
                    : (video.duration > 0 ? video.duration : 0.0);
            return new Result(true, effectiveDuration, fps);
        };
    }

    /** avg_frame_rate 우선, 없으면 r_frame_rate. 파싱 불가/0 이면 0 반환(호출자 폴백). */
    private static double fpsOf(FFmpegStream video) {
        double fps = toDouble(video.avg_frame_rate);
        if (fps <= 0) {
            fps = toDouble(video.r_frame_rate);
        }
        return fps;
    }

    private static double toDouble(org.apache.commons.lang3.math.Fraction fraction) {
        if (fraction == null) {
            return 0.0;
        }
        try {
            double v = fraction.doubleValue();
            return Double.isFinite(v) && v > 0 ? v : 0.0;
        } catch (ArithmeticException e) {
            return 0.0;
        }
    }
}
