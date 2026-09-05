package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.common.util.LabelCoordinateScaler;
import kr.co.cudo.authoring.common.util.LetterboxTransform;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.service.port.Java2DImageResizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 해상도 파생 리스케일 확정 정책 검증 — <b>종횡비 보존 + 가변 캔버스(패딩 없음)</b> (@design ADR-018 · AC-003).
 *
 * <p>프리셋의 두 수치는 고정 캔버스가 아니라 <b>크기 상한</b>이다. 원본의 짧은 변을 프리셋의 짧은 값에
 * 맞추고, 그 배율로 계산한 긴 변이 프리셋의 긴 값을 넘을 때만 배율을 낮춘다.
 *
 * <p>실제 이미지 파일({@code @TempDir} + ImageIO)로 픽셀을 검사한다 — 리사이즈 계층을 우회하면
 * GREEN 이 거짓 신호가 된다.
 *
 * <p><b>구 기대값 폐기</b>: 이 클래스는 원래 「1080×1920 을 720p 로 리스케일하면 캔버스가 1280×720 이고
 * 좌우가 검정 패딩이며 라벨 x 에 offsetX 가 가산된다」를 단언했다. 검정 패딩이 학습 노이즈로 산출물에
 * 실려 나가므로 폐기됐고, 같은 원본은 이제 720×1280 으로 산출되며 좌표 변환은 곱셈만이다.
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

    /** 네 모서리를 서로 다른 색으로 칠한 원본 — 산출 모서리가 원본에서 유래했는지 판정하는 데 쓴다. */
    private Path writeCornerMarkedImage(String name, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.GRAY);
        g.fillRect(0, 0, w, h);
        int qw = w / 2;
        int qh = h / 2;
        // 좌상=빨강, 우상=초록, 좌하=파랑, 우하=검정. 검정 사분면을 일부러 둬서 "검정이면 패딩"이라는
        // 약한 판정에 기대지 않는다(원본이 검은 영상일 때 위양성).
        g.setColor(Color.RED);
        g.fillRect(0, 0, qw, qh);
        g.setColor(Color.GREEN);
        g.fillRect(w - qw, 0, qw, qh);
        g.setColor(Color.BLUE);
        g.fillRect(0, h - qh, qw, qh);
        g.setColor(Color.BLACK);
        g.fillRect(w - qw, h - qh, qw, qh);
        g.dispose();
        Path p = dir.resolve(name);
        Files.createDirectories(dir);
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    /**
     * AC-003 ⑧ — 720p 프리셋 기준 산출 크기 표.
     *
     * <p>세로 영상(1080×1920)과 파노라마(2560×1080)는 세 후보안(박스 피팅 / 짧은변만 / 짧은변+긴변 상한)이
     * 갈리는 유일한 지점이라 반드시 포함한다.
     */
    @ParameterizedTest(name = "{0}x{1} → {2}x{3}")
    @CsvSource({
            "1920, 1080, 1280,  720",   // 16:9 — 세 안이 모두 같다
            "1440, 1080,  960,  720",   // 4:3 — 짧은 변 기준
            " 640,  480,  960,  720",   // 업스케일 허용
            "1080, 1920,  720, 1280",   // 세로 영상 — 박스 피팅이면 405x720 로 과소 산출된다
            "2560, 1080, 1280,  540"    // 파노라마 — 긴 변 상한에 걸려 배율이 낮아진다
    })
    @DisplayName("720p_프리셋의_산출_크기가_원본_종횡비로_정해진다")
    void outputSizeFollowsSourceAspect(int srcW, int srcH, int expectedW, int expectedH) throws IOException {
        LetterboxTransform box = LetterboxTransform.of(srcW, srcH,
                ResolutionPreset.RESL_720P.width(), ResolutionPreset.RESL_720P.height());

        assertThat(box.drawW()).isEqualTo(expectedW);
        assertThat(box.drawH()).isEqualTo(expectedH);
        // 패딩이 없으므로 오프셋은 항상 0 — 라벨 좌표 변환이 곱셈만이라는 확정 사양의 전제다.
        assertThat(box.offsetX()).isZero();
        assertThat(box.offsetY()).isZero();
        // 종횡비 보존 — 반올림 오차 1px 이내.
        assertThat((double) expectedW / expectedH)
                .isCloseTo((double) srcW / srcH, org.assertj.core.data.Offset.offset(0.01));

        // 실제 픽셀 산출도 같은 크기여야 한다 — 계산기만 맞고 리사이즈가 어긋나면 좌표가 그림과 틀어진다.
        Path src = writeImage("src-" + srcW + "x" + srcH + ".png", srcW, srcH, Color.RED);
        Path dst = dir.resolve("out/dst-" + srcW + "x" + srcH + ".png");
        new Java2DImageResizer().resize(src, dst,
                ResolutionPreset.RESL_720P.width(), ResolutionPreset.RESL_720P.height());
        BufferedImage out = ImageIO.read(dst.toFile());
        assertThat(out.getWidth()).isEqualTo(expectedW);
        assertThat(out.getHeight()).isEqualTo(expectedH);
    }

    @Test
    @DisplayName("짧은변기준_긴변상한_규칙은_박스피팅과_다르다")
    void ruleDiffersFromBoxFitting() {
        int srcW = 1080;
        int srcH = 1920;
        int targetW = ResolutionPreset.RESL_720P.width();
        int targetH = ResolutionPreset.RESL_720P.height();

        LetterboxTransform box = LetterboxTransform.of(srcW, srcH, targetW, targetH);

        // 박스 피팅(min(targetW/srcW, targetH/srcH))은 축을 원본 방향에 맞추지 않아 405x720 을 낸다.
        double boxFitScale = Math.min((double) targetW / srcW, (double) targetH / srcH);
        assertThat(Math.round(srcW * boxFitScale)).isEqualTo(405);
        assertThat(box.drawW()).isNotEqualTo(405).isEqualTo(720);

        // 「짧은변 우선 + 긴변 상한」을 분기로 쓴 정의와 수학적으로 같음을 고정한다.
        int shortTarget = Math.min(targetW, targetH);
        int longTarget = Math.max(targetW, targetH);
        int srcShort = Math.min(srcW, srcH);
        int srcLong = Math.max(srcW, srcH);
        double branched = (double) shortTarget / srcShort;
        if (srcLong * branched > longTarget) {
            branched = (double) longTarget / srcLong;
        }
        assertThat(box.scale()).isEqualTo(branched);
    }

    @Test
    @DisplayName("긴변_상한_클램프가_없으면_파노라마_산출이_상한을_넘는다")
    void longSideClampBoundsPanorama() {
        // 2560x1080 을 짧은 변만 맞추면 긴 변이 1280 을 넘는다 → 클램프가 실제로 가드하는 지점.
        int srcW = 2560;
        int srcH = 1080;
        double shortOnlyScale = (double) ResolutionPreset.RESL_720P.height() / srcH;
        assertThat(Math.round(srcW * shortOnlyScale)).isGreaterThan(ResolutionPreset.RESL_720P.width());

        LetterboxTransform box = LetterboxTransform.of(srcW, srcH,
                ResolutionPreset.RESL_720P.width(), ResolutionPreset.RESL_720P.height());
        assertThat(box.drawW()).isEqualTo(ResolutionPreset.RESL_720P.width());
        assertThat(box.drawH()).isEqualTo(540);
    }

    @Test
    @DisplayName("산출_이미지의_네_모서리가_원본에서_유래한다_패딩_0")
    void everyCornerPixelOriginatesFromSource() throws IOException {
        // given — 비-16:9 세로 원본. 구 레터박스라면 좌우가 검정 패딩으로 채워졌을 형태다.
        Path src = writeCornerMarkedImage("corners.png", 1080, 1920);
        Path dst = dir.resolve("out/corners-720.png");

        // when
        new Java2DImageResizer().resize(src, dst, ResolutionPreset.RESL_720P.width(),
                ResolutionPreset.RESL_720P.height());

        // then — 캔버스가 산출 크기와 같고(720x1280) 네 모서리가 원본 사분면 색을 그대로 갖는다.
        BufferedImage out = ImageIO.read(dst.toFile());
        assertThat(out.getWidth()).isEqualTo(720);
        assertThat(out.getHeight()).isEqualTo(1280);

        int maxX = out.getWidth() - 1;
        int maxY = out.getHeight() - 1;
        assertThat(new Color(out.getRGB(0, 0))).isEqualTo(Color.RED);
        assertThat(new Color(out.getRGB(maxX, 0))).isEqualTo(Color.GREEN);
        assertThat(new Color(out.getRGB(0, maxY))).isEqualTo(Color.BLUE);
        // 우하 사분면은 원본이 검정이다 — "검정이면 패딩" 이라는 판정으로는 구분되지 않으므로,
        // 나머지 세 모서리가 원본 색을 갖는 것과 함께 봐야 패딩 부재가 증명된다.
        assertThat(new Color(out.getRGB(maxX, maxY))).isEqualTo(Color.BLACK);
    }

    @Test
    @DisplayName("라벨_좌표는_균일_배율의_곱셈만이며_오프셋_가산이_없다")
    void labelCoordinatesAreScaleOnly() {
        LetterboxTransform box = LetterboxTransform.of(1080, 1920,
                ResolutionPreset.RESL_720P.width(), ResolutionPreset.RESL_720P.height());
        double scale = box.scale();

        // 구 기대값 폐기 — 레터박스 시절에는 x 에 offsetX 가 가산돼 "단순 배율과 달라야 한다"가 기대였다.
        assertThat(box.offsetX()).isZero();
        assertThat(box.offsetY()).isZero();

        // BBOX(정규 nested)
        String bbox = LabelCoordinateScaler.scalePointCn("[[100,200],[300,400]]", "BBOX",
                scale, scale, box.offsetX(), box.offsetY());
        long expectedX0 = Math.round(100 * scale);
        long expectedX1 = Math.round(300 * scale);
        long expectedY0 = Math.round(200 * scale);
        assertThat(bbox).isEqualTo("[[" + expectedX0 + "," + expectedY0 + "],["
                + expectedX1 + "," + Math.round(400 * scale) + "]]");

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
    @DisplayName("변환된_라벨_좌표가_산출_이미지_경계_안에_든다")
    void scaledCoordinatesStayInsideOutputBounds() {
        // AC-003 — 원본 우하단 극단 좌표가 산출 경계를 넘지 않아야 한다(파노라마: 긴 변 상한 케이스).
        int srcW = 2560;
        int srcH = 1080;
        LetterboxTransform box = LetterboxTransform.of(srcW, srcH,
                ResolutionPreset.RESL_720P.width(), ResolutionPreset.RESL_720P.height());

        assertThat(Math.round(srcW * box.scale())).isLessThanOrEqualTo(box.drawW());
        assertThat(Math.round(srcH * box.scale())).isLessThanOrEqualTo(box.drawH());
    }

    @Test
    @DisplayName("종횡비가_같으면_전체를_채운다")
    void sameAspectRatioFillsWholeCanvas() throws IOException {
        Path src = writeImage("wide.png", 1920, 1080, Color.RED);
        Path dst = dir.resolve("out/wide-720.png");

        new Java2DImageResizer().resize(src, dst, 1280, 720);

        BufferedImage out = ImageIO.read(dst.toFile());
        assertThat(out.getWidth()).isEqualTo(1280);
        assertThat(out.getHeight()).isEqualTo(720);
        assertThat(new Color(out.getRGB(2, 2))).isEqualTo(Color.RED);
        assertThat(LetterboxTransform.of(1920, 1080, 1280, 720).isExactFit()).isTrue();
    }

    /**
     * <b>산출 파일 크기 = 스냅샷 산출 크기</b> — Phase B 가 리사이저에 넘기는 값이 프리셋 상한이어야
     * 성립한다(@design ADR-018).
     *
     * <p>구 케이스 폐기 사유: 이 검증은 원래 「산출 크기를 상한으로 다시 넣어도 같은 크기가 나온다(멱등)」
     * 였고 그 판단 자체는 옳았으나, 열거한 5종이 <b>전부 멱등 구간</b>이라 속성이 거짓인데도 통과했다.
     * 긴 변 상한이 정확히 걸리고 반올림이 올림으로 떨어지는 구간에서는 재적용이 1px 작은 크기를 내므로
     * (아래 {@link #recomputingFromComputedSizeIsNotIdempotent}), 리사이저에 넘길 값은 산출 크기가 아니라
     * <b>프리셋 상한</b>이어야 한다. 이제 그 배선을 실제 이미지 파일 크기로 직접 단언한다.
     */
    @ParameterizedTest(name = "{0}x{1} + {2} → {3}x{4}")
    @CsvSource({
            "1920, 1080, RESL_720P,  1280,  720",
            "1440, 1080, RESL_720P,   960,  720",
            " 640,  480, RESL_720P,   960,  720",
            "1080, 1920, RESL_720P,   720, 1280",
            "2560, 1080, RESL_720P,  1280,  540",
            // ↓ 재적용이 멱등이 아닌 구간 — 상한 대신 산출 크기를 넘기면 파일이 1px 작아진다.
            "2560, 1080, RESL_480P,   854,  360",
            "1366,  768, RESL_1080P, 1920, 1079"
    })
    @DisplayName("산출_이미지_파일_크기가_스냅샷_산출크기와_정확히_같다")
    void resizedFileMatchesSnapshotSize(int srcW, int srcH, ResolutionPreset preset,
                                        int expectedW, int expectedH) throws IOException {
        // Phase A 가 확정하는 값(스냅샷 targetW/targetH · 응답 · 라벨 배율의 원천).
        LetterboxTransform box = LetterboxTransform.of(srcW, srcH, preset.width(), preset.height());
        assertThat(box.drawW()).isEqualTo(expectedW);
        assertThat(box.drawH()).isEqualTo(expectedH);

        // Phase B 가 실제로 산출하는 파일 — 리사이저에 <b>프리셋 상한</b>을 넘긴 결과여야 한다.
        Path src = writeImage("snap-" + srcW + "x" + srcH + "-" + preset.name() + ".png", srcW, srcH, Color.RED);
        Path dst = dir.resolve("out/snap-" + srcW + "x" + srcH + "-" + preset.name() + ".png");
        new Java2DImageResizer().resize(src, dst, preset.width(), preset.height());

        BufferedImage out = ImageIO.read(dst.toFile());
        assertThat(out.getWidth()).isEqualTo(expectedW);
        assertThat(out.getHeight()).isEqualTo(expectedH);

        // 라벨 최대 좌표(원본 우하단)가 산출 <b>파일</b> 경계를 넘지 않는다 — 계산기끼리 맞아도 파일이
        // 1px 작으면 최우측 라벨이 이미지 밖으로 나간다.
        assertThat(Math.round(srcW * box.scale())).isLessThanOrEqualTo(out.getWidth());
        assertThat(Math.round(srcH * box.scale())).isLessThanOrEqualTo(out.getHeight());
    }

    /**
     * 잘못된 배선(산출 크기를 상한으로 넘김)이 <b>실제 픽셀</b>에서 어떤 손상을 내는지 고정한다.
     *
     * <p>계산기 수준 단언만으로는 "응답·DB 가 보고하는 크기와 실제 파일이 갈린다"는 증상이 드러나지 않는다.
     * 여기서는 파일을 실제로 산출해 1px 작아짐과 라벨 좌표 이탈을 직접 관측한다.
     */
    @Test
    @DisplayName("산출크기를_상한으로_넘기면_파일이_1px_작아지고_라벨이_경계를_벗어난다")
    void feedingComputedSizeShrinksFileAndPushesLabelOutOfBounds() throws IOException {
        int srcW = 2560;
        int srcH = 1080;
        ResolutionPreset preset = ResolutionPreset.RESL_480P;
        LetterboxTransform box = LetterboxTransform.of(srcW, srcH, preset.width(), preset.height());
        assertThat(box.drawW()).isEqualTo(854);

        Path src = writeImage("wrong-wiring.png", srcW, srcH, Color.RED);

        // 올바른 배선 — 프리셋 상한을 넘긴다.
        Path good = dir.resolve("out/good.png");
        new Java2DImageResizer().resize(src, good, preset.width(), preset.height());
        assertThat(ImageIO.read(good.toFile()).getWidth()).isEqualTo(box.drawW());

        // 잘못된 배선 — 이미 도출이 끝난 산출 크기를 상한으로 넘겨 변환이 두 번 걸린다.
        Path bad = dir.resolve("out/bad.png");
        new Java2DImageResizer().resize(src, bad, box.drawW(), box.drawH());
        BufferedImage badOut = ImageIO.read(bad.toFile());
        assertThat(badOut.getWidth()).isEqualTo(box.drawW() - 1);

        // 라벨은 스냅샷 배율로 변환되므로, 그 좌표가 잘못 산출된 파일의 경계를 넘는다.
        long maxLabelX = Math.round(srcW * box.scale());
        assertThat(maxLabelX).isGreaterThan(badOut.getWidth() - 1L);
    }

    @Test
    @DisplayName("산출크기를_상한으로_재적용하면_멱등이_아니다_그래서_프리셋을_넘겨야_한다")
    void recomputingFromComputedSizeIsNotIdempotent() {
        // 이 성질이 「Phase B 에 스냅샷 산출 크기를 넘기면 안 되는」 이유다. 멱등이 아님을 명시적으로
        // 고정해 두어야, 누군가 다시 targetW/targetH 를 넘기는 배선으로 되돌릴 때 근거가 남는다.
        record Case(int srcW, int srcH, ResolutionPreset preset) { }
        for (Case c : new Case[]{
                new Case(2560, 1080, ResolutionPreset.RESL_480P),
                new Case(1366, 768, ResolutionPreset.RESL_1080P)}) {
            LetterboxTransform first = LetterboxTransform.of(
                    c.srcW(), c.srcH(), c.preset().width(), c.preset().height());
            LetterboxTransform again = LetterboxTransform.of(
                    c.srcW(), c.srcH(), first.drawW(), first.drawH());
            assertThat(again.drawW()).isLessThan(first.drawW());
        }

        // 반대로 대부분의 조합은 멱등이라, 멱등 케이스만 열거하면 위 결함을 한 건도 잡지 못한다.
        for (int[] src : new int[][]{{1920, 1080}, {1440, 1080}, {640, 480}, {1080, 1920}, {2560, 1080}}) {
            LetterboxTransform first = LetterboxTransform.of(src[0], src[1],
                    ResolutionPreset.RESL_720P.width(), ResolutionPreset.RESL_720P.height());
            LetterboxTransform again = LetterboxTransform.of(src[0], src[1], first.drawW(), first.drawH());
            assertThat(again.drawW()).isEqualTo(first.drawW());
            assertThat(again.drawH()).isEqualTo(first.drawH());
        }
    }

    @Test
    @DisplayName("음수_오프셋은_거부된다")
    void negativeOffsetRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        LabelCoordinateScaler.scalePointCn("[[1,2]]", "BBOX", 1.0, 1.0, -1d, 0d))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
