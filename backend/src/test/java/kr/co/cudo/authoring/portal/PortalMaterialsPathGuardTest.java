package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.service.PortalMaterialsPathGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부가 준 소재 절대경로의 재검증을 고정한다 — <b>이번 라운드의 보안 핵심</b>.
 *
 * <h3>★ 픽스처는 「겨눈 조건만 거짓」이 되게 짠다</h3>
 * <p>거부를 확인하는 시험은 <b>다른 조건이 대신 막아 주지 못하게</b> 해야 성립한다. 그래서 탈출
 * 시험의 대상 파일은 <b>실재하는 정규 파일</b>로 두고(부재·비정규 파일로 먼저 걸리지 않게), 루트만
 * 벗어나게 한다. 그렇게 하지 않으면 판정에서 루트 검사를 통째로 빼도 시험이 죽지 않는다.
 *
 * <h3>★ 통과 시 돌려주는 것은 「그 실경로」다</h3>
 * <p>표기 경로를 돌려주면 소비측이 검증 이후 다시 링크를 따라가 검증 대상과 사용 대상이 갈린다
 * (TOCTOU). 그래서 루트 안 심링크 통과 케이스에서 <b>돌려준 값이 링크가 아니라 실체</b>임을 본다.
 *
 * @design INT-014
 */
class PortalMaterialsPathGuardTest {

    private final PortalMaterialsPathGuard guard = new PortalMaterialsPathGuard();

    @Test
    @DisplayName("루트_하위의_정규파일은_통과하고_실경로를_돌려준다")
    void insideRoot_passes(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("repo"));
        Path zip = Files.writeString(root.resolve("deploy.zip"), "z");

        PortalMaterialsPathGuard.Check check =
                guard.resolveWithinRoot(root.toString(), zip.toString());

        assertThat(check.ok()).isTrue();
        assertThat(check.realPath()).isEqualTo(zip.toRealPath());
    }

    @Test
    @DisplayName("★상위이동_표기로_루트를_벗어나면_거부한다_대상은_실재하는_정규파일이다")
    void parentTraversal_isRejected(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("repo"));
        // ⚠ 겨눈 것은 「루트 밖」 하나다 — 대상은 실재하는 정규 파일이라 부재·비정규 판정이
        //   대신 막아 주지 못한다.
        Files.writeString(tmp.resolve("secret.zip"), "s");

        PortalMaterialsPathGuard.Check check = guard.resolveWithinRoot(
                root.toString(), root.resolve("../secret.zip").toString());

        assertThat(check.verdict()).isEqualTo(PortalMaterialsPathGuard.Verdict.ESCAPED);
        assertThat(check.realPath()).isNull();
    }

    @Test
    @DisplayName("★루트_밖의_절대경로는_거부한다_대상은_실재하는_정규파일이다")
    void absoluteOutsideRoot_isRejected(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("repo"));
        Path outside = Files.writeString(tmp.resolve("outside.zip"), "o");

        PortalMaterialsPathGuard.Check check =
                guard.resolveWithinRoot(root.toString(), outside.toString());

        assertThat(check.verdict()).isEqualTo(PortalMaterialsPathGuard.Verdict.ESCAPED);
    }

    @Test
    @DisplayName("★★루트_안에_있지만_밖을_가리키는_심링크는_거부한다_표기검사만으로는_통과한다")
    void symlinkPointingOutsideRoot_isRejected(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("repo"));
        Path outside = Files.writeString(tmp.resolve("outside.zip"), "o");
        Path link = root.resolve("deploy.zip");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 심링크를 만들 수 없는 파일 시스템 — 이 축은 그 환경에서 의미가 없다.
        }

        // ⚠ 표기(lexical)로는 루트 하위다 — 이 케이스를 막는 것은 <실경로 단계> 뿐이다.
        assertThat(link.startsWith(root)).isTrue();

        PortalMaterialsPathGuard.Check check =
                guard.resolveWithinRoot(root.toString(), link.toString());

        assertThat(check.verdict()).isEqualTo(PortalMaterialsPathGuard.Verdict.ESCAPED);
    }

    @Test
    @DisplayName("루트_안을_가리키는_심링크는_통과하고_돌려주는_값은_링크가_아니라_실체다")
    void symlinkInsideRoot_passesAndResolvesToRealTarget(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("repo")).toRealPath();
        Path real = Files.writeString(root.resolve("real.zip"), "z");
        Path link = root.resolve("alias.zip");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }

        PortalMaterialsPathGuard.Check check =
                guard.resolveWithinRoot(root.toString(), link.toString());

        assertThat(check.ok()).isTrue();
        // ★ 링크 경로가 아니라 실체 경로를 돌려준다 — 이 값으로만 열어야 검증과 사용이 같아진다.
        assertThat(check.realPath()).isEqualTo(real.toRealPath());
        assertThat(check.realPath()).isNotEqualTo(link);
    }

    @Test
    @DisplayName("★루트_밖의_존재하지_않는_경로는_부재가_아니라_탈출이다_표기단계가_먼저_잘라야_한다")
    void outsideRootAndMissing_isEscapedNotAbsent(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("repo"));

        // ★ 이 시험이 <표기 단계만> 겨눈다. 위 두 탈출 시험은 표기·실경로 <어느 한쪽만 빼도>
        //   나머지가 대신 막아 주므로 한 단계를 지목하지 못한다(실효 겹수 2). 여기서는 대상이
        //   실재하지 않아 실경로 단계에 닿지 못하므로, 표기 단계가 없으면 판정이 부재로 바뀐다.
        //   ⚠ 둘 다 열지 않는다는 점에서는 같으나, 표기 단계가 없으면 <루트 밖 경로의 존재 여부를
        //   파일 시스템에 물어보게> 되어 탐색 표면이 생긴다.
        PortalMaterialsPathGuard.Check check =
                guard.resolveWithinRoot(root.toString(), tmp.resolve("nope.zip").toString());

        assertThat(check.verdict()).isEqualTo(PortalMaterialsPathGuard.Verdict.ESCAPED);
    }

    @Test
    @DisplayName("대상이_없으면_부재로_판정한다_탈출과_구분된다")
    void missingTarget_isAbsent(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("repo"));

        PortalMaterialsPathGuard.Check check =
                guard.resolveWithinRoot(root.toString(), root.resolve("nope.zip").toString());

        assertThat(check.verdict()).isEqualTo(PortalMaterialsPathGuard.Verdict.ABSENT);
    }

    @Test
    @DisplayName("디렉터리는_정규파일이_아니라_열지_않는다")
    void directory_isNotRegularFile(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("repo"));
        Path dir = Files.createDirectory(root.resolve("sub"));

        PortalMaterialsPathGuard.Check check =
                guard.resolveWithinRoot(root.toString(), dir.toString());

        assertThat(check.verdict()).isEqualTo(PortalMaterialsPathGuard.Verdict.NOT_REGULAR_FILE);
    }

    @Test
    @DisplayName("루트나_경로가_비면_열지_않는다")
    void blank_isRejected(@TempDir Path tmp) {
        assertThat(guard.resolveWithinRoot(null, "/x").verdict())
                .isEqualTo(PortalMaterialsPathGuard.Verdict.BLANK);
        assertThat(guard.resolveWithinRoot(tmp.toString(), "  ").verdict())
                .isEqualTo(PortalMaterialsPathGuard.Verdict.BLANK);
    }
}
