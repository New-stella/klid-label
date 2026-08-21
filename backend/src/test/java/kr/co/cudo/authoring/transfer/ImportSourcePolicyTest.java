package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
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
 * 사람이 넣는 산출물 경로가 <b>거부되어야 할 때 거부되는지</b> 고정한다(AC-048).
 *
 * <h3>왜 부정 경로가 이 도메인의 핵심 시험인가</h3>
 * <p>이 경로가 뚫리면 서버 파일시스템의 임의 위치를 읽게 된다. 정상 경로가 동작하는 것만 확인하고
 * 넘어가면, 막혀야 하는 것이 막히는지는 아무도 검증하지 않은 채 배포된다.
 *
 * <h3>표기만 보는 검사로는 부족하다</h3>
 * <p>허용 범위 <b>안</b>의 이름이 허용 범위 <b>밖</b>을 가리키는 심링크일 수 있다. 표기를 접어
 * 보는 검사는 그것을 통과시키므로, 실제로 닿는 자리로 다시 판정해야 한다(CWE-59). 그리고 판정한
 * 그 실경로를 돌려줘야 판정과 사용 대상이 갈라지지 않는다(CWE-367).
 *
 * @design DOMAIN-017
 * @design AC-048
 */
class ImportSourcePolicyTest {

    @TempDir
    Path allowedRoot;

    @TempDir
    Path outside;

    private ImportSourcePolicy policy;

    @BeforeEach
    void setUp() {
        VideoArtifactRootResolver resolver = new VideoArtifactRootResolver(
                allowedRoot.toString(), "", allowedRoot.toString(),
                allowedRoot.toString(), allowedRoot.toString(), "co-locate");
        policy = new ImportSourcePolicy(resolver);
    }

    @Test
    @DisplayName("허용_범위_안의_폴더는_실경로로_돌려준다")
    void 허용_범위_안의_폴더는_실경로로_돌려준다() throws IOException {
        Path folder = Files.createDirectory(allowedRoot.resolve("dataset"));

        Path verified = policy.verifyFolder(folder.toString());

        // 판정한 그 경로를 돌려줘야 판정 이후 다시 심링크를 따라가는 일이 없다(CWE-367).
        assertThat(verified).isEqualTo(folder.toRealPath());
    }

    @Test
    @DisplayName("상위로_거슬러_올라가는_표기는_거부한다")
    void 상위로_거슬러_올라가는_표기는_거부한다() throws IOException {
        Files.createDirectory(outside.resolve("secret"));
        String traversal = allowedRoot.resolve("..").resolve(outside.getFileName())
                .resolve("secret").toString();

        assertThatThrownBy(() -> policy.verifyFolder(traversal))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("허용_범위_밖의_절대경로는_거부한다")
    void 허용_범위_밖의_절대경로는_거부한다() throws IOException {
        Path elsewhere = Files.createDirectory(outside.resolve("elsewhere"));

        assertThatThrownBy(() -> policy.verifyFolder(elsewhere.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("허용_범위_안의_이름이_밖을_가리키는_심링크면_거부한다")
    void 허용_범위_안의_이름이_밖을_가리키는_심링크면_거부한다() throws IOException {
        Path target = Files.createDirectory(outside.resolve("target"));
        Path link = allowedRoot.resolve("looks-inside");
        Files.createSymbolicLink(link, target);

        // 표기만 보면 허용 범위 안이다 — 실제로 닿는 자리로 판정하지 않으면 여기서 통과한다(CWE-59).
        assertThatThrownBy(() -> policy.verifyFolder(link.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("허용_범위_안이지만_없는_폴더는_찾을_수_없음이다")
    void 허용_범위_안이지만_없는_폴더는_찾을_수_없음이다() {
        assertThatThrownBy(() -> policy.verifyFolder(allowedRoot.resolve("nope").toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("허용_범위_밖은_존재_여부와_무관하게_같은_사유로_거부한다")
    void 허용_범위_밖은_존재_여부와_무관하게_같은_사유로_거부한다() throws IOException {
        Path existing = Files.createDirectory(outside.resolve("exists"));
        Path missing = outside.resolve("missing");

        // 있는 것과 없는 것의 응답이 갈리면 응답 자체가 "그 위치가 있는지"를 알려주는 오라클이 된다.
        assertThatThrownBy(() -> policy.verifyFolder(existing.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> policy.verifyFolder(missing.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("빈_값과_길이를_넘는_값은_거부한다")
    void 빈_값과_길이를_넘는_값은_거부한다() {
        assertThatThrownBy(() -> policy.verifyFolder("  "))
                .isInstanceOf(CustomException.class);
        String tooLong = allowedRoot + "/" + "a".repeat(ImportSourcePolicy.FOLDER_PATH_MAX);
        assertThatThrownBy(() -> policy.verifyFolder(tooLong))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("폴더_자리에_파일을_주면_거부한다")
    void 폴더_자리에_파일을_주면_거부한다() throws IOException {
        Path file = Files.createFile(allowedRoot.resolve("not-a-folder.txt"));

        assertThatThrownBy(() -> policy.verifyFolder(file.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("영상_파일도_같은_기준으로_판정하고_실경로를_돌려준다")
    void 영상_파일도_같은_기준으로_판정하고_실경로를_돌려준다() throws IOException {
        Path video = Files.createFile(allowedRoot.resolve("clip.mp4"));
        assertThat(policy.verifyVideoFile(video.toString())).isEqualTo(video.toRealPath());

        Path outsideVideo = Files.createFile(outside.resolve("clip.mp4"));
        assertThatThrownBy(() -> policy.verifyVideoFile(outsideVideo.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        Path folder = Files.createDirectory(allowedRoot.resolve("folder"));
        assertThatThrownBy(() -> policy.verifyVideoFile(folder.toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }
}
