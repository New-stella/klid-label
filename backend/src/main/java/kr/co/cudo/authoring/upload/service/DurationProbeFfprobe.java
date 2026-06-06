package kr.co.cudo.authoring.upload.service;

import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * ffprobe 바이너리 기반 duration 추출 — 운영 기본 구현.
 *
 * <p>{@link TusUploadService.DurationProbe} 구현체. 테스트는 stub 함수를 직접 주입해
 * ffprobe 바이너리 의존성을 격리한다.
 */
@Slf4j
@Component
public class DurationProbeFfprobe implements TusUploadService.DurationProbe {

    private final String ffprobePath;

    public DurationProbeFfprobe(@Value("${authoring.ffmpeg.ffprobe-binary:ffprobe}") String ffprobePath) {
        this.ffprobePath = ffprobePath;
    }

    @Override
    public int probe(Path filePath) {
        try {
            FFprobe ffprobe = new FFprobe(ffprobePath);
            FFmpegProbeResult probe = ffprobe.probe(filePath.toString());
            if (probe == null || probe.getFormat() == null) {
                throw new IllegalStateException("ffprobe 응답에 format 정보가 없습니다.");
            }
            return (int) Math.round(probe.getFormat().duration);
        } catch (IOException e) {
            throw new IllegalStateException("ffprobe 호출 실패: " + e.getMessage(), e);
        }
    }
}
