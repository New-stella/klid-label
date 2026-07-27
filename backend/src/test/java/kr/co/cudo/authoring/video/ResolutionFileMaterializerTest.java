package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.service.ResizeConcurrencyGate;
import kr.co.cudo.authoring.video.service.ResolutionFileMaterializer;
import kr.co.cudo.authoring.video.service.ResolutionSnapshot;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
