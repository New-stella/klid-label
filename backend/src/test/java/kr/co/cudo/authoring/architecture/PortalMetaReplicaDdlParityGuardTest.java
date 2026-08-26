package kr.co.cudo.authoring.architecture;

import jakarta.persistence.Column;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포털 복제본의 <b>물리 DDL</b>이 엔티티 매핑 전 컬럼을 담고 있는지 고정하는 회귀 가드
 * (@design INT-009 — 「복제 범위 — 원본과 동형이며 전 컬럼을 옮긴다」).
 *
 * <h3>왜 이 가드가 필요한가 — 실사고와 그 선행 사고</h3>
 * control 은 {@code V128}(2026-07-24)로 {@code EVNT_ANNO_CN} 을 추가했는데 포털 복제본 DDL 은
 * 따라오지 않아 두 스키마가 어긋나 있었다. 복제 경로가 네이티브 쿼리라 복제 자체는 돌았지만,
 * 같은 엔티티를 쓰는 JPA 파생 조회({@code findByRawSnAndActiveYn})는 그 컬럼을 SELECT 하므로
 * DDL 대로 프로비저닝된 포털 DB 에서 <b>42703(undefined_column)</b> 으로 깨진다.
 *
 * <h3>기존 두 가드가 이 축을 못 잡는다 — 세 가드가 합쳐져야 전 구간이 닫힌다</h3>
 * <ul>
 *   <li>{@link PortalMetaReplicaPayloadParityGuardTest} — payload 축(직렬화 → 전송 계약 → 복원)</li>
 *   <li>{@link PortalMetaReplicaColumnParityGuardTest} — SQL 축(엔티티 ↔ 포털 INSERT 컬럼 목록)</li>
 *   <li><b>이 가드</b> — DDL 축(엔티티 ↔ 포털 물리 스키마)</li>
 * </ul>
 * 앞 두 축을 다 맞춰도 <b>DDL 만 빠지면 두 가드가 green 인 채 런타임에서 깨진다.</b>
 * 통합시험도 이 드리프트를 원리적으로 못 잡는다 — 테스트 인프라에서 control/portal 두 데이터소스가
 * <b>같은 물리 테이블</b>을 가리켜(PostgresContainerContextCustomizerFactory) 포털 전용 DDL 이 아예
 * 적용되지 않기 때문이다.
 *
 * <h3>판정 방향(비대칭이며 의도된 것)</h3>
 * <ul>
 *   <li><b>엔티티에 있는데 DDL 에 없으면 실패</b> — 운영에서 그대로 깨진다.</li>
 *   <li><b>DDL 에만 있는 컬럼은 실패로 보지 않는다</b> — 레거시·선반영 컬럼일 수 있다.
 *       대신 조용히 넘기지 않고 WARN 으로 드러낸다.</li>
 * </ul>
 *
 * <h3>왜 스프링을 띄우지 않는가</h3>
 * 판정에 필요한 것은 DDL 텍스트와 엔티티의 {@code @Column} 매핑뿐이라 컨텍스트·DB 가 필요 없다.
 * 새 {@code @SpringBootTest} 는 캐시 컨텍스트를 늘려 {@code TestContextDiversityRatchetTest} 상한에 걸린다.
 */
class PortalMetaReplicaDdlParityGuardTest {

    private static final Logger log = LoggerFactory.getLogger(PortalMetaReplicaDdlParityGuardTest.class);

    /** 복제본 물리 테이블. */
    private static final String TABLE = "LS_DATASET_VIDEO_META";

    /**
     * 포털 복제본 프로비저닝 DDL — control Flyway 가 스캔하지 않는 경로에 의도적으로 둔다.
     * <b>읽기 전용 파싱 대상</b>이며 이 가드가 수정하지 않는다.
     */
    private static final Path PORTAL_MIGRATION_DIR = Paths.get("src/main/resources/db/portal");

    /**
     * 온프렘 설치가 실제로 로드하는 단일 생성본(gen-schema-sql.sh 덤프).
     * 운영 포털 DB 의 형상을 정하는 것은 <b>이 파일</b>이므로 함께 본다.
     */
    private static final Path ONPREM_PORTAL_SCHEMA = Paths.get("../deploy/onprem/db/portal-schema.sql");

    /** {@code -- ...} 줄 주석. 이 DDL 들에는 문자열 리터럴 안에 {@code --} 가 없다. */
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:[A-Za-z0-9_]+\\.)?" + TABLE + "\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_TABLE = Pattern.compile(
            "ALTER\\s+TABLE\\s+(?:ONLY\\s+)?(?:[A-Za-z0-9_]+\\.)?" + TABLE + "\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ADD_COLUMN = Pattern.compile(
            "ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern DROP_COLUMN = Pattern.compile(
            "DROP\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE);

    /** 테이블 정의 안에서 컬럼이 아닌 항목(제약)의 선두 토큰. */
    private static final List<String> CONSTRAINT_TOKENS =
            List.of("PRIMARY", "CONSTRAINT", "UNIQUE", "FOREIGN", "CHECK", "KEY", "EXCLUDE", "LIKE");

    @Test
    @DisplayName("포털_복제본_프로비저닝_DDL이_엔티티_전_컬럼을_담는다")
    void portalMigrationDdl_containsEveryEntityColumn() {
        assertCoversEntity(portalMigrationColumns(), "db/portal/V*.sql(프로비저닝 DDL)");
    }

    @Test
    @DisplayName("온프렘_설치가_로드하는_포털_스키마가_엔티티_전_컬럼을_담는다")
    void onpremPortalSchema_containsEveryEntityColumn() {
        assertCoversEntity(onpremSchemaColumns(), "deploy/onprem/db/portal-schema.sql(설치 로드본)");
    }

    @Test
    @DisplayName("온프렘_포털_스키마가_프로비저닝_DDL과_같은_컬럼집합이다_덤프_재생성_누락_차단")
    void onpremPortalSchema_matchesMigrationDdl() {
        // 덤프는 db/portal 을 적용한 결과의 생성물이다 — DDL 을 추가하고 재생성을 잊으면
        // 설치되는 포털 DB 만 옛 형상으로 남는다(정확히 이번 결함의 선행 사고 형태).
        List<String> migration = portalMigrationColumns();
        List<String> dump = onpremSchemaColumns();

        assertThat(dump)
                .as("포털 DDL 을 추가했으면 설치 로드본도 재생성해야 한다"
                        + "(deploy/onprem/scripts/gen-schema-sql.sh). "
                        + "DDL 에만 있음=%s / 덤프에만 있음=%s",
                        minus(migration, dump), minus(dump, migration))
                .containsExactlyInAnyOrderElementsOf(migration);
    }

    // ── 판정 ──────────────────────────────────────────────────────────────────

    private static void assertCoversEntity(List<String> ddlColumns, String source) {
        List<String> entityColumns = entityColumns();
        List<String> missing = minus(entityColumns, ddlColumns);

        assertThat(missing)
                .as("엔티티에 컬럼이 추가되면 포털 복제본 DDL 도 함께 따라와야 한다(INT-009). "
                        + "빠지면 payload·INSERT 축 가드가 green 인 채 포털 DB 에서 42703 으로 깨진다. "
                        + "출처=%s / 누락=%s", source, missing)
                .isEmpty();

        // 역방향은 실패로 보지 않는다 — 레거시·선반영 컬럼일 수 있다. 다만 조용히 넘기지도 않는다.
        List<String> ddlOnly = minus(ddlColumns, entityColumns);
        if (!ddlOnly.isEmpty()) {
            log.warn("[MetaRepl] 포털 복제본 DDL 에만 있고 엔티티에 없는 컬럼 source={} columns={} "
                    + "— 결함은 아니지만 레거시인지 반영 누락인지 확인할 것(INT-009)", source, ddlOnly);
        }
    }

    // ── 파싱 ──────────────────────────────────────────────────────────────────

    /** 프로비저닝 DDL 을 버전 순으로 이어 붙여 최종 컬럼 집합을 재구성한다. */
    private static List<String> portalMigrationColumns() {
        List<Path> files;
        try (Stream<Path> stream = Files.list(PORTAL_MIGRATION_DIR)) {
            files = stream.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(PortalMetaReplicaDdlParityGuardTest::versionOf))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "포털 프로비저닝 DDL 디렉터리를 읽지 못했다: " + PORTAL_MIGRATION_DIR.toAbsolutePath()
                            + " — 경로가 옮겨졌다면 이 가드의 상수를 함께 갱신할 것", e);
        }
        assertThat(files)
                .as("포털 프로비저닝 DDL(V*.sql)을 하나도 찾지 못했다 — 경로가 바뀌었는지 확인할 것: %s",
                        PORTAL_MIGRATION_DIR.toAbsolutePath())
                .isNotEmpty();

        StringBuilder sql = new StringBuilder();
        for (Path file : files) {
            sql.append(read(file)).append('\n');
        }
        return columnsOf(sql.toString(), PORTAL_MIGRATION_DIR.toString());
    }

    private static List<String> onpremSchemaColumns() {
        assertThat(Files.exists(ONPREM_PORTAL_SCHEMA))
                .as("온프렘 포털 스키마 생성본을 찾지 못했다: %s — 파일이 옮겨졌다면 이 가드의 상수를 "
                        + "함께 갱신할 것", ONPREM_PORTAL_SCHEMA.toAbsolutePath())
                .isTrue();
        return columnsOf(read(ONPREM_PORTAL_SCHEMA), ONPREM_PORTAL_SCHEMA.toString());
    }

    private static int versionOf(Path file) {
        Matcher matcher = Pattern.compile("^V(\\d+)__").matcher(file.getFileName().toString());
        assertThat(matcher.find()).as("버전 접두를 읽지 못했다: %s", file).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    /** {@code CREATE TABLE} 컬럼 + 이후 {@code ADD COLUMN} 누적 − {@code DROP COLUMN}. */
    private static List<String> columnsOf(String rawSql, String source) {
        String sql = LINE_COMMENT.matcher(rawSql).replaceAll("");

        LinkedHashSet<String> columns = new LinkedHashSet<>(createTableColumns(sql, source));

        for (String statement : sql.split(";")) {
            if (!ALTER_TABLE.matcher(statement).find()) {
                continue;
            }
            // ALTER COLUMN ... TYPE (타입 정합)은 컬럼 집합을 바꾸지 않으므로 그대로 지나간다.
            Matcher add = ADD_COLUMN.matcher(statement);
            while (add.find()) {
                columns.add(normalize(add.group(1)));
            }
            Matcher drop = DROP_COLUMN.matcher(statement);
            while (drop.find()) {
                columns.remove(normalize(drop.group(1)));
            }
        }
        return new ArrayList<>(columns);
    }

    private static List<String> createTableColumns(String sql, String source) {
        Matcher matcher = CREATE_TABLE.matcher(sql);
        assertThat(matcher.find())
                .as("%s 에서 %s 테이블 정의를 찾지 못했다 — DDL 형태가 바뀌었다면 이 가드의 파서를 "
                        + "함께 갱신할 것", source, TABLE)
                .isTrue();

        String body = balancedBody(sql, matcher.end() - 1, source);
        List<String> columns = new ArrayList<>();
        for (String part : splitTopLevel(body)) {
            if (part.isBlank()) {
                continue;
            }
            String head = part.split("\\s+")[0];
            if (CONSTRAINT_TOKENS.contains(head.toUpperCase(Locale.ROOT))) {
                continue;
            }
            columns.add(normalize(head));
        }
        assertThat(columns)
                .as("%s 의 테이블 정의에서 컬럼을 하나도 읽지 못했다 — 파서를 함께 갱신할 것", source)
                .isNotEmpty();
        return columns;
    }

    /** {@code openIndex} 의 여는 괄호에 대응하는 닫는 괄호까지의 본문. */
    private static String balancedBody(String sql, int openIndex, String source) {
        int depth = 0;
        for (int i = openIndex; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return sql.substring(openIndex + 1, i);
                }
            }
        }
        throw new AssertionError("괄호가 닫히지 않았다: " + source + " — 파서를 함께 갱신할 것");
    }

    /** 괄호 깊이 0 의 콤마로만 분리한다({@code numeric(10,7)} 을 쪼개지 않기 위해). */
    private static List<String> splitTopLevel(String csv) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : csv.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString().trim());
        return parts;
    }

    /** 엔티티가 매핑한 전 컬럼(식별자 포함 — JPA 파생 조회가 SELECT 하므로 DDL 에 있어야 한다). */
    private static List<String> entityColumns() {
        List<String> columns = new ArrayList<>();
        for (Field field : LsDatasetVideoMeta.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            if (column == null || column.name().isBlank()) {
                continue;
            }
            columns.add(normalize(column.name()));
        }
        assertThat(columns)
                .as("엔티티 @Column 매핑을 읽지 못했다 — 매핑 방식이 바뀌었다면 이 가드를 함께 갱신할 것")
                .isNotEmpty();
        return columns;
    }

    /** PostgreSQL 은 비인용 식별자를 소문자로 접는다 — 대조는 대문자로 통일한다. */
    private static String normalize(String identifier) {
        return identifier.replace("\"", "").toUpperCase(Locale.ROOT);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("DDL 읽기 실패: " + path.toAbsolutePath(), e);
        }
    }

    private static List<String> minus(List<String> left, List<String> right) {
        List<String> result = new ArrayList<>(left);
        result.removeAll(right);
        return result;
    }
}
