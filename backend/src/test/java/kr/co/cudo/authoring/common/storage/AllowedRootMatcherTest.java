package kr.co.cudo.authoring.common.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 허용 루트 판정 규칙({@link AllowedRootMatcher})의 단위 시험 — 파일시스템을 건드리지 않는다.
 *
 * <h3>왜 해석기를 가짜로 주입해 재는가</h3>
 * <p>이 클래스가 정하는 것은 <b>규칙</b>이지 실경로 해석 방식이 아니다. 실제 심링크를 만들어 재면
 * 규칙이 아니라 그 환경의 파일시스템을 재게 되고, 심링크를 만들 수 없는 환경에서는 아무것도 검증하지
 * 못한다. 그래서 해석기를 가짜로 주입해 <b>규칙만</b> 고정한다.
 *
 * <p>실제 심링크를 건 왕복 검증은 {@code VideoArtifactRootResolverSymlinkRootTest} 가 맡는다.
 *
 * @design ADR-065
 */
class AllowedRootMatcherTest {

    private static final Path ROOT_LABEL = Path.of("/nas-storage");
    private static final Path ROOT_REAL = Path.of("/nas-storage1");

    /** 표기 루트를 실제 자리로 펴는 가짜 해석기 — 그 밖의 경로는 그대로 돌려준다. */
    private static final UnaryOperator<Path> RESOLVER =
            path -> ROOT_LABEL.equals(path) ? ROOT_REAL : path;

    @Test
    @DisplayName("루트의_표기로_시작하면_통과한다")
    void 루트의_표기로_시작하면_통과한다() {
        assertThat(AllowedRootMatcher.startsWithRoot(
                Path.of("/nas-storage/klid-at"), ROOT_LABEL, RESOLVER)).isTrue();
    }

    @Test
    @DisplayName("★루트가_실제로_가리키는_자리로_시작해도_통과한다")
    void 루트가_실제로_가리키는_자리로_시작해도_통과한다() {
        // 이것이 이 규칙의 존재 이유다 — 탐색 창구가 돌려준 실경로를 되보냈을 때 거부되지 않아야 한다.
        assertThat(AllowedRootMatcher.startsWithRoot(
                Path.of("/nas-storage1/klid-at"), ROOT_LABEL, RESOLVER)).isTrue();
    }

    @Test
    @DisplayName("두_표기_어느_쪽으로도_시작하지_않으면_거부한다")
    void 두_표기_어느_쪽으로도_시작하지_않으면_거부한다() {
        assertThat(AllowedRootMatcher.startsWithRoot(
                Path.of("/etc/passwd"), ROOT_LABEL, RESOLVER)).isFalse();
    }

    @Test
    @DisplayName("이름이_접두만_같고_경계가_다르면_거부한다")
    void 이름이_접두만_같고_경계가_다르면_거부한다() {
        // 문자열 접두가 아니라 경로 요소 단위로 판정해야 한다 — 이 성질이 깨지면 인접 디렉터리가 뚫린다.
        // (실제 사고가 이 자리에서 났다: 허용 루트 `/nas-storage` 옆의 `/nas-storage1` 이 별개 디렉터리다)
        assertThat(AllowedRootMatcher.startsWithRoot(
                Path.of("/nas-storage-other/x"), ROOT_LABEL, RESOLVER)).isFalse();
    }

    @Test
    @DisplayName("해석기가_없으면_표기_기준으로만_판정한다")
    void 해석기가_없으면_표기_기준으로만_판정한다() {
        assertThat(AllowedRootMatcher.startsWithRoot(
                Path.of("/nas-storage/klid-at"), ROOT_LABEL, null)).isTrue();
        assertThat(AllowedRootMatcher.startsWithRoot(
                Path.of("/nas-storage1/klid-at"), ROOT_LABEL, null)).isFalse();
    }

    @Test
    @DisplayName("해석기가_null을_돌려줘도_예외로_번지지_않고_거부한다")
    void 해석기가_null을_돌려줘도_예외로_번지지_않고_거부한다() {
        assertThat(AllowedRootMatcher.startsWithRoot(
                Path.of("/nas-storage1/klid-at"), ROOT_LABEL, path -> null)).isFalse();
    }

    @Test
    @DisplayName("후보나_루트가_비면_거부한다")
    void 후보나_루트가_비면_거부한다() {
        assertThat(AllowedRootMatcher.startsWithRoot(null, ROOT_LABEL, RESOLVER)).isFalse();
        assertThat(AllowedRootMatcher.startsWithRoot(Path.of("/nas-storage"), null, RESOLVER)).isFalse();
    }

    @Test
    @DisplayName("★허용_루트_목록이_비면_거부한다_비었으니_다_통과로_뒤집히지_않는다")
    void 허용_루트_목록이_비면_거부한다() {
        // fail-closed — 목록이 비었을 때 "제한 없음"으로 읽히면 판정기가 통째로 무력해진다.
        assertThat(AllowedRootMatcher.startsWithAnyRoot(
                Path.of("/nas-storage/klid-at"), List.of(), RESOLVER)).isFalse();
        assertThat(AllowedRootMatcher.startsWithAnyRoot(
                Path.of("/nas-storage/klid-at"), null, RESOLVER)).isFalse();
    }

    @Test
    @DisplayName("목록_중_하나라도_맞으면_통과한다")
    void 목록_중_하나라도_맞으면_통과한다() {
        List<Path> roots = List.of(Path.of("/other-mount"), ROOT_LABEL);
        assertThat(AllowedRootMatcher.startsWithAnyRoot(
                Path.of("/nas-storage1/klid-at"), roots, RESOLVER)).isTrue();
    }
}
