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
 * 죽은 계정 권한 테이블 2종({@code MNG_ACCT_AUTHRT} / {@code MNG_ACCT_USER_AUTHRT}) 제거 가드.
 *
 * <p><b>배경</b>: 역할 분리 리팩토링에서 저작도구 인가 역할의 단일 진실원이 저작도구 소유
 * {@code LS_USER_ROLE}(V75) 로 이관되면서 위 두 테이블의 런타임 참조가 0 이 됐다. 그럼에도
 * 엔티티({@code MngAcctAuthrt})·시드·테스트 픽스처만 잔존해 "관제 계정 권한을 저작도구가 계속
 * 쓴다"는 오인을 재생산했다. V165 가 두 테이블을 DROP 하고, 본 테스트가 회귀(누군가 다시
 * 엔티티/시드/SQL 에 되살리는 것)를 차단한다.
 *
 * <p><b>스캔 방식</b>: ArchUnit 미사용 프로젝트이므로 {@link MngAcctUserTableRemovalTest} 와 동일한
 * 순수 파일 스캔을 사용한다. Java 는 블록·라인 주석을, SQL 은 {@code --} / 블록 주석을 제거한 뒤
 * 판정하므로 "구 구조 설명" Javadoc·마이그레이션 주석은 위반으로 오탐되지 않는다.
 *
 * <p><b>마이그레이션 예외 3종</b>: {@code V1}(최초 CREATE) · {@code V75}(구 매핑 → LS_USER_ROLE 이관
 * SELECT) · {@code V165}(DROP 본체) 는 이미 적용된 이력이라 내용을 바꿀 수 없다(체크섬). 이 3개만
 * 스캔에서 제외하고, 그 외 어떤 마이그레이션도 두 테이블을 다시 언급하지 못하게 막는다.
 *
 * <p><b>가드 이력</b>: {@code MngAcctUserAuthrt}/{@code MngAcctAuthrt} 엔티티 타입 참조 가드는 원래
 * 구 {@code MngAcctWriteGuardTest}(V165 당시 "관제 소유 {@code MNG_ACCT_USER} 쓰기 금지" 로 책임이
 * 좁아진 클래스)에 있었으나 본 클래스로 옮겨왔다(검증 유실 없음). 그 구 클래스는 V169 에서
 * {@code MNG_ACCT_USER} 자체가 삭제되면서 <b>삭제됐고</b>, 그 취지는 {@link MngAcctUserTableRemovalTest}
 * 가 <b>{@code MNG_} 접두 참조 0</b> 이라는 더 강한 기준으로 승계했다.
 */
class DeadAcctAuthrtTableRemovalTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java");
    private static final Path MAIN_RESOURCES = Paths.get("src/main/resources");
    private static final Path TEST_RESOURCES = Paths.get("src/test/resources");
    private static final Path MIGRATION_DIR = Paths.get("src/main/resources/db/migration");
    private static final Path V165 = MIGRATION_DIR.resolve("V165__drop_dead_acct_authrt_tables.sql");

    /** 이미 적용돼 내용 변경이 불가능한(Flyway 체크섬) 이력 마이그레이션 — 스캔 제외. */
    private static final List<String> MIGRATION_ALLOWLIST = List.of(
            "V1__phase2_base_schema.sql",
            "V75__create_ls_user_role.sql",
            "V165__drop_dead_acct_authrt_tables.sql");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern JAVA_LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--[^\\n\\r]*");

    /** 죽은 테이블 2종 — {@code MNG_ACCT_AUTHRT} 는 접두 단어경계로 한정(다른 테이블 오탐 방지). */
    private static final Pattern DEAD_TABLES =
            Pattern.compile("\\bMNG_ACCT_(USER_)?AUTHRT\\b", Pattern.CASE_INSENSITIVE);

    /** 삭제된 엔티티 타입 식별자 2종. */
    private static final Pattern DEAD_ENTITY_TYPES =
            Pattern.compile("\\bMngAcct(User)?Authrt\\b");

    /** JPA 매핑 선언 — {@code @Table(name = "MNG_ACCT_AUTHRT")} 형태. */
    private static final Pattern DEAD_TABLE_MAPPING = Pattern.compile(
            "@Table\\s*\\(\\s*name\\s*=\\s*\"MNG_ACCT_(USER_)?AUTHRT\"", Pattern.CASE_INSENSITIVE);

    /** 사용자 마스터 — 뒤에 '_' 가 오면(=MNG_ACCT_USER_AUTHRT) 제외(negative lookahead). */
    private static final Pattern USER_MASTER =
            Pattern.compile("\\bMNG_ACCT_USER(?!_)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("죽은_권한테이블_2종은_엔티티_매핑이_존재하지_않는다")
    void 죽은_권한테이블_2종은_엔티티_매핑이_존재하지_않는다() {
        // given: 프로덕션 main 자바 소스 전체(주석 제거)
        List<Source> sources = loadJavaSources();

        // when: @Table(name="MNG_ACCT_AUTHRT" | "MNG_ACCT_USER_AUTHRT") 매핑 선언 탐지
        List<String> violations = scan(sources, DEAD_TABLE_MAPPING);

        // then: V165 로 DROP 된 테이블에 대한 JPA 매핑은 0건이어야 한다
        //       (남으면 ddl-auto=validate 에서 "테이블 없음"으로 2노드 기동 실패)
        assertThat(violations)
                .as("V165 로 삭제된 테이블에 대한 @Table 매핑이 남아있다. 위반 파일: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("삭제된_권한엔티티_타입_참조가_프로덕션코드에_없다")
    void 삭제된_권한엔티티_타입_참조가_프로덕션코드에_없다() {
        // given
        List<Source> sources = loadJavaSources();

        // when: MngAcctAuthrt / MngAcctUserAuthrt 식별자 참조 탐지
        List<String> violations = scan(sources, DEAD_ENTITY_TYPES);

        // then: 엔티티/리포가 삭제됐으므로 참조 0건
        assertThat(violations)
                .as("삭제된 권한 엔티티 타입 참조가 프로덕션 코드에 남아있다. 위반 파일: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("죽은_권한테이블_2종은_실행되는_SQL과_시드에서_참조되지_않는다")
    void 죽은_권한테이블_2종은_실행되는_SQL과_시드에서_참조되지_않는다() {
        // given: main 자바 + main/test 리소스 SQL(이력 마이그레이션 3종 제외), 주석 제거
        List<Source> sources = new ArrayList<>(loadJavaSources());
        sources.addAll(loadSqlSources(MAIN_RESOURCES));
        sources.addAll(loadSqlSources(TEST_RESOURCES));

        // when: 두 테이블명 참조 탐지
        List<String> violations = scan(sources, DEAD_TABLES);

        // then: 실행되는 어떤 SQL(시드·테스트 픽스처·신규 마이그레이션)도 참조하면 안 된다
        assertThat(violations)
                .as("V165 로 삭제된 테이블을 참조하는 SQL 이 남아있다(실행 시 관계없음 오류). 위반 파일: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("V165는_죽은_권한테이블_2종만_DROP하고_MNG_ACCT_USER는_건드리지_않는다")
    void V165는_죽은_권한테이블_2종만_DROP하고_MNG_ACCT_USER는_건드리지_않는다() {
        // given: V165 실행 SQL 본문(주석 제거 — 롤백 절차 주석의 DDL 인용이 오탐되지 않게)
        assertThat(Files.exists(V165)).as("V165 마이그레이션 파일이 존재해야 한다").isTrue();
        String executable = stripSqlComments(read(V165)).toUpperCase();

        // when/then: 매핑 테이블 먼저, 코드 마스터 나중 순서로 두 테이블만 DROP
        int userAuthrtIdx = executable.indexOf("DROP TABLE IF EXISTS MNG_ACCT_USER_AUTHRT");
        int authrtIdx = executable.indexOf("DROP TABLE IF EXISTS MNG_ACCT_AUTHRT");
        assertThat(userAuthrtIdx).as("MNG_ACCT_USER_AUTHRT DROP 구문이 있어야 한다").isNotNegative();
        assertThat(authrtIdx).as("MNG_ACCT_AUTHRT DROP 구문이 있어야 한다").isNotNegative();
        assertThat(userAuthrtIdx)
                .as("매핑 테이블(MNG_ACCT_USER_AUTHRT)을 코드 마스터보다 먼저 DROP 해야 한다")
                .isLessThan(authrtIdx);

        // then: ★사용자 마스터(MNG_ACCT_USER)는 실행 SQL 에 절대 등장하면 안 된다.
        //       실수로 포함되면 전 사용자 데이터가 소실된다(비가역).
        assertThat(USER_MASTER.matcher(executable).find())
                .as("V165 실행 SQL 이 사용자 마스터 MNG_ACCT_USER 를 참조한다 — 전 사용자 데이터 소실 위험")
                .isFalse();

        // then: LS_USER_ROLE(역할 단일 진실원)도 건드리지 않는다
        assertThat(executable)
                .as("V165 는 역할 진실원 LS_USER_ROLE 을 건드리면 안 된다")
                .doesNotContain("LS_USER_ROLE");
    }

    @Test
    @DisplayName("V165는_구버전_롤백용_재생성_DDL을_주석으로_보존한다")
    void V165는_구버전_롤백용_재생성_DDL을_주석으로_보존한다() {
        // given: V165 전문(주석 포함)
        String raw = read(V165).toUpperCase();

        // when/then: 구버전 jar 롤백 시 ddl-auto=validate 기동 실패를 막을 재생성 DDL 이 주석에 있어야 한다.
        //   Flyway 는 down-migration 을 수행하지 않으므로 DBA 수동 적용 절차가 파일에 남아야 한다.
        assertThat(raw)
                .as("롤백용 MNG_ACCT_AUTHRT 재생성 DDL 이 주석에 보존돼야 한다")
                .contains("CREATE TABLE IF NOT EXISTS MNG_ACCT_AUTHRT");
        assertThat(raw)
                .as("롤백용 MNG_ACCT_USER_AUTHRT 재생성 DDL 이 주석에 보존돼야 한다")
                .contains("CREATE TABLE IF NOT EXISTS MNG_ACCT_USER_AUTHRT");
        assertThat(raw)
                .as("Flyway 이력 정리(DELETE FROM flyway_schema_history) 절차가 주석에 있어야 한다")
                .contains("FLYWAY_SCHEMA_HISTORY");
    }

    // --- helpers ---

    private List<String> scan(List<Source> sources, Pattern... patterns) {
        List<String> violations = new ArrayList<>();
        for (Source source : sources) {
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(source.content());
                if (matcher.find()) {
                    violations.add(source.path() + " (pattern: " + pattern.pattern() + ")");
                    break; // 파일당 1회만 보고
                }
            }
        }
        return violations;
    }

    private List<Source> loadJavaSources() {
        return load(MAIN_JAVA, ".java", this::stripJavaComments, path -> true);
    }

    private List<Source> loadSqlSources(Path root) {
        return load(root, ".sql", this::stripSqlComments,
                path -> !(path.getParent() != null
                        && path.getParent().endsWith(MIGRATION_DIR.getFileName())
                        && MIGRATION_ALLOWLIST.contains(path.getFileName().toString())));
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

    private record Source(String path, String content) {
    }
}
