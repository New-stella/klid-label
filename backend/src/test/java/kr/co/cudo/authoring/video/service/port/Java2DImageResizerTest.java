package kr.co.cudo.authoring.video.service.port;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Java2D 다운스케일 구현 단위 테스트 — 실제 BufferedImage 입출력으로 동작 검증.
 */
class Java2DImageResizerTest {

    private final Java2DImageResizer resizer = new Java2DImageResizer();

    private Path writeSourceImage(Path dir, int w, int h) throws Exception {
        Files.createDirectories(dir);
        Path src = dir.resolve("src.jpg");
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                img.setRGB(x, y, (x * 7 + y * 13) & 0xFFFFFF);
            }
        }
        ImageIO.write(img, "jpg", src.toFile());
        return src;
    }

    @Test
    @DisplayName("다운스케일시_타겟해상도_이미지가_생성됨")
    void downscaleProducesTargetSizeImage() throws Exception {
        Path dir = Files.createTempDirectory("j2d-");
        Path src = writeSourceImage(dir, 200, 120);
        Path dst = dir.resolve("out.jpg");

        resizer.resize(src, dst, 100, 60);

        assertThat(Files.exists(dst)).isTrue();
        assertThat(Files.size(dst)).isGreaterThanOrEqualTo(100L);
        BufferedImage out = ImageIO.read(dst.toFile());
        assertThat(out.getWidth()).isEqualTo(100);
        assertThat(out.getHeight()).isEqualTo(60);
    }

    @Test
    @DisplayName("실측_해상도_읽기")
    void readDimensions() throws Exception {
        Path dir = Files.createTempDirectory("j2d-");
        Path src = writeSourceImage(dir, 320, 240);

        int[] dim = resizer.readDimensions(src);

        assertThat(dim[0]).isEqualTo(320);
        assertThat(dim[1]).isEqualTo(240);
    }

    @Test
    @DisplayName("손상_이미지_읽기시_추상_500_오류")
    void corruptImageThrowsAbstractError() throws Exception {
        Path dir = Files.createTempDirectory("j2d-");
        Path bad = dir.resolve("corrupt.jpg");
        Files.write(bad, new byte[]{0, 1, 2, 3, 4}); // not a valid image

        assertThatThrownBy(() -> resizer.readDimensions(bad))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("dst_부모디렉터리_없어도_생성후_리스케일_성공")
    void createsMissingParentDirBeforeWrite() throws Exception {
        // given: 소스는 존재하지만 dst 부모 디렉터리는 아직 없는 파생 프레임 출력 경로
        Path dir = Files.createTempDirectory("j2d-");
        Path src = writeSourceImage(dir, 200, 120);
        Path dst = dir.resolve("resolution").resolve("28").resolve("frame_0001.jpg");
        assertThat(Files.exists(dst.getParent())).isFalse();

        // when: 존재하지 않는 하위 경로로 리사이즈 (수정 전엔 ImageIO.write IOException → CustomException)
        resizer.resize(src, dst, 100, 60);

        // then: 부모 디렉터리가 생성되고 파일이 정상 산출됨
        assertThat(Files.exists(dst)).isTrue();
        assertThat(Files.size(dst)).isGreaterThanOrEqualTo(100L);
        BufferedImage out = ImageIO.read(dst.toFile());
        assertThat(out.getWidth()).isEqualTo(100);
        assertThat(out.getHeight()).isEqualTo(60);
    }

    @Test
    @DisplayName("타겟해상도_0_이하시_400")
    void nonPositiveTargetRejected() throws Exception {
        Path dir = Files.createTempDirectory("j2d-");
        Path src = writeSourceImage(dir, 200, 120);
        Path dst = dir.resolve("out.jpg");

        assertThatThrownBy(() -> resizer.resize(src, dst, 0, 60))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }
}
