package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.service.port.BrampVideoResizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BrampVideoResizer 단위 테스트 — 실제 ffmpeg 바이너리 없이 실패/정리 경로 검증.
 * <p>존재하지 않는 바이너리를 주입하여 즉시 실패시키고, hang 없이 CustomException 으로
 * 변환되며 출력 파일이 남지 않는지(정상 산출 미생성) 확인한다. waitFor 타임아웃 로직 자체는
 * 실제 바이너리 통합 테스트 범위이므로 여기서는 예외/정리 경로만 검증한다.
 */
class BrampVideoResizerTest {

    @Test
    @DisplayName("ffmpeg_실패시_예외_및_프로세스_정리_출력파일_미생성")
    void resizeFailureThrowsAndNoOutput(@TempDir Path tmp) throws Exception {
        // given — 존재하지 않는 바이너리 → 프로세스 시작 실패(IOException) 또는 non-zero exit
        BrampVideoResizer resizer = new BrampVideoResizer("ffmpeg-nonexistent-binary-xyz", 5);
        Path src = tmp.resolve("src.mp4");
        Files.write(src, new byte[]{1, 2, 3});
        Path dst = tmp.resolve("out").resolve("dst.mp4");

        // when / then
        assertThatThrownBy(() -> resizer.resize(src, dst, 0.5, 960, 540))
                .isInstanceOf(CustomException.class);

        // 정상 산출물이 생성되지 않았어야 함
        assertThat(Files.exists(dst)).isFalse();
    }
}
