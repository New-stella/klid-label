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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★★<b>영상 제외 경계</b> 정적 가드 — 제외 술어가 <b>붙으면 안 되는 패키지</b>에 한 글자도 없다.
 * [@design ADR-069] [@design AC-1121]
 *
 * <h2>왜 소스 스캔인가</h2>
 * <p>제외 결정의 가장 큰 위험은 다음 라운드에서 <b>「일관성」을 이유로</b> 배치·관제 통지·학습데이터
 * 산출물·통계·포털에 같은 조건이 붙는 것이다. 그 위반은 <b>실패가 아니라 조용한 축소</b>로 나타나
 * (행이 사라질 뿐 오류가 없다) 정상 경로 시험을 전부 통과한다. 그리고 그 경로들을 통합시험에서 실제로
 * 돌리려면 승인·동결·산출까지 필요해, 해당 시험이 없는 조합에서는 위반이 영영 드러나지 않는다.
 *
 * <p>이 프로젝트는 <b>기동 시 스키마 검증이 실제로 수행되지 않아</b> 이런 부류를 정적 스캔으로만
 * 잡아 왔다({@code MngControlMasterTableRemovalTest} 와 같은 방식이며 그 골격을 따른다).
 *
 * <h2>왜 <b>금지 목록</b>이고 허용 목록이 아닌가</h2>
 * <p>제외 술어는 <b>작업 목록(작업 배정)·검수 목록에는 앞으로 붙어야 한다</b> — 그 라운드가 붙일 자리를
 * 이 가드가 막으면 안 된다. 그래서 「어디에 있어도 되는가」가 아니라 <b>「어디에는 절대 없어야 하는가」</b>
 * 를 고정한다. 금지 패키지가 늘거나 줄면 그것은 <b>결정의 변경</b>이므로 이 목록을 고치는 것 자체가
 * 의식적인 행위가 된다.
 *
 * <h2>주석은 판정에서 뺀다</h2>
 * <p>경계를 <b>설명하는</b> javadoc(「여기에 붙이지 말 것」)이 위반으로 오탐되면 안 되므로, 자바
 * 블록·라인 주석을 제거한 뒤 판정한다.
 */
class VideoExclusionBoundarySourceGuardTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java");
    private static final Path MAIN_RESOURCES = Paths.get("src/main/resources");

    /**
     * ★<b>제외 술어가 절대 없어야 하는 패키지</b> — 이 목록을 줄이는 것은 결정의 변경이다.
     *
     * <ul>
     *   <li>{@code batch} — 배치 파이프라인(단계 진행 · 회수 스윕). 붙으면 제외한 영상의 처리가 멈춘다.</li>
     *   <li>{@code controlnotify} — 관제 통지. 붙으면 통지가 빠지거나 미뤄진다.</li>
     *   <li>{@code dataset} — 학습데이터 산출물과 <b>콘텐츠 해시</b>. 해시 입력에 들어가면 제외를 켰다
     *       끄는 것만으로 산출물이 재생성되고 수정 통지가 나간다(내용은 하나도 바뀌지 않았는데).</li>
     *   <li>{@code stats} — 통계는 <b>자산 총량</b> 축이라 화면 숨김과 축이 다르다. 붙으면 총량이 사람의
     *       화면 정리에 따라 흔들린다.</li>
     *   <li>{@code portal} — 다른 채널이다.</li>
     * </ul>
     */
    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            "kr/co/cudo/authoring/batch",
            "kr/co/cudo/authoring/controlnotify",
            "kr/co/cudo/authoring/dataset",
            "kr/co/cudo/authoring/stats",
            "kr/co/cudo/authoring/portal");

    /** 제외 표시의 물리명·필드명 — 두 표기 모두 잡는다. */
    private static final Pattern EXCLUSION_FLAG =
            Pattern.compile("\\bEXCL_YN\\b|\\bexclYn\\b|\\bexcl_yn\\b", Pattern.CASE_INSENSITIVE);

    /** 배제 술어 소유자의 타입·상수 참조. */
    private static final Pattern EXCLUSION_SCOPE =
            Pattern.compile("\\bVideoExclusionScope\\b");

    private static final Pattern JAVA_BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern JAVA_LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");
    private static final Pattern SQL_BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--[^\\n\\r]*");
    /** SQL 문자열 리터럴 — {@code COMMENT ON COLUMN} 설명문이 판정에 섞이지 않게 걷어낸다. */
    private static final Pattern SQL_STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'", Pattern.DOTALL);

    @Test
    @DisplayName("★★경계_배치·관제통지·산출물·통계·포털_패키지에_제외_술어가_한_글자도_없다")
    void forbiddenPackagesNeverReferenceTheExclusionFlag() {
        // ★양성 대조 — 「반드시 잡혀야 하는 것」이 실제로 잡히는지 먼저 본다. 이게 없으면 패턴이 아무것도
        //   못 잡는 상태에서도 0건이 나와 가드가 거짓으로 초록이 된다.
        assertThat(loadJava(MAIN_JAVA.resolve("kr/co/cudo/authoring/video/repository")).stream()
                .filter(s -> EXCLUSION_FLAG.matcher(s.content()).find()
                        || EXCLUSION_SCOPE.matcher(s.content()).find())
                .map(Source::path))
                .as("양성 대조 실패 — 술어 소유자 패키지에서조차 제외 표시를 못 찾는다면 이 가드의 0건은"
                        + " 아무것도 증명하지 못한다")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (String pkg : FORBIDDEN_PACKAGES) {
            Path root = MAIN_JAVA.resolve(pkg);
            assertThat(Files.isDirectory(root))
                    .as("금지 패키지 경로(%s)가 존재해야 한다 — 패키지가 개명되면 이 가드가 조용히"
                            + " 아무것도 검사하지 않게 된다", root)
                    .isTrue();
            for (Source source : loadJava(root)) {
                if (EXCLUSION_FLAG.matcher(source.content()).find()
                        || EXCLUSION_SCOPE.matcher(source.content()).find()) {
                    violations.add(source.path());
                }
            }
        }

        assertThat(violations)
                .as("★제외는 <저작도구 화면 시야만>이다. 배치 파이프라인·관제 통지·학습데이터 산출물"
                        + "(콘텐츠 해시 포함)·통계·포털에 제외 조건을 붙이면, 관제가 보던 행이 예고 없이"
                        + " 사라져 ADR-037(검수 완료·통지 건의 관제 접근 무조건 보장)을 깨고, 제외를 켰다"
                        + " 끄는 것만으로 산출물이 재생성된다. 위반 파일: %s", violations)
                .isEmpty();
    }

    /**
     * ★데이터마트 조회 뷰는 <b>관제가 SELECT 하는 계약면</b>이다 — 뷰를 만드는 SQL 어디에도 제외 조건이
     * 들어가면 안 된다. 실행 시점 판정은 {@code VideoExclusionBoundaryIT} 가 뷰 정의를 직접 읽어
     * 확인하고, 이 가드는 <b>그 뷰를 만드는 원본</b>을 막는다(두 가드는 서로를 대신하지 못한다).
     */
    @Test
    @DisplayName("★★경계_데이터마트_뷰를_만드는_SQL에_제외_조건이_들어가지_않는다")
    void datamartViewSqlNeverReferencesTheExclusionFlag() {
        int scanned = 0;
        List<String> violations = new ArrayList<>();
        for (Source source : loadSql(MAIN_RESOURCES)) {
            scanned += viewStatements(source.content()).size();
            // ★파일 단위가 아니라 <b>뷰를 만드는 문장 단위</b>로 본다 — 같은 파일(예: 베이스라인)이
            //   영상 원장 DDL 과 뷰 정의를 함께 담을 수 있어, 파일 전체를 훑으면 정상 컬럼 선언이
            //   위반으로 오탐된다.
            for (String statement : viewStatements(source.content())) {
                if (EXCLUSION_FLAG.matcher(statement).find()) {
                    violations.add(source.path());
                    break;
                }
            }
        }

        // ★양성 대조 — 뷰 정의를 하나도 못 찾았다면 위 0건은 「위반이 없다」가 아니라 「검사를 안 했다」다.
        assertThat(scanned)
                .as("데이터마트 뷰 정의 문장을 한 건도 찾지 못했다 — 추출 규칙이 깨졌거나 뷰가 다른"
                        + " 곳으로 옮겨졌다. 그대로 두면 이 가드는 영영 아무것도 검사하지 않는다")
                .isGreaterThanOrEqualTo(4);

        assertThat(violations)
                .as("★데이터마트 조회 뷰(V_COMPLETED_*)를 만드는 SQL 이 제외 표시를 참조한다 —"
                        + " 검수 완료·통지 건에 대한 관제 접근 보장(ADR-037)이 깨진다. 위반 파일: %s",
                        violations)
                .isEmpty();
    }

    // --- helpers ---

    /**
     * {@code CREATE [OR REPLACE] VIEW V_COMPLETED_...} 한 문장씩 잘라낸다(세미콜론까지).
     *
     * <p>주석·문자열 리터럴은 호출 전에 이미 제거돼 있어 세미콜론이 문장 경계로 안전하게 쓰인다.
     */
    private static List<String> viewStatements(String sql) {
        Matcher m = Pattern.compile(
                        "CREATE\\s+(?:OR\\s+REPLACE\\s+)?VIEW\\s+[^;]*?V_COMPLETED_[^;]*;",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(sql);
        List<String> statements = new ArrayList<>();
        while (m.find()) {
            statements.add(m.group());
        }
        return statements;
    }

    private record Source(String path, String content) {
    }

    private List<Source> loadJava(Path root) {
        return load(root, ".java",
                s -> JAVA_LINE_COMMENT.matcher(JAVA_BLOCK_COMMENT.matcher(s).replaceAll(" "))
                        .replaceAll(" "));
    }

    private List<Source> loadSql(Path root) {
        return load(root, ".sql",
                s -> SQL_STRING_LITERAL.matcher(
                                SQL_LINE_COMMENT.matcher(
                                                SQL_BLOCK_COMMENT.matcher(s).replaceAll(" "))
                                        .replaceAll(" "))
                        .replaceAll(" "));
    }

    private List<Source> load(Path root, String suffix,
                              java.util.function.UnaryOperator<String> stripper) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .map(p -> new Source(p.toString(), stripper.apply(read(p))))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 오탐 신고용 — 매칭 위치를 보고할 때 쓴다(현재는 파일 단위 보고라 미사용 방지용 참조). */
    @SuppressWarnings("unused")
    private static String firstMatch(Pattern pattern, String content) {
        Matcher m = pattern.matcher(content);
        return m.find() ? m.group() : "";
    }
}
