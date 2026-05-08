package kr.co.cudo.authoring.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SeedImageGeneratorTest {

    @Test
    @DisplayName("SeedImageGenerator_생성_성공시_1920_1080_JPEG_저장")
    void generatesValidJpeg(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("seed/9001/frame_1.jpg");

        boolean created = SeedImageGenerator.generate(
                target, "EVT_FALL", "CCTV-001", 1, LocalDateTime.now());

        assertThat(created).isTrue();
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.size(target)).isGreaterThan(1024L); // 비어있지 않음

        BufferedImage img = ImageIO.read(target.toFile());
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isEqualTo(SeedImageGenerator.IMAGE_WIDTH);
        assertThat(img.getHeight()).isEqualTo(SeedImageGenerator.IMAGE_HEIGHT);
    }

    @Test
    @DisplayName("SeedImageGenerator_재실행시_skip_멱등성")
    void idempotentSkip(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("seed/9002/frame_1.jpg");
        boolean first = SeedImageGenerator.generate(target, "EVT_VIOLENCE", "CCTV-002", 1, null);
        long firstSize = Files.size(target);
        long firstMtime = Files.getLastModifiedTime(target).toMillis();

        Thread.sleep(20);

        boolean second = SeedImageGenerator.generate(target, "EVT_VIOLENCE", "CCTV-002", 1, null);
        long secondSize = Files.size(target);
        long secondMtime = Files.getLastModifiedTime(target).toMillis();

        assertThat(first).isTrue();
        assertThat(second).isFalse(); // 두 번째는 skip
        assertThat(secondSize).isEqualTo(firstSize);
        assertThat(secondMtime).isEqualTo(firstMtime); // 변경되지 않음
    }

    @Test
    @DisplayName("SeedImageGenerator_알수없는_이벤트_코드도_생성_성공")
    void unknownEventCodeStillGenerates(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("frame.jpg");
        boolean created = SeedImageGenerator.generate(target, "EVT_UNKNOWN", "CCTV-XYZ", 5, null);
        assertThat(created).isTrue();
        BufferedImage img = ImageIO.read(target.toFile());
        assertThat(img.getWidth()).isEqualTo(SeedImageGenerator.IMAGE_WIDTH);
    }

    @Test
    @DisplayName("SeedImageGenerator_부모_디렉토리_자동_생성")
    void createsParentDirectories(@TempDir Path tmp) throws Exception {
        Path nested = tmp.resolve("a/b/c/d/frame_1.jpg");
        assertThat(Files.exists(nested.getParent())).isFalse();
        SeedImageGenerator.generate(nested, "EVT_FIRE", "CCTV-N", 1, null);
        assertThat(Files.exists(nested)).isTrue();
    }

    @Test
    @DisplayName("SeedImageGenerator_resolveSafe_PathTraversal_차단")
    void resolveSafePathTraversalBlocked(@TempDir Path tmp) {
        Path base = tmp.toAbsolutePath().normalize();
        // SeedImageRunner 의 resolveSafe 도 동일 정책 검증 — 별도 클래스이므로 간접 검증
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                kr.co.cudo.authoring.dev.SeedImageRunner.resolveSafe(base, "../../etc/passwd"));
    }
}
