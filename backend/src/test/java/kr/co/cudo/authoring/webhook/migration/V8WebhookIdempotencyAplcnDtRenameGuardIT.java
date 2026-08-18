package kr.co.cudo.authoring.webhook.migration;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V8({@code LS_WEBHOOK_IDEMPOTENCY.APLY_DT} → {@code APLCN_DT}) 마이그레이션 <b>자체</b>의 성질 검증 —
 * 멱등 · 데이터 보존 · fail-closed · 부속 객체(PK · 인덱스 2종 · NOT NULL · 타입 · 컬럼 순서) 보존.
 *
 * <p><b>왜 별도 스키마에서 돌리나</b>: 애플리케이션 컨텍스트가 붙는 {@code klid_at} 은 이미 V8 이 적용된
 * 상태라 <b>개명 전</b> 형상을 관측할 수 없다. 여기서는 전용 스크래치 스키마에 개명 전 형상을 그대로
 * 세우고 실제 V8 스크립트 원문을 그 스키마의 {@code search_path} 에서 실행한다 — V8 이 카탈로그 조회를
 * {@code current_schema()}/{@code to_regclass} 로 스코프하기 때문에 그대로 동작한다
 * ({@code V5StandardColumnRenameGuardIT} · {@code V7MonNotiAcmlSttsWidthGuardIT} 와 동일 골격).
 *
 * <p>커넥션은 Hikari 풀이 아니라 {@link DriverManager} 로 직접 연다. 풀 커넥션에 {@code search_path} 를
 * 걸면 반납 후 다른 테스트에 그 설정이 새어 나간다.
 *
 * <p><b>데이터 보존이 이 파일의 핵심이다</b>: 이 원장은 미결 위탁의 상관키 저장소라, 행이 사라지면
 * 뒤늦게 도착한 콜백이 발급 게이트를 통과하지 못하고 미결 스위퍼도 회수 대상을 잃는다. 개명을
 * DROP+ADD 나 테이블 재생성으로 구현하면 값과 함께 그 복구 가능성이 사라진다.
 *
 * @design ERD-021
 */
class V8WebhookIdempotencyAplcnDtRenameGuardIT {

    private static final Path V8_SQL = Paths.get(
            "src/main/resources/db/migration/V8__rename_webhook_idempotency_aply_dt_to_aplcn_dt.sql");

    /** 테스트마다 다른 스키마를 쓴다 — 같은 컨테이너를 공유하므로 이름이 겹치면 서로를 지운다. */
    private static final AtomicInteger SCHEMA_SEQ = new AtomicInteger();

    private static final String TABLE = "ls_webhook_idempotency";
    private static final String OLD_COLUMN = "aply_dt";
    private static final String NEW_COLUMN = "aplcn_dt";

    /**
     * 개명 <b>전</b> 형상 — V1 베이스라인의 정의를 그대로 옮긴 것이다(FK 만 뺀다, 부모 테이블이 없다).
     * 컬럼 순서까지 그대로 두는 것이 중요하다 — RENAME 이 순서를 보존하는지 확인해야 하기 때문이다.
     */
    private static final String PRE_RENAME_DDL = """
            CREATE TABLE ls_webhook_idempotency (
                idmp_key    character varying(128) NOT NULL,
                chnl_cd     character varying(32) NOT NULL,
                stts_cd     character varying(16) NOT NULL,
                otsd_job_id character varying(200),
                aply_dt     timestamp without time zone,
                reg_dt      timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                mdfcn_dt    timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                raw_sn      bigint,
                CONSTRAINT ls_webhook_idempotency_pkey PRIMARY KEY (idmp_key)
            )""";

    private String schema;
    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        schema = "v8_guard_" + SCHEMA_SEQ.incrementAndGet();
        conn = DriverManager.getConnection(
                PostgresTestContainer.INSTANCE.getJdbcUrl(),
                PostgresTestContainer.INSTANCE.getUsername(),
                PostgresTestContainer.INSTANCE.getPassword());
        exec("CREATE SCHEMA " + schema);
        exec("SET search_path TO " + schema);
        exec(PRE_RENAME_DDL);
        exec("CREATE INDEX idx_ls_webhook_idempotency_ch_state ON ls_webhook_idempotency (chnl_cd, stts_cd)");
        exec("CREATE INDEX idx_ls_webhook_idempotency_ext_job ON ls_webhook_idempotency (otsd_job_id)");
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

    // ------------------------------------------------------------------ 멱등

    @Test
    @DisplayName("V8을_두_번_적용해도_안전하다")
    void migrationIsIdempotent() throws Exception {
        // given
        seedProcessed("vlm-req-1", "2026-08-15 10:00:00");

        // when — 두 번 적용(두 번째는 이미 새 이름이라 아무것도 하지 않아야 한다)
        applyV8();
        applyV8();

        // then
        assertThat(hasColumn(NEW_COLUMN)).isTrue();
        assertThat(hasColumn(OLD_COLUMN)).isFalse();
        assertThat(scalarLong("SELECT count(*) FROM " + TABLE)).isEqualTo(1L);
    }

    @Test
    @DisplayName("테이블이_없으면_아무것도_하지_않는다")
    void migrationIsNoOpWhenTableAbsent() throws Exception {
        // given — 이 마이그레이션이 다룰 테이블 자체가 없는 스키마
        exec("DROP TABLE " + TABLE);

        // when / then — 예외 없이 통과한다(테이블을 만들어 내지 않는다)
        applyV8();
        assertThat(scalarLong(
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_schema = current_schema() AND table_name = '" + TABLE + "'"))
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("적용일시_컬럼이_둘_다_없으면_컬럼을_만들어_내지_않는다")
    void migrationDoesNotFabricateColumn() throws Exception {
        // given — 옛 이름도 새 이름도 없는 형상. 값을 지어내지 않는 것이 이 저장소의 규칙이다.
        exec("ALTER TABLE " + TABLE + " DROP COLUMN " + OLD_COLUMN);

        // when / then
        applyV8();
        assertThat(hasColumn(NEW_COLUMN)).isFalse();
        assertThat(hasColumn(OLD_COLUMN)).isFalse();
    }

    // ------------------------------------------------------------------ 데이터 보존

    @Test
    @DisplayName("기존_원장_행의_적용일시_값이_개명_후에도_보존된다")
    void existingRowsSurviveRename() throws Exception {
        // given — 처리 완료(적용일시 있음)와 미결(null) 두 축. 개명이 재생성이면 여기서 사라진다.
        //   상관키가 사라지면 지각 콜백이 발급 게이트를 통과하지 못하고 미결 스위퍼도 회수 대상을 잃는다.
        seedProcessed("vlm-req-11", "2026-08-15 09:30:00");
        seedIssued("vlm-req-12");

        // when
        applyV8();

        // then — 행수·값이 그대로다
        assertThat(scalarLong("SELECT count(*) FROM " + TABLE)).isEqualTo(2L);
        assertThat(scalarString(
                "SELECT to_char(" + NEW_COLUMN + ", 'YYYY-MM-DD HH24:MI:SS') FROM " + TABLE
                        + " WHERE idmp_key = 'vlm-req-11'"))
                .isEqualTo("2026-08-15 09:30:00");
        assertThat(scalarString(
                "SELECT " + NEW_COLUMN + " FROM " + TABLE + " WHERE idmp_key = 'vlm-req-12'"))
                .as("미결 행의 null 은 null 그대로여야 한다 — 개명이 기본값을 주입하면 안 된다")
                .isNull();
        // 같은 행의 다른 컬럼도 온전해야 한다(테이블 재작성 여부의 교차 확인)
        assertThat(scalarString("SELECT otsd_job_id FROM " + TABLE + " WHERE idmp_key = 'vlm-req-11'"))
                .isEqualTo("ext-job-11");
    }

    // ------------------------------------------------------------------ fail-closed

    @Test
    @DisplayName("옛_이름과_새_이름이_동시에_있으면_마이그레이션이_중단된다")
    void abortsWhenBothColumnNamesExist() throws Exception {
        // given — 수기 조작이나 실패한 부분 적용의 흔적. 어느 쪽이 정본인지 알 수 없다.
        //   조용히 진행하면 옛 컬럼에 남은 값이 아무도 읽지 않는 채로 사라진다.
        exec("ALTER TABLE " + TABLE + " ADD COLUMN " + NEW_COLUMN + " timestamp without time zone");
        seedProcessed("vlm-req-21", "2026-08-15 08:00:00");

        // when / then — 중단되고, DO 블록은 원자적이라 형상도 그대로다
        assertThatThrownBy(this::applyV8)
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertGuardMessage(e, "vlm-req-21"));
        assertThat(hasColumn(OLD_COLUMN)).isTrue();
        assertThat(hasColumn(NEW_COLUMN)).isTrue();
        assertThat(scalarLong("SELECT count(*) FROM " + TABLE)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ 부속 객체 · 성질 보존

    @Test
    @DisplayName("타입과_널허용과_컬럼_순서가_개명_전과_동일하다")
    void columnShapeIsUnchangedByRename() throws Exception {
        // given — 개명 전 형상을 먼저 읽어 둔다(하드코딩한 기대값과 비교하면 DDL 이 바뀔 때 같이 낡는다)
        String beforeType = dataType(OLD_COLUMN);
        String beforeNullable = isNullable(OLD_COLUMN);
        long beforePosition = ordinalPosition(OLD_COLUMN);

        // when
        applyV8();

        // then — 이름만 바뀌고 성질은 그대로다. RENAME 이 아니라 DROP+ADD 였다면 순서가 맨 뒤로 밀린다.
        assertThat(dataType(NEW_COLUMN)).isEqualTo(beforeType).isEqualTo("timestamp without time zone");
        assertThat(isNullable(NEW_COLUMN)).isEqualTo(beforeNullable).isEqualTo("YES");
        assertThat(ordinalPosition(NEW_COLUMN))
                .as("RENAME 은 컬럼 순서를 보존한다 — DROP+ADD 면 맨 뒤로 밀린다")
                .isEqualTo(beforePosition).isEqualTo(5L);
    }

    @Test
    @DisplayName("기본키와_인덱스_2종이_개명_후에도_유지된다")
    void keysAndIndexesSurviveRename() throws Exception {
        // when
        applyV8();

        // then — 개명 대상 컬럼은 어느 인덱스에도 없지만, 테이블 재생성으로 구현했다면 함께 사라진다.
        assertThat(indexDef("ls_webhook_idempotency_pkey")).contains("UNIQUE").contains("idmp_key");
        assertThat(indexDef("idx_ls_webhook_idempotency_ch_state")).contains("chnl_cd").contains("stts_cd");
        assertThat(indexDef("idx_ls_webhook_idempotency_ext_job")).contains("otsd_job_id");

        // then — PK 가 실제로 강제되는지(정의 문자열만 보면 무력화를 놓친다)
        seedIssued("vlm-req-31");
        assertThatThrownBy(() -> seedIssued("vlm-req-31")).isInstanceOf(SQLException.class);
    }

    /**
     * 중단 사유가 <b>이 가드가 만든 것</b>인지 확인한다 — 아무 SQLException 이나 통과하면 가드의 존재
     * 이유가 사라진다. PG 가 스스로 내는 {@code column "aplcn_dt" of relation ... already exists} 로는
     * 통과하지 못한다.
     *
     * <p>동시에 <b>원장 값이 새지 않는지</b>도 단언한다(CWE-209) — {@code IDMP_KEY} 는 외부 시스템과
     * 주고받는 상관키라 사유 메시지에 실을 것이 아니다. 사람이 조치하는 데 필요한 것은 컬럼 이름뿐이다.
     */
    private void assertGuardMessage(Throwable e, String secretKey) {
        String msg = String.valueOf(e.getMessage());
        assertThat(msg)
                .as("가드가 만든 중단 사유여야 한다 — PG 원문(already exists)으로는 통과하지 못한다")
                .contains(TABLE + "." + OLD_COLUMN)
                .contains(TABLE + "." + NEW_COLUMN)
                .contains("동시에 존재");
        assertThat(msg)
                .as("외부 상관키를 사유에 실으면 로그로 새어 나간다 (CWE-209)")
                .doesNotContain(secretKey);
    }

    // ------------------------------------------------------------------ 내부

    private void applyV8() throws IOException, SQLException {
        assertThat(Files.exists(V8_SQL)).as("V8 마이그레이션 파일이 존재해야 한다 (%s)", V8_SQL).isTrue();
        exec(Files.readString(V8_SQL));
    }

    /** 콜백 처리 완료 행 — 적용일시가 채워진 축. */
    private void seedProcessed(String key, String aplyDt) throws SQLException {
        exec("INSERT INTO " + TABLE + " (idmp_key, chnl_cd, stts_cd, otsd_job_id, " + OLD_COLUMN + ")"
                + " VALUES ('" + key + "', 'VLM', 'PROCESSED', 'ext-job-"
                + key.substring(key.lastIndexOf('-') + 1) + "', TIMESTAMP '" + aplyDt + "')");
    }

    /** 미결 행 — 적용일시가 null 인 축. */
    private void seedIssued(String key) throws SQLException {
        exec("INSERT INTO " + TABLE + " (idmp_key, chnl_cd, stts_cd) VALUES ('"
                + key + "', 'VLM', 'ISSUED')");
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private boolean hasColumn(String column) throws SQLException {
        return scalarLong("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = current_schema()"
                + " AND table_name = '" + TABLE + "' AND column_name = '" + column + "'") == 1L;
    }

    private String dataType(String column) throws SQLException {
        return scalarString("SELECT data_type FROM information_schema.columns"
                + " WHERE table_schema = current_schema()"
                + " AND table_name = '" + TABLE + "' AND column_name = '" + column + "'");
    }

    private String isNullable(String column) throws SQLException {
        return scalarString("SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_schema = current_schema()"
                + " AND table_name = '" + TABLE + "' AND column_name = '" + column + "'");
    }

    private long ordinalPosition(String column) throws SQLException {
        return scalarLong("SELECT ordinal_position FROM information_schema.columns"
                + " WHERE table_schema = current_schema()"
                + " AND table_name = '" + TABLE + "' AND column_name = '" + column + "'");
    }

    private String indexDef(String indexName) throws SQLException {
        String def = scalarString("SELECT indexdef FROM pg_indexes"
                + " WHERE schemaname = current_schema()"
                + " AND tablename = '" + TABLE + "' AND indexname = '" + indexName + "'");
        assertThat(def).as("인덱스 %s 가 존재해야 한다", indexName).isNotNull();
        return def;
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
