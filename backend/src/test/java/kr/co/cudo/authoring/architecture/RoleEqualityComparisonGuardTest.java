package kr.co.cudo.authoring.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>역할 동등 비교 잔여 가드</b> — 프로덕션 소스 스캔 (@design ADR-055 · @design ROLE-004 · @design AC-125).
 *
 * <h3>무엇을 지키나</h3>
 * <p>Spring 의 역할 계층은 <b>권한(authority) 축에만</b> 걸린다. 서비스가 역할 enum 을 그대로
 * 동등 비교하면 관리자가 그 지점에서 떨어져, 관리 기능은 쓰되 목록 조회·라벨 접근·검수·배정·통계
 * 에서 거부되어 계층이 반쪽만 성립한다. 그래서 그 자리를 계층 반영 판정기
 * ({@code TokenClaims#hasRole})로 옮겼고, 이 가드는 <b>새 코드가 판정기를 우회해 동등 비교로
 * 돌아가는 것</b>을 막는다.
 *
 * <h3>★비교는 양방향이다 — 「잔여 0건」은 이 축에서 정반대 신호다</h3>
 * <ul>
 *   <li><b>예상에 없는 지점이 생기면 RED</b> — 판정기를 우회한 새 코드다.</li>
 *   <li><b>예상에 있는데 사라져도 RED</b> — 아래 여섯은 계층을 <b>타면 안 되는</b> 자리라, 없어졌다는
 *       것은 누군가 예외를 실수로 판정기에 이관했다는 뜻이다. 특히 원본 이미지 축 둘은 이관되는
 *       순간 관리자에게 개인정보가 열린다.</li>
 *   <li><b>건수까지 고정</b> — 같은 파일 안에 비교가 하나 더 늘어도 클래스 집합은 그대로라, 개수를
 *       박지 않으면 그 증식을 놓친다.</li>
 * </ul>
 *
 * <h3>파일 경로를 박지 않는다</h3>
 * <p>기대 집합의 키는 <b>단순 클래스명</b>이다. 패키지를 옮겨도 가드가 죽지 않고, 실패 메시지에는
 * 실측 경로와 줄번호를 그대로 실어 찾아가게 한다.
 *
 * <h3>★이 가드가 못 보는 것 (정직하게 적어 둔다)</h3>
 * <ul>
 *   <li><b>주석은 세지 않는다</b> — 블록·라인 주석을 공백으로 지운 뒤 매칭한다. 「되돌리지 말 것」
 *       주석에 금지 형태를 그대로 인용하면 그 파일이 위양성이 되던 실제 사고를 막는다.</li>
 *   <li><b>문자열 리터럴·텍스트 블록은 코드로 센다</b> — 파서가 아니라 정규식이다. 비교식을 문자열에
 *       담는 코드가 생기면 위양성이 난다(현재 0건).</li>
 *   <li><b>{@code //} 를 품은 문자열 리터럴</b>(URL 등)은 그 뒤가 주석으로 오인돼 지워진다. 같은 줄
 *       뒤쪽에 비교식이 이어 있으면 놓친다(현재 0건).</li>
 *   <li><b>역할을 문자열로 비교하는 형태</b>({@code roleName()} 이나 {@code ROLE_CD} 문자열 비교)는
 *       대상이 아니다 — 저장값 축이라 계층 축과 다르다.</li>
 *   <li><b>{@code switch} 분기·리플렉션·간접 참조</b>는 세지 않는다. switch 는 값 매핑에도 쓰여
 *       기계적으로 막으면 잡음이 되므로, 인가 분기를 switch 로 쓰는 코드가 생기면 리뷰가 잡아야 한다.</li>
 * </ul>
 */
class RoleEqualityComparisonGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    /** 블록 주석 제거용 (javadoc 포함). */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /** 라인 주석 제거용. */
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    /**
     * 역할 enum 상수 참조. 앞에 식별자 문자가 오면 제외한다 — 그러지 않으면 {@code MyRole.ADMIN}
     * 같은 <b>다른 타입</b>의 꼬리를 우리 enum 으로 오인한다.
     */
    private static final String ROLE_CONST = "(?<![A-Za-z0-9_$])Role\\.[A-Z][A-Z0-9_]*";

    /**
     * 역할 접근자 호출. 수신자는 선택이라 {@code actor.role()} 과 record 내부의 {@code role()} 을
     * 함께 집는다. 앞의 식별자 문자를 막지 않으면 {@code hasRole()} 의 꼬리가 그대로 걸린다.
     */
    private static final String ROLE_CALL = "(?<![A-Za-z0-9_$])role\\s*\\(\\s*\\)";

    /**
     * 선택적 수신자 사슬({@code actor.} · {@code this.claims.}). 접근자가 <b>패턴 뒤쪽</b>에 오는
     * 형태(좌우가 뒤집힌 비교)에서는 수신자를 명시적으로 먹어야 한다 — 앞쪽에 올 때는 정규식이
     * 토큰 중간부터 매칭을 시작할 수 있어 필요 없지만, 뒤쪽에서는 {@code actor.} 를 건너뛸 방법이
     * 없어 매칭이 통째로 실패한다. <b>탐지기 canary 가 실제로 이 누락을 잡아냈다.</b>
     */
    private static final String RECEIVER = "(?:[A-Za-z_$][A-Za-z0-9_$]*\\s*\\.\\s*)*";

    /** 수신자를 포함한 역할 접근자 표기. */
    private static final String ROLE_ACCESS = RECEIVER + ROLE_CALL;

    /**
     * 동등 비교의 네 형태. {@code ==}/{@code !=} 는 좌우가 바뀔 수 있고({@code Yoda}) 참조 비교 대신
     * {@code equals} 를 쓰는 우회도 같은 축이라 함께 막는다.
     */
    private static final List<Pattern> EQUALITY_FORMS = List.of(
            Pattern.compile(ROLE_ACCESS + "\\s*[=!]=\\s*" + ROLE_CONST),
            Pattern.compile(ROLE_CONST + "\\s*[=!]=\\s*" + ROLE_ACCESS),
            Pattern.compile(ROLE_ACCESS + "\\s*\\.equals\\s*\\(\\s*" + ROLE_CONST),
            Pattern.compile(ROLE_CONST + "\\s*\\.equals\\s*\\(\\s*" + ROLE_ACCESS));

    /**
     * <b>의도한 잔여 — 여섯 곳뿐이며 사유가 저마다 다르다.</b> 「예외를 몰아 둔 목록」이 아니라
     * 각각이 독립된 결정이므로, 하나를 지우거나 옮길 때 그 사유를 여기서 먼저 읽게 한다.
     */
    private record IntendedSite(String className, int occurrences, String reason) {}

    private static final List<IntendedSite> INTENDED = List.of(
            new IntendedSite("AdminPasswordService", 1,
                    "관리자 전용. 판정기로 바꾸면 의미가 「관리자 이상」이 되어, 상위 역할이 생기는 날 자동으로 넓어진다"),
            new IntendedSite("AdminSessionService", 1,
                    "관리자 전용. 위와 같은 사유 — 지금은 상위가 없어 동치이나 의미가 다르다"),
            new IntendedSite("RoleClaimService", 1,
                    "요청 바디의 값 검증이다 — 인가 판정이 아니다. 계층을 태울 대상 자체가 아니다"),
            new IntendedSite("FrameImageService", 1,
                    "원본(비-비식별) 이미지 서빙 — 계층의 유일한 예외(ROLE-004). 관리자는 이 축을 물려받지 않는다"),
            new IntendedSite("LabelService", 1,
                    "같은 예외의 선언 축(응답의 프레임 이미지 종류). 원본 서빙과 한 정책의 두 얼굴이다"),
            new IntendedSite("ReviewService", 1,
                    "검수 제출 = 작업자 전용. 독립 게이트라 판정기로 바꿔 얻는 것이 없다"));

    // ---------------------------------------------------------------- 스캔

    private record Hit(String className, String path, int line, String snippet) {}

    private static List<Hit> scan() {
        try (Stream<Path> files = Files.walk(MAIN_SRC)) {
            List<Hit> hits = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(p -> collect(p, hits));
            return hits;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void collect(Path file, List<Hit> hits) {
        String source = read(file);
        String code = blank(blank(source, BLOCK_COMMENT), LINE_COMMENT);
        String className = file.getFileName().toString().replace(".java", "");
        for (Pattern form : EQUALITY_FORMS) {
            Matcher m = form.matcher(code);
            while (m.find()) {
                hits.add(new Hit(className, file.toString(), lineOf(code, m.start()), m.group().trim()));
            }
        }
    }

    /**
     * UTF-8 로 <b>직접</b> 읽는다. 외부 grep 에 기대면 한글이 많은 파일을 도구가 이진으로 오판해
     * 조용히 건너뛰는 사고가 난다 — 그러면 가드가 침묵으로 통과한다.
     */
    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("소스를 읽지 못했다: " + file, e);
        }
    }

    /** 주석을 <b>공백으로</b> 지운다 — 줄바꿈을 남겨야 줄번호가 원문과 어긋나지 않는다. */
    private static String blank(String source, Pattern comment) {
        Matcher m = comment.matcher(source);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            StringBuilder blanked = new StringBuilder(m.group().length());
            for (char c : m.group().toCharArray()) {
                blanked.append(c == '\n' || c == '\r' ? c : ' ');
            }
            m.appendReplacement(out, Matcher.quoteReplacement(blanked.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static int lineOf(String code, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static Map<String, Integer> countByClass(List<Hit> hits) {
        Map<String, Integer> counts = new TreeMap<>();
        hits.forEach(h -> counts.merge(h.className(), 1, Integer::sum));
        return counts;
    }

    private static Map<String, Integer> intendedCounts() {
        Map<String, Integer> expected = new TreeMap<>();
        INTENDED.forEach(s -> expected.put(s.className(), s.occurrences()));
        return expected;
    }

    private static String describe(List<Hit> hits, String className) {
        return hits.stream()
                .filter(h -> h.className().equals(className))
                .map(h -> "    " + h.path() + ":" + h.line() + "  →  " + h.snippet())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }

    // ---------------------------------------------------------------- 시험

    @Test
    @DisplayName("★판정기를_우회한_새_동등_비교가_없다_예상에_없는_지점이_생기면_RED")
    void noUnexpectedEqualityComparison() {
        List<Hit> hits = scan();
        Map<String, Integer> actual = countByClass(hits);
        Map<String, Integer> expected = intendedCounts();

        Map<String, String> unexpected = new LinkedHashMap<>();
        actual.keySet().stream()
                .filter(c -> !expected.containsKey(c))
                .forEach(c -> unexpected.put(c, describe(hits, c)));

        assertThat(unexpected)
                .as("""
                        역할 enum 을 그대로 동등 비교하는 새 지점이 생겼다.
                        계층(관리자 → 검수자)이 이 자리에는 걸리지 않아 관리자가 그대로 거부된다.
                        TokenClaims.hasRole(...) 로 바꾸거나, 계층을 타면 안 되는 자리라면
                        사유를 적어 INTENDED 목록에 올려라(사유 없이 목록만 늘리지 말 것).
                        발견 위치:
                        %s""".formatted(String.join("\n", unexpected.values())))
                .isEmpty();
    }

    @Test
    @DisplayName("★계층을_타면_안_되는_예외가_사라지지_않았다_잔여_0건은_이_축에서_정반대_신호다")
    void intendedExceptionsStillPresent() {
        Map<String, Integer> actual = countByClass(scan());

        List<String> vanished = INTENDED.stream()
                .filter(s -> !actual.containsKey(s.className()))
                .map(s -> "    " + s.className() + " — " + s.reason())
                .toList();

        assertThat(vanished)
                .as("""
                        계층을 타면 안 되는 자리의 동등 비교가 사라졌다.
                        판정기로 실수 이관했거나 클래스가 개명·삭제됐다는 뜻이다.
                        원본 이미지 축(FrameImageService·LabelService)이 여기 뜨면 관리자에게
                        개인정보가 열린 것이므로 즉시 되돌려라.
                        사라진 예외:
                        %s""".formatted(String.join("\n", vanished)))
                .isEmpty();
    }

    @Test
    @DisplayName("예외_지점의_비교_건수까지_고정한다_같은_파일에서_하나_늘어도_RED")
    void occurrenceCountIsPinned() {
        List<Hit> hits = scan();
        Map<String, Integer> actual = countByClass(hits);

        List<String> mismatched = INTENDED.stream()
                .filter(s -> actual.containsKey(s.className()))
                .filter(s -> actual.get(s.className()) != s.occurrences())
                .map(s -> "    " + s.className() + " — 기대 " + s.occurrences() + "건, 실측 "
                        + actual.get(s.className()) + "건\n" + describe(hits, s.className()))
                .toList();

        assertThat(mismatched)
                .as("""
                        예외 파일 안에서 동등 비교 건수가 달라졌다.
                        클래스 집합만 보면 놓치는 증식이라 개수까지 박아 둔다.
                        비교가 하나 늘었다면 그 새 자리도 계층을 타면 안 되는지 따로 판단해야 한다.
                        어긋난 곳:
                        %s""".formatted(String.join("\n", mismatched)))
                .isEmpty();
    }

    @Test
    @DisplayName("탐지기_자체가_살아_있다_네_형태를_모두_잡는다")
    void detectorCanary() {
        // 실제 코드에 없는 형태(Yoda·equals)까지 정규식이 살아 있는지 직접 확인한다.
        // 이게 없으면 그 세 패턴이 조용히 죽어도 아무 시험이 알려주지 않는다.
        assertThat(matchesAnyForm("if (actor.role() == Role.REVIEWER) {")).isTrue();
        assertThat(matchesAnyForm("if (actor.role() != Role.ADMIN) {")).isTrue();
        assertThat(matchesAnyForm("if (Role.REVIEWER == actor.role()) {")).isTrue();
        assertThat(matchesAnyForm("if (actor.role().equals(Role.WORKER)) {")).isTrue();
        assertThat(matchesAnyForm("if (Role.WORKER.equals(actor.role())) {")).isTrue();
        assertThat(matchesAnyForm("if (role() == Role.ADMIN) {")).isTrue();

        // 위양성 방어 — 판정기 호출·문자열 축·다른 타입의 꼬리는 잡지 않는다.
        assertThat(matchesAnyForm("if (actor.hasRole(Role.REVIEWER)) {")).isFalse();
        assertThat(matchesAnyForm("if (TokenClaims.hasRole(actor, Role.WORKER)) {")).isFalse();
        assertThat(matchesAnyForm("if (Role.ADMIN.name().equals(currentRole)) {")).isFalse();
        assertThat(matchesAnyForm("if (req.role() != null && !req.role().equals(currentRole)) {")).isFalse();
        assertThat(matchesAnyForm("if (actor.hasRole() == Role.ADMIN) {")).isFalse();
    }

    private static boolean matchesAnyForm(String snippet) {
        return EQUALITY_FORMS.stream().anyMatch(p -> p.matcher(snippet).find());
    }

    @Test
    @DisplayName("스캔이_실제로_소스를_읽었다_경로가_어긋나_0건_스캔이면_RED")
    void scanActuallyReadsSources() {
        // 소스 루트가 어긋나면 "예상에 없는 지점 0건" 이 그냥 통과한다. 침묵을 막는다.
        try (Stream<Path> files = Files.walk(MAIN_SRC)) {
            long javaFiles = files.filter(p -> p.getFileName().toString().endsWith(".java")).count();
            assertThat(javaFiles)
                    .as("프로덕션 소스를 한 건도 읽지 못했다 — 작업 디렉터리나 경로 전제가 바뀌었다")
                    .isGreaterThan(500L);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
