package kr.co.cudo.authoring.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개발용 로그인 <b>표준 계정 번호 단일 원천 가드</b> — 프로덕션 소스 스캔 (@design AC-1016).
 *
 * <h3>무엇을 막는가</h3>
 * <p>그 번호는 두 곳이 함께 알아야 한다 — 토큰을 <b>발급</b>하는 쪽과 진입 시 작업자 자동 등록에서
 * <b>제외</b>하는 쪽. 복제되면 dev 계정이 늘 때 한쪽만 갱신돼 <b>새 계정만 제외에서 빠지고</b>,
 * 그 계정에서 원래 사고(화면에서 고른 역할과 실제 인가가 어긋난 채 굳음)가 그대로 재현된다.
 * 이 저장소의 반복 결함 패턴이며, 실제로 이 번호가 그 형태로 한 번 사고를 냈다(2026-09-01).
 *
 * <h3>어떻게 막는가 (판정축 둘)</h3>
 * <ol>
 *   <li><b>상수 선언은 소유자 파일에만</b> — {@code DEFAULT_USER_NO_*} 선언이 다른 프로덕션
 *       파일에 나타나면 그 자체가 두 번째 진실원이다.</li>
 *   <li><b>소비자 파일에 번호 리터럴이 없다</b> — 상수를 안 쓰고 숫자를 그대로 박는 우회를 막는다.
 *       스코프를 소비자 두 파일로 좁히는 이유는 같은 숫자가 Swagger 예시·무관한 시드에도 나와
 *       전역 스캔이 오탐투성이가 되기 때문이다(실측 확인).</li>
 * </ol>
 *
 * <p>주석 스트리핑 필수 — 주석이 상수명이나 번호를 언급하기만 해도 오탐되기 때문
 * (이 저장소의 다른 단일 원천 가드와 동일 관례).
 */
class DevStandardAccountsSingleSourceGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    private static final String OWNER_FILE = "DevStandardAccounts.java";

    /** 상수 <b>선언</b> 패턴 — 참조({@code DevStandardAccounts.DEFAULT_USER_NO_ADMIN})는 걸리지 않는다. */
    private static final Pattern CONSTANT_DECLARATION =
            Pattern.compile("(?m)^[^\\n]*\\bstatic\\s+final\\s+String\\s+DEFAULT_USER_NO_\\w+\\s*=");

    /** 상수 4종이 실제로 선언돼 있어야 한다 — 아니면 아래 단언들이 공허하게 통과한다. */
    private static final List<String> REQUIRED_CONSTANTS = List.of(
            "DEFAULT_USER_NO_REVIEWER", "DEFAULT_USER_NO_WORKER",
            "DEFAULT_USER_NO_PORTAL", "DEFAULT_USER_NO_ADMIN");

    /** 번호를 상수로만 받아야 하는 소비자 — 발급 쪽과 제외 판정을 쓰는 쪽. */
    private static final List<Path> CONSUMERS = List.of(
            MAIN_SRC.resolve("kr/co/cudo/authoring/dev/service/DevTokenService.java"),
            MAIN_SRC.resolve("kr/co/cudo/authoring/user/service/AutoWorkerRegistrar.java"));

    private static Path ownerFile() {
        return MAIN_SRC.resolve("kr/co/cudo/authoring/user/service/" + OWNER_FILE);
    }

    @Test
    @DisplayName("★표준_계정_번호_상수는_소유자_파일_한_곳에서만_선언된다")
    void constantsAreDeclaredOnlyInOwnerFile() {
        List<String> offenders = mainSources().stream()
                .filter(p -> !p.getFileName().toString().equals(OWNER_FILE))
                .filter(p -> CONSTANT_DECLARATION.matcher(stripComments(read(p))).find())
                .map(Path::toString)
                .toList();

        assertThat(offenders)
                .as("표준 계정 번호가 %s 밖에서 다시 선언됐다 — dev 계정이 늘 때 한쪽만 갱신돼 "
                        + "새 계정만 자동 등록 제외에서 빠진다", OWNER_FILE)
                .isEmpty();
    }

    @Test
    @DisplayName("소유자_파일이_실제로_상수_4종을_보유한다_공허한_통과_방지")
    void ownerFileActuallyDeclaresTheConstants() {
        String owner = stripComments(read(ownerFile()));

        for (String constant : REQUIRED_CONSTANTS) {
            assertThat(owner)
                    .as("%s 선언이 %s 에 없다 — 위 가드가 검사할 대상이 사라졌다", constant, OWNER_FILE)
                    .containsPattern("static\\s+final\\s+String\\s+" + constant + "\\s*=");
        }
    }

    @Test
    @DisplayName("★소비자_파일은_번호를_리터럴로_박지_않는다_상수로만_받는다")
    void consumersDoNotInlineTheNumbers() {
        String owner = stripComments(read(ownerFile()));
        List<String> numbers = numbersDeclaredBy(owner);

        assertThat(numbers)
                .as("소유자 파일에서 번호를 하나도 읽지 못했다 — 표기가 바뀌었으면 이 가드도 함께 고친다")
                .hasSameSizeAs(REQUIRED_CONSTANTS);

        for (Path consumer : CONSUMERS) {
            String source = stripComments(read(consumer));
            for (String number : numbers) {
                assertThat(source)
                        .as("%s 가 표준 계정 번호 %s 를 직접 박았다 — %s 의 상수를 참조할 것",
                                consumer.getFileName(), number, OWNER_FILE)
                        .doesNotContain(number);
            }
        }
    }

    /** 소유자 파일의 {@code DEFAULT_USER_NO_* = "1001"} 선언에서 값만 뽑는다. */
    private static List<String> numbersDeclaredBy(String owner) {
        return Pattern.compile("DEFAULT_USER_NO_\\w+\\s*=\\s*\"(\\d+)\"")
                .matcher(owner)
                .results()
                .map(r -> r.group(1))
                .distinct()
                .toList();
    }

    private static List<Path> mainSources() {
        try (Stream<Path> walk = Files.walk(MAIN_SRC)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stripComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll(" ");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
