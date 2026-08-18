package kr.co.cudo.authoring.sysconfig.migration;

import kr.co.cudo.authoring.support.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V11(포털 보존기간 설정 3키 시드) 마이그레이션 <b>자체</b>의 성질 검증 — 실적재 · 멱등 ·
 * 운영값 보존 · 스키마 한정자 부재. @design DFEAT-055
 *
 * <h3>왜 텍스트 검사가 아니라 실행인가</h3>
 * <p>이 파일이 지키려는 것은 "INSERT 문이 적혀 있다"가 아니라 <b>"적용하면 3행이 실제로 조회된다"</b>이다.
 * 이 3키를 읽는 삭제 배치는 값이 없으면 <b>폴백하지 않고 그 회차를 건너뛰므로</b>(파괴적 기능의
 * fail-open 방지), 시드가 안 들어가면 기능이 <b>죽은 채로 조용히</b> 배포된다 — 오류도 로그도 나지 않는다.
 *
 * <h3>왜 별도 스키마에서 돌리나</h3>
 * <p>애플리케이션 컨텍스트가 붙는 스키마는 이미 V11 이 적용된 상태라 <b>적용 전</b> 형상을 관측할 수
 * 없다. 전용 스크래치 스키마에 대상 테이블만 세우고 실제 V11 원문을 그 스키마의 {@code search_path}
 * 에서 실행한다({@code V9TaskTableStdTermRenameGuardIT} 와 동일 골격). 커넥션은 Hikari 풀이 아니라
 * {@link DriverManager} 로 직접 연다 — 풀 커넥션에 {@code search_path} 를 걸면 반납 후 다른 테스트로
 * 새어 나간다.
 */
class V11PortalRetentionSeedIT {

    private static final Path V11_SQL = Paths.get(
            "src/main/resources/db/migration/V11__seed_portal_retention_config.sql");

    /** 테스트마다 다른 스키마를 쓴다 — 같은 컨테이너를 공유하므로 이름이 겹치면 서로를 지운다. */
    private static final AtomicInteger SCHEMA_SEQ = new AtomicInteger();

    /** 시드 대상 3키와 기대 기본값. 실패분만 1일인 것은 의도다(다시 올리면 그만인 잔여물). */
    private static final List<String[]> SEEDED = List.of(
            new String[]{"portal.datamart.retention-days", "7"},
            new String[]{"portal.upload.retention-days", "7"},
            new String[]{"portal.upload.failed-retention-days", "1"});

    /** V1 베이스라인의 정의를 그대로 옮긴 것 — 시드가 꽂히는 대상 테이블. */
    private static final String CONFIG_DDL = """
            CREATE TABLE ls_system_config (
                stng_key     character varying(100) NOT NULL,
                stng_value   character varying(4000),
                stng_type_cd character varying(20) NOT NULL,
                expln        character varying(500),
                mdfr_id      character varying(30),
                mdfcn_dt     timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                CONSTRAINT ls_system_config_pkey PRIMARY KEY (stng_key)
            )""";

    private String schema;
    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        schema = "v11_seed_" + SCHEMA_SEQ.incrementAndGet();
        conn = DriverManager.getConnection(
                PostgresTestContainer.INSTANCE.getJdbcUrl(),
                PostgresTestContainer.INSTANCE.getUsername(),
                PostgresTestContainer.INSTANCE.getPassword());
        exec("CREATE SCHEMA " + schema);
        exec("SET search_path TO " + schema);
        exec(CONFIG_DDL);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null) {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            } finally {
                conn.close();
            }
        }
    }

    // ------------------------------------------------------------------ 실적재

    @Test
    @DisplayName("V11_적용_후_보존기간_3키가_실제로_조회된다")
    void seedsThreeRows() throws Exception {
        // when
        applyV11();

        // then — 값·타입까지 확인한다. 타입이 NUMBER 가 아니면 getInt 가 INVALID_INPUT 을 던져
        //   배치는 시드가 있는데도 값을 못 읽는다(= 시드가 없는 것과 같다).
        for (String[] row : SEEDED) {
            assertThat(scalarString(
                    "SELECT stng_value FROM ls_system_config WHERE stng_key = " + literal(row[0])))
                    .as("%s 기본값", row[0])
                    .isEqualTo(row[1]);
            assertThat(scalarString(
                    "SELECT stng_type_cd FROM ls_system_config WHERE stng_key = " + literal(row[0])))
                    .as("%s 는 SystemConfigService.getInt 로 읽히므로 NUMBER 여야 한다", row[0])
                    .isEqualTo("NUMBER");
        }
        assertThat(retentionRowCount()).isEqualTo(SEEDED.size());
    }

    @Test
    @DisplayName("시드_기본값은_ConfigKeys_허용범위_안에_있다")
    void seededDefaultsAreWithinAllowedRange() throws Exception {
        applyV11();

        for (String[] row : SEEDED) {
            int[] range = kr.co.cudo.authoring.sysconfig.ConfigKeys.NUMBER_RANGE.get(row[0]);
            assertThat(range).as("%s 가 범위 맵에 등록돼 있어야 한다", row[0]).isNotNull();
            int seeded = Integer.parseInt(scalarString(
                    "SELECT stng_value FROM ls_system_config WHERE stng_key = " + literal(row[0])));
            assertThat(seeded)
                    .as("%s — 시드가 허용 범위 밖이면 화면에서 저장 한 번에 400 이 난다", row[0])
                    .isBetween(range[0], range[1]);
        }
    }

    // ------------------------------------------------------------------ 멱등 · 운영값 보존

    @Test
    @DisplayName("V11을_두_번_적용해도_행이_늘지_않는다")
    void migrationIsIdempotent() throws Exception {
        // when — 재적용(복구 절차·수기 재실행에서 실제로 일어난다)
        applyV11();
        applyV11();

        // then — ON CONFLICT DO NOTHING 이 없으면 여기서 PK 위반으로 마이그레이션이 깨진다.
        assertThat(retentionRowCount()).isEqualTo(SEEDED.size());
    }

    @Test
    @DisplayName("운영자가_바꿔둔_보존일수를_재적용이_기본값으로_되돌리지_않는다")
    void reapplyKeepsOperatorValue() throws Exception {
        // given — 운영자가 화면에서 30일로 늘려 둔 상태
        applyV11();
        exec("UPDATE ls_system_config SET stng_value = '30', mdfr_id = 'reviewer-1'"
                + " WHERE stng_key = 'portal.upload.retention-days'");

        // when
        applyV11();

        // then — UPSERT 로 썼다면 여기서 7 로 되돌아가고, 운영자가 모르는 사이 보존기간이 줄어
        //   아직 지우면 안 되는 자산이 삭제 대상이 된다.
        assertThat(scalarString("SELECT stng_value FROM ls_system_config"
                + " WHERE stng_key = 'portal.upload.retention-days'")).isEqualTo("30");
        assertThat(scalarString("SELECT mdfr_id FROM ls_system_config"
                + " WHERE stng_key = 'portal.upload.retention-days'")).isEqualTo("reviewer-1");
    }

    // ------------------------------------------------------------------ 이 저장소의 실사고 형태

    @Test
    @DisplayName("V11은_스키마_한정자와_Flyway_placeholder를_쓰지_않는다")
    void sqlAvoidsKnownFailureModes() throws Exception {
        String sql = Files.readString(V11_SQL);

        assertThat(sql)
                .as("'public.' 을 박으면 대상 스키마(klid_at)가 아닌 곳을 건드려 신규 DB 재적용이"
                        + " 조용히 어긋난다 — 이 저장소에서 실제로 난 사고다")
                .doesNotContain("public.");
        assertThat(sql)
                .as("주석 안이라도 Flyway placeholder 로 해석돼 파싱이 통째로 실패한다 — 실사고 이력")
                .doesNotContain("${");
        assertThat(sql.toUpperCase())
                .as("PostgreSQL 표준 문법만 쓴다(MariaDB 고유 문법 금지)")
                .doesNotContain("AUTO_INCREMENT")
                .doesNotContain("ENGINE=");
    }

    @Test
    @DisplayName("이미_적용된_V1과_V2는_이번_변경에_포함되지_않는다")
    void baselineMigrationsAreUntouched() {
        // 체크섬이 바뀌면 이미 적용된 DB 는 기동이 거부된다(전 노드 기동 실패).
        // 스키마 변경은 반드시 새 버전 파일로만 한다 — V11 이 그 새 파일이다.
        assertThat(Files.exists(V11_SQL))
                .as("V11 마이그레이션 파일이 존재해야 한다 (%s)", V11_SQL).isTrue();
        assertThat(Files.exists(Paths.get("src/main/resources/db/migration/V1__baseline.sql")))
                .as("베이스라인은 그대로 있어야 한다(시드를 V1 에 끼워 넣지 않았다)").isTrue();
    }

    // ------------------------------------------------------------------ 내부

    private void applyV11() throws IOException, SQLException {
        assertThat(Files.exists(V11_SQL)).as("V11 마이그레이션 파일이 존재해야 한다 (%s)", V11_SQL).isTrue();
        exec(Files.readString(V11_SQL));
    }

    private long retentionRowCount() throws SQLException {
        List<String> keys = new ArrayList<>();
        for (String[] row : SEEDED) {
            keys.add(literal(row[0]));
        }
        return scalarLong("SELECT count(*) FROM ls_system_config WHERE stng_key IN ("
                + String.join(", ", keys) + ")");
    }

    private static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private long scalarLong(String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private String scalarString(String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
