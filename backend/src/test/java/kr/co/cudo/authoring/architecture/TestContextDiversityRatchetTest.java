package kr.co.cudo.authoring.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시되는 <b>스프링 테스트 컨텍스트 종류 수</b>의 래칫 — 시험 소스 정적 스캔.
 *
 * <h3>무엇을 막나 — 이 결함은 「언젠가」가 아니라 「예정된 시점」에 터진다</h3>
 * <p>Spring 은 테스트 컨텍스트를 캐시에 <b>살려 둔다</b>(기본 상한 32). JPA·Hibernate·Flyway·
 * 듀얼 데이터소스·WebClient 를 물고 있는 컨텍스트가 수십 개 상주하면 테스트 워커 힙이 회수 불가에
 * 빠져, 전체 회귀가 <b>결과를 하나도 내지 못한 채</b> GC 에 묶인다(실측: Old 100% · GC 점유율 94% ·
 * 결과 XML 0건). 로그에 {@code FAILED} 가 없다는 것이 통과의 증거가 되지 못하는 형태의 고장이다.
 *
 * <p>핵심은 <b>이 수가 단조 증가한다</b>는 것이다. 서로 다른 {@code @MockBean} 조합이나
 * {@code @TestPropertySource} 값을 쓰는 시험이 하나 늘 때마다 컨텍스트가 하나 늘고 <b>되돌아가지
 * 않는다</b>. 힙을 올리는 처방은 이미 한 번 썼고(그 시간이 끝나서 이 가드가 생겼다) 또 올리면 같은
 * 자리에 다시 온다. 단조 증가에는 <b>상한을 박는 것</b>이 대응이다.
 *
 * <h3>무엇이 컨텍스트를 가르나 (= 이 스캔이 세는 축)</h3>
 * <p>캐시 키는 {@code MergedContextConfiguration} 이다. 이 저장소에서 실제로 갈리는 축만 센다 —
 * {@code @SpringBootTest(properties/webEnvironment)} · {@code @ActiveProfiles} ·
 * {@code @TestPropertySource} · {@code @ContextConfiguration} · {@code @Import} ·
 * {@code @MockBean}/{@code @SpyBean} 타입집합 · {@code @DynamicPropertySource} ·
 * 중첩 {@code @TestConfiguration} · {@code @AutoConfigure*}.
 *
 * <p>{@code @ActiveProfiles} 는 현재 <b>갈리지 않는다</b> — 전 클래스가 같은 값({@code "local"})이다.
 * 과거 기록이 이것을 분기 요인으로 적어 둔 적이 있으나 실측으로 기각됐다. 그래도 키에는 넣어 둔다 —
 * 앞으로 프로파일이 갈리면 그때는 진짜 축이 되기 때문이다.
 *
 * <h3>{@code @DynamicPropertySource} 는 Y/N 이 아니라 클래스마다 별개다</h3>
 * <p>{@code DynamicPropertiesContextCustomizer} 의 {@code equals}/{@code hashCode} 는
 * <b>{@code Set<Method>}</b> 로 구현돼 있다(바이트코드 확인). 선언 클래스가 다르면 {@code Method}
 * 객체가 달라 <b>절대 같아지지 않는다</b> — 프로퍼티 값이 완전히 동일해도 컨텍스트가 갈린다.
 * 그래서 여기서도 <b>선언 클래스명을 키에 넣는다</b>.
 *
 * <p>이 축의 처방은 프로퍼티 통일이 아니라 <b>공통 베이스 클래스로 끌어올리기</b>다(상속하면 같은
 * {@code Method} 객체라 공유된다). 이 저장소는 현재 {@code @SpringBootTest} 클래스에 공통 베이스가
 * 하나도 없다.
 *
 * <h3>왜 정적 스캔인가 — 가드 자신이 컨텍스트를 만들면 안 된다</h3>
 * <p>런타임에 캐시 통계를 읽으려면 컨텍스트를 띄워야 하는데, 그러면 <b>측정 대상을 측정 행위가
 * 늘린다</b>. 이 결함은 소스에 적힌 어노테이션으로 판정되므로 기존 정적 스캔 가드
 * ({@code LockOrderGuardTest} · {@code PresetResolutionDependencyGuardTest} · {@code *RemovalTest})와
 * 같은 골격으로 충분하다. 이 시험은 스프링을 전혀 쓰지 않는다.
 *
 * <h3>순진한 {@code grep} 은 세 축 전부에서 틀린다 (직접 겪은 오측 3종)</h3>
 * <ol>
 *   <li><b>주석 안 언급을 센다.</b> {@code grep -ral} 은 <i>"왜 @SpringBootTest 가 아니라…"</i>
 *       같은 javadoc 을 함께 세어 실제보다 많이 나온다(실측 316 대 309).</li>
 *   <li><b>같은 줄 선언에서 엉뚱한 타입을 집는다.</b> 이 저장소에는
 *       {@code @MockBean private VlmClient vlmClient;} 처럼 한 줄로 쓴 선언이 실재한다. 줄 단위
 *       정규식은 줄 끝까지 삼킨 뒤 <b>다음 필드</b>의 타입을 집는다(실측 오추출:
 *       {@code VlmClient} 를 {@code Long} 으로, {@code AiServerClient} 를 {@code ListAppender} 로).</li>
 *   <li><b>문자열 안의 괄호에 깨진다.</b> 어노테이션 인자를 괄호 균형으로 자를 때 문자열 리터럴을
 *       건너뛰지 않으면 <b>클래스 본문을 통째로 삼킨다</b>(실측 3파일).</li>
 *   <li><b>문자열 안의 어노테이션 <i>이름</i>을 센다.</b> 이 가드가 처음 돌 때 <b>자기 자신을</b>
 *       {@code @SpringBootTest} 클래스로 셌다 — 위 실패 메시지 리터럴에 그 이름이 들어 있기
 *       때문이다(310/75 로 각 1 과다). 주석만 지워서는 안 되고 <b>리터럴도 탐지 대상에서 빼야</b>
 *       한다.</li>
 * </ol>
 * <p>그래서 스캐너는 한 번의 훑기로 <b>두 벌</b>을 만든다({@link Source}) — 길이가 같아 인덱스를
 * 공유한다.
 * <ul>
 *   <li>{@code code} : 주석 <b>과</b> 리터럴 내용을 공백으로 지운 것 — <b>탐지</b>(어노테이션 위치·
 *       괄호 균형·정규식)에 쓴다. 리터럴이 비어 있으므로 괄호에 속지 않는다.</li>
 *   <li>{@code full} : 주석만 지우고 리터럴은 <b>보존</b>한 것 — <b>추출</b>(인자 본문)에 쓴다.
 *       프로퍼티 값({@code enabled=true} 대 {@code =false})이 곧 컨텍스트를 가르는 축이라
 *       지우면 서로 다른 컨텍스트가 같아 보인다.</li>
 * </ul>
 *
 * <h3>상한을 올리려는 사람에게</h3>
 * <p>이 수를 <b>올리는 방향으로 고치지 마라.</b> 새 시험이 이 가드를 깨뜨렸다면, 그 시험이 기존
 * 컨텍스트를 재사용할 수 있는지부터 본다 — 대개 다음 중 하나로 해결된다.
 * <ul>
 *   <li>{@code @MockBean} 을 <b>공용 목 묶음</b>으로 통일한다(조합이 다르면 새 컨텍스트다)</li>
 *   <li>{@code @TestPropertySource} 값을 기존 시험과 <b>같은 집합</b>으로 맞춘다</li>
 *   <li>{@code @DynamicPropertySource} 를 <b>공통 베이스 클래스</b>로 올린다</li>
 *   <li>빈 등록·조건 검증이면 {@code ApplicationContextRunner} 로 내린다(이 저장소에 선례 다수)</li>
 * </ul>
 * <p>어느 것도 아니고 정말 새 컨텍스트가 필요하다면, 상한을 올리되 <b>왜 재사용이 불가능한지</b>를
 * 이 javadoc 에 남겨라. 근거 없이 숫자만 올리면 이 가드는 아무것도 막지 못한다.
 */
class TestContextDiversityRatchetTest {

    private static final Path TEST_SRC = Paths.get("src/test/java");

    /**
     * 캐시되는 컨텍스트 종류의 상한.
     *
     * <p>2026-08-25 실측 기준선 = <b>74</b>(클래스 309개, 그중 <b>63종이 단 한 클래스 전용</b>,
     * 크기 분포 {@code [149, 74, 5, 3, 3, 2x6, 1x63]}). Spring 캐시 기본 상한이 32 이므로 지금은
     * <b>이미 스래싱 구간</b>이다 — 즉 이 값은 「건강한 목표치」가 아니라 <b>더 나빠지는 것만 막는
     * 현재 수위</b>다. 줄이는 작업이 진행되면 이 상수도 함께 내려라.
     *
     * <h3>2026-09-09 — 74 에서 75 로 (+1). 재사용이 불가능한 이유</h3>
     * <p>포털 서버간 창구 시험({@code PortalDatasetCleanupTriggerControllerTest})이 고유 컨텍스트를
     * 하나 만든다. 그 시험은 <b>사전 공유 키가 설정된</b> 형상을 요구하는데, 그 값은
     * {@code PortalSystemApiKeyFilter} 의 <b>생성자에서 읽혀 빈으로 굳는다</b> — 기동 이후에는
     * 바꿀 수 없으므로 컨텍스트 프로퍼티로 주는 것 말고 방법이 없다.
     *
     * <p>짝이 되는 시험({@code PortalDatasetCleanupTriggerClosedByDefaultTest})은 <b>정반대 형상</b>
     * (키가 빈 채로 창구가 닫히는지)을 검증하므로 두 형상은 <b>한 컨텍스트에 공존할 수 없다.</b>
     * 다만 그쪽은 기본값이 이미 빈 값이라 프로퍼티를 두지 않아 기존 컨텍스트를 재사용한다 —
     * 그래서 늘어난 것이 <b>+1 뿐</b>이다(둘 다 새로 만들었다면 +2 였다).
     *
     * <p>⚠ 뒤쪽 시험은 fail-closed 가 실제로 닫는지를 보는 것이라 지울 수 없다. 그것이 없으면
     * 키 미설정 시 창구가 열려 버리는 회귀를 아무것도 잡지 못한다.
     */
    private static final int MAX_DISTINCT_CONTEXTS = 75;

    /** 스캔이 조용히 멈춘 것을 잡는 하한 — 경로 오타·디렉터리 이동 시 walk 가 0건이 된다. */
    private static final int MIN_SCANNED_FILES = 500;

    @Test
    @DisplayName("캐시되는_스프링_테스트_컨텍스트_종류가_상한을_넘지_않는다")
    void distinctContextConfigurationsStayUnderCeiling() {
        Scan scan = scanTestSources();

        // 위반 목록만 보지 않는다 — 아무것도 못 찾으면 "위반 없음"이 아니라 "가드가 멈췄다"다.
        assertThat(scan.scannedFiles())
                .as("시험 소스 스캔이 멈췄다(경로 오타/디렉터리 이동?) — %s", TEST_SRC.toAbsolutePath())
                .isGreaterThanOrEqualTo(MIN_SCANNED_FILES);
        assertThat(scan.springBootTestClasses())
                .as("@SpringBootTest 를 한 건도 못 찾았다 — 스캔이 유효하지 않다")
                .isPositive();
        assertThat(scan.distinctKeys())
                .as("컨텍스트 종류가 2 미만이면 키 계산이 무너진 것이다")
                .hasSizeGreaterThanOrEqualTo(2);

        assertThat(scan.distinctKeys().size())
                .as("캐시되는 스프링 테스트 컨텍스트 종류가 상한(%d)을 넘었다 — 현재 %d "
                                + "(@SpringBootTest %d개). 컨텍스트가 하나 늘면 테스트 워커 힙에 "
                                + "그만큼 상주하며 되돌아가지 않는다. 새 컨텍스트를 만들지 않고 "
                                + "기존 것을 재사용하는 방법은 이 클래스 javadoc 참조.",
                        MAX_DISTINCT_CONTEXTS, scan.distinctKeys().size(), scan.springBootTestClasses())
                .isLessThanOrEqualTo(MAX_DISTINCT_CONTEXTS);
    }

    // 스캔 ---------------------------------------------------------------------

    /** 스캔 결과 — 판정값과 함께 <b>스캔이 실제로 돌았다는 증거</b>를 싣는다. */
    private record Scan(int scannedFiles, int springBootTestClasses, Set<String> distinctKeys) {
    }

    private static Scan scanTestSources() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(TEST_SRC)) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Set<String> keys = new TreeSet<>();
        int springBootTestClasses = 0;
        for (Path file : files) {
            Source src = blank(read(file));
            List<String> springBootTest = annotationArgs(src, "SpringBootTest");
            if (springBootTest.isEmpty()) {
                continue;
            }
            springBootTestClasses++;
            String className = file.getFileName().toString().replace(".java", "");
            keys.add(contextKey(src, springBootTest.get(0), className));
        }
        return new Scan(files.size(), springBootTestClasses, keys);
    }

    /** 캐시 키를 가르는 축만 모아 하나의 문자열로 만든다. */
    private static String contextKey(Source src, String springBootTestArgs, String className) {
        return String.join("|",
                norm(springBootTestArgs),
                joinSorted(annotationArgs(src, "ActiveProfiles")),
                joinSorted(annotationArgs(src, "TestPropertySource")),
                joinSorted(annotationArgs(src, "ContextConfiguration")),
                joinSorted(annotationArgs(src, "Import")),
                joinSorted(mockedTypes(src.code())),
                // Set<Method> 동일성이라 선언 클래스가 다르면 별개 컨텍스트다.
                src.code().contains("@DynamicPropertySource") ? "DYN:" + className : "-",
                src.code().contains("@TestConfiguration") ? "NESTED" : "-",
                joinSorted(matchAll(AUTO_CONFIGURE, src.code())));
    }

    /**
     * {@code @MockBean}/{@code @SpyBean} 이 선언한 타입 집합.
     *
     * <p>선언이 <b>같은 줄</b>인 형태와 <b>다음 줄</b>인 형태가 둘 다 있으므로 개행을 넘나드는 단일
     * 패턴으로 잡는다. 패키지 한정은 떼어낸다 — 같은 타입을 한쪽은 FQN, 한쪽은 단순명으로 쓴 곳이
     * 실재하는데 Spring 은 <b>실제 타입</b>으로 비교하므로 그 둘은 같은 컨텍스트다.
     */
    private static final Pattern MOCK_DECL = Pattern.compile(
            "@(MockBean|SpyBean)\\b\\s*(?:\\([^)]*\\))?\\s*"
                    + "(?:(?:private|protected|public|static|final)\\s+)*"
                    + "([A-Za-z0-9_.]+(?:\\s*<[^>;{}]*>)?)\\s+\\w+\\s*[;=]");

    private static final Pattern AUTO_CONFIGURE = Pattern.compile("@(AutoConfigure\\w+)");

    private static List<String> mockedTypes(String src) {
        List<String> out = new ArrayList<>();
        Matcher matcher = MOCK_DECL.matcher(src);
        while (matcher.find()) {
            String type = norm(matcher.group(2)).replaceAll("[A-Za-z0-9_]+\\.", "");
            out.add(matcher.group(1) + ":" + type);
        }
        return out;
    }

    private static List<String> matchAll(Pattern pattern, String src) {
        List<String> out = new ArrayList<>();
        Matcher matcher = pattern.matcher(src);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    /**
     * {@code @Name(...)} 의 인자를 <b>괄호 균형</b>으로 잘라 반환한다(인자 없으면 빈 문자열).
     *
     * <p><b>탐지는 {@code code}, 추출은 {@code full}</b> — 두 벌은 길이가 같아 인덱스를 공유한다.
     * 리터럴이 비워진 {@code code} 위에서 위치와 균형을 계산하므로 리터럴 안의 괄호나 어노테이션
     * 이름에 속지 않고, 잘라내는 본문은 리터럴이 살아 있는 {@code full} 에서 가져온다.
     */
    private static List<String> annotationArgs(Source src, String name) {
        String code = src.code();
        List<String> out = new ArrayList<>();
        Matcher matcher = Pattern.compile("@" + name + "\\b").matcher(code);
        while (matcher.find()) {
            int open = matcher.end();
            while (open < code.length() && Character.isWhitespace(code.charAt(open))) {
                open++;
            }
            if (open >= code.length() || code.charAt(open) != '(') {
                out.add("");
                continue;
            }
            out.add(src.full().substring(open + 1, closingParen(code, open)));
        }
        return out;
    }

    /**
     * {@code open} 위치의 {@code (} 에 대응하는 {@code )} 의 인덱스(못 찾으면 소스 끝).
     *
     * <p>리터럴이 비워진 {@code code} 위에서만 호출한다 — 그래서 리터럴 건너뛰기가 필요 없다.
     */
    private static int closingParen(String code, int open) {
        int depth = 0;
        int cursor = open;
        while (cursor < code.length()) {
            char c = code.charAt(cursor);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return cursor;
                }
            }
            cursor++;
        }
        return code.length();
    }

    /** 리터럴의 끝(닫는 따옴표 <b>다음</b>) 위치. 텍스트 블록과 이스케이프를 처리한다. */
    private static int literalEnd(String src, int start, char quote) {
        int n = src.length();
        if (quote == '"' && src.startsWith("\"\"\"", start)) {
            int end = src.indexOf("\"\"\"", start + 3);
            return (end < 0) ? n : end + 3;
        }
        int cursor = start + 1;
        while (cursor < n) {
            char c = src.charAt(cursor);
            if (c == '\\') {
                cursor += 2;
                continue;
            }
            if (c == quote) {
                return cursor + 1;
            }
            if (c == '\n') {
                return cursor;              // 미종료 리터럴 방어
            }
            cursor++;
        }
        return n;
    }

    /**
     * 같은 소스의 두 벌 — <b>길이가 같아 인덱스를 공유한다</b>.
     *
     * @param code 주석 <b>과</b> 리터럴 내용을 공백으로 지운 것 — 탐지용
     * @param full 주석만 지우고 리터럴은 보존한 것 — 추출용
     */
    private record Source(String code, String full) {
    }

    /**
     * 주석·리터럴을 <b>같은 길이의 공백</b>으로 치환해 두 벌을 만든다(길이 보존 — 인덱스 공유).
     *
     * <p>주석을 지우는 것은 주석 안의 어노테이션 언급을 세지 않기 위해서고, {@code code} 에서
     * 리터럴까지 지우는 것은 <b>문자열 안의 어노테이션 이름·괄호</b>에 속지 않기 위해서다
     * (이 가드가 자기 실패 메시지 때문에 스스로를 오탐한 실측 사례가 있다).
     */
    private static Source blank(String src) {
        StringBuilder code = new StringBuilder(src.length());
        StringBuilder full = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                end = (end < 0) ? n : end + 2;
                appendBlank(code, full, src, i, end, true);
                i = end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                end = (end < 0) ? n : end;
                appendBlank(code, full, src, i, end, true);
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = literalEnd(src, i, c);
                // code 는 비우고, full 은 보존한다.
                appendBlank(code, full, src, i, end, false);
                i = end;
            } else {
                code.append(c);
                full.append(c);
                i++;
            }
        }
        return new Source(code.toString(), full.toString());
    }

    /**
     * {@code [from, to)} 구간을 {@code code} 에는 공백으로, {@code full} 에는 원문 또는 공백으로 넣는다.
     *
     * <p>줄바꿈은 양쪽 모두 보존한다 — 지우면 줄 구조가 무너져 {@code //} 주석 경계 계산이 어긋난다.
     *
     * @param blankFull {@code true} 면 {@code full} 에도 공백을 넣는다(주석), {@code false} 면 원문 보존(리터럴)
     */
    private static void appendBlank(StringBuilder code, StringBuilder full,
                                    String src, int from, int to, boolean blankFull) {
        for (int k = from; k < to; k++) {
            char c = src.charAt(k);
            char blanked = (c == '\n') ? '\n' : ' ';
            code.append(blanked);
            full.append(blankFull ? blanked : c);
        }
    }

    private static String norm(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String joinSorted(List<String> values) {
        Set<String> sorted = new TreeSet<>();
        for (String value : values) {
            sorted.add(norm(value));
        }
        return String.join(",", sorted);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
