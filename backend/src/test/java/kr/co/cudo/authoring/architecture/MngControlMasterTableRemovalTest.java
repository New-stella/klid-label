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
 * 관제 공유 클립·CCTV·지자체 마스터 4종 제거 가드
 * ({@code MNG_CLIP_MASTER} / {@code MNG_CLIP_EVNT_LST} / {@code MNG_RESOURCE_CCTV} /
 * {@code MNG_EX_LOCAL_GOV}).
 *
 * <p><b>배경</b>: 관제 2차에서 적재 주체가 반전되면서 관제는 영상 메타를 <b>인입 평면값</b>
 * ({@code LS_DATA_INGEST})으로 직접 실어 보낸다. 위 4종은 저작도구가 <b>남의 스키마를 읽던</b>
 * 잔존 경로이며, 실DB 에는 아예 없거나(관제 2차 전면 개명) 0행이라 조회해도 값이 나오지 않는다.
 * V167 이 4종을 DROP 하고, 본 테스트가 회귀(누군가 다시 엔티티·리포·시드·SQL 에 되살리는 것)를
 * 차단한다.
 *
 * <p><b>왜 소스 스캔인가</b>: 이 프로젝트는 {@code JpaBuilderConfig} 가
 * {@code spring.jpa.hibernate.ddl-auto} 를 EMF 로 넘기지 않아 <b>부팅 시 스키마 검증이 실제로
 * 수행되지 않는다</b>(존재하지 않는 테이블을 매핑해도 기동은 성공한다 — 실측 확인). 즉 삭제 누락이
 * 기동에서 잡히지 않으므로 <b>정적 스캔이 유일한 결정론적 가드</b>다.
 * {@link DeadAcctAuthrtTableRemovalTest}(Phase 1) 와 동일한 방식이다.
 *
 * <p><b>스캔 범위</b>: {@code src/main/java} + {@code src/test/java}(본 가드 자신 제외) +
 * {@code src/main/resources} · {@code src/test/resources} 의 모든 {@code .sql}(시드·테스트 픽스처
 * 포함). Java 는 블록·라인 주석을, SQL 은 {@code --} / 블록 주석을 제거한 뒤 판정하므로 "구 구조
 * 설명" Javadoc·마이그레이션 주석은 위반으로 오탐되지 않는다.
 *
 * <p><b>이력 마이그레이션 예외</b>: 이미 적용된 마이그레이션은 내용을 바꿀 수 없다(Flyway 체크섬 —
 * 바꾸면 전 노드 기동 실패). {@code V2}(최초 CREATE) · {@code V62}/{@code V63}(stub 교정·생성) ·
 * {@code V164}(백필 JOIN) · {@code V166}(과도기 폴백을 설명하는 {@code COMMENT ON COLUMN}
 * <b>문자열 리터럴</b> — SQL 주석이 아니라 스트리퍼가 걷어내지 못한다) ·
 * {@code V167}(DROP 본체, 테이블명을 적어야 지울 수 있다) 만 제외한다.
 * 그 외 어떤 마이그레이션도 4종을 다시 언급하지 못한다.
 *
 * <p>V166 이 남긴 <b>DB 컬럼 주석의 폐기된 서술</b>은 {@code V167} 이 같은 컬럼에
 * {@code COMMENT ON COLUMN} 을 다시 실행해 갱신한다(파일은 못 고쳐도 DB 는 최신이 된다).
 */
class MngControlMasterTableRemovalTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java");
    private static final Path TEST_JAVA = Paths.get("src/test/java");
    private static final Path MAIN_RESOURCES = Paths.get("src/main/resources");
    private static final Path TEST_RESOURCES = Paths.get("src/test/resources");
    private static final Path MIGRATION_DIR = Paths.get("src/main/resources/db/migration");
    private static final Path V167 =
            MIGRATION_DIR.resolve("V167__drop_mng_clip_cctv_localgov_tables.sql");

    /** 이미 적용돼 내용 변경이 불가능한(Flyway 체크섬) 이력 마이그레이션 + DROP 본체 — 스캔 제외. */
    private static final List<String> MIGRATION_ALLOWLIST = List.of(
            "V2__phase3_video_queue_quartz.sql",
            "V62__fix_mng_clip_master_stub_real_schema.sql",
            "V63__create_mng_clip_evnt_lst_stub.sql",
            "V164__backfill_ls_data_raw_evnt_type_cd_from_ingest.sql",
            "V166__add_ingest_evnt_type_and_privacy_columns.sql",
            "V167__drop_mng_clip_cctv_localgov_tables.sql");

    /**
     * 자바 스캔 예외 — <b>정확히 2개</b>이며 늘리지 말 것.
     *
     * <ul>
     *   <li>가드 자신 — 위반 패턴을 문자열로 보유한다.</li>
     *   <li>{@code V164EvntTypeBackfillIT} — 이미 적용된 V164 원본({@code JOIN MNG_CLIP_EVNT_LST})을
     *       <b>파일 그대로 재실행</b>해 검증하는 구조라, 실행 직전 V63 원문 DDL 로 스크래치 테이블을
     *       스스로 만들고 종료 시 DROP 한다. V164 파일은 Flyway 체크섬 때문에 고칠 수 없고, 실제 배포
     *       순서(V63 CREATE → V164 JOIN → V167 DROP)에서는 정상 동작하므로 <b>이 테스트가 지키는
     *       불변식 자체가 유효</b>하다. 자세한 근거는 그 클래스 javadoc 참조.</li>
     *   <li>{@code V167CctvBackfillIT} — 같은 이유. V167 의 <b>백필</b>은 정의상 마스터가 살아 있는
     *       시점(DROP 직전)에 도는 문장이라, 그 시점을 재현하려면 스크래치 마스터가 필요하다.
     *       역시 테스트 종료 시 DROP 한다.</li>
     * </ul>
     *
     * <p>⚠ 이 목록을 늘릴 때는 "<b>스스로 만들고 스스로 지우는 스크래치</b>"인지 확인하라 —
     * 운영 스키마에 남는 참조를 여기 넣으면 가드가 무력화된다.
     */
    private static final String SELF = "MngControlMasterTableRemovalTest.java";
    private static final List<String> JAVA_ALLOWLIST =
            List.of(SELF, "V164EvntTypeBackfillIT.java", "V167CctvBackfillIT.java");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern JAVA_LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--[^\\n\\r]*");
    /** SQL 문자열 리터럴 — {@code ''} 이스케이프 포함. */
    private static final Pattern SQL_STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'", Pattern.DOTALL);

    /** 삭제된 테이블 4종의 물리명. */
    private static final Pattern DEAD_TABLES = Pattern.compile(
            "\\bMNG_(CLIP_MASTER|CLIP_EVNT_LST|RESOURCE_CCTV|EX_LOCAL_GOV)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 삭제된 엔티티·리포지토리 타입 식별자(Q 클래스 포함). */
    private static final Pattern DEAD_TYPES = Pattern.compile(
            "\\bQ?Mng(ClipMaster|ClipEvntLst|ResourceCctv|ExLocalGov)\\w*\\b");

    /** JPA 매핑 선언 — {@code @Table(name = "MNG_RESOURCE_CCTV")} 형태. */
    private static final Pattern DEAD_TABLE_MAPPING = Pattern.compile(
            "@Table\\s*\\(\\s*name\\s*=\\s*\"MNG_(CLIP_MASTER|CLIP_EVNT_LST|RESOURCE_CCTV|EX_LOCAL_GOV)\"",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("삭제된_관제마스터_4종은_엔티티_매핑이_존재하지_않는다")
    void 삭제된_관제마스터_4종은_엔티티_매핑이_존재하지_않는다() {
        // given: 프로덕션 main 자바 소스 전체(주석 제거)
        List<Source> sources = loadJavaSources(MAIN_JAVA);

        // when
        List<String> violations = scan(sources, DEAD_TABLE_MAPPING);

        // then: V167 로 DROP 된 테이블에 대한 JPA 매핑은 0건이어야 한다
        assertThat(violations)
                .as("V167 로 삭제된 테이블에 대한 @Table 매핑이 남아있다. 위반 파일: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("삭제된_관제마스터_엔티티리포_타입_참조가_소스전체에_없다")
    void 삭제된_관제마스터_엔티티리포_타입_참조가_소스전체에_없다() {
        // given: main + test 자바 전체(가드 자신 제외)
        List<Source> sources = new ArrayList<>(loadJavaSources(MAIN_JAVA));
        sources.addAll(loadJavaSources(TEST_JAVA));

        // when
        List<String> violations = scan(sources, DEAD_TYPES);

        // then: 엔티티·리포지토리가 삭제됐으므로 타입 참조 0건
        assertThat(violations)
                .as("삭제된 관제 마스터 엔티티/리포지토리 타입 참조가 남아있다. 위반 파일: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("삭제된_관제마스터_4종은_실행되는_SQL과_시드에서_참조되지_않는다")
    void 삭제된_관제마스터_4종은_실행되는_SQL과_시드에서_참조되지_않는다() {
        // given: main/test 자바 + main/test 리소스 SQL(이력 마이그레이션 제외), 주석 제거
        List<Source> sources = new ArrayList<>(loadJavaSources(MAIN_JAVA));
        sources.addAll(loadJavaSources(TEST_JAVA));
        sources.addAll(loadSqlSources(MAIN_RESOURCES));
        sources.addAll(loadSqlSources(TEST_RESOURCES));

        // when
        List<String> violations = scan(sources, DEAD_TABLES);

        // then: 실행되는 어떤 SQL(native 쿼리·시드·테스트 픽스처·신규 마이그레이션)도 참조하면 안 된다
        assertThat(violations)
                .as("V167 로 삭제된 테이블을 참조하는 실행 코드/SQL 이 남아있다(실행 시 relation 없음 오류)."
                        + " 위반 파일: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("V167은_4종만_DROP하고_인입·영상_테이블은_건드리지_않는다")
    void V167은_4종만_DROP하고_인입_영상_테이블은_건드리지_않는다() {
        // given: V167 실행 SQL 본문(주석 제거 — 롤백 절차 주석의 DDL 인용이 오탐되지 않게)
        assertThat(Files.exists(V167)).as("V167 마이그레이션 파일이 존재해야 한다").isTrue();
        // 주석 + <문자열 리터럴>까지 걷어낸다 — COMMENT ON COLUMN 의 설명문에 테이블명이 등장하는데,
        //   그것은 <참조>가 아니라 설명이라 DDL 판정에 섞이면 오탐이 된다.
        String executable = stripSqlLiterals(stripSqlComments(read(V167))).toUpperCase();

        // when/then: 4종 DROP 이 모두 있어야 한다
        for (String table : List.of("MNG_CLIP_EVNT_LST", "MNG_CLIP_MASTER",
                "MNG_RESOURCE_CCTV", "MNG_EX_LOCAL_GOV")) {
            assertThat(executable)
                    .as("%s DROP 구문이 있어야 한다", table)
                    .contains("DROP TABLE IF EXISTS " + table);
        }

        // then: ★파괴적 구문은 위 4개 DROP <말고는 하나도 없어야> 한다(비가역 소실 방지).
        //   V167 은 인덱스 생성·백필 INSERT·주석 갱신도 하므로 "LS_DATA_RAW 라는 문자열이
        //   등장하는가"로는 판정할 수 없다(백필이 그 테이블을 읽는다) — <행위>로 판정한다.
        assertThat(dropTargets(executable))
                .as("V167 의 DROP 대상은 관제 공유 마스터 4종뿐이어야 한다 — 그 외 테이블이 섞이면"
                        + " 비가역 데이터 소실이다")
                .containsExactlyInAnyOrder("MNG_CLIP_EVNT_LST", "MNG_CLIP_MASTER",
                        "MNG_RESOURCE_CCTV", "MNG_EX_LOCAL_GOV");

        // then: DROP(TABLE) 외의 파괴적 구문은 하나도 없다. 백필은 <INSERT 전용>이며 기존 행을
        //   수정·삭제하지 않는다(멱등은 NOT EXISTS + ON CONFLICT DO NOTHING 으로 달성).
        for (String forbidden : List.of("DELETE FROM", "TRUNCATE", "ALTER TABLE", "UPDATE ")) {
            assertThat(executable)
                    .as("V167 실행 SQL 에 파괴적 구문(%s)이 있다 — 이 마이그레이션은 인덱스+백필 INSERT"
                            + " + DROP 4건 + 주석 갱신만 한다", forbidden)
                    .doesNotContain(forbidden);
        }

        // then: ★백필은 <원본만> 대상이며 <멱등>이어야 한다. 두 술어 중 하나라도 빠지면
        //   파생영상에 인입 행이 생겨 "파생은 자기 인입 행이 없다" 불변식이 깨지거나,
        //   재실행 시 중복 행이 쌓인다.
        assertThat(executable)
                .as("백필이 파생영상을 제외하지 않는다(ORGNL_RAW_SN IS NULL 누락)")
                .contains("ORGNL_RAW_SN IS NULL");
        assertThat(executable)
                .as("백필 멱등 가드(NOT EXISTS)가 없다 — 재실행 시 중복 행이 쌓인다")
                .contains("NOT EXISTS");
        assertThat(executable)
                .as("백필 UK 충돌 회피(ON CONFLICT DO NOTHING)가 없다")
                .contains("ON CONFLICT (VMS_CLIP_ID) DO NOTHING");

        // then: ★백필이 개인정보 3필드를 채우면 안 된다 — 마스터에 없던 값이라 지어내는 것이 되고
        //   export 원천 판정이 근거 없는 값을 싣는다(과소/과대 신고).
        for (String privacyColumn : List.of("ANONY_INCL_YN", "PSDO_INCL_YN", "PRVC_INCL_YN")) {
            assertThat(executable)
                    .as("V167 백필이 개인정보 필드(%s)를 채운다 — 원천이 없는 값을 지어내면 안 된다",
                            privacyColumn)
                    .doesNotContain(privacyColumn);
        }

        // then: 범위 밖 테이블은 실행 SQL 에 아예 등장하지 않는다(백필이 읽는 LS_DATA_RAW·
        //   LS_DATA_INGEST 와 그 소스인 4종만 등장해야 한다).
        for (String outOfScope : List.of("LS_DATA_SRC", "MNG_ACCT_USER", "LS_EVNT_TYPE",
                "LS_RAW_DATA_STATUS", "LS_DATA_LBL")) {
            assertThat(executable)
                    .as("V167 실행 SQL 이 범위 밖 테이블 %s 를 참조한다", outOfScope)
                    .doesNotContain(outOfScope);
        }
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



    @Test
    @DisplayName("V167은_구버전_롤백용_재생성_DDL을_주석으로_보존한다")
    void V167은_구버전_롤백용_재생성_DDL을_주석으로_보존한다() {
        // given: V167 전문(주석 포함)
        String raw = read(V167).toUpperCase();

        // when/then: 구버전 jar 롤백 시 2노드 기동 실패를 막을 재생성 DDL 이 주석에 있어야 한다.
        //   Flyway 는 down-migration 을 수행하지 않으므로 DBA 수동 적용 절차가 파일에 남아야 한다.
        for (String table : List.of("MNG_CLIP_MASTER", "MNG_CLIP_EVNT_LST",
                "MNG_RESOURCE_CCTV", "MNG_EX_LOCAL_GOV")) {
            assertThat(raw)
                    .as("롤백용 %s 재생성 DDL 이 주석에 보존돼야 한다", table)
                    .contains("CREATE TABLE IF NOT EXISTS PUBLIC." + table);
        }
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
