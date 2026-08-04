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
 * 관제 이벤트유형 마스터 2종 제거 가드
 * ({@code MNG_EX_EVNT_TYPE} / {@code MNG_EX_EVNT_TYPE_MAP}).
 *
 * <p><b>배경</b>: 관제 2차에서 적재 주체가 반전되어 이벤트유형코드·이벤트명·이벤트분류코드가
 * <b>인입 평면값</b>({@code LS_DATA_INGEST})으로 온다. V168 이 저작도구 소유 마스터
 * ({@code LS_EVNT_TYPE})를 만들어 인입값을 자동 등록하게 하고 2종을 DROP 했다. 본 테스트가
 * 회귀(누군가 다시 엔티티·리포·시드·SQL 에 되살리는 것)를 차단한다.
 *
 * <p><b>왜 소스 스캔인가</b>: 이 프로젝트는 {@code JpaBuilderConfig} 가
 * {@code spring.jpa.hibernate.ddl-auto} 를 EMF 로 넘기지 않아 <b>부팅 시 스키마 검증이 실제로
 * 수행되지 않는다</b>(존재하지 않는 테이블을 매핑해도 기동은 성공한다). 즉 삭제 누락이 기동에서
 * 잡히지 않으므로 <b>정적 스캔이 유일한 결정론적 가드</b>다.
 * {@link MngControlMasterTableRemovalTest}(Phase 3) 와 동일한 방식이다.
 *
 * <p><b>이력 마이그레이션 예외</b>: 이미 적용된 마이그레이션은 내용을 바꿀 수 없다(Flyway 체크섬 —
 * 바꾸면 전 노드 기동 실패). {@code V2}(최초 CREATE) · {@code V71}(stub 교정) ·
 * {@code V168}(이관·DROP 본체, 테이블명을 적어야 지울 수 있다) 만 제외한다.
 */
class EvntTypeMasterTableRemovalTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java");
    private static final Path TEST_JAVA = Paths.get("src/test/java");
    private static final Path MAIN_RESOURCES = Paths.get("src/main/resources");
    private static final Path TEST_RESOURCES = Paths.get("src/test/resources");
    private static final Path MIGRATION_DIR = Paths.get("src/main/resources/db/migration");
    private static final Path V168 =
            MIGRATION_DIR.resolve("V168__create_ls_evnt_type_and_drop_mng_masters.sql");

    /** 이미 적용돼 내용 변경이 불가능한(Flyway 체크섬) 이력 마이그레이션 + 이관·DROP 본체 — 스캔 제외. */
    private static final List<String> MIGRATION_ALLOWLIST = List.of(
            "V2__phase3_video_queue_quartz.sql",
            "V71__align_mng_ex_evnt_type_columns.sql",
            "V168__create_ls_evnt_type_and_drop_mng_masters.sql");

    /**
     * 자바 스캔 예외 — <b>정확히 2개</b>이며 늘리지 말 것.
     *
     * <ul>
     *   <li>가드 자신 — 위반 패턴을 문자열로 보유한다.</li>
     *   <li>{@code V168EvntTypeMigrationIT} — V168 의 <b>이관</b>은 정의상 마스터가 살아 있는 시점
     *       (DROP 직전)에 도는 문장이라, 그 시점을 재현하려면 스크래치 마스터가 필요하다. 스스로
     *       만들고 종료 시 스스로 DROP 한다.</li>
     * </ul>
     *
     * <p>⚠ 이 목록을 늘릴 때는 "<b>스스로 만들고 스스로 지우는 스크래치</b>"인지 확인하라 —
     * 운영 스키마에 남는 참조를 여기 넣으면 가드가 무력화된다.
     */
    private static final String SELF = "EvntTypeMasterTableRemovalTest.java";

    /** 표시명 4단 폴백의 단일 진실원 — 이 경로만 폴백식을 보유할 수 있다. */
    private static final String POLICY =
            "kr/co/cudo/authoring/eventtype/policy/EventTypeDisplayNamePolicy.java";
    private static final List<String> JAVA_ALLOWLIST = List.of(SELF, "V168EvntTypeMigrationIT.java");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern JAVA_LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--[^\\n\\r]*");
    /** SQL 문자열 리터럴 — {@code ''} 이스케이프 포함. */
    private static final Pattern SQL_STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'", Pattern.DOTALL);

    /** 삭제된 테이블 2종의 물리명 (MAP 이 먼저 매칭되도록 대안 순서 주의). */
    private static final Pattern DEAD_TABLES = Pattern.compile(
            "\\bMNG_EX_EVNT_TYPE(_MAP)?\\b", Pattern.CASE_INSENSITIVE);

    /** 삭제된 엔티티·리포지토리 타입 식별자(Q 클래스 포함). */
    private static final Pattern DEAD_TYPES = Pattern.compile("\\bQ?MngExEvntType\\w*\\b");

    /** JPA 매핑 선언 — {@code @Table(name = "MNG_EX_EVNT_TYPE")} 형태. */
    private static final Pattern DEAD_TABLE_MAPPING = Pattern.compile(
            "@Table\\s*\\(\\s*name\\s*=\\s*\"MNG_EX_EVNT_TYPE(_MAP)?\"", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("삭제된_이벤트유형마스터_2종은_엔티티_매핑이_존재하지_않는다")
    void 삭제된_이벤트유형마스터_2종은_엔티티_매핑이_존재하지_않는다() {
        // given: 프로덕션 main 자바 소스 전체(주석 제거)
        List<Source> sources = loadJavaSources(MAIN_JAVA);

        // when
        List<String> violations = scan(sources, DEAD_TABLE_MAPPING);

        // then
        assertThat(violations)
                .as("V168 로 삭제된 테이블에 대한 @Table 매핑이 남아있다. 위반 파일: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("삭제된_이벤트유형마스터_엔티티리포_타입_참조가_소스전체에_없다")
    void 삭제된_이벤트유형마스터_엔티티리포_타입_참조가_소스전체에_없다() {
        // given: main + test 자바 전체(가드 자신 제외)
        List<Source> sources = new ArrayList<>(loadJavaSources(MAIN_JAVA));
        sources.addAll(loadJavaSources(TEST_JAVA));

        // when
        List<String> violations = scan(sources, DEAD_TYPES);

        // then
        assertThat(violations)
                .as("삭제된 이벤트유형 마스터 엔티티/리포지토리 타입 참조가 남아있다. 위반 파일: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("삭제된_이벤트유형마스터_2종은_실행되는_SQL과_시드에서_참조되지_않는다")
    void 삭제된_이벤트유형마스터_2종은_실행되는_SQL과_시드에서_참조되지_않는다() {
        // given: main/test 자바 + main/test 리소스 SQL(이력 마이그레이션 제외), 주석 제거
        List<Source> sources = new ArrayList<>(loadJavaSources(MAIN_JAVA));
        sources.addAll(loadJavaSources(TEST_JAVA));
        sources.addAll(loadSqlSources(MAIN_RESOURCES));
        sources.addAll(loadSqlSources(TEST_RESOURCES));

        // when
        List<String> violations = scan(sources, DEAD_TABLES);

        // then: 실행되는 어떤 SQL(native 쿼리·시드·테스트 픽스처·신규 마이그레이션)도 참조하면 안 된다
        assertThat(violations)
                .as("V168 로 삭제된 테이블을 참조하는 실행 코드/SQL 이 남아있다(실행 시 relation 없음 오류)."
                        + " 위반 파일: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("V168은_2종만_DROP하고_인입·영상_테이블은_건드리지_않는다")
    void V168은_2종만_DROP하고_인입_영상_테이블은_건드리지_않는다() {
        // given: V168 실행 SQL 본문(주석 + 문자열 리터럴 제거 — 롤백 절차 주석의 DDL 인용과
        //   COMMENT ON 설명문이 오탐되지 않게)
        assertThat(Files.exists(V168)).as("V168 마이그레이션 파일이 존재해야 한다").isTrue();
        String executable = stripSqlLiterals(stripSqlComments(read(V168))).toUpperCase();

        // when/then: 2종 DROP 이 모두 있어야 한다
        for (String table : List.of("MNG_EX_EVNT_TYPE_MAP", "MNG_EX_EVNT_TYPE")) {
            assertThat(executable)
                    .as("%s DROP 구문이 있어야 한다", table)
                    .contains("DROP TABLE IF EXISTS " + table);
        }

        // then: ★파괴적 구문은 위 2개 DROP <말고는 하나도 없어야> 한다(비가역 소실 방지).
        //   V168 은 테이블 생성·컬럼 추가·이관 INSERT·프리셋 UPDATE 도 하므로 "테이블명이 등장하는가"
        //   로는 판정할 수 없다 — <행위>로 판정한다.
        assertThat(dropTargets(executable))
                .as("V168 의 DROP 대상은 관제 이벤트유형 마스터 2종뿐이어야 한다 — 그 외 테이블이 섞이면"
                        + " 비가역 데이터 소실이다")
                .containsExactlyInAnyOrder("MNG_EX_EVNT_TYPE_MAP", "MNG_EX_EVNT_TYPE");

        // then: 행 삭제 구문은 하나도 없다. 이관은 <INSERT 전용>이며 기존 행을 지우지 않는다.
        for (String forbidden : List.of("DELETE FROM", "TRUNCATE", "DROP COLUMN")) {
            assertThat(executable)
                    .as("V168 실행 SQL 에 파괴적 구문(%s)이 있다 — 이 마이그레이션은 컬럼 추가 + 테이블"
                            + " 생성 + 이관 INSERT + 프리셋 UPDATE + DROP 2건만 한다", forbidden)
                    .doesNotContain(forbidden);
        }

        // then: ★UPDATE 는 프리셋 축 전환 1건뿐이어야 한다(다른 테이블을 갱신하면 범위 이탈).
        assertThat(updateTargets(executable))
                .as("V168 의 UPDATE 대상은 LS_LABEL_PRESET 뿐이어야 한다")
                .containsExactly("LS_LABEL_PRESET");

        // then: ★이관은 멱등이어야 한다(재실행 시 중복·덮어쓰기가 없다)
        assertThat(countOccurrences(executable, "ON CONFLICT (EVNT_TYPE_CD) DO NOTHING"))
                .as("이관 INSERT 2건(마스터·인입)이 모두 ON CONFLICT DO NOTHING 이어야 한다")
                .isEqualTo(2);

        // then: 범위 밖 테이블은 실행 SQL 에 아예 등장하지 않는다.
        for (String outOfScope : List.of("LS_DATA_SRC", "MNG_ACCT_USER", "LS_DATA_RAW",
                "LS_RAW_DATA_STATUS", "LS_DATA_LBL")) {
            assertThat(executable)
                    .as("V168 실행 SQL 이 범위 밖 테이블 %s 를 참조한다", outOfScope)
                    .doesNotContain(outOfScope);
        }
    }

    @Test
    @DisplayName("V168은_구버전_롤백용_재생성_DDL을_주석으로_보존한다")
    void V168은_구버전_롤백용_재생성_DDL을_주석으로_보존한다() {
        // given: V168 전문(주석 포함)
        String raw = read(V168).toUpperCase();

        // when/then: 구버전 jar 롤백 시 런타임 파손을 막을 재생성 DDL 이 주석에 있어야 한다.
        //   Flyway 는 down-migration 을 수행하지 않으므로 DBA 수동 적용 절차가 파일에 남아야 한다.
        for (String table : List.of("MNG_EX_EVNT_TYPE", "MNG_EX_EVNT_TYPE_MAP")) {
            assertThat(raw)
                    .as("롤백용 %s 재생성 DDL 이 주석에 보존돼야 한다", table)
                    .contains("CREATE TABLE IF NOT EXISTS PUBLIC." + table);
        }
        assertThat(raw)
                .as("Flyway 이력 정리(DELETE FROM flyway_schema_history) 절차가 주석에 있어야 한다")
                .contains("FLYWAY_SCHEMA_HISTORY");
        assertThat(raw)
                .as("프리셋 축 되돌리기 절차가 주석에 있어야 한다(구버전은 카테고리 키를 기대한다)")
                .contains("LS_LABEL_PRESET");
    }

    @Test
    @DisplayName("표시명_폴백_판정은_정책클래스_한곳에만_있다")
    void 표시명_폴백_판정은_정책클래스_한곳에만_있다() {
        // given: main 자바 + 실행 SQL 전체. 표시명은 4단 폴백
        //   COALESCE(운영자 표시명, 관제 수신 유형명, 카테고리명, 유형코드)이며, 이 판정이 복제되면
        //   화면(필터·라벨맵·관리)과 산출물(승인 시점 동결 → export event_name)이 조용히 갈라진다.
        List<Source> sources = new ArrayList<>(loadJavaSources(MAIN_JAVA));
        sources.addAll(loadSqlSources(MAIN_RESOURCES));

        // when: 폴백 구성요소인 OPTR_INDCT_NM 을 <판정 목적으로> 쓰는 파일
        //   (COALESCE 안에 등장하거나 자바에서 다른 이름 후보와 함께 등장하는 형태)
        Pattern fallback = Pattern.compile(
                "COALESCE\\s*\\([^;]*OPTR_INDCT_NM", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        List<String> violations = new ArrayList<>();
        for (Source source : sources) {
            if (fallback.matcher(source.content()).find() && !source.path().endsWith(POLICY)) {
                violations.add(source.path());
            }
        }

        // then: 정책 클래스(SQL 상수 보유) 외에는 폴백식을 직접 쓰지 않는다
        assertThat(violations)
                .as("표시명 폴백식이 %s 밖에 복제돼 있다 — 판정은 한 곳에만 둔다. 위반: %s",
                        POLICY, violations)
                .isEmpty();

        // then: 정책 클래스는 실재하고 4단 폴백을 모두 보유한다(가드가 빈 껍데기가 되지 않게)
        Path policy = MAIN_JAVA.resolve(POLICY);
        assertThat(Files.exists(policy)).as("표시명 정책 클래스가 존재해야 한다").isTrue();
        String policySource = read(policy);
        for (String part : List.of("OPTR_INDCT_NM", "EVNT_NM", "EVNT_CTGRY_NM", "EVNT_TYPE_CD")) {
            assertThat(policySource).as("폴백 구성요소 %s 가 정책에 있어야 한다", part).contains(part);
        }
    }

    @Test
    @DisplayName("이벤트분류코드는_유형코드에서_유도되지_않는다")
    void 이벤트분류코드는_유형코드에서_유도되지_않는다() {
        // given: main 자바 + 실행 SQL 전체 — 대분류는 <관제 수신값>이며 코드에서 유도하지 않는다
        //   (사용자 확정 2026-08-04). 유도하면 비규격 코드에서 존재하지 않는 대분류가 만들어지고
        //   제외 필터가 영상을 조용히 숨긴다.
        List<Source> sources = new ArrayList<>(loadJavaSources(MAIN_JAVA));
        sources.addAll(loadSqlSources(MAIN_RESOURCES));

        // when: 유형코드에서 부분문자열을 잘라내는 패턴
        Pattern derivation = Pattern.compile(
                "SUBSTRING\\s*\\(\\s*(R\\.)?EVNT_TYPE_CD|EVNT_TYPE_CD\\s*\\.\\s*substring\\s*\\(",
                Pattern.CASE_INSENSITIVE);
        List<String> violations = scan(sources, derivation);

        // then
        assertThat(violations)
                .as("이벤트유형코드에서 대분류를 유도하는 코드가 있다 — 폐기된 규칙이다. 위반 파일: %s",
                        violations)
                .isEmpty();
    }

    /** 실행 SQL 의 {@code DROP TABLE [IF EXISTS] X} 대상 테이블명을 모두 뽑는다. */
    private static List<String> dropTargets(String executableUpperCase) {
        return targetsOf(executableUpperCase, "DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([A-Z0-9_.]+)");
    }

    /** 실행 SQL 의 {@code UPDATE X} 대상 테이블명을 모두 뽑는다. */
    private static List<String> updateTargets(String executableUpperCase) {
        return targetsOf(executableUpperCase, "\\bUPDATE\\s+([A-Z0-9_.]+)");
    }

    private static List<String> targetsOf(String source, String regex) {
        Matcher m = Pattern.compile(regex).matcher(source);
        List<String> targets = new ArrayList<>();
        while (m.find()) {
            String t = m.group(1);
            targets.add(t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t);
        }
        return targets;
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int idx = source.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = source.indexOf(needle, idx + needle.length());
        }
        return count;
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

    private List<Source> loadJavaSources(Path root) {
        return load(root, ".java", this::stripJavaComments,
                path -> !JAVA_ALLOWLIST.contains(path.getFileName().toString()));
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

    /** SQL 작은따옴표 문자열 리터럴 제거 — 설명문 안의 테이블명이 참조로 오탐되지 않게. */
    private String stripSqlLiterals(String source) {
        return SQL_STRING_LITERAL.matcher(source).replaceAll(" ");
    }

    private String stripSqlComments(String source) {
        String noBlock = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return SQL_LINE_COMMENT.matcher(noBlock).replaceAll(" ");
    }

    private record Source(String path, String content) {
    }
}
