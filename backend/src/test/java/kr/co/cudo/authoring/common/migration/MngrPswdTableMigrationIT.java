package kr.co.cudo.authoring.common.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V21 — 관리자 자격 저장 표가 <b>무엇을 만들었는지</b> 실 DB 로 검증
 * (Testcontainers PostgreSQL, Flyway migrate 후 부팅). [@design ADR-046]
 *
 * <h3>왜 이 시험이 따로 필요한가</h3>
 * <p>{@code FlywaySquashBaselineIT} 는 «적용된 버전·파일 목록»만 단언하므로 V21 이 <b>돌았다</b>는
 * 것은 증명해도 «무엇을 만들었는가»는 보지 않는다. 그리고 이 프로젝트의 {@code ddl-auto=validate} 는
 * 실제로 동작하지 않아 기동 성공을 정합의 증거로 삼을 수 없다 — 즉 이 시험이 없으면 컬럼 폭·제약이
 * 어긋나도 잡아 줄 층이 하나도 없다.
 *
 * <h3>특히 「행 최대 1개」는 여기서만 볼 수 있다</h3>
 * <p>단일 행은 <b>기본키와 체크 제약이 함께</b> 걸려야 성립한다. 한쪽만 있으면 애플리케이션은 멀쩡히
 * 동작하고, 어긋남은 두 번째 행이 실제로 들어간 뒤에야 드러난다. 그래서 제약 정의를 읽는 데 그치지
 * 않고 <b>두 번째 INSERT 를 실제로 시도</b>한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class MngrPswdTableMigrationIT {

    private static final String TABLE = "ls_mngr_pswd";

    /** Flyway 가 읽는 <b>유일한</b> 마이그레이션 디렉터리(운영 배포본). 시험 작업 디렉터리는 backend 모듈 루트다. */
    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    /** 이 표를 만든 파일 — 시드를 넣지 않는다는 결정이 적힌 곳이다. */
    private static final String CREATING_MIGRATION = "V21__add_ls_mngr_pswd.sql";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    /**
     * 이 표에 쓰는 프로덕션 경로는 아직 없고 시드도 없다 — 시험이 넣은 행만 존재하므로 비우고 시작·
     * 비우고 끝낸다. 남기면 「두 번째 행 INSERT 가 실패한다」가 첫 행부터 기본키에 걸려 무엇을 막았는지
     * 구분되지 않는다.
     *
     * <p>⚠ 그래서 <b>행수로는 「마이그레이션이 시드를 넣지 않았다」를 볼 수 없다</b> — 여기서 지운 뒤라
     * 항상 0 이기 때문이다. 그 판정은 마이그레이션 원문을 읽어서 한다(아래 시드 시험).
     */
    @BeforeEach
    @AfterEach
    void clearTable() {
        jdbc().update("DELETE FROM " + TABLE);
    }

    private Map<String, Object> column(String name) {
        List<Map<String, Object>> rows = jdbc().queryForList(
                "SELECT data_type, character_maximum_length, is_nullable, column_default "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                TABLE, name);
        assertThat(rows).as("%s.%s 컬럼이 존재해야 한다", TABLE, name).hasSize(1);
        return rows.get(0);
    }

    private String constraintDef(String name) {
        List<String> defs = jdbc().queryForList(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = ? AND connamespace = current_schema()::regnamespace",
                String.class, name);
        assertThat(defs).as("제약 %s 가 존재해야 한다", name).hasSize(1);
        return defs.get(0);
    }

    private int insert(int sn) {
        return jdbc().update(
                "INSERT INTO " + TABLE + " (mngr_pswd_sn, pswd_hash) VALUES (?, ?)",
                sn, "$2a$12$" + "x".repeat(53));
    }

    // ------------------------------------------------------------------ 컬럼 형상

    @Test
    @DisplayName("컬럼_물리명과_폭이_표준도메인과_일치한다")
    void 컬럼_형상이_표준을_따른다() {
        assertThat(column("mngr_pswd_sn").get("data_type")).isEqualTo("bigint");
        assertThat(column("mngr_pswd_sn").get("is_nullable")).isEqualTo("NO");

        // 비밀번호해시 = 사업표준 도메인 번호V100. 좁으면 해시가 잘려 들어가 자격이 조용히 깨진다.
        assertThat(column("pswd_hash").get("data_type")).isEqualTo("character varying");
        assertThat(((Number) column("pswd_hash").get("character_maximum_length")).intValue()).isEqualTo(100);
        assertThat(column("pswd_hash").get("is_nullable")).isEqualTo("NO");

        assertThat(((Number) column("mdfr_id").get("character_maximum_length")).intValue()).isEqualTo(30);
        assertThat(column("mdfr_id").get("is_nullable")).isEqualTo("YES");

        assertThat(column("pswd_mdfcn_dt").get("data_type")).isEqualTo("timestamp without time zone");
        assertThat(column("pswd_mdfcn_dt").get("is_nullable")).isEqualTo("YES");
    }

    @Test
    @DisplayName("★세대·판수·버전_컬럼을_두지_않는다 — 부재가 설계 결정이다")
    void 세대_컬럼을_두지_않는다() {
        List<String> columns = jdbc().queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? ORDER BY ordinal_position",
                String.class, TABLE);

        // 자격 교체 시 기존 유효창 무효화는 <서명이 현재 자격에 의존하게> 해서 이룬다. 여기에 세대
        // 컬럼이 생기면 그 무효화 규칙이 두 갈래가 되고, 토큰 형식과 DB 상태가 결합한다.
        assertThat(columns)
                .as("관리자 자격 표의 컬럼 — 늘리는 것 자체가 설계 결정을 되돌리는 일이다")
                .containsExactly("mngr_pswd_sn", "pswd_hash", "mdfr_id", "pswd_mdfcn_dt");
    }

    // ------------------------------------------------------------------ 단일 행

    @Test
    @DisplayName("단일_행_제약은_기본키와_체크_두_겹이다")
    void 단일_행_제약이_두_겹이다() {
        assertThat(constraintDef("ls_mngr_pswd_pkey")).isEqualTo("PRIMARY KEY (mngr_pswd_sn)");

        // 체크만 있으면 값이 전부 1 인 행이 여러 개 들어가고, 기본키만 있으면 1·2·3 이 나란히 선다.
        assertThat(constraintDef("ck_ls_mngr_pswd_single_row"))
                .contains("CHECK").contains("mngr_pswd_sn = 1");
    }

    @Test
    @DisplayName("★두_번째_행_INSERT_가_실패한다 — 자격이 둘이면 어느 것이 현재인지 판정할 수 없다")
    void 두_번째_행_INSERT_가_실패한다() {
        assertThatCode(() -> insert(1)).doesNotThrowAnyException();

        // 같은 일련번호 → 기본키가 막는다.
        assertThatThrownBy(() -> insert(1)).isInstanceOf(DataAccessException.class);
        // 다른 일련번호 → 체크가 막는다. 이 갈래가 없으면 1·2 가 나란히 선다.
        assertThatThrownBy(() -> insert(2)).isInstanceOf(DataAccessException.class);

        assertThat(jdbc().queryForObject("SELECT count(*) FROM " + TABLE, Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("★마이그레이션이_시드_행을_넣지_않는다 — 행이_없는_것이_정상이고_그때는_배포_설정값으로_폴백한다")
    void 마이그레이션이_시드_행을_넣지_않는다() throws Exception {
        // ★ 왜 DB 행수가 아니라 파일 본문인가
        //   이 시험이 보려는 것은 «마이그레이션이 넣지 않았다» 이지 «지금 표가 비어 있다» 가 아니다.
        //   그런데 @BeforeEach clearTable() 이 매 시험 전에 이 표를 비우므로, 행수 단언은
        //   마이그레이션이 시드를 넣든 말든 <항상> 0 이라 무엇도 지키지 못한다(실증됨 — 시드 INSERT 를
        //   넣어도 초록이었다). 그래서 clearTable 과 <독립> 인 마이그레이션 원문으로 판정한다.
        String creating = stripSqlComments(Files.readString(MIGRATION_DIR.resolve(CREATING_MIGRATION)));
        assertThat(creating)
                .as("%s — 시드를 넣는 순간 «행 없음 → 배포 설정값 폴백» 이라는 확정 동작이 죽는다",
                        CREATING_MIGRATION)
                .doesNotContainIgnoringCase("insert");

        // 표를 만든 파일이 아니어도 시드는 들어올 수 있다 — 배포되는 전 파일에서 이 표로의 적재를 막는다.
        for (String name : listLiveSql()) {
            assertThat(stripSqlComments(Files.readString(MIGRATION_DIR.resolve(name))))
                    .as("%s 에 %s 시드가 있으면 설정값 폴백 경로가 죽는다", name, TABLE)
                    .doesNotContainPattern("(?is)insert\\s+into\\s+" + TABLE);
        }
    }

    /**
     * SQL 주석(<code>--</code> 줄 · 블록)을 걷어낸다 — 주석 안의 «INSERT» 낱말에 오탐하지 않으려는 것이다.
     * 이 마이그레이션은 «시드를 넣지 않는 이유» 를 산문으로 길게 적고 있어, 그 문장이 손질되면
     * 그 낱말이 주석으로 들어올 수 있다.
     */
    private static String stripSqlComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("--[^\\n]*", " ");
    }

    private static List<String> listLiveSql() throws Exception {
        assertThat(Files.isDirectory(MIGRATION_DIR))
                .as("마이그레이션 디렉터리(%s)가 존재해야 한다 (시험 작업 디렉터리=backend 모듈 루트)",
                        MIGRATION_DIR.toAbsolutePath())
                .isTrue();
        try (Stream<Path> paths = Files.list(MIGRATION_DIR)) {
            return paths.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }

    // ------------------------------------------------------------------ 주석

    @Test
    @DisplayName("표와_컬럼에_주석이_있다 — 왜_단일_행인지가_DB_에도_남아_있어야_한다")
    void 주석이_있다() {
        assertThat(jdbc().queryForObject(
                "SELECT obj_description(to_regclass(?))", String.class, TABLE)).isNotBlank();
        for (String c : List.of("mngr_pswd_sn", "pswd_hash", "mdfr_id", "pswd_mdfcn_dt")) {
            assertThat(jdbc().queryForObject(
                    "SELECT col_description(to_regclass(?), "
                            + "  (SELECT attnum FROM pg_attribute WHERE attrelid = to_regclass(?) AND attname = ?))",
                    String.class, TABLE, TABLE, c))
                    .as("%s.%s 컬럼 주석", TABLE, c)
                    .isNotBlank();
        }
    }
}
