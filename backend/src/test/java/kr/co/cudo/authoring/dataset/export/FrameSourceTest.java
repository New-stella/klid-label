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

        // then — deid base 하위 파일로 해석(A-1: 반환은 판정에 쓴 실경로)
        assertThat(resolved).contains(deidImg.toRealPath());
    }

    @Test
    @DisplayName("비식별본이_없는_프레임은_DEIDENTIFIED_export_에서_제외되고_rawBase_로_대체되지_않음")
    void deidKindNeverFallsBackToRawBase() throws IOException {
        // given — 비식별 경로가 raw base 하위를 가리키는 오염/구 파생 데이터. 파일은 실제로 존재한다.
        Path underRaw = writeDummy(rawBase, "resolution/9/frames/frame-0.jpg");
        LsDataSrc frame = LsDataSrc.create(9L, 0L, underRaw.toString(), null);
        frame.attachDeidPath(underRaw.toString());

        // when — DEIDENTIFIED 요청
        Optional<Path> resolved = frameSource().resolveImage(9L, ExportKind.DEIDENTIFIED, frame);

        // then — E-ISSUE-22: rawBase 폴백 제거 → 원본 픽셀로 대체하지 않고 fail-closed 로 제외
        assertThat(resolved).isEmpty();
    }

    @Test
    @DisplayName("raw_base와_deid_base가_동일_문자열이어도_frames_raw_하위_파일은_DEIDENTIFIED로_통과하지_않는다")
    void deidKindRejectsRawFrameSubtreeEvenWhenBasesAreIdentical(@TempDir Path sharedBase) throws IOException {
        // given — 운영(prd)처럼 STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH (동일 경로)
        FrameSource sameBaseSource = new FrameSource(sharedBase.toString(), sharedBase.toString());
        Path rawFrame = sharedBase.resolve("frames/raw/26/frame-0.jpg");
        Files.createDirectories(rawFrame.getParent());
        Files.writeString(rawFrame, "original-pixels");
        LsDataSrc frame = LsDataSrc.create(26L, 0L, rawFrame.toString(), null);
        frame.attachDeidPath(rawFrame.toString()); // 오염된 비식별 경로

        // when
        Optional<Path> resolved = sameBaseSource.resolveImage(26L, ExportKind.DEIDENTIFIED, frame);

        // then — base 검사는 통과하지만 서브트리 검사가 원본 프레임 유입을 차단(fail-closed)
        assertThat(rawFrame.startsWith(sharedBase)).isTrue();
        assertThat(resolved).isEmpty();
    }

    @Test
    @DisplayName("동일_base_환경에서도_frames_deid_하위_비식별프레임은_정상_해석된다")
    void deidKindStillResolvesUnderDeidSubtreeWhenBasesAreIdentical(@TempDir Path sharedBase) throws IOException {
        // given — 두 base 동일 + 규약대로 frames/deid 하위에 저장된 비식별 프레임(rawSn=26 참조 데이터 형태)
        FrameSource sameBaseSource = new FrameSource(sharedBase.toString(), sharedBase.toString());
        Path deidFrame = sharedBase.resolve("frames/deid/26/frame-0.jpg");
        Files.createDirectories(deidFrame.getParent());
        Files.writeString(deidFrame, "deid-pixels");
        LsDataSrc frame = LsDataSrc.create(26L, 0L, sharedBase.resolve("frames/raw/26/frame-0.jpg").toString(), null);
        frame.attachDeidPath(deidFrame.toString());

        // when / then — 정상 경로는 폴백 제거에도 깨지지 않는다
        assertThat(sameBaseSource.resolveImage(26L, ExportKind.DEIDENTIFIED, frame))
                .contains(deidFrame.toRealPath());
    }

    @Test
    @DisplayName("DEIDENTIFIED는_ORIGINAL경로를_orgnl로_해석하지않는다_격리")
    void deidDoesNotLeakOriginalPixelIntoOriginalKind() throws IOException {
        // given — raw base 하위에만 존재하는 파일. ORIGINAL 경로 필드는 비워 격리 확인
        Path deidUnderRaw = writeDummy(rawBase, "resolution/9/frames/frame-0.jpg");
        LsDataSrc frame = LsDataSrc.create(9L, 0L, null, null);
        frame.attachDeidPath(deidUnderRaw.toString());

        // when — ORIGINAL 요청은 srcFilePathNm(null) 만 참조 → deid 픽셀이 원본으로 새지 않음
        Optional<Path> original = frameSource().resolveImage(9L, ExportKind.ORIGINAL, frame);

        // then — ORIGINAL 은 rawBase 단일 + null 경로 → empty(격리 유지)
        assertThat(original).isEmpty();
    }

    @Test
    @DisplayName("DEIDENTIFIED_경로순회는_두_base_모두에서_fail_secure로_empty")
    void emptyWhenDeidPathTraversalOutsideBothBases() {
        // given — 악의적 deid 경로가 두 base 모두 밖의 절대경로를 가리킴
        LsDataSrc frame = LsDataSrc.create(7L, 0L, "/frames/raw/7/frame-0.jpg", null);
        frame.attachDeidPath("/etc/passwd");

        // when / then — deidBase·rawBase 양쪽 다 FORBIDDEN → skip(empty)
        assertThat(frameSource().resolveImage(7L, ExportKind.DEIDENTIFIED, frame)).isEmpty();
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

    @Test
    @DisplayName("비식별_경로가_원본프레임_심볼릭링크면_export에서_건너뛴다")
    void deidSymlinkToRawFrameIsSkipped() throws IOException {
        // given — 운영(prd)처럼 두 base 가 <b>같은 디렉토리</b>인 상황을 하나의 base 로 재현한다.
        Path shared = deidBase;
        Path rawFrame = shared.resolve("frames/raw/26/000001.jpg");
        Files.createDirectories(rawFrame.getParent());
        Files.writeString(rawFrame, "ORIGINAL-PII-PIXELS");
        Path deidLink = shared.resolve("frames/deid/26/000001.jpg");
        Files.createDirectories(deidLink.getParent());
        Files.createSymbolicLink(deidLink, rawFrame);

        LsDataSrc frame = LsDataSrc.create(26L, 0L, rawFrame.toString(), null);
        frame.attachDeidPath(deidLink.toString());
        FrameSource sharedBaseSource = new FrameSource(shared.toString(), shared.toString());

        // when — 비식별 벌 산출
        Optional<Path> resolved = sharedBaseSource.resolveImage(26L, ExportKind.DEIDENTIFIED, frame);

        // then — 심링크로 원본 픽셀이 비식별 벌에 실리지 않는다(fail-secure skip)
        assertThat(resolved).isEmpty();
    }

    @Test
    @DisplayName("비식별_경로가_실제_비식별파일이면_심링크가_아니어도_정상_해석된다")
    void deidRealFileStillResolvesUnderSharedBase() throws IOException {
        Path shared = deidBase;
        Path deidFrame = shared.resolve("frames/deid/26/000001.jpg");
        Files.createDirectories(deidFrame.getParent());
        Files.writeString(deidFrame, "DEID-PIXELS");
        LsDataSrc frame = LsDataSrc.create(26L, 0L, shared.resolve("frames/raw/26/000001.jpg").toString(), null);
        frame.attachDeidPath(deidFrame.toString());

        Optional<Path> resolved = new FrameSource(shared.toString(), shared.toString())
                .resolveImage(26L, ExportKind.DEIDENTIFIED, frame);

        assertThat(resolved).contains(deidFrame.toRealPath());
    }

    @Test
    @DisplayName("A1_비식별_검증후_심링크가_원본으로_교체돼도_export는_검증시점_비식별파일을_복사한다")
    void deidResolvedPathSurvivesPostVerificationSymlinkSwap() throws IOException {
        // given — 운영(prd)처럼 두 base 동일. 비식별 실파일 + 그것을 가리키는 심링크가 DB 경로다.
        Path shared = deidBase;
        Path realDeid = shared.resolve("frames/deid/26/real-000001.jpg");
        Files.createDirectories(realDeid.getParent());
        Files.writeString(realDeid, "DEID-PIXELS");
        Path rawFrame = shared.resolve("frames/raw/26/000001.jpg");
        Files.createDirectories(rawFrame.getParent());
        Files.writeString(rawFrame, "ORIGINAL-PII-PIXELS");
        Path deidLink = shared.resolve("frames/deid/26/000001.jpg");
        Files.createSymbolicLink(deidLink, realDeid);

        LsDataSrc frame = LsDataSrc.create(26L, 0L, rawFrame.toString(), null);
        frame.attachDeidPath(deidLink.toString());

        // when — 해석 직후(= export 가 파일을 열기 직전) 심링크를 원본 프레임으로 교체한다.
        Optional<Path> resolved = new FrameSource(shared.toString(), shared.toString())
                .resolveImage(26L, ExportKind.DEIDENTIFIED, frame);
        assertThat(resolved).isPresent();
        Files.delete(deidLink);
        Files.createSymbolicLink(deidLink, rawFrame);

        // then — export 가 복사하는 것은 검증 시점의 비식별 픽셀이다(원본 유출 없음).
        assertThat(Files.readString(resolved.get())).isEqualTo("DEID-PIXELS");
    }
}
