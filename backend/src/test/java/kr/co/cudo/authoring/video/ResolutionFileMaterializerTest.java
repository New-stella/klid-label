package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.service.ResizeConcurrencyGate;
import kr.co.cudo.authoring.video.service.ResolutionFileMaterializer;
import kr.co.cudo.authoring.video.service.ResolutionSnapshot;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import kr.co.cudo.authoring.video.service.port.Java2DImageResizer;
import kr.co.cudo.authoring.video.service.port.VideoFileCopier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase B(파일 I/O) 단위 테스트 — 락·트랜잭션·리포지토리 없이 순수 파일 산출·정리(#3/#6)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResolutionFileMaterializerTest {

    @Mock ImageResizer imageResizer;
    @Mock VideoFileCopier videoFileCopier;
    @Mock ResizeConcurrencyGate resizeGate;

    ResolutionFileMaterializer materializer;

    /**
     * raw base 와 deid base 를 <b>같은 디렉토리</b>로 주입한다 — 운영(prd) 이 두 값을 동일 경로
     * ({@code /nas-storage})로 쓰는 환경을 재현한다. 이 조건에서도 파생 산출물이 비식별 전용
     * 서브트리({@code frames/deid/…})에 놓이는지 단언해야 base 이동이 실제로 효과가 있다.
     */
    @TempDir Path base;

    @BeforeEach
    void setup() {
        materializer = new ResolutionFileMaterializer(imageResizer, videoFileCopier, resizeGate);
        ReflectionTestUtils.setField(materializer, "storageDeidentifiedPath", base.toString());
    }

    private ResolutionSnapshot snapshot(long newRawSn, Path videoSrc, Path videoDst, List<ResolutionSnapshot.FrameSpec> frames) {
        return new ResolutionSnapshot(newRawSn, 200L, 42L, ResolutionPreset.RESL_720P,
                1920, 1080, 1280, 720, 0.6667, 0.6667, 0, 0, "rev1", videoSrc, videoDst,
                java.time.Instant.now(), frames);
    }

    @Test
    @DisplayName("materialize는_게이트획득후_비디오복사와_프레임리스케일을_수행하고_게이트를_반환한다")
    void materializeCopiesVideoAndResizesFramesUnderGate() {
        Path videoSrc = base.resolve("videos/deid.mp4");
        Path videoDst = base.resolve("resolution/900/video/RESL_720P.mp4");
        Path fSrc = base.resolve("frames/deid/f0.jpg");
        Path fDst = base.resolve("resolution/900/frames/f0.jpg");
        when(videoFileCopier.exists(videoSrc)).thenReturn(true);
        ResolutionSnapshot s = snapshot(900L, videoSrc, videoDst, List.of(
                new ResolutionSnapshot.FrameSpec(1000L, 0L, 0L, null, fSrc, fDst)));

        materializer.materialize(s);

        verify(resizeGate).acquire();
        verify(videoFileCopier).copy(videoSrc, videoDst);
        verify(imageResizer).resize(fSrc, fDst, 1280, 720);
        verify(resizeGate).release();
    }

    /**
     * 리사이저에 넘기는 값은 <b>프리셋 상한</b>이지 스냅샷의 산출 크기가 아니다 (@design ADR-018).
     *
     * <p>리사이저는 인자를 상한으로 받아 {@code LetterboxTransform} 을 스스로 적용한다. 이미 도출이 끝난
     * 산출 크기를 넘기면 같은 원본에 변환이 두 번 걸려, 긴 변 상한이 정확히 걸리고 반올림이 올림으로
     * 떨어지는 구간에서 산출 파일이 스냅샷보다 1px 작아진다(2560x1080+480p → 854 대신 853).
     * 그러면 라벨 최우측 좌표가 이미지 밖으로 나가고, 응답·DB 가 보고하는 크기와 실제 파일이 갈린다.
     *
     * <p>위 {@link #materializeCopiesVideoAndResizesFramesUnderGate} 는 프리셋(1280x720)과 산출
     * 크기(1280x720)가 <b>우연히 같은</b> 조합이라 이 배선을 구분하지 못한다 — 두 값이 갈리는 조합이
     * 반드시 필요하다.
     */
    @Test
    @DisplayName("리사이저에는_산출크기가_아니라_프리셋_상한을_넘긴다")
    void materializePassesPresetLimitNotComputedSize() {
        Path videoSrc = base.resolve("videos/deid.mp4");
        Path videoDst = base.resolve("resolution/910/video/RESL_480P.mp4");
        Path fSrc = base.resolve("frames/deid/f0.jpg");
        Path fDst = base.resolve("resolution/910/frames/f0.jpg");
        when(videoFileCopier.exists(videoSrc)).thenReturn(true);
        // 원본 2560x1080 + 480p → 산출 854x360. 프리셋 수치(854x480)와 산출 크기(854x360)가 갈린다.
        ResolutionSnapshot s = new ResolutionSnapshot(910L, 200L, 42L, ResolutionPreset.RESL_480P,
                2560, 1080, 854, 360, 0.33359375, 0.33359375, 0, 0, "rev1", videoSrc, videoDst,
                java.time.Instant.now(), List.of(
                new ResolutionSnapshot.FrameSpec(1000L, 0L, 0L, null, fSrc, fDst)));

        materializer.materialize(s);

        verify(imageResizer).resize(fSrc, fDst,
                ResolutionPreset.RESL_480P.width(), ResolutionPreset.RESL_480P.height());
        verify(imageResizer, never()).resize(any(), any(), eq(854), eq(360));
    }

    /**
     * <b>종단 가드</b> — {@code materialize} → <b>실제</b> {@link Java2DImageResizer} → 산출 파일 치수를
     * 한 줄로 잇는다 (@design ADR-018 · AC-003 ⑨).
     *
     * <p>위 {@link #materializePassesPresetLimitNotComputedSize} 는 Mockito 인자 검증이라 파일을 만들지
     * 않고, {@code ResolutionLetterboxTest} 는 리사이저를 직접 호출해 materializer 를 경유하지 않으며,
     * {@code ResolutionDerivativeFlowIntegrationTest} 는 리사이저를 {@code @MockBean} 으로 격리한다 —
     * 세 층 어디에도 「materializer 가 실제 리사이저에 무엇을 넘겨 어떤 크기의 파일이 나오는가」를
     * 확인하는 지점이 없었다. 리사이저 구현 교체·인자 의미 변경처럼 인자 층의 시야 밖에서 같은 증상이
     * 나는 변화를 이 케이스가 잡는다.
     *
     * <p><b>비멱등 조합이어야 한다</b>: 2560x1080 + 480p 는 프리셋 상한(854x480)과 산출 크기(854x360)가
     * 갈리고, 산출 크기를 상한으로 되넘기면 파일이 853x360 으로 1px 작아진다. 멱등 구간(예: 1920x1080
     * + 720p)만 쓰면 두 배선이 같은 결과를 내 이 결함 클래스를 한 건도 잡지 못한다.
     */
    @Test
    @DisplayName("실제_리사이저로_materialize하면_산출_파일이_스냅샷_산출크기와_같다")
    void materializeWithRealResizerWritesSnapshotSizedFile() throws Exception {
        ResolutionFileMaterializer real = new ResolutionFileMaterializer(
                new Java2DImageResizer(), videoFileCopier, resizeGate);
        ReflectionTestUtils.setField(real, "storageDeidentifiedPath", base.toString());

        Path videoSrc = base.resolve("videos/deid.mp4");
        Path videoDst = base.resolve("videos/resolution/200/920/RESL_480P.mp4");
        when(videoFileCopier.exists(videoSrc)).thenReturn(true);

        // 비식별 원본 프레임을 실제 이미지 파일로 깐다(2560x1080 파노라마).
        Path fSrc = base.resolve("frames/deid/42/frame-0.png");
        Files.createDirectories(fSrc.getParent());
        BufferedImage srcImg = new BufferedImage(2560, 1080, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = srcImg.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 2560, 1080);
        g.dispose();
        ImageIO.write(srcImg, "png", fSrc.toFile());

        Path fDst = base.resolve("frames/deid/920/frame-0.png");
        ResolutionSnapshot s = new ResolutionSnapshot(920L, 200L, 42L, ResolutionPreset.RESL_480P,
                2560, 1080, 854, 360, 0.33359375, 0.33359375, 0, 0, "rev1", videoSrc, videoDst,
                java.time.Instant.now(), List.of(
                new ResolutionSnapshot.FrameSpec(1000L, 0L, 0L, null, fSrc, fDst)));

        real.materialize(s);

        // 산출 파일 치수 == 스냅샷이 응답·DB·라벨 배율의 원천으로 확정한 산출 크기.
        BufferedImage out = ImageIO.read(fDst.toFile());
        assertThat(out.getWidth()).isEqualTo(s.targetW());
        assertThat(out.getHeight()).isEqualTo(s.targetH());

        // 라벨 최우측·최하단 좌표(원본 극단 × 스냅샷 배율)가 산출 파일 경계 안에 든다.
        assertThat(Math.round(s.srcW() * s.scaleX())).isLessThanOrEqualTo(out.getWidth());
        assertThat(Math.round(s.srcH() * s.scaleY())).isLessThanOrEqualTo(out.getHeight());
    }

    @Test
    @DisplayName("비식별_비디오원본이_없으면_NOT_FOUND로_실패하고_게이트를_반환한다")
    void materializeFailsWhenDeidVideoMissing() {
        Path videoSrc = base.resolve("videos/deid.mp4");
        Path videoDst = base.resolve("resolution/901/video/RESL_720P.mp4");
        when(videoFileCopier.exists(videoSrc)).thenReturn(false);
        ResolutionSnapshot s = snapshot(901L, videoSrc, videoDst, List.of());

        assertThatThrownBy(() -> materializer.materialize(s))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(videoFileCopier, never()).copy(any(), any());
        verify(resizeGate).release(); // 실패해도 슬롯 반환(finally)
    }

    @Test
    @DisplayName("cleanup은_파생_비디오와_비식별프레임디렉토리를_삭제하고_원본프레임은_보존하며_true를_반환한다")
    void cleanupRemovesDerivativeArtifactsAndReturnsTrue() throws Exception {
        long newRawSn = 902L;
        Path framesDir = base.resolve("frames/deid/" + newRawSn);
        Files.createDirectories(framesDir);
        Path frame0 = Files.write(framesDir.resolve("frame-0.jpg"), new byte[]{1, 2, 3});
        Path videoDir = base.resolve("videos/resolution/200");
        Files.createDirectories(videoDir);
        Path derivativeVideo = Files.write(videoDir.resolve("RESL_720P.mp4"), new byte[]{7});
        // 같은 base 안의 원본 프레임 서브트리 — cleanup 이 절대 건드리면 안 된다(원본 보존).
        Path rawFramesDir = base.resolve("frames/raw/" + newRawSn);
        Files.createDirectories(rawFramesDir);
        Path rawFrame = Files.write(rawFramesDir.resolve("frame-0.jpg"), new byte[]{4});
        Path unrelated = Files.write(base.resolve("keep.mp4"), new byte[]{0});

        boolean clean = materializer.cleanup(newRawSn, derivativeVideo);

        assertThat(clean).isTrue();
        assertThat(frame0).doesNotExist();
        assertThat(base.resolve("frames/deid/" + newRawSn)).doesNotExist();
        assertThat(derivativeVideo).doesNotExist();
        assertThat(rawFrame).exists();
        assertThat(unrelated).exists();
    }

    @Test
    @DisplayName("cleanup후에도_파일이_잔존하면_false를_반환한다(#3_잔존감지)")
    void cleanupReturnsFalseWhenArtifactRemains() throws Exception {
        long newRawSn = 903L;
        // videoDst 를 프레임 디렉토리 밖의 '비어있지 않은 디렉토리'로 만들어 파일 삭제(deleteIfExists)가
        // DirectoryNotEmptyException 으로 실패·잔존하도록 유도한다(프레임 재귀삭제 범위와 분리).
        Path videoDst = base.resolve("otherplace/RESL_720P.mp4");
        Files.createDirectories(videoDst);
        Files.write(videoDst.resolve("inner.tmp"), new byte[]{9});

        boolean clean = materializer.cleanup(newRawSn, videoDst);

        assertThat(clean).isFalse();
        assertThat(videoDst).exists(); // 잔존 확인
    }

    @Test
    @DisplayName("cleanup은_newRawSn이_null이면_true를_반환하고_아무것도_하지않는다")
    void cleanupNullIsNoOp() {
        assertThat(materializer.cleanup(null, null)).isTrue();
    }
}
