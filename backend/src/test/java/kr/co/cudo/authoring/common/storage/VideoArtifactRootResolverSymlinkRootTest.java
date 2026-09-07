package kr.co.cudo.authoring.common.storage;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 허용 저장소 루트가 <b>바로가기(심링크)</b>인 형상에서 산출 base 판정이 성립하는지 고정한다(ADR-065).
 *
 * <h3>왜 별도 시험이 필요한가 — 이 결함은 시험 밑을 그대로 통과했다</h3>
 * <p>운영 현장에서 허용 루트가 심링크였고, 위치 판정의 <b>첫 표기 단계만</b> 설정 원문과 비교해
 * 실경로 표기로 들어온 값이 거부됐다. 그런데 기존 시험은 그것을 잡지 못했다. macOS 에서는
 * {@code @TempDir} 자체가 이미 심링크 위에 있는데도 통과했는데, 이유는 <b>판정기가 돌려준 실경로를
 * 다시 판정기에 넣어 보지 않았기</b> 때문이다 — 즉 <b>왕복을 한 번도 닫지 않았다</b>.
 *
 * <p>게다가 그 우연한 심링크는 플랫폼에 따라 있고 없다(리눅스의 {@code /tmp} 는 심링크가 아니다).
 * 그래서 이 시험은 <b>링크를 명시적으로 만들어</b> 플랫폼과 무관하게 조건을 고정한다.
 *
 * <h3>기준 자리를 실경로로 먼저 편다</h3>
 * <p>{@code @TempDir} 가 이미 심링크일 수 있으므로 그대로 쓰면 "우리가 만든 링크"와 "환경이 준 링크"가
 * 섞여 무엇을 재는지 흐려진다. 그래서 {@code toRealPath()} 로 기준을 잡고 <b>우리가 만든 링크만</b>
 * 심링크가 되게 한다.
 *
 * <h3>넓힌 것과 넓히지 않은 것을 함께 잰다</h3>
 * <p>넓히는 변경에서 가장 위험한 것은 <b>무엇이 안 넓어졌는지</b>가 어디에도 남지 않는 것이다.
 * 그래서 통과 축뿐 아니라 <b>거부가 그대로임</b>(범위 밖을 가리키는 링크·상위 이동 표기)을 함께 잰다.
 * 그 둘이 없으면 이 시험은 "방어를 푼 것"과 구분되지 않는다.
 *
 * @design ADR-065
 */
class VideoArtifactRootResolverSymlinkRootTest {

    @TempDir
    Path tmpDir;

    /** 환경이 준 심링크를 걷어낸 기준 자리 — 우리가 만든 링크만 심링크가 되게 한다. */
    private Path base;
    /** 실제 디렉터리(허용 루트가 가리키는 자리). */
    private Path real;
    /** 허용 루트로 설정할 <b>표기</b> — {@link #real} 을 가리키는 바로가기. */
    private Path link;
    /** 허용 범위 밖 디렉터리. */
    private Path outside;

    @BeforeEach
    void setUp() throws IOException {
        base = tmpDir.toRealPath();
        real = Files.createDirectories(base.resolve("real"));
        outside = Files.createDirectories(base.resolve("outside"));
        link = base.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | FileSystemException e) {
            // 심링크를 만들 수 없는 환경(권한 없는 Windows 등)에서는 조용히 통과시키지 않고 건너뛴다.
            // 조용한 통과는 가드가 없는 것과 같다.
            assumeTrue(false, "심링크를 만들 수 없는 환경이라 건너뛴다: " + e);
        }
    }

    /** 허용 루트를 <b>표기(바로가기)</b> 로 설정한 리졸버 — 운영 현장 형상 그대로. */
    private VideoArtifactRootResolver resolverWithLinkRoot() {
        return new VideoArtifactRootResolver(
                link.toString(), "", link.toString(), link.toString(),
                base.resolve("labeling").toString(),
                VideoArtifactRootResolver.STRATEGY_CO_LOCATE);
    }

    private Path seedVideo(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path video = dir.resolve("clip.mp4");
        Files.writeString(video, "raw-bytes");
        return video;
    }

    // ── 넓힌 축 ────────────────────────────────────────────────

    @Test
    @DisplayName("★허용_루트가_바로가기일_때_루트가_실제로_가리키는_자리_표기로_들어와도_통과한다")
    void 실제_자리_표기로_들어와도_통과한다() throws IOException {
        // given — 원본이 "실제 자리" 표기로 적혀 있다(탐색 창구가 돌려주는 형태).
        Path video = seedVideo(real.resolve("videos"));

        // when
        Path root = resolverWithLinkRoot().videoRoot(1L, video.toString());

        // then — 거부되지 않고 산출 루트가 도출된다.
        //  ★이 단언이 이 시험의 존재 이유다. 표기 단계를 종전으로 되돌리면 여기서 FORBIDDEN 이 난다.
        assertThat(root).isEqualTo(video.getParent().resolve("1"));
    }

    @Test
    @DisplayName("허용_루트가_바로가기여도_원래_표기로_들어오면_여전히_통과한다")
    void 원래_표기로_들어오면_여전히_통과한다() throws IOException {
        // 하위호환 — 이미 원장에 적재된 표기 기준 경로가 계속 통과해야 한다.
        seedVideo(real.resolve("videos"));
        Path videoAsLabel = link.resolve("videos").resolve("clip.mp4");

        Path root = resolverWithLinkRoot().videoRoot(2L, videoAsLabel.toString());

        assertThat(root).isEqualTo(videoAsLabel.getParent().resolve("2"));
    }

    // ── 넓히지 않은 축 (이 셋이 없으면 방어를 푼 것과 구분되지 않는다) ──

    @Test
    @DisplayName("★허용_루트_안의_이름이_범위_밖을_가리키는_바로가기면_여전히_거부한다")
    void 범위_밖을_가리키는_바로가기는_여전히_거부한다() throws IOException {
        // given — 허용 루트 <안>의 이름이지만 실제로 닿는 자리는 밖이다(CWE-59).
        Path escape = real.resolve("escape");
        Files.createSymbolicLink(escape, outside);
        Path video = seedVideo(outside.resolve("videos"));
        Path videoViaEscape = link.resolve("escape").resolve("videos").resolve(video.getFileName());

        // then — 표기 단계는 통과하지만 실경로 재검사가 막는다. 그 방어는 이번 변경으로 완화되지 않았다.
        assertThatThrownBy(() -> resolverWithLinkRoot().videoRoot(3L, videoViaEscape.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("★상위로_거슬러_올라가는_표기는_여전히_거부한다")
    void 상위_이동_표기는_여전히_거부한다() throws IOException {
        // given — 정규화하면 허용 범위 밖으로 떨어진다(CWE-22).
        Path video = seedVideo(outside.resolve("videos"));
        String traversal = link.resolve("..").resolve("outside").resolve("videos")
                .resolve(video.getFileName()).toString();

        assertThatThrownBy(() -> resolverWithLinkRoot().videoRoot(4L, traversal))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("허용_범위_밖의_절대경로는_여전히_거부한다")
    void 허용_범위_밖의_절대경로는_여전히_거부한다() throws IOException {
        Path video = seedVideo(outside.resolve("videos"));

        assertThatThrownBy(() -> resolverWithLinkRoot().videoRoot(5L, video.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ── 범위가 넓어지지 않았음 ──────────────────────────────────

    @Test
    @DisplayName("바로가기가_없는_형상에서는_판정_결과가_종전과_같다")
    void 바로가기가_없는_형상에서는_판정_결과가_종전과_같다() throws IOException {
        // given — 허용 루트가 심링크가 아닌 보통 디렉터리다. 이때 realOrNearest(root) == root 이므로
        //  표기 단계의 판정이 변경 전과 비트 단위로 같아야 한다.
        Path plain = Files.createDirectories(base.resolve("plain"));
        VideoArtifactRootResolver resolver = new VideoArtifactRootResolver(
                plain.toString(), "", plain.toString(), plain.toString(),
                base.resolve("labeling").toString(),
                VideoArtifactRootResolver.STRATEGY_CO_LOCATE);
        Path inside = seedVideo(plain.resolve("videos"));
        Path outsideVideo = seedVideo(outside.resolve("videos2"));

        // then — 안은 통과, 밖은 거부. 넓어진 것이 없다.
        assertThat(resolver.videoRoot(6L, inside.toString()))
                .isEqualTo(inside.getParent().resolve("6"));
        assertThatThrownBy(() -> resolver.videoRoot(7L, outsideVideo.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
