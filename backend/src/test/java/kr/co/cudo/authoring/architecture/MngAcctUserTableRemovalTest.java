package kr.co.cudo.authoring.architecture;

import org.hibernate.annotations.Immutable;
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
 * 관제 사용자 마스터({@code MNG_ACCT_USER}) 제거 가드 — <b>{@code MNG_*} 9종 제거의 마지막 조각</b>.
 *
 * <p><b>배경</b>: 관제 2차 실DB 에는 {@code MNG_} 접두 테이블이 0개이고, 저작도구는 관제 DB 를
 * 조회하지 않는다. 사용자 정보는 관제가 브라우저 {@code localStorage} 로 인계하는 값
 * ({@code userId}·{@code userNm})을 <b>역할 클레임 시점에 자동등록</b>하는 방식으로 바뀌었고,
 * V169 가 저작도구 소유 마스터({@code LS_ACNT_USER})를 만들어 기존 행을 이관한 뒤
 * {@code MNG_ACCT_USER} 를 DROP 했다.
 *
 * <p><b>이 클래스가 승계한 책임</b>: 구 {@code MngAcctWriteGuardTest} 는 "관제 소유 테이블이니
 * <b>쓰기 금지</b>"를 검사했다. 테이블이 우리 소유로 바뀌면서 쓰기는 <b>정당해졌고</b>(자동등록),
 * 그 취지("관제 소유 테이블에 손대지 않는다")는 <b>{@code MNG_} 접두 전체 참조 0</b> 이라는 더 넓고
 * 강한 기준으로 승계된다 — 쓰기만 막던 것을 참조 자체를 막는 것으로 강화했으므로 검증 유실이 아니다.
 *
 * <p><b>왜 소스 스캔인가</b>: {@code JpaBuilderConfig} 가 {@code spring.jpa.hibernate.ddl-auto} 를
 * EMF 로 넘기지 않아 <b>부팅 시 스키마 검증이 실제로 수행되지 않는다</b>(없는 테이블을 매핑해도 기동은
 * 성공한다). 삭제 누락이 기동에서 안 잡히므로 정적 스캔이 유일한 결정론적 가드다
 * ({@link MngControlMasterTableRemovalTest}·{@link EvntTypeMasterTableRemovalTest} 와 동일 방식).
 */
class MngAcctUserTableRemovalTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java");
    private static final Path TEST_JAVA = Paths.get("src/test/java");
    private static final Path MAIN_RESOURCES = Paths.get("src/main/resources");
    private static final Path TEST_RESOURCES = Paths.get("src/test/resources");
    private static final Path MIGRATION_DIR = Paths.get("src/test/resources/db-archive/migration");
    private static final Path V169 =
            MIGRATION_DIR.resolve("V169__create_ls_acnt_user_and_drop_mng_acct_user.sql");

    /**
     * 이력 마이그레이션 경계 — 버전 169 이하는 <b>이미 적용돼 내용을 바꿀 수 없다</b>(Flyway 체크섬을
     * 건드리면 2노드가 기동 실패한다). 그 이후 신규 마이그레이션은 {@code MNG_} 를 쓸 이유가 없으므로
     * 스캔 대상이다. 파일 목록을 나열하는 대신 버전으로 경계를 두어 <b>가드가 저절로 낡지 않게</b> 한다.
     */
    private static final int FROZEN_MIGRATION_MAX_VERSION = 169;

    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__");

    /**
     * 자바 스캔 예외 — <b>두 부류뿐</b>이며 늘릴 때는 어느 부류인지 확인하라.
     *
     * <ol>
     *   <li><b>가드 자신</b> — 위반 패턴·검증 대상 테이블명을 문자열로 보유한다. 이 파일들이 하는
     *       단언은 전부 "존재하지 않는다 / 참조되지 않는다"는 <b>부정</b>이라 운영 스키마에 참조를
     *       남기지 않는다.</li>
     *   <li><b>스크래치 이관 IT</b> — 이관·백필은 정의상 <b>원본이 살아 있던 시점</b>의 문장이라,
     *       재현하려면 그 테이블이 필요하다. 전부 <b>스스로 만들고 종료 시 스스로 DROP</b> 한다.</li>
     * </ol>
     *
     * <p>⚠ 늘릴 때 기준: "스스로 만들고 스스로 지우는 스크래치"이거나 "부재를 단언하는 가드"인가.
     * 운영 스키마에 남는 참조를 여기 넣으면 가드가 무력화된다.
     */
    private static final List<String> JAVA_ALLOWLIST = List.of(
            // ① 가드 자신 (부재 단언)
            "MngAcctUserTableRemovalTest.java",
            "MngControlMasterTableRemovalTest.java",
            "EvntTypeMasterTableRemovalTest.java",
            "DeadAcctAuthrtTableRemovalTest.java",
            "LsClipScheduleQueFkIT.java",
            "LsDataRawChildFkCascadeIT.java",
            // ② 스크래치 이관·백필 IT (자체 CREATE → 자체 DROP)
            "V75MigrateLsUserRoleMigrationTest.java",
            "V164EvntTypeBackfillIT.java",
            "V167CctvBackfillIT.java",
            "V168EvntTypeMigrationIT.java",
            "V169AcntUserMigrationIT.java");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern JAVA_LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--[^\\n\\r]*");
    /** SQL 문자열 리터럴 — {@code ''} 이스케이프 포함. */
    private static final Pattern SQL_STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'", Pattern.DOTALL);

    /** 삭제된 사용자 마스터 물리명. */
    private static final Pattern DEAD_TABLE =
            Pattern.compile("\\bMNG_ACCT_USER\\b", Pattern.CASE_INSENSITIVE);

    /** 삭제된 엔티티 타입 식별자(Q 클래스 포함). */
    private static final Pattern DEAD_TYPE = Pattern.compile("\\bQ?MngAcctUser\\b");

    /** {@code MNG_} 접두 물리명 전체 — 9종 제거 완료 판정축. */
    private static final Pattern ANY_MNG_TABLE =
            Pattern.compile("\\bMNG_[A-Z0-9_]+\\b", Pattern.CASE_INSENSITIVE);

    /** JPA 매핑 선언 — {@code @Table(name = "MNG_...")} 형태. */
    private static final Pattern MNG_TABLE_MAPPING = Pattern.compile(
            "@Table\\s*\\(\\s*name\\s*=\\s*\"MNG_[A-Z0-9_]+\"", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("삭제된_사용자마스터는_엔티티_매핑이_존재하지_않는다")
    void 삭제된_사용자마스터는_엔티티_매핑이_존재하지_않는다() {
        // given: 프로덕션 main 자바 소스 전체(주석 제거)
        List<Source> sources = loadJavaSources(MAIN_JAVA);

        // when: @Table(name="MNG_...") 매핑 선언 탐지 — 사용자 마스터가 마지막이므로 접두 전체로 본다
        List<String> violations = scan(sources, MNG_TABLE_MAPPING);

        // then
        assertThat(violations)
                .as("관제 소유(MNG_) 테이블에 대한 @Table 매핑이 남아있다 — 9종 전부 DROP 됐다. 위반: %s",
                        violations)
                .isEmpty();
    }

    @Test
    @DisplayName("삭제된_사용자마스터_엔티티_타입_참조가_소스전체에_없다")
    void 삭제된_사용자마스터_엔티티_타입_참조가_소스전체에_없다() {
        // given: main + test 자바 전체(가드 자신 제외)
        List<Source> sources = new ArrayList<>(loadJavaSources(MAIN_JAVA));
        sources.addAll(loadJavaSources(TEST_JAVA));

        // when
        List<String> violations = scan(sources, DEAD_TYPE);

        // then
        assertThat(violations)
                .as("삭제된 MngAcctUser 엔티티(또는 QMngAcctUser) 타입 참조가 남아있다. 위반: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("삭제된_사용자마스터는_실행되는_SQL과_시드에서_참조되지_않는다")
    void 삭제된_사용자마스터는_실행되는_SQL과_시드에서_참조되지_않는다() {
        // given: main/test 자바 + main/test 리소스 SQL(이력 마이그레이션 제외), 주석 제거
        List<Source> sources = allExecutableSources();

        // when
        List<String> violations = scan(sources, DEAD_TABLE);

        // then
        assertThat(violations)
                .as("V169 로 삭제된 MNG_ACCT_USER 를 참조하는 실행 코드/SQL 이 남아있다"
                        + "(실행 시 relation 없음 오류). 위반: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("MNG_접두_관제테이블_참조가_실행경로에_0건이다")
    void MNG_접두_관제테이블_참조가_실행경로에_0건이다() {
        // given: 실행되는 main/test 자바 + 시드·테스트 SQL(주석·SQL 문자열 리터럴 제거).
        //   ★이것이 "MNG_* 9종 제거 완료"의 판정축이다. 구 MngAcctWriteGuardTest 의
        //   "관제 소유 테이블에 쓰지 않는다" 취지를 "참조 자체가 0" 으로 강화해 승계한 것이다.
        List<Source> sources = allExecutableSources();

        // when
        List<String> violations = scan(sources, ANY_MNG_TABLE);

        // then
        assertThat(violations)
                .as("관제 소유(MNG_) 테이블을 참조하는 실행 코드/SQL 이 남아있다 — 9종은 V162·V165·V167·"
                        + "V168·V169 로 전부 제거됐다. 위반: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("V169는_사용자마스터만_DROP하고_다른_테이블은_건드리지_않는다")
    void V169는_사용자마스터만_DROP하고_다른_테이블은_건드리지_않는다() {
        // given: V169 실행 SQL 본문(주석 + 문자열 리터럴 제거 — 롤백 절차 주석의 DDL 인용과
        //   COMMENT ON 설명문이 오탐되지 않게)
        assertThat(Files.exists(V169)).as("V169 마이그레이션 파일이 존재해야 한다").isTrue();
        String executable = stripSqlLiterals(stripSqlComments(read(V169))).toUpperCase();

        // when/then: 사용자 마스터 DROP 이 있어야 한다
        assertThat(executable)
                .as("MNG_ACCT_USER DROP 구문이 있어야 한다")
                .contains("DROP TABLE IF EXISTS MNG_ACCT_USER");

        // then: ★파괴적 구문은 그 DROP 하나뿐이어야 한다(비가역 소실 방지).
        assertThat(dropTargets(executable))
                .as("V169 의 DROP 대상은 관제 사용자 마스터 하나뿐이어야 한다")
                .containsExactly("MNG_ACCT_USER");

        // then: 행 삭제·컬럼 삭제 구문은 하나도 없다. 이관은 <INSERT 전용>이다.
        for (String forbidden : List.of("DELETE FROM", "TRUNCATE", "DROP COLUMN", "UPDATE ")) {
            assertThat(executable)
                    .as("V169 실행 SQL 에 파괴적 구문(%s)이 있다 — 이 마이그레이션은 테이블 생성 +"
                            + " 이관 INSERT + DROP 1건만 한다", forbidden)
                    .doesNotContain(forbidden);
        }

        // then: ★이관은 멱등이어야 한다(재실행 시 중복·덮어쓰기 없음)
        assertThat(executable)
                .as("이관 INSERT 가 ON CONFLICT DO NOTHING 이어야 한다")
                .contains("ON CONFLICT (USER_NO) DO NOTHING");

        // then: 범위 밖 테이블은 실행 SQL 에 아예 등장하지 않는다.
        for (String outOfScope : List.of("LS_USER_ROLE", "LS_DATA_RAW", "LS_TASK_ASSIGNMENT",
                "LS_DATA_LBL", "LS_RAW_DATA_STATUS")) {
            assertThat(executable)
                    .as("V169 실행 SQL 이 범위 밖 테이블 %s 를 참조한다", outOfScope)
                    .doesNotContain(outOfScope);
        }
    }

    @Test
    @DisplayName("V169는_구버전_롤백용_재생성_DDL을_주석으로_보존한다")
    void V169는_구버전_롤백용_재생성_DDL을_주석으로_보존한다() {
        // given: V169 전문(주석 포함)
        String raw = read(V169).toUpperCase();

        // when/then: Flyway 는 down-migration 을 하지 않으므로 구버전 jar 롤백 시 런타임 파손을
        //   막을 재생성 DDL + 데이터 복원 + 이력 정리 절차가 파일에 남아야 한다(V162 규약).
        assertThat(raw)
                .as("롤백용 MNG_ACCT_USER 재생성 DDL 이 주석에 보존돼야 한다")
                .contains("CREATE TABLE IF NOT EXISTS PUBLIC.MNG_ACCT_USER");
        assertThat(raw)
                .as("이관 데이터를 되돌리는 절차가 주석에 있어야 한다")
                .contains("INSERT INTO PUBLIC.MNG_ACCT_USER");
        assertThat(raw)
                .as("Flyway 이력 정리(DELETE FROM flyway_schema_history) 절차가 주석에 있어야 한다")
                .contains("FLYWAY_SCHEMA_HISTORY");
    }

    @Test
    @DisplayName("LS_ACNT_USER_엔티티는_더_이상_Immutable이_아니다")
    void LS_ACNT_USER_엔티티는_더_이상_Immutable이_아니다() {
        // given/when: 사용자 마스터가 저작도구 소유로 바뀌어 <자동등록으로 우리가 쓴다>.
        //   구 @Immutable(관제 소유 READ 전용) 이 남아 있으면 JPA dirty checking 갱신이 조용히
        //   무시되어 이름 갱신이 사라진다.
        //   ※ Class.forName 을 쓰는 이유: 이 가드는 "엔티티가 존재하고 Immutable 이 아니다"를
        //     판정하며, 타입을 컴파일 의존으로 걸면 부재 시 <컴파일 실패>라 사유가 드러나지 않는다.
        Class<?> entity;
        try {
            entity = Class.forName("kr.co.cudo.authoring.user.entity.LsAcntUser");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("저작도구 소유 사용자 마스터 엔티티(LsAcntUser)가 존재해야 한다", e);
        }
        Immutable immutable = entity.getAnnotation(Immutable.class);

        // then
        assertThat(immutable)
                .as("LsAcntUser 는 저작도구 소유 쓰기 가능 엔티티이므로 @Immutable 이면 안 된다")
                .isNull();
    }

    // --- helpers ---

    private List<Source> allExecutableSources() {
        List<Source> sources = new ArrayList<>(loadJavaSources(MAIN_JAVA));
        sources.addAll(loadJavaSources(TEST_JAVA));
        sources.addAll(loadSqlSources(MAIN_RESOURCES));
        sources.addAll(loadSqlSources(TEST_RESOURCES));
        return sources;
    }

    /** 실행 SQL 의 {@code DROP TABLE [IF EXISTS] X} 대상 테이블명을 모두 뽑는다. */
    private static List<String> dropTargets(String executableUpperCase) {
        Matcher m = Pattern.compile("DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([A-Z0-9_.]+)")
                .matcher(executableUpperCase);
        List<String> targets = new ArrayList<>();
        while (m.find()) {
            String t = m.group(1);
            targets.add(t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t);
        }
        return targets;
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
                MngAcctUserTableRemovalTest::isScannableSql);
    }

    /** 이력 마이그레이션(버전 {@value #FROZEN_MIGRATION_MAX_VERSION} 이하)은 내용 변경 불가 → 스캔 제외. */
    private static boolean isScannableSql(Path path) {
        Matcher m = MIGRATION_VERSION.matcher(path.getFileName().toString());
        if (!m.find()) {
            return true;
        }
        return Integer.parseInt(m.group(1)) > FROZEN_MIGRATION_MAX_VERSION;
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

    /** SQL 작은따옴표 문자열 리터럴 제거 — 설명문(COMMENT ON) 안의 테이블명이 참조로 오탐되지 않게. */
    private String stripSqlLiterals(String source) {
        return SQL_STRING_LITERAL.matcher(source).replaceAll(" ");
    }

    private record Source(String path, String content) {
    }
}
