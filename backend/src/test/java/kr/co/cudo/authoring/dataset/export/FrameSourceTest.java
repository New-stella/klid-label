package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FrameSource} 순수 단위 테스트 — 종류별 base(raw-path/deidentified-path) 하위 정합 +
 * CWE-22 containment + 부재/이탈 fail-secure(Optional.empty) 를 검증한다.
 */
class FrameSourceTest {

    @TempDir
    Path rawBase;
    @TempDir
    Path deidBase;

    private FrameSource frameSource() {
        return new FrameSource(rawBase.toString(), deidBase.toString());
    }

    private Path writeDummy(Path base, String rel) throws IOException {
        Path target = base.resolve(rel);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "img-bytes");
        return target;
    }

    @Test
    @DisplayName("ORIGINAL은_raw_base하위_원본프레임경로로_해석된다")
    void resolvesOriginalUnderRawBase() throws IOException {
        // given — 원본 프레임을 raw-path 하위에 두고 srcFilePathNm 에 절대경로 저장
        Path img = writeDummy(rawBase, "frames/raw/7/frame-0.jpg");
        LsDataSrc frame = LsDataSrc.create(7L, 0L, img.toString(), null);

        // when
        Optional<Path> resolved = frameSource().resolveImage(7L, ExportKind.ORIGINAL, frame);

        // then
        assertThat(resolved).contains(img.normalize());
    }

    @Test
    @DisplayName("DEIDENTIFIED는_deid_base하위_비식별프레임경로로_해석된다")
    void resolvesDeidUnderDeidBase() throws IOException {
        // given — 비식별 프레임을 deid-path 하위에 두고 deIdntfSrcFilePathNm 에 저장
        Path deidImg = writeDummy(deidBase, "frames/deid/7/frame-0.jpg");
        LsDataSrc frame = LsDataSrc.create(7L, 0L, rawBase.resolve("frames/raw/7/frame-0.jpg").toString(), null);
        frame.attachDeidPath(deidImg.toString());

        // when
        Optional<Path> resolved = frameSource().resolveImage(7L, ExportKind.DEIDENTIFIED, frame);

        // then — deid base 하위 파일로 해석
        assertThat(resolved).contains(deidImg.normalize());
    }

    @Test
    @DisplayName("파일이_존재하지_않으면_empty")
    void emptyWhenFileMissing() {
        // given — 경로만 있고 실제 파일 없음
        LsDataSrc frame = LsDataSrc.create(7L, 0L, rawBase.resolve("frames/raw/7/none.jpg").toString(), null);

        // when / then
        assertThat(frameSource().resolveImage(7L, ExportKind.ORIGINAL, frame)).isEmpty();
    }

    @Test
    @DisplayName("경로가_null이면_empty")
    void emptyWhenPathNull() {
        // given — deid 경로 미설정 상태의 프레임에 DEIDENTIFIED 요청
        LsDataSrc frame = LsDataSrc.create(7L, 0L, rawBase.resolve("frames/raw/7/frame-0.jpg").toString(), null);

        // when / then — getDeidFilePath() == null → empty
        assertThat(frameSource().resolveImage(7L, ExportKind.DEIDENTIFIED, frame)).isEmpty();
    }

    @Test
    @DisplayName("base_밖_경로순회는_fail_secure로_empty")
    void emptyWhenPathTraversalOutsideBase() {
        // given — 악의적 DB 경로가 base 밖 절대경로를 가리킴
        LsDataSrc frame = LsDataSrc.create(7L, 0L, "/etc/passwd", null);

        // when / then — resolveSafe FORBIDDEN → 조용히 skip(empty)
        assertThat(frameSource().resolveImage(7L, ExportKind.ORIGINAL, frame)).isEmpty();
    }

    @Test
    @DisplayName("kind가_null이면_empty")
    void emptyWhenKindNull() {
        // given
        LsDataSrc frame = LsDataSrc.create(7L, 0L, rawBase.resolve("frames/raw/7/frame-0.jpg").toString(), null);

        // when / then — kind null 은 fail-secure
        assertThat(frameSource().resolveImage(7L, null, frame)).isEmpty();
    }

    @Test
    @DisplayName("frame이_null이면_empty")
    void emptyWhenFrameNull() {
        // when / then — 프레임 엔티티 null 은 fail-secure
        assertThat(frameSource().resolveImage(7L, ExportKind.ORIGINAL, null)).isEmpty();
    }

    @Test
    @DisplayName("경로가_blank면_empty")
    void emptyWhenPathBlank() {
        // given — srcFilePathNm 이 공백
        LsDataSrc frame = LsDataSrc.create(7L, 0L, "   ", null);

        // when / then — resolveSafe 호출 전 blank 가드로 skip
        assertThat(frameSource().resolveImage(7L, ExportKind.ORIGINAL, frame)).isEmpty();
    }

    @Test
    @DisplayName("대상경로가_디렉토리면_empty")
    void emptyWhenTargetIsDirectory() throws IOException {
        // given — base 하위에 파일이 아닌 디렉토리가 위치
        Path dir = rawBase.resolve("frames/raw/7/frame-0.jpg");
        Files.createDirectories(dir);
        LsDataSrc frame = LsDataSrc.create(7L, 0L, dir.toString(), null);

        // when / then — 정규파일이 아니므로 empty
        assertThat(frameSource().resolveImage(7L, ExportKind.ORIGINAL, frame)).isEmpty();
    }

    @Test
    @DisplayName("base내부_심볼릭링크가_base밖을_가리키면_empty")
    void emptyWhenSymlinkEscapesBase(@TempDir Path outside) throws IOException {
        // given — base 밖에 실제 타깃 파일을 두고, base 내부에 그 파일을 가리키는 심볼릭 링크 생성
        Path outsideTarget = outside.resolve("secret.jpg");
        Files.writeString(outsideTarget, "leaked");
        Path linkDir = rawBase.resolve("frames/raw/7");
        Files.createDirectories(linkDir);
        Path link = linkDir.resolve("frame-0.jpg");
        try {
            Files.createSymbolicLink(link, outsideTarget);
        } catch (IOException | UnsupportedOperationException e) {
            // 심링크 미지원 환경(Windows 권한 등) — 이 케이스는 스킵
            Assumptions.abort("symbolic link not supported in this environment");
        }
        LsDataSrc frame = LsDataSrc.create(7L, 0L, link.toString(), null);

        // when — lexical 로는 base 하위지만 toRealPath 는 base 밖
        Optional<Path> resolved = frameSource().resolveImage(7L, ExportKind.ORIGINAL, frame);

        // then — CWE-59 하드닝으로 fail-secure(empty)
        assertThat(resolved).isEmpty();
    }

    @Test
    @DisplayName("base내부_심볼릭링크가_base안을_가리키면_해석된다")
    void resolvesWhenSymlinkStaysInsideBase() throws IOException {
        // given — base 하위 실제 파일 + base 하위 다른 위치의 심볼릭 링크
        Path realImg = writeDummy(rawBase, "frames/raw/7/real.jpg");
        Path linkDir = rawBase.resolve("frames/raw/7");
        Path link = linkDir.resolve("frame-0.jpg");
        try {
            Files.createSymbolicLink(link, realImg);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic link not supported in this environment");
        }
        LsDataSrc frame = LsDataSrc.create(7L, 0L, link.toString(), null);

        // when — 심링크가 base 안을 가리키므로 통과
        Optional<Path> resolved = frameSource().resolveImage(7L, ExportKind.ORIGINAL, frame);

        // then — 정상 해석(비어있지 않음)
        assertThat(resolved).isPresent();
    }
}
