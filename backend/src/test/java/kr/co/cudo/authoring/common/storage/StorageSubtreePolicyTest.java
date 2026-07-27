package kr.co.cudo.authoring.common.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서브트리 격리 정책 단위 테스트 — 운영에서 raw base 와 deid base 가 <b>같은 경로</b>일 때
 * base 검사만으로는 막지 못하는 원본 프레임 유입을 서브트리 검사가 차단하는지 검증한다(E-ISSUE-22).
 */
class StorageSubtreePolicyTest {

    @TempDir Path base;

    @Test
    @DisplayName("raw_base와_deid_base가_동일_문자열이어도_frames_raw_하위는_비식별산출물로_인정되지_않는다")
    void rawFrameSubtreeIsRejectedEvenWhenBasesAreIdentical() {
        // given: 운영(prd)처럼 두 base 가 같은 디렉토리
        Path rawBase = base;
        Path deidBase = base;
        Path rawFrame = rawBase.resolve("frames/raw/26/frame-0.jpg").normalize();

        // then: base 검사는 통과하지만 서브트리 검사는 거부
        assertThat(rawFrame.startsWith(deidBase)).isTrue();
        assertThat(StorageSubtreePolicy.isDeidentifiedArtifact(deidBase, rawFrame)).isFalse();
        assertThat(StorageSubtreePolicy.isRawFrameArtifact(rawBase, rawFrame)).isTrue();
    }

    @Test
    @DisplayName("frames_deid_와_videos_하위만_비식별_산출물로_인정된다")
    void onlyDeidConventionsAreAccepted() {
        assertThat(StorageSubtreePolicy.isDeidentifiedArtifact(base,
                base.resolve("frames/deid/26/frame-0.jpg").normalize())).isTrue();
        assertThat(StorageSubtreePolicy.isDeidentifiedArtifact(base,
                base.resolve("videos/26/deidentified.mp4").normalize())).isTrue();
        assertThat(StorageSubtreePolicy.isDeidentifiedArtifact(base,
                base.resolve("videos/resolution/17/19/RESL_720P.mp4").normalize())).isTrue();
        // 구 파생 경로(raw base 하위 resolution/…)는 비식별 산출물이 아니다.
        assertThat(StorageSubtreePolicy.isDeidentifiedArtifact(base,
                base.resolve("resolution/19/frames/frame-0.jpg").normalize())).isFalse();
        // 레거시 원본 프레임 스킴(frames/{rawSn})도 비식별 서브트리가 아니다.
        assertThat(StorageSubtreePolicy.isDeidentifiedArtifact(base,
                base.resolve("frames/8/frame-0.jpg").normalize())).isFalse();
    }

    @Test
    @DisplayName("base_밖_경로는_어떤_서브트리도_아니다")
    void outsideBaseIsNeverAccepted() {
        Path outside = base.getParent().resolve("elsewhere/frames/deid/1/f.jpg").normalize();
        assertThat(StorageSubtreePolicy.isDeidentifiedArtifact(base, outside)).isFalse();
        assertThat(StorageSubtreePolicy.isRawFrameArtifact(base, outside)).isFalse();
    }

    @Test
    @DisplayName("파생_산출물_상대경로_규약이_비식별_서브트리를_만족한다")
    void generatedRelativePathsSatisfyPolicy() {
        assertThat(StorageSubtreePolicy.isDeidentifiedArtifact(base,
                base.resolve(StorageSubtreePolicy.deidFramesDir(19) + "/frame-0.jpg").normalize())).isTrue();
        assertThat(StorageSubtreePolicy.isDeidentifiedArtifact(base,
                base.resolve(StorageSubtreePolicy.resolutionVideoFile(17, 19, "RESL_480P")).normalize())).isTrue();
    }

    @Test
    @DisplayName("A6_파생영상_경로키에_파생RAW_SN이_포함돼_같은_부모프리셋_파생끼리_경로가_겹치지_않는다")
    void resolutionVideoPathIsKeyedByDerivativeRawSn() {
        String a = StorageSubtreePolicy.resolutionVideoFile(13, 20, "RESL_1080P");
        String b = StorageSubtreePolicy.resolutionVideoFile(13, 30, "RESL_1080P");

        // 구 규약((부모,프리셋)만 키잉)이면 두 값이 같아 상호 덮어쓰기·공유 파일 오삭제가 성립했다.
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo("videos/resolution/13/20/RESL_1080P.mp4");
        assertThat(b).isEqualTo("videos/resolution/13/30/RESL_1080P.mp4");
    }

    // ---------------------------------------------------------------------
    // H-2 — 심링크 우회(실파일 검증)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("비식별_경로가_원본프레임을_가리키는_심볼릭링크면_거부된다")
    void symlinkFromDeidToRawFrameIsRejected() throws IOException {
        // given: 운영(prd)처럼 두 base 가 같은 디렉토리 + 원본 프레임 실파일
        Path rawFrame = base.resolve("frames/raw/26/000001.jpg");
        Files.createDirectories(rawFrame.getParent());
        Files.writeString(rawFrame, "ORIGINAL-PII-PIXELS");
        Path deidLink = base.resolve("frames/deid/26/000001.jpg");
        Files.createDirectories(deidLink.getParent());
        Files.createSymbolicLink(deidLink, rawFrame);

        // then: lexical 서브트리 + realpath⊂realBase 는 둘 다 통과하지만 실경로 판정이 거부한다.
        assertThat(StorageSubtreePolicy.isDeidentifiedArtifact(base, deidLink)).isTrue();
        assertThat(deidLink.toRealPath().startsWith(base.toRealPath())).isTrue();
        StorageSubtreePolicy.Verification v =
                StorageSubtreePolicy.verifyDeidentifiedFile(base, deidLink.toString());
        assertThat(v.ok()).isFalse();
        assertThat(v.verdict()).isEqualTo(StorageSubtreePolicy.Verdict.OUTSIDE_DEID_SUBTREE);
    }

    @Test
    @DisplayName("실파일_비식별_프레임은_판정을_통과한다")
    void realDeidFileIsAccepted() throws IOException {
        Path deidFrame = base.resolve("frames/deid/26/000001.jpg");
        Files.createDirectories(deidFrame.getParent());
        Files.writeString(deidFrame, "DEID-PIXELS");

        StorageSubtreePolicy.Verification v =
                StorageSubtreePolicy.verifyDeidentifiedFile(base, deidFrame.toString());
        assertThat(v.ok()).isTrue();
        // A-1 — 반환 경로는 판정에 쓴 실경로(toRealPath)다. 실파일이면 같은 파일을 가리킨다.
        assertThat(v.path()).isEqualTo(deidFrame.toRealPath());
        assertThat(Files.readString(v.path())).isEqualTo("DEID-PIXELS");
    }

    // ---------------------------------------------------------------------
    // A-1 — 판정(실경로)과 사용(반환 경로)의 TOCTOU 창 (CWE-367/CWE-59)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("A1_판정후_심링크가_원본프레임으로_교체돼도_반환경로는_검증시점_실파일을_가리킨다")
    void verifiedPathIsImmuneToPostVerificationSymlinkSwap() throws IOException {
        // given — 운영(prd)처럼 두 base 가 같은 디렉토리. 정상 비식별 실파일 + 그것을 가리키는 심링크.
        Path realDeid = base.resolve("frames/deid/26/real-000001.jpg");
        Files.createDirectories(realDeid.getParent());
        Files.writeString(realDeid, "DEID-PIXELS");
        Path rawFrame = base.resolve("frames/raw/26/000001.jpg");
        Files.createDirectories(rawFrame.getParent());
        Files.writeString(rawFrame, "ORIGINAL-PII-PIXELS");
        Path deidLink = base.resolve("frames/deid/26/000001.jpg");
        Files.createSymbolicLink(deidLink, realDeid);

        // when — ①검증 통과 후 ②소비 직전에 심링크를 원본 프레임으로 교체(공격 창 재현)
        StorageSubtreePolicy.Verification v =
                StorageSubtreePolicy.verifyDeidentifiedFile(base, deidLink.toString());
        assertThat(v.ok()).isTrue();
        Files.delete(deidLink);
        Files.createSymbolicLink(deidLink, rawFrame);

        // then — 소비측이 v.path() 를 열면 검증 시점의 비식별 실파일을 읽는다.
        //        (lexical 경로를 반환하던 구 구현은 여기서 ORIGINAL-PII-PIXELS 를 읽어 원본이 비식별본으로
        //         서빙·export 됐다 — 이 단언을 되돌리면 실패한다.)
        assertThat(Files.readString(v.path())).isEqualTo("DEID-PIXELS");
        assertThat(v.path()).isEqualTo(realDeid.toRealPath());
        // 대조 — lexical 경로(교체된 심링크)를 그대로 열면 원본 픽셀이 나온다(공격이 실재함을 실측).
        assertThat(Files.readString(deidLink)).isEqualTo("ORIGINAL-PII-PIXELS");
    }

    @Test
    @DisplayName("부재_디렉토리_base이탈_공백은_각각의_사유코드로_거부된다")
    void nonOkVerdictsAreDistinguished() throws IOException {
        assertThat(StorageSubtreePolicy.verifyDeidentifiedFile(base, "  ").verdict())
                .isEqualTo(StorageSubtreePolicy.Verdict.BLANK);
        assertThat(StorageSubtreePolicy.verifyDeidentifiedFile(base,
                base.resolve("frames/deid/26/none.jpg").toString()).verdict())
                .isEqualTo(StorageSubtreePolicy.Verdict.MISSING);
        Path dir = base.resolve("frames/deid/26/sub");
        Files.createDirectories(dir);
        assertThat(StorageSubtreePolicy.verifyDeidentifiedFile(base, dir.toString()).verdict())
                .isEqualTo(StorageSubtreePolicy.Verdict.NOT_REGULAR_FILE);
        assertThat(StorageSubtreePolicy.verifyDeidentifiedFile(base,
                base.getParent().resolve("elsewhere/frames/deid/1/f.jpg").toString()).verdict())
                .isEqualTo(StorageSubtreePolicy.Verdict.OUTSIDE_BASE);
    }
}
