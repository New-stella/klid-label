package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 사람이 넣는 산출물 경로가 <b>거부되어야 할 때 거부되는지</b> 고정한다(AC-1080).
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
 * <h3>★ 이 시험이 <b>덮지 못하던</b> 것 — 왕복을 닫지 않았다 (ADR-065)</h3>
 * <p>구 시험은 <b>표기 경로를 넣고 실경로가 나오는 것</b>만 확인했다. 판정기가 돌려준 그 실경로를
 * <b>다시 판정기에 넣어 보지 않았다.</b> 그래서 허용 루트 자체가 바로가기인 형상에서 표기 단계가
 * 자기가 내준 경로를 거부하는데도 전건 초록이었다. 맥에서는 임시 폴더가 이미 바로가기 뒤에 있어
 * 형상은 갖춰져 있었는데도 그랬고, 리눅스에서는 형상조차 만들어지지 않았다.
 *
 * <p>그래서 아래 왕복 가드는 <b>플랫폼에 기대지 않고</b> 바로가기를 직접 만들어 형상을 고정한다.
 * 만들 수 없는 환경에서는 조용히 통과시키지 않고 <b>건너뛴다</b>({@link Assumptions}) — 재현되지
 * 않는 것을 초록으로 세면 가드가 없는 것과 같다.
 *
 * @design DOMAIN-017
 * @design ADR-065
 * @design AC-1079
 * @design AC-1080
 */
class ImportSourcePolicyTest {

    @TempDir
    Path allowedRoot;

    @TempDir
    Path outside;

    /** 허용 루트로 <b>선언할 바로가기</b>를 놓을 자리 — 링크 자체는 허용 범위 안이 아니어도 된다. */
    @TempDir
    Path linkHome;

    private ImportSourcePolicy policy;

    @BeforeEach
    void setUp() {
        policy = policyWithRoot(allowedRoot);
    }

    private static ImportSourcePolicy policyWithRoot(Path root) {
        return new ImportSourcePolicy(new VideoArtifactRootResolver(
                root.toString(), "", root.toString(), root.toString(), root.toString(), "co-locate"));
    }

    /**
     * 허용 루트 <b>자체가 바로가기</b>인 판정기를 만든다 — 설정에 적힌 표기와 실제로 닿는 자리가
     * 서로 다른 형상이다. 바로가기를 만들 수 없는 환경이면 통과가 아니라 <b>건너뜀</b>으로 답한다.
     */
    private ImportSourcePolicy symlinkRootPolicy() {
        Path link = linkHome.resolve("root-link");
        try {
            Files.createSymbolicLink(link, allowedRoot);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("바로가기를 만들 수 없는 환경이다 — 이 축은 여기서 재현되지 않는다: "
                    + e.getClass().getSimpleName());
        }
        return policyWithRoot(link);
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

    // ------------------------------------------------------------ 허용 루트가 바로가기인 형상 (ADR-065)

    /**
     * ★핵심 가드 — <b>판정기가 돌려준 실경로를 그대로 되넣으면 통과한다</b>.
     *
     * <p>표기 단계가 설정 원문만 인정하면 여기서 400 이 난다. 그 상태가 현장에서 「고른 폴더를 서버가
     * 거부한다」로 나타났다. 이 단언이 없으면 표기 단계를 되돌려도 시험이 전부 초록이다.
     */
    @Test
    @DisplayName("★바로가기_루트가_돌려준_실경로를_그대로_되넣으면_통과한다_왕복이_닫힌다")
    void 바로가기_루트가_돌려준_실경로를_그대로_되넣으면_통과한다_왕복이_닫힌다() throws IOException {
        ImportSourcePolicy linked = symlinkRootPolicy();
        Path folder = Files.createDirectories(allowedRoot.resolve("dataset"));

        // 표기(바로가기 루트 아래)로 물으면 실경로가 돌아온다.
        Path first = linked.verifyFolder(linkHome.resolve("root-link").resolve("dataset").toString());
        assertThat(first).isEqualTo(folder.toRealPath());

        // 그 실경로를 되넣으면 같은 자리로 통과해야 한다 — 화면이 하는 일이 정확히 이것이다.
        assertThat(linked.verifyFolder(first.toString())).isEqualTo(first);
    }

    /** 바로가기 루트의 <b>원래 표기</b>로도 계속 통과한다 — 넓히기만 했고 좁힌 것이 없다. */
    @Test
    @DisplayName("바로가기_루트의_원래_표기로도_여전히_통과한다")
    void 바로가기_루트의_원래_표기로도_여전히_통과한다() throws IOException {
        ImportSourcePolicy linked = symlinkRootPolicy();
        Files.createDirectories(allowedRoot.resolve("dataset"));

        assertThat(linked.verifyFolder(linkHome.resolve("root-link").resolve("dataset").toString()))
                .isEqualTo(allowedRoot.resolve("dataset").toRealPath());
    }

    /**
     * ⚠ 완화 범위 한정 — 바로가기 루트에서도 <b>범위 밖을 가리키는 바로가기</b>는 거부된다(CWE-59).
     *
     * <p>이 단언이 없으면 표기 단계를 넓힌 것과 세 단계를 함께 푼 것이 구분되지 않는다.
     */
    @Test
    @DisplayName("바로가기_루트에서도_범위_밖을_가리키는_바로가기는_여전히_거부한다")
    void 바로가기_루트에서도_범위_밖을_가리키는_바로가기는_여전히_거부한다() throws IOException {
        ImportSourcePolicy linked = symlinkRootPolicy();
        Path target = Files.createDirectories(outside.resolve("target"));
        Files.createSymbolicLink(allowedRoot.resolve("looks-inside"), target);

        for (String path : List.of(
                linkHome.resolve("root-link").resolve("looks-inside").toString(),
                allowedRoot.toRealPath().resolve("looks-inside").toString())) {
            assertThatThrownBy(() -> linked.verifyFolder(path))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
    }

    /** ⚠ 완화 범위 한정 — 바로가기 루트에서도 상위로 거슬러 올라가는 표기는 거부된다(CWE-22). */
    @Test
    @DisplayName("바로가기_루트에서도_상위로_거슬러_올라가는_표기는_여전히_거부한다")
    void 바로가기_루트에서도_상위로_거슬러_올라가는_표기는_여전히_거부한다() throws IOException {
        ImportSourcePolicy linked = symlinkRootPolicy();
        Files.createDirectories(outside.resolve("secret"));
        String traversal = linkHome.resolve("root-link").resolve("..").resolve("..")
                .resolve(outside.getFileName()).resolve("secret").toString();

        assertThatThrownBy(() -> linked.verifyFolder(traversal))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    /**
     * ⚠ 완화 범위 한정 — 바로가기 루트에서도 <b>허용 범위 밖은 실재 여부와 무관하게</b> 같은 사유로
     * 거부되고, 거부 사유에 요청 원문을 되싣지 않는다(CWE-209).
     */
    @Test
    @DisplayName("바로가기_루트에서도_허용_범위_밖은_존재_여부가_응답으로_새지_않는다")
    void 바로가기_루트에서도_허용_범위_밖은_존재_여부가_응답으로_새지_않는다() throws IOException {
        ImportSourcePolicy linked = symlinkRootPolicy();
        Path existing = Files.createDirectories(outside.resolve("exists"));
        Path missing = outside.resolve("missing");

        for (String path : List.of(existing.toString(), missing.toString())) {
            assertThatThrownBy(() -> linked.verifyFolder(path))
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> {
                        assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                        assertThat(e.getMessage()).doesNotContain(path);
                    });
        }
    }

    /**
     * 바로가기가 <b>없는</b> 루트에서는 판정 결과가 종전과 같다 — 넓어지는 것은 루트의 표기와 실제
     * 자리가 갈리는 경우 하나뿐이다.
     *
     * <p>루트를 실경로로 선언해 플랫폼이 끼워 넣는 바로가기(맥의 {@code /var})까지 걷어낸 형상이다.
     */
    @Test
    @DisplayName("바로가기가_없는_루트에서는_받아들임과_거부가_모두_종전과_같다")
    void 바로가기가_없는_루트에서는_받아들임과_거부가_모두_종전과_같다() throws IOException {
        ImportSourcePolicy plain = policyWithRoot(allowedRoot.toRealPath());
        Path folder = Files.createDirectories(allowedRoot.resolve("dataset"));
        Path escape = Files.createSymbolicLink(allowedRoot.resolve("escape"),
                Files.createDirectories(outside.resolve("target")));

        assertThat(plain.verifyFolder(folder.toRealPath().toString())).isEqualTo(folder.toRealPath());
        for (String rejected : List.of(allowedRoot.toRealPath().resolve("escape").toString(),
                outside.resolve("target").toString())) {
            assertThatThrownBy(() -> plain.verifyFolder(rejected))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
        assertThatThrownBy(() -> plain.verifyFolder(allowedRoot.toRealPath().resolve("nope").toString()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
