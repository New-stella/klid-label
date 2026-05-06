package kr.co.cudo.authoring.batch.step;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * net.bramp.ffmpeg 래퍼 기반 FrameWriter — 운영(local 외 프로파일) 구현.
 * <p>
 * Phase 5 본체는 FfmpegFrameExtractor.FrameWriter 인터페이스로 추상화하여 단위 테스트 격리한다.
 * 본 구현은 ffmpeg 바이너리가 PATH 또는 ${authoring.ffmpeg.binary} 에 존재한다고 가정하며,
 * 단위 테스트는 별도 InMemoryFrameWriter 또는 Mockito stub 으로 대체한다.
 * <p>
 * 보안:
 * - Command Injection (CWE-78): net.bramp.ffmpeg API 는 인자를 분리 전달하므로 안전.
 *   FFmpegBuilder API 호출만 사용하고 사용자 입력으로 명령 문자열 조합 금지.
 *
 * 주의: 실제 net.bramp.ffmpeg.FFmpeg 호출은 ffmpeg 바이너리 의존이 강해
 * 본 클래스는 시스템에 ffmpeg 가 없는 환경(local 빌드 서버)에서 빈 frame 파일을 생성한다.
 * 운영 환경에서는 실제 추출 로직으로 확장.
 */
// TODO(Phase 5.1): net.bramp.ffmpeg FFmpegBuilder로 실제 프레임 추출 구현 — 현재는 stub
@Slf4j
@Profile("!test")
@Component
public class BrampFfmpegFrameWriter implements FfmpegFrameExtractor.FrameWriter {

    private final int threads;
    private final String binary;

    public BrampFfmpegFrameWriter(@Value("${authoring.ffmpeg.threads:2}") int threads,
                                   @Value("${authoring.ffmpeg.binary:ffmpeg}") String binary) {
        this.threads = threads;
        this.binary = binary;
    }

    @Override
    public boolean sourceExists(Path sourceVideo) {
        return sourceVideo != null && Files.exists(sourceVideo);
    }

    @Override
    public void writeFrame(Path sourceVideo, Path outputFrame, int frameIndex) throws IOException {
        // 운영 ffmpeg 호출은 별도 PR 에서 net.bramp.ffmpeg.FFmpegBuilder 사용으로 확장.
        // 본 Phase 5 단계에서는 빈 jpeg 파일을 생성하여 LS_DATA_SRC 레코드 정합만 보장.
        log.warn("[Batch] BrampFfmpegFrameWriter is stub - empty JPEG written for frameIndex={}", frameIndex);
        if (outputFrame.getParent() != null && !Files.exists(outputFrame.getParent())) {
            Files.createDirectories(outputFrame.getParent());
        }
        Files.write(outputFrame, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9}); // JPEG SOI/EOI
        log.debug("[Batch][FrameWriter] wrote stub frame index={} path={} (threads={}, binary={})",
                frameIndex, outputFrame.getFileName(), threads, binary);
    }
}
