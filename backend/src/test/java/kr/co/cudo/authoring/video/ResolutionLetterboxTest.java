package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.common.util.LabelCoordinateScaler;
import kr.co.cudo.authoring.common.util.LetterboxTransform;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.service.port.Java2DImageResizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-1 — 해상도 파생의 <b>종횡비 보존(레터박스)</b> 확정 정책 검증.
 *
 * <p>실제 이미지 파일({@code @TempDir} + ImageIO)로 픽셀을 검사한다 — 리사이즈 계층을 우회하면
 * GREEN 이 거짓 신호가 된다.
 */
class ResolutionLetterboxTest {

    @TempDir Path dir;

    private Path writeImage(String name, int w, int h, Color fill) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(fill);
        g.fillRect(0, 0, w, h);
        g.dispose();
        Path p = dir.resolve(name);
        Files.createDirectories(dir);
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    @Test
    @DisplayName("비16대9_원본이_레터박스로_종횡비를_보존한다")
    void portraitSourceKeepsAspectRatioWithLetterbox() throws IOException {
        // given — 1080x1920 세로 영상 프레임(비-16:9)
        Path src = writeImage("src.png", 1080, 1920, Color.RED);
        Path dst = dir.resolve("out/dst.png");

        // when — 720p(1280x720) 프리셋으로 리스케일
        new Java2DImageResizer().resize(src, dst, ResolutionPreset.RESL_720P.width(),
                ResolutionPreset.RESL_720P.height());

        // then — 캔버스는 목표 해상도, 내용은 종횡비 보존(405x720)으로 중앙 배치되고 좌우는 패딩
        BufferedImage out = ImageIO.read(dst.toFile());
        assertThat(out.getWidth()).isEqualTo(1280);
        assertThat(out.getHeight()).isEqualTo(720);

        LetterboxTransform box = LetterboxTransform.of(1080, 1920, 1280, 720);
        assertThat(box.drawW()).isEqualTo(405);
        assertThat(box.drawH()).isEqualTo(720);
        assertThat(box.offsetX()).isEqualTo((1280 - 405) / 2);
        assertThat(box.offsetY()).isZero();

        // 좌측 패딩은 검정, 중앙은 원본 색 — 강제 스케일(왜곡)이면 좌측도 빨강이라 실패한다.
        assertThat(new Color(out.getRGB(5, 360))).isEqualTo(Color.BLACK);
        assertThat(new Color(out.getRGB(640, 360))).isEqualTo(Color.RED);
        assertThat(new Color(out.getRGB(1275, 360))).isEqualTo(Color.BLACK);
    }

    @Test
    @DisplayName("레터박스_패딩_오프셋이_라벨_좌표에_반영된다")
    void labelCoordinatesIncludeLetterboxOffset() {
        LetterboxTransform box = LetterboxTransform.of(1080, 1920, 1280, 720);
        double scale = box.scale();

        // BBOX(정규 nested) — x 는 배율 + offsetX, y 는 배율 + offsetY(=0)
        String bbox = LabelCoordinateScaler.scalePointCn("[[100,200],[300,400]]", "BBOX",
                scale, scale, box.offsetX(), box.offsetY());
        long expectedX0 = Math.round(100 * scale + box.offsetX());
        long expectedX1 = Math.round(300 * scale + box.offsetX());
        long expectedY0 = Math.round(200 * scale);
        assertThat(bbox).isEqualTo("[[" + expectedX0 + "," + expectedY0 + "],["
                + expectedX1 + "," + Math.round(400 * scale) + "]]");
        // 오프셋 미반영(=단순 배율)이면 x 가 원점 쪽으로 밀려 그림과 어긋난다.
        assertThat(expectedX0).isNotEqualTo(Math.round(100 * scale));

        // SKELETON 삼중값 — x/y 만 변환, 가시성 v 는 불변
        String skeleton = LabelCoordinateScaler.scalePointCn("[[100,200,2]]", "SKELETON",
                scale, scale, box.offsetX(), box.offsetY());
        assertThat(skeleton).isEqualTo("[[" + expectedX0 + "," + expectedY0 + ",2]]");

        // 평탄 배열(POLYGON 레거시) — 짝수 인덱스 x, 홀수 인덱스 y
        String flat = LabelCoordinateScaler.scalePointCn("[100,200,300,400]", "POLYGON",
                scale, scale, box.offsetX(), box.offsetY());
        assertThat(flat).isEqualTo("[" + expectedX0 + "," + expectedY0 + ","
                + expectedX1 + "," + Math.round(400 * scale) + "]");

        // 객체 배열(세그멘테이션 레거시)
        String objects = LabelCoordinateScaler.scalePointCn("[{\"x\":100,\"y\":200}]", "POLYGON",
                scale, scale, box.offsetX(), box.offsetY());
        assertThat(objects).isEqualTo("[{\"x\":" + expectedX0 + ",\"y\":" + expectedY0 + "}]");
    }

    @Test
    @DisplayName("종횡비가_같으면_패딩없이_전체를_채운다")
    void sameAspectRatioHasNoPadding() throws IOException {
        Path src = writeImage("wide.png", 1920, 1080, Color.RED);
        Path dst = dir.resolve("out/wide-720.png");

        new Java2DImageResizer().resize(src, dst, 1280, 720);

        BufferedImage out = ImageIO.read(dst.toFile());
        assertThat(new Color(out.getRGB(2, 2))).isEqualTo(Color.RED);
        assertThat(LetterboxTransform.of(1920, 1080, 1280, 720).isExactFit()).isTrue();
    }

    @Test
    @DisplayName("음수_오프셋은_거부된다")
    void negativeOffsetRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        LabelCoordinateScaler.scalePointCn("[[1,2]]", "BBOX", 1.0, 1.0, -1d, 0d))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
