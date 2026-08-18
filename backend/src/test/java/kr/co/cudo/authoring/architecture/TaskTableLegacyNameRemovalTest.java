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
 * V9 표준용어 개명({@code LS_TASK_ASSIGNMENT} → {@code LS_TASK_ALTMNT} ·
 * {@code LS_TASK_EVENT_LOG} → {@code LS_TASK_EVNT_LOG}) 가드 — <b>옛 물리명이 되살아나면 실패</b>한다.
 *
 * <h3>왜 이 가드가 필요한가</h3>
 * <p>개명 누락은 <b>조용히</b> 생긴다. 물리명은 대부분 문자열 리터럴(네이티브 SQL·시드·
 * {@code @Table(name=)})이라 <b>컴파일이 잡아 주지 않고</b>, 이 저장소는
 * {@code spring.jpa.hibernate.ddl-auto} 가 EMF 로 전달되지 않아 <b>부팅 시 스키마 검증도 수행되지
 * 않는다</b>(없는 테이블을 매핑해도 기동은 성공한다). 그래서 정적 스캔이 유일한 결정론적 가드다
 * ({@link MngAcctUserTableRemovalTest} 와 동일 방식).
 *
 * <h3>동결 대상은 스캔하지 않는다</h3>
 * <p>{@code V1}~{@code V8} 원문과 {@code db-archive/} 는 <b>이미 적용됐거나 원문 보존 대상</b>이라
 * 내용을 바꿀 수 없다(Flyway 체크섬을 건드리면 노드가 기동 실패한다). 그 파일들이 옛 이름을 갖고 있는
 * 것이 <b>정상</b>이며 — {@code V9} 가 그 뒤에 개명한다 — 스캔에서 제외한다.
 *
 * <p>{@code V9} 자신도 제외 대상이다. 개명 스크립트는 정의상 옛 이름을 지목해야 한다.
 */
class TaskTableLegacyNameRemovalTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java");
    private static final Path TEST_JAVA = Paths.get("src/test/java");
    private static final Path MAIN_RESOURCES = Paths.get("src/main/resources");
    private static final Path TEST_RESOURCES = Paths.get("src/test/resources");

    /**
     * 동결 마이그레이션 경계 — 버전 8 이하는 <b>이미 적용돼 내용을 바꿀 수 없다</b>. 개명은 {@code V9}
     * 가 수행하므로 그 이하 파일이 옛 이름을 갖고 있는 것이 정상이다. 파일 목록을 나열하는 대신
     * 버전으로 경계를 두어 <b>가드가 저절로 낡지 않게</b> 한다.
     */
    private static final int FROZEN_MIGRATION_MAX_VERSION = 8;

    /** 개명을 <b>수행하는</b> 스크립트 — 옛 이름을 지목하는 것이 그 일이다. */
    private static final int RENAME_MIGRATION_VERSION = 9;

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__");

    /**
     * 자바 스캔 예외 — <b>두 부류뿐</b>이며 늘릴 때는 어느 부류인지 확인하라.
     *
     * <ol>
     *   <li><b>가드 자신</b> — 옛 이름을 패턴·목록으로 보유한다. 하는 단언이 전부 "존재하지 않는다"는
     *       <b>부정</b>이라 운영 스키마에 참조를 남기지 않는다.</li>
     *   <li><b>개명 전 형상을 재현하는 마이그레이션 IT</b> — 개명 <b>전</b>을 관측하려면 옛 이름이
     *       필요하다. 전부 <b>스스로 만들고 스스로 지우는 스크래치</b>다.</li>
     *   <li><b>동결 아카이브 원문을 재생하는 IT</b> — 옛 이름이 <b>아카이브를 찾아 들어가는 검색 키</b>로
     *       쓰인다. 그 원문은 고칠 수 없으므로(체크섬·원문 보존) 재생 사본에서 오늘의 이름으로
     *       바꿔치기하며, 그 바꿔치기의 <b>왼쪽 항</b>이 옛 이름이다.</li>
     * </ol>
     *
     * <p>⚠ 늘릴 때 기준: <b>실행 시 그 이름으로 DB 에 접근하는가</b>. 접근한다면 예외가 아니라 결함이다.
     * 위 세 부류는 전부 "부재를 단언" 하거나 "스크래치를 세운다" 거나 "문자열을 치환할 뿐" 이라
     * 운영 스키마에 옛 이름으로 접근하지 않는다.
     */
    private static final List<String> JAVA_ALLOWLIST = List.of(
            // ① 가드 자신 (부재 단언)
            "TaskTableLegacyNameRemovalTest.java",
            "MngAcctUserTableRemovalTest.java",
            "V9TaskTableStdTermRenameIT.java",
            // ② 개명 전 형상을 세우는 스크래치 IT (자체 CREATE → 자체 DROP)
            "V9TaskTableStdTermRenameGuardIT.java",
            "V4DropUnusedTablesIT.java",
            // ③ 동결 아카이브(V146) 원문을 재생하며 옛 이름을 치환 검색 키로 보유
            "LsDataRawOrphanCleanupIT.java");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern JAVA_LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--[^\\n\\r]*");
    /** SQL 문자열 리터럴 — {@code ''} 이스케이프 포함. */
    private static final Pattern SQL_STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'", Pattern.DOTALL);

    /**
     * 개명된 옛 물리명 — 테이블 2종과 <b>그 이름을 품은 파생 객체 전부</b>(시퀀스·제약·인덱스).
     *
     * <p><b>단어 경계를 쓰지 않는 것이 의도다.</b> 언더스코어는 정규식에서 <b>단어 문자</b>라
     * {@code \b} 를 붙이면 앞뒤가 언더스코어인 형태가 통째로 빠져나간다 — 실제로
     * {@code uk_ls_task_assignment}(앞) 와 {@code ls_task_assignment_assignment_id_seq}(뒤) 가
     * 그렇게 새는데, 이 둘은 이번 개명에서 <b>가장 빠뜨리기 쉬운 객체</b>다.
     *
     * <p>{@code LS_TASK_ASSIGN_HISTORY}(V4 가 이미 DROP)는 {@code ASSIGNMENT} 가 아니라
     * {@code ASSIGN_} 이라 걸리지 않는다 — 오탐이 아니다.
     */
    private static final Pattern LEGACY_PHYSICAL_NAME =
            Pattern.compile("LS_TASK_(?:ASSIGNMENT|EVENT_LOG)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("옛_물리명이_실행되는_자바와_SQL에_남아있지_않다")
    void 옛_물리명이_실행되는_자바와_SQL에_남아있지_않다() {
        // given: 실행되는 main/test 자바 + 배포 마이그레이션 + 시드 SQL(동결분 제외, 주석 제거)
        List<Source> sources = allExecutableSources();

        // when
        List<String> violations = scan(sources, LEGACY_PHYSICAL_NAME);

        // then
        assertThat(violations)
                .as("V9 로 개명된 옛 물리명(LS_TASK_ASSIGNMENT / LS_TASK_EVENT_LOG 및 그 파생 객체)이 "
                        + "실행 코드·SQL 에 남아있다 — 실행 시 relation 없음 오류가 난다. 위반: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("두_엔티티가_새_물리명으로_매핑돼_있다")
    void 두_엔티티가_새_물리명으로_매핑돼_있다() {
        // given: 위 스캔은 <부재>만 본다. 새 이름이 실제로 붙었는지는 별개 축이라 함께 고정한다
        //   (@Table 을 통째로 지워도 부재 단언은 통과한다).
        String assignment = read(MAIN_JAVA.resolve(
                "kr/co/cudo/authoring/assignment/entity/LsTaskAssignment.java"));
        String eventLog = read(MAIN_JAVA.resolve(
                "kr/co/cudo/authoring/assignment/entity/LsTaskEventLog.java"));

        // then
        assertThat(assignment)
                .as("배정 엔티티가 표준 물리명으로 매핑돼야 한다")
                .contains("@Table(name = \"LS_TASK_ALTMNT\"")
                .contains("name = \"UK_LS_TASK_ALTMNT\"");
        assertThat(eventLog)
                .as("이벤트 로그 엔티티가 표준 물리명으로 매핑돼야 한다")
                .contains("@Table(name = \"LS_TASK_EVNT_LOG\")");
    }

    @Test
    @DisplayName("자바_식별자는_개명하지_않는다")
    void 자바_식별자는_개명하지_않는다() {
        // given: 이번 개명의 범위는 <물리명 문자열>뿐이다. 클래스·필드·Q클래스·API 경로·FE 타입은
        //   그대로 두기로 확정됐다(클래스 개명은 임포트·Q클래스까지 번져 범위가 다르다).
        //   범위가 조용히 확대되면 여기서 잡힌다.
        List<Source> sources = new ArrayList<>(loadJavaSources(MAIN_JAVA));

        // when: 개명된 이름을 딴 자바 타입이 새로 생겼는지 본다
        List<String> violations = scan(sources,
                Pattern.compile("\\bQ?LsTask(?:Altmnt|EvntLog)\\b"));

        // then
        assertThat(violations)
                .as("자바 식별자는 이번 개명 범위가 아니다 — 엔티티는 LsTaskAssignment · LsTaskEventLog "
                        + "그대로 유지한다. 위반: %s", violations)
                .isEmpty();
    }

    // --- helpers ---

    private List<Source> allExecutableSources() {
        List<Source> sources = new ArrayList<>(loadJavaSources(MAIN_JAVA));
        sources.addAll(loadJavaSources(TEST_JAVA));
        sources.addAll(loadSqlSources(MAIN_RESOURCES));
        sources.addAll(loadSqlSources(TEST_RESOURCES));
        return sources;
    }

    private List<String> scan(List<Source> sources, Pattern... patterns) {
        List<String> violations = new ArrayList<>();
        for (Source source : sources) {
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(source.content());
                if (matcher.find()) {
                    violations.add(source.path() + " (match: " + matcher.group() + ")");
                    break; // 파일당 1회만 보고
                }
            }
        }
        return violations;
    }

    private List<Source> loadJavaSources(Path root) {
        return load(root, ".java", this::stripJavaComments,
                path -> !JAVA_ALLOWLIST.contains(path.getFileName().toString()));
    }

    private List<Source> loadSqlSources(Path root) {
        return load(root, ".sql", s -> stripSqlLiterals(stripSqlComments(s)),
                TaskTableLegacyNameRemovalTest::isScannableSql);
    }

    /**
     * 스캔 대상 SQL 판정.
     *
     * <ul>
     *   <li>버전 {@value #FROZEN_MIGRATION_MAX_VERSION} 이하 — 이미 적용돼 내용 변경 불가</li>
     *   <li>{@code V}{@value #RENAME_MIGRATION_VERSION} — 개명을 수행하는 스크립트 자신</li>
     *   <li>{@code db-archive/} — 원문 보존 아카이브(부모 디렉터리명으로 판정)</li>
     * </ul>
     */
    private static boolean isScannableSql(Path path) {
        if (path.toString().replace('\\', '/').contains("/db-archive/")) {
            return false;
        }
        Matcher m = MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!m.find()) {
            return true;
        }
        int version = Integer.parseInt(m.group(1));
        return version > FROZEN_MIGRATION_MAX_VERSION && version != RENAME_MIGRATION_VERSION;
    }

    private List<Source> load(Path root, String suffix,
                              java.util.function.UnaryOperator<String> stripper,
                              java.util.function.Predicate<Path> include) {
        assertThat(Files.isDirectory(root))
                .as("스캔 대상 디렉토리(%s)가 존재해야 한다 (테스트 작업 디렉토리=backend 모듈 루트)",
                        root.toAbsolutePath())
                .isTrue();
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(suffix))
                    .filter(include)
                    .map(p -> new Source(p.toString(), stripper.apply(read(p))))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("소스 스캔 실패: " + root, e);
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }

    private String stripJavaComments(String source) {
        String noBlock = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return JAVA_LINE_COMMENT.matcher(noBlock).replaceAll(" ");
    }

    private String stripSqlComments(String source) {
        String noBlock = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return SQL_LINE_COMMENT.matcher(noBlock).replaceAll(" ");
    }

    private String stripSqlLiterals(String source) {
        return SQL_STRING_LITERAL.matcher(source).replaceAll(" ");
    }

    private record Source(String path, String content) {
    }
}
