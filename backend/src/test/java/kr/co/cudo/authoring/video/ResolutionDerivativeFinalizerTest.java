package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.LsResolutionExportRepository;
import kr.co.cudo.authoring.video.repository.LsResolutionLblMapRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.ResizeConcurrencyGate;
import kr.co.cudo.authoring.video.service.ResolutionDerivativeFinalizer;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 해상도 파생영상 확정기 단위 테스트 — Phase 2 보안 수정(MEDIUM 고아 파일 정리) 회귀 방어.
 *
 * <p>{@link ResolutionDerivativeFinalizer#cleanupDerivativeArtifacts(Long)} 가 이미 기록된 파생 산출물
 * (리스케일 프레임 디렉토리 + 파생 비디오 파일)을 실제 FS 에서 삭제하는지 검증한다. 원본 경로는 삭제 대상이 아니다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResolutionDerivativeFinalizerTest {

    @Mock VideoRepository videoRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsDataLblRepository lblRepository;
    @Mock LsResolutionLblMapRepository lblMapRepository;
    @Mock LsResolutionExportRepository exportRepository;
    @Mock LsDeidentProcLogRepository deidentProcLogRepository;
    @Mock ImageResizer imageResizer;
    @Mock VideoFileCopier videoFileCopier;
    @Mock ResizeConcurrencyGate resizeGate;

    ResolutionDerivativeFinalizer finalizer;

    @TempDir Path base;

    @BeforeEach
    void setup() {
        finalizer = new ResolutionDerivativeFinalizer(videoRepository, srcRepository, lblRepository,
                lblMapRepository, exportRepository, deidentProcLogRepository, imageResizer, videoFileCopier, resizeGate);
        ReflectionTestUtils.setField(finalizer, "storageRawPath", base.toString());
    }

    @Test
    @DisplayName("finalize_중간실패시_파생_비디오와_프레임_파일이_정리된다")
    void cleanupRemovesDerivativeVideoAndFrameFiles() throws Exception {
        long newRawSn = 9001L;

        // given — finalize 중간까지 이미 쓰인 파생 산출물: 리스케일 프레임 디렉토리 + 파생 비디오 파일.
        Path framesDir = base.resolve("resolution/" + newRawSn + "/frames");
        Files.createDirectories(framesDir);
        Path frame0 = Files.write(framesDir.resolve("f0.jpg"), new byte[]{1, 2, 3});
        Path frame1 = Files.write(framesDir.resolve("f1.jpg"), new byte[]{4, 5, 6});

        Path videoDir = base.resolve("resolution/" + newRawSn + "/video");
        Files.createDirectories(videoDir);
        Path derivativeVideo = Files.write(videoDir.resolve("RES_720P.mp4"), new byte[]{7, 8, 9});

        LsDataRaw child = mock(LsDataRaw.class);
        when(child.getRawFilePathNm()).thenReturn(derivativeVideo.toString());
        when(videoRepository.findById(newRawSn)).thenReturn(Optional.of(child));

        // 원본(삭제 금지) 파일 — base 밖 정리 안 됨을 함께 확인.
        Path unrelated = Files.write(base.resolve("keep.mp4"), new byte[]{0});

        // when
        finalizer.cleanupDerivativeArtifacts(newRawSn);

        // then — 파생 프레임 디렉토리·프레임 파일·파생 비디오 모두 삭제, 무관 파일은 보존.
        assertThat(frame0).doesNotExist();
        assertThat(frame1).doesNotExist();
        assertThat(base.resolve("resolution/" + newRawSn)).doesNotExist();
        assertThat(derivativeVideo).doesNotExist();
        assertThat(unrelated).exists();
    }

    @Test
    @DisplayName("cleanup은_newRawSn이_null이면_아무것도_하지않는다")
    void cleanupIsNoOpForNull() {
        finalizer.cleanupDerivativeArtifacts(null); // 예외 없이 무시
    }
}
