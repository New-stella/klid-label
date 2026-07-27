package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DatasetExportPathResolver} 순수 단위 테스트 — Spring 컨텍스트 없이 경로 계산/방어를 검증한다.
 *
 * <p>Phase 5A 폴더 규약(co-locate): {@code {dirname(원본영상)}/{RAW_SN}/v{n}/{orgnl|deid}/}.
 * 롤백 전략({@code labeling-root})은 구 규약 {@code {labeling_root}/{RAW_SN}/v{n}/{orgnl|deid}/}.
 */
class DatasetExportPathResolverTest {

    @TempDir
    Path nasRoot;

    /** 원본 영상이 놓인 디렉터리(관제 NAS 모사) — 허용 마운트 루트 하위. */
    private Path videoDir;
    private Path originalVideo;
    private DatasetExportPathResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        videoDir = Files.createDirectories(nasRoot.resolve("clips").resolve("2026-07"));
        originalVideo = Files.write(videoDir.resolve("clip-001.mp4"), new byte[]{1, 2, 3});
        resolver = new DatasetExportPathResolver(ArtifactRootTestSupport.coLocate(nasRoot));
    }

    private String rawPath() {
        return originalVideo.toString();
    }

    @Test
    @DisplayName("승인시_산출물이_원본영상_디렉터리_하위_rawSn_에_생성됨")
    void resolvesUnderOriginalVideoDirectory() {
        // given / when
        Path videoRoot = resolver.resolveVideoRoot(42L, rawPath());
        Path orgnl = resolver.resolve(42L, rawPath(), ExportKind.ORIGINAL, 1);

        // then — 원본 영상과 형제인 {rawSn} 디렉터리 하위에 v1/orgnl 이 놓인다
        assertThat(videoRoot).isEqualTo(videoDir.resolve("42"));
        assertThat(orgnl).isEqualTo(videoDir.resolve("42").resolve("v1").resolve("orgnl"));
        assertThat(orgnl.startsWith(videoRoot)).isTrue();
    }

    @Test
    @DisplayName("EXPORT_PATH_NM_이_영상루트를_가리켜_한_경로로_전_버전이_커버됨")
    void videoRootCoversAllVersions() {
        // given — 서로 다른 버전 2개
        Path videoRoot = resolver.resolveVideoRoot(42L, rawPath());

        // when
        Path v1 = resolver.resolve(42L, rawPath(), ExportKind.ORIGINAL, 1);
        Path v2 = resolver.resolve(42L, rawPath(), ExportKind.DEIDENTIFIED, 2);

        // then — 영상 루트 하나로 v1·v2 를 모두 커버한다(관제 롤백 선택 가능)
        assertThat(v1.startsWith(videoRoot)).isTrue();
        assertThat(v2.startsWith(videoRoot)).isTrue();
        assertThat(v1.getParent().getFileName()).hasToString("v1");
        assertThat(v2.getParent().getFileName()).hasToString("v2");
    }

    @Test
    @DisplayName("같은_디렉터리에_여러_원본영상이_있어도_rawSn_별로_산출루트가_분리됨")
    void separateRootsPerRawSnInSameDirectory() throws IOException {
        // given — 같은 디렉터리에 두 번째 원본 영상
        Path second = Files.write(videoDir.resolve("clip-002.mp4"), new byte[]{9});

        // when
        Path first = resolver.resolveVideoRoot(11L, rawPath());
        Path other = resolver.resolveVideoRoot(12L, second.toString());

        // then — 부모는 같고 rawSn 세그먼트로 분리된다
        assertThat(first.getParent()).isEqualTo(other.getParent());
        assertThat(first).isNotEqualTo(other);
    }

    @Test
    @DisplayName("RAW_FILE_PATH_NM_에_상위경로순회가_들어와도_산출이_허용루트_밖으로_안나감")
    void rejectsTraversalInRawFilePath() {
        // given — '..' 로 허용 루트 밖(부모)을 노리는 손상 데이터
        String traversal = videoDir.resolve("..").resolve("..").resolve("..")
                .resolve("outside").resolve("clip.mp4").toString();

        // when / then — 폴백 없이 거부(FORBIDDEN)
        assertThatThrownBy(() -> resolver.resolveVideoRoot(42L, traversal))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("심링크로_허용루트_밖을_가리켜도_toRealPath_재검증에서_차단됨")
    void rejectsSymlinkEscapingAllowedRoot(@TempDir Path outside) throws IOException {
        // given — 허용 루트 <안>의 디렉터리가 실제로는 밖을 가리키는 심링크
        Path realOutsideDir = Files.createDirectories(outside.resolve("elsewhere"));
        Path linked = nasRoot.resolve("linked");
        try {
            Files.createSymbolicLink(linked, realOutsideDir);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 심링크 미지원 FS — 검증 불가(건너뜀)
        }
        String rawPath = linked.resolve("clip.mp4").toString();

        // when / then — lexical 로는 허용 루트 하위지만 실경로가 밖이라 차단된다(CWE-59)
        assertThatThrownBy(() -> resolver.resolveVideoRoot(42L, rawPath))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("허용루트_밖_절대경로면_기본루트로_새지_않고_거부된다")
    void rejectsAbsolutePathOutsideAllowlist(@TempDir Path outside) {
        // given — 실측 손상 데이터(호스트 절대경로) 모사
        String hostPath = outside.resolve("Users").resolve("ck").resolve("clip.mp4").toString();

        // when / then — labeling-root 등 기본 루트로 폴백하지 않는다
        assertThatThrownBy(() -> resolver.resolveVideoRoot(4L, hostPath))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("RAW_FILE_PATH_NM_이_null_이거나_빈값이면_명시적_실패")
    void rejectsBlankRawFilePath() {
        // when / then — NPE 로 새지 않고 CustomException(INVALID_INPUT) 로 정규화된다(S9)
        assertThatThrownBy(() -> resolver.resolveVideoRoot(4L, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> resolver.resolveVideoRoot(4L, "   "))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("산출루트가_원본영상_파일자체와_같으면_거부된다")
    void rejectsRootCollidingWithOriginalVideo() throws IOException {
        // given — 확장자 없는 원본 파일명이 rawSn 과 같은 극단 케이스(원본 덮어쓰기 위험)
        Path collide = Files.write(videoDir.resolve("77"), new byte[]{1});

        // when / then
        assertThatThrownBy(() -> resolver.resolveVideoRoot(77L, collide.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("version이_0이하면_거부된다")
    void rejectsNonPositiveVersion() {
        assertThatThrownBy(() -> resolver.resolve(1L, rawPath(), ExportKind.ORIGINAL, 0))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> resolver.resolve(1L, rawPath(), ExportKind.ORIGINAL, -5))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("videoId가_0이하면_거부된다")
    void rejectsNonPositiveVideoId() {
        assertThatThrownBy(() -> resolver.resolve(0L, rawPath(), ExportKind.ORIGINAL, 1))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> resolver.resolve(-1L, rawPath(), ExportKind.DEIDENTIFIED, 1))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("kind가_null이면_거부된다")
    void rejectsNullKind() {
        assertThatThrownBy(() -> resolver.resolve(1L, rawPath(), null, 1))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("버전이_orgnl보다_상위경로다")
    void versionSegmentIsParentOfKind() {
        Path orgnl = resolver.resolve(7L, rawPath(), ExportKind.ORIGINAL, 3);
        Path deid = resolver.resolve(7L, rawPath(), ExportKind.DEIDENTIFIED, 3);

        assertThat(orgnl.getFileName()).hasToString("orgnl");
        assertThat(orgnl.getParent().getFileName()).hasToString("v3");
        assertThat(deid.getFileName()).hasToString("deid");
        assertThat(orgnl.getParent()).isEqualTo(deid.getParent());
    }

    @Test
    @DisplayName("롤백전략이면_구조가_labeling루트_하위로_되돌아간다")
    void labelingRootStrategyRestoresLegacyLayout(@TempDir Path legacyRoot) {
        // given — A-8 롤백 플래그
        DatasetExportPathResolver legacy =
                new DatasetExportPathResolver(ArtifactRootTestSupport.labelingRoot(legacyRoot));

        // when — 원본 경로를 주더라도 무시하고 고정 루트를 쓴다
        Path resolved = legacy.resolve(9L, rawPath(), ExportKind.DEIDENTIFIED, 2);

        // then
        assertThat(resolved).isEqualTo(legacyRoot.resolve("9").resolve("v2").resolve("deid"));
    }

    @Test
    @DisplayName("경로세그먼트에_구분자나_상위참조가_섞이면_거부된다")
    void rejectsUnsafeSegments() {
        Path base = resolver.resolveVideoRoot(42L, rawPath());

        assertThatThrownBy(() -> VideoArtifactRootResolver.resolveUnder(base, "../escape"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> VideoArtifactRootResolver.resolveUnder(base, "a/b"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }
}
