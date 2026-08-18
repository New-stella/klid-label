package kr.co.cudo.authoring.assignment.migration;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V9({@code LS_TASK_ASSIGNMENT} → {@code LS_TASK_ALTMNT} · {@code LS_TASK_EVENT_LOG} →
 * {@code LS_TASK_EVNT_LOG}) 마이그레이션 <b>자체</b>의 성질 검증 — 멱등 · 데이터 보존 · fail-closed ·
 * <b>부속 객체 13종 전수 추종</b> · 컬럼 무변경.
 *
 * <h3>이 파일이 막는 것</h3>
 * <p>{@code ALTER TABLE ... RENAME TO} 는 <b>시퀀스·제약·인덱스 이름을 따라오게 하지 않는다</b>. 정의는
 * 자동 추종하므로 하나를 빠뜨려도 <b>애플리케이션은 멀쩡히 동작</b>하고, 어긋남은 카탈로그를 직접 볼
 * 때에야 드러난다. 실제로 이 스키마에는 그렇게 생긴 잔재가 하나 있었다 —
 * {@code ls_task_event_log_event_seq_seq} 는 <b>존재하지 않는 {@code event_seq} 컬럼</b>을 이름에 달고
 * 있었다(실제 컬럼은 {@code evnt_id}). 그래서 여기서는 "옛 이름 잔존 0 · 새 이름 전부 존재"를
 * <b>카탈로그 전수 조회</b>로 단언한다.
 *
 * <h3>왜 별도 스키마에서 돌리나</h3>
 * <p>애플리케이션 컨텍스트가 붙는 스키마는 이미 V9 가 적용된 상태라 <b>개명 전</b> 형상을 관측할 수
 * 없다. 여기서는 전용 스크래치 스키마에 개명 전 형상을 그대로 세우고 실제 V9 스크립트 원문을 그
 * 스키마의 {@code search_path} 에서 실행한다 — V9 가 카탈로그 조회를 {@code current_schema()} /
 * {@code to_regclass} 로 스코프하기 때문에 그대로 동작한다
 * ({@code V5StandardColumnRenameGuardIT} · {@code V8WebhookIdempotencyAplcnDtRenameGuardIT} 와 동일 골격).
 *
 * <p>커넥션은 Hikari 풀이 아니라 {@link DriverManager} 로 직접 연다. 풀 커넥션에 {@code search_path} 를
 * 걸면 반납 후 다른 테스트에 그 설정이 새어 나간다.
 *
 * <h3>데이터 보존이 왜 중요한가</h3>
 * <p>{@code LS_TASK_EVENT_LOG} 는 배정·재배정·검수 승인·반려의 <b>행 단위 감사 원장</b>이다. 개명을
 * 재생성으로 구현하면 ①V4 가 {@code LS_TASK_ASSIGN_HISTORY} 를 지울 때 근거로 삼은 재배정 증적이
 * 사라지고 ②비식별 신고 차단 판정의 fail-closed 보조축인 {@code APPROVE} 이력이 사라져 <b>승인 이력이
 * 있는 영상에 신고가 통과</b>한다.
 */
class V9TaskTableStdTermRenameGuardIT {

    private static final Path V9_SQL = Paths.get(
            "src/main/resources/db/migration/V9__rename_task_tables_to_std_terms.sql");

    /** 테스트마다 다른 스키마를 쓴다 — 같은 컨테이너를 공유하므로 이름이 겹치면 서로를 지운다. */
    private static final AtomicInteger SCHEMA_SEQ = new AtomicInteger();

    private static final String OLD_ASGN = "ls_task_assignment";
    private static final String NEW_ASGN = "ls_task_altmnt";
    private static final String OLD_EVT = "ls_task_event_log";
    private static final String NEW_EVT = "ls_task_evnt_log";

    /**
     * 개명 대상 객체 13종 — {@code {종류, 옛 이름, 새 이름}}.
     *
     * <p>이 목록이 이 파일의 <b>판정축</b>이다. 하나라도 빠뜨리면 옛 이름이 카탈로그에 남는데,
     * 정의는 자동 추종하므로 <b>기능 테스트로는 절대 드러나지 않는다</b>.
     */
    private static final List<String[]> RENAMED_OBJECTS = List.of(
            new String[]{"r", OLD_ASGN, NEW_ASGN},
            new String[]{"r", OLD_EVT, NEW_EVT},
            new String[]{"S", "ls_task_assignment_assignment_id_seq", "ls_task_altmnt_assignment_id_seq"},
            // ★ 접두 치환이 아니다 — 옛 이름은 존재하지 않는 event_seq 컬럼을 달고 있었고,
            //   실제 컬럼명 evnt_id 로 바로잡는다.
            new String[]{"S", "ls_task_event_log_event_seq_seq", "ls_task_evnt_log_evnt_id_seq"},
            new String[]{"i", "ls_task_assignment_pkey", "ls_task_altmnt_pkey"},
            new String[]{"i", "ls_task_event_log_pkey", "ls_task_evnt_log_pkey"},
            new String[]{"i", "uk_ls_task_assignment", "uk_ls_task_altmnt"},
            new String[]{"i", "ix_ls_task_assignment_raw", "ix_ls_task_altmnt_raw"},
            new String[]{"i", "ix_ls_task_assignment_user", "ix_ls_task_altmnt_user"},
            new String[]{"i", "ix_ls_task_event_log_actor", "ix_ls_task_evnt_log_actor"},
            new String[]{"i", "ix_ls_task_event_log_raw", "ix_ls_task_evnt_log_raw"});

    /** 제약 축(이름이 인덱스와 겹치지 않는 FK 2종 + 위 PK/UNIQUE 3종의 제약 이름). */
    private static final List<String[]> RENAMED_CONSTRAINTS = List.of(
            new String[]{NEW_ASGN, "ls_task_assignment_pkey", "ls_task_altmnt_pkey"},
            new String[]{NEW_ASGN, "uk_ls_task_assignment", "uk_ls_task_altmnt"},
            new String[]{NEW_ASGN, "fk_ls_task_assignment_raw", "fk_ls_task_altmnt_raw"},
            new String[]{NEW_EVT, "ls_task_event_log_pkey", "ls_task_evnt_log_pkey"},
            new String[]{NEW_EVT, "fk_ls_task_event_log_raw", "fk_ls_task_evnt_log_raw"});

    /**
     * 개명 <b>전</b> 형상 — V1 베이스라인/{@code schema.sql} 의 정의를 그대로 옮긴 것이다.
     * 컬럼 순서·IDENTITY 시퀀스 이름까지 그대로 두는 것이 중요하다 — RENAME 이 무엇을 보존하고
     * 무엇을 보존하지 않는지가 이 테스트의 주제이기 때문이다.
     */
    private static final List<String> PRE_RENAME_DDL = List.of(
            // 부모 — FK CASCADE 를 실제로 확인하기 위한 최소 정의
            "CREATE TABLE ls_data_raw (raw_sn bigint PRIMARY KEY)",
            """
            CREATE TABLE ls_task_assignment (
                assignment_id bigint NOT NULL,
                user_no       bigint NOT NULL,
                raw_data_id   bigint NOT NULL,
                task_type_cd  character varying(20) NOT NULL,
                reg_user_no   bigint NOT NULL,
                reg_dt        timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                ver           bigint DEFAULT 0 NOT NULL,
                CONSTRAINT ls_task_assignment_pkey PRIMARY KEY (assignment_id)
            )""",
            """
            ALTER TABLE ls_task_assignment ALTER COLUMN assignment_id
                ADD GENERATED BY DEFAULT AS IDENTITY (
                    SEQUENCE NAME ls_task_assignment_assignment_id_seq START WITH 1 INCREMENT BY 1)""",
            "ALTER TABLE ls_task_assignment ADD CONSTRAINT uk_ls_task_assignment"
                    + " UNIQUE (raw_data_id, user_no, task_type_cd)",
            "ALTER TABLE ls_task_assignment ADD CONSTRAINT fk_ls_task_assignment_raw"
                    + " FOREIGN KEY (raw_data_id) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE",
            "CREATE INDEX ix_ls_task_assignment_raw ON ls_task_assignment USING btree (raw_data_id)",
            "CREATE INDEX ix_ls_task_assignment_user"
                    + " ON ls_task_assignment USING btree (user_no, task_type_cd)",
            """
            CREATE TABLE ls_task_event_log (
                evnt_id         bigint NOT NULL,
                raw_data_id     bigint NOT NULL,
                evnt_type_cd    character varying(20) NOT NULL,
                actor_user_no   bigint NOT NULL,
                subject_user_no bigint,
                prev_user_no    bigint,
                rsn             character varying(500),
                ocrn_dt         timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
                CONSTRAINT ls_task_event_log_pkey PRIMARY KEY (evnt_id)
            )""",
            // ★ 옛 시퀀스명이 실제 컬럼(evnt_id)이 아니라 사라진 event_seq 를 달고 있는 것이 현재 형상이다.
            """
            ALTER TABLE ls_task_event_log ALTER COLUMN evnt_id
                ADD GENERATED BY DEFAULT AS IDENTITY (
                    SEQUENCE NAME ls_task_event_log_event_seq_seq START WITH 1 INCREMENT BY 1)""",
            "ALTER TABLE ls_task_event_log ADD CONSTRAINT fk_ls_task_event_log_raw"
                    + " FOREIGN KEY (raw_data_id) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE",
            "CREATE INDEX ix_ls_task_event_log_actor ON ls_task_event_log USING btree (actor_user_no)",
            "CREATE INDEX ix_ls_task_event_log_raw"
                    + " ON ls_task_event_log USING btree (raw_data_id, ocrn_dt)");

    private String schema;
    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        schema = "v9_guard_" + SCHEMA_SEQ.incrementAndGet();
        conn = DriverManager.getConnection(
                PostgresTestContainer.INSTANCE.getJdbcUrl(),
                PostgresTestContainer.INSTANCE.getUsername(),
                PostgresTestContainer.INSTANCE.getPassword());
        exec("CREATE SCHEMA " + schema);
        exec("SET search_path TO " + schema);
        for (String ddl : PRE_RENAME_DDL) {
            exec(ddl);
        }
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

    // ------------------------------------------------------------------ 부속 객체 전수

    @Test
    @DisplayName("개명_후_옛_이름_객체가_하나도_남지_않고_13종이_전부_새_이름이다")
    void everyDependentObjectFollowsTheRename() throws Exception {
        // when
        applyV9();

        // then — 목록 전수. 하나를 빠뜨려도 정의는 자동 추종하므로 기능 테스트로는 안 드러난다.
        List<String> stale = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String[] o : RENAMED_OBJECTS) {
            if (relExists(o[0], o[1])) {
                stale.add(o[1]);
            }
            if (!relExists(o[0], o[2])) {
                missing.add(o[2]);
            }
        }
        for (String[] c : RENAMED_CONSTRAINTS) {
            if (constraintExists(c[0], c[1])) {
                stale.add(c[1]);
            }
            if (!constraintExists(c[0], c[2])) {
                missing.add(c[2]);
            }
        }

        assertThat(stale).as("옛 이름이 카탈로그에 남아 있다 — 정의가 자동 추종해도 이름은 따라오지 않는다")
                .isEmpty();
        assertThat(missing).as("새 이름으로 존재해야 할 객체가 없다").isEmpty();
        assertThat(RENAMED_OBJECTS.size() + RENAMED_CONSTRAINTS.size())
                .as("개명 대상은 테이블 2 · 시퀀스 2 · 인덱스 7 · 제약 5 = 16 항목(인덱스와 제약은 "
                        + "PK/UNIQUE 3종이 양쪽 축에 겹쳐 실제 물리 객체는 13개다)")
                .isEqualTo(16);
    }

    // ------------------------------------------------------------------ 멱등 · no-op

    @Test
    @DisplayName("V9를_두_번_적용해도_안전하다")
    void migrationIsIdempotent() throws Exception {
        // given
        seedAssignment(1L, 10L, 1000L);
        seedEvent(1000L, "ASSIGN");

        // when — 두 번 적용(두 번째는 이미 새 이름이라 아무것도 하지 않아야 한다)
        applyV9();
        applyV9();

        // then
        assertThat(relExists("r", NEW_ASGN)).isTrue();
        assertThat(relExists("r", OLD_ASGN)).isFalse();
        assertThat(scalarLong("SELECT count(*) FROM " + NEW_ASGN)).isEqualTo(1L);
        assertThat(scalarLong("SELECT count(*) FROM " + NEW_EVT)).isEqualTo(1L);
    }

    @Test
    @DisplayName("테이블이_없으면_아무것도_하지_않는다")
    void migrationIsNoOpWhenTablesAbsent() throws Exception {
        // given — 이 마이그레이션이 다룰 테이블 자체가 없는 스키마
        exec("DROP TABLE " + OLD_ASGN);
        exec("DROP TABLE " + OLD_EVT);

        // when / then — 예외 없이 통과한다(테이블을 만들어 내지 않는다)
        applyV9();
        assertThat(relExists("r", NEW_ASGN)).isFalse();
        assertThat(relExists("r", NEW_EVT)).isFalse();
    }

    @Test
    @DisplayName("한쪽만_이미_개명돼_있어도_나머지_한쪽을_마저_개명한다")
    void migrationCompletesPartiallyRenamedSchema() throws Exception {
        // given — 배정만 손으로 먼저 옮겨 둔 형상(부속 객체는 옛 이름 그대로다)
        exec("ALTER TABLE " + OLD_ASGN + " RENAME TO " + NEW_ASGN);

        // when
        applyV9();

        // then — 남은 짝도, 먼저 옮긴 쪽의 부속 객체도 전부 새 이름이 된다
        assertThat(relExists("r", NEW_EVT)).isTrue();
        assertThat(relExists("r", OLD_EVT)).isFalse();
        assertThat(relExists("S", "ls_task_altmnt_assignment_id_seq")).isTrue();
        assertThat(relExists("i", "ix_ls_task_altmnt_raw")).isTrue();
        assertThat(constraintExists(NEW_ASGN, "uk_ls_task_altmnt")).isTrue();
    }

    // ------------------------------------------------------------------ 데이터 보존

    @Test
    @DisplayName("기존_배정행과_감사행이_개명_후에도_보존된다")
    void existingRowsSurviveRename() throws Exception {
        // given — 개명이 재생성이면 여기서 사라진다. 감사 원장이 사라지면 재배정 증적과
        //   승인 이력(신고 차단의 fail-closed 보조축)이 함께 사라진다.
        seedAssignment(1L, 10L, 1000L);
        seedAssignment(2L, 11L, 1001L);
        seedEvent(1000L, "ASSIGN");
        seedEvent(1000L, "APPROVE");

        // when
        applyV9();

        // then — 행수·값이 그대로다
        assertThat(scalarLong("SELECT count(*) FROM " + NEW_ASGN)).isEqualTo(2L);
        assertThat(scalarLong("SELECT count(*) FROM " + NEW_EVT)).isEqualTo(2L);
        assertThat(scalarString(
                "SELECT task_type_cd FROM " + NEW_ASGN + " WHERE assignment_id = 1"))
                .isEqualTo("LABELER");
        assertThat(scalarLong(
                "SELECT count(*) FROM " + NEW_EVT + " WHERE evnt_type_cd = 'APPROVE'"))
                .as("승인 이력이 사라지면 승인 이력이 있는 영상에 비식별 신고가 통과한다")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("컬럼명과_타입과_순서가_개명_전과_동일하다")
    void columnsAreUntouched() throws Exception {
        // given — 개명 전 컬럼 형상(하드코딩한 기대값과 비교하면 DDL 이 바뀔 때 같이 낡는다)
        List<String> asgnBefore = columnSignature(OLD_ASGN);
        List<String> evtBefore = columnSignature(OLD_EVT);

        // when
        applyV9();

        // then — ★컬럼은 <하나도> 개명하지 않는다. ASSIGNMENT_ID 등은 전부 등록 용어라 이미 정합이다.
        assertThat(columnSignature(NEW_ASGN)).isEqualTo(asgnBefore);
        assertThat(columnSignature(NEW_EVT)).isEqualTo(evtBefore);
        assertThat(asgnBefore.get(0))
                .as("배정 PK 컬럼명은 그대로여야 한다")
                .startsWith("1:assignment_id:");
        assertThat(evtBefore)
                .as("이벤트 로그의 사용자 축 컬럼명도 그대로여야 한다")
                .anyMatch(c -> c.contains(":actor_user_no:"))
                .anyMatch(c -> c.contains(":subject_user_no:"))
                .anyMatch(c -> c.contains(":prev_user_no:"));
    }

    // ------------------------------------------------------------------ 부속 객체 실동작

    @Test
    @DisplayName("유니크와_외래키가_개명_후에도_실제로_강제된다")
    void constraintsAreStillEnforcedAfterRename() throws Exception {
        // given
        applyV9();
        seedAssignment(1L, 10L, 1000L);

        // then — 정의 문자열만 보면 무력화를 놓친다. 실제 위반으로 확인한다.
        assertThatThrownBy(() -> seedAssignment(2L, 10L, 1000L))
                .as("(영상, 사용자, 작업유형) 유니크가 살아 있어야 중복 배정이 막힌다")
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> exec("INSERT INTO " + NEW_ASGN
                + " (assignment_id, user_no, raw_data_id, task_type_cd, reg_user_no)"
                + " VALUES (9, 10, 88888, 'LABELER', 1)"))
                .as("부모 영상이 없는 배정은 FK 로 막혀야 한다")
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("외래키_CASCADE_삭제가_개명_후에도_동작한다")
    void cascadeDeleteStillWorksAfterRename() throws Exception {
        // given
        seedAssignment(1L, 10L, 1000L);
        seedEvent(1000L, "ASSIGN");
        applyV9();

        // when — 부모 영상 삭제
        exec("DELETE FROM ls_data_raw WHERE raw_sn = 1000");

        // then — 자식이 함께 정리된다(FK 정의가 살아 있다는 실동작 증거)
        assertThat(scalarLong("SELECT count(*) FROM " + NEW_ASGN)).isZero();
        assertThat(scalarLong("SELECT count(*) FROM " + NEW_EVT)).isZero();
    }

    @Test
    @DisplayName("IDENTITY_시퀀스가_새_이름으로_바뀌고_채번이_이어진다")
    void identitySequenceIsRenamedAndKeepsCounting() throws Exception {
        // given — 개명 전에 두 건을 채번해 시퀀스를 진행시켜 둔다(FK 부모 먼저)
        exec("INSERT INTO ls_data_raw (raw_sn) VALUES (1000)");
        exec("INSERT INTO " + OLD_EVT + " (raw_data_id, evnt_type_cd, actor_user_no)"
                + " VALUES (1000, 'ASSIGN', 1)");
        exec("INSERT INTO " + OLD_EVT + " (raw_data_id, evnt_type_cd, actor_user_no)"
                + " VALUES (1000, 'SUBMIT', 1)");
        long lastBefore = scalarLong("SELECT max(evnt_id) FROM " + OLD_EVT);

        // when
        applyV9();

        // then — 이름이 실제 컬럼(evnt_id)에 맞게 바뀐다
        assertThat(relExists("S", "ls_task_evnt_log_evnt_id_seq")).isTrue();
        assertThat(relExists("S", "ls_task_event_log_event_seq_seq")).isFalse();

        // then — 채번 위치가 보존된다. 재생성했다면 여기서 1 부터 다시 나와 PK 충돌이 난다.
        exec("INSERT INTO " + NEW_EVT + " (raw_data_id, evnt_type_cd, actor_user_no)"
                + " VALUES (1000, 'APPROVE', 1)");
        assertThat(scalarLong("SELECT max(evnt_id) FROM " + NEW_EVT)).isEqualTo(lastBefore + 1);

        // then — 컬럼이 여전히 그 시퀀스에 매여 있다(개명이 IDENTITY 연결을 끊지 않았다)
        assertThat(scalarString(
                "SELECT pg_get_serial_sequence(" + literal(NEW_EVT) + ", 'evnt_id')"))
                .endsWith(".ls_task_evnt_log_evnt_id_seq");
    }

    // ------------------------------------------------------------------ fail-closed

    @Test
    @DisplayName("옛_이름과_새_이름_테이블이_동시에_있으면_마이그레이션이_중단된다")
    void abortsWhenBothTableNamesExist() throws Exception {
        // given — 수기 조작이나 실패한 부분 적용의 흔적. 어느 쪽이 정본인지 알 수 없다.
        //   조용히 진행하면 옛 테이블에 남은 감사 행이 아무도 읽지 않는 채로 남는다.
        exec("CREATE TABLE " + NEW_EVT + " (evnt_id bigint PRIMARY KEY)");
        seedEvent(1000L, "APPROVE");

        // when / then — 중단되고, DO 블록은 원자적이라 형상도 그대로다
        assertThatThrownBy(this::applyV9)
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertGuardMessage(e));
        assertThat(relExists("r", OLD_EVT)).isTrue();
        assertThat(relExists("r", NEW_EVT)).isTrue();
        assertThat(relExists("r", OLD_ASGN))
                .as("DO 블록이 원자적이므로 앞선 짝의 개명도 함께 롤백돼야 한다")
                .isTrue();
        assertThat(scalarLong("SELECT count(*) FROM " + OLD_EVT)).isEqualTo(1L);
    }

    /**
     * 중단 사유가 <b>이 가드가 만든 것</b>인지 확인한다 — 아무 {@link SQLException} 이나 통과하면 가드의
     * 존재 이유가 사라진다. PG 가 스스로 내는 {@code relation ... already exists} 로는 통과하지 못한다.
     *
     * <p>동시에 <b>원장 값이 새지 않는지</b>도 단언한다(CWE-209/359) — 이 원장의 행은 사용자 번호와
     * 사유 문구를 담아 PII 인접이다. 사람이 조치하는 데 필요한 것은 테이블 이름뿐이다.
     */
    private void assertGuardMessage(Throwable e) {
        String msg = String.valueOf(e.getMessage());
        assertThat(msg)
                .as("가드가 만든 중단 사유여야 한다 — PG 원문(already exists)으로는 통과하지 못한다")
                .contains(OLD_EVT)
                .contains(NEW_EVT)
                .contains("동시에 존재");
        assertThat(msg)
                .as("원장 값(사용자 번호·사유 문구)을 사유에 실으면 로그로 새어 나간다 (CWE-209/359)")
                .doesNotContain(SECRET_RSN)
                .doesNotContain(String.valueOf(SECRET_ACTOR));
    }

    // ------------------------------------------------------------------ 내부

    /** 사유 문구·행위자 번호는 사유 메시지에 실려서는 안 되는 값이다. */
    private static final String SECRET_RSN = "개인정보-노출-신고-상세";
    private static final long SECRET_ACTOR = 777001L;

    private void applyV9() throws IOException, SQLException {
        assertThat(Files.exists(V9_SQL)).as("V9 마이그레이션 파일이 존재해야 한다 (%s)", V9_SQL).isTrue();
        exec(Files.readString(V9_SQL));
    }

    private void seedAssignment(long id, long userNo, long rawSn) throws SQLException {
        String table = relExists("r", OLD_ASGN) ? OLD_ASGN : NEW_ASGN;
        exec("INSERT INTO ls_data_raw (raw_sn) VALUES (" + rawSn + ") ON CONFLICT DO NOTHING");
        exec("INSERT INTO " + table
                + " (assignment_id, user_no, raw_data_id, task_type_cd, reg_user_no)"
                + " VALUES (" + id + ", " + userNo + ", " + rawSn + ", 'LABELER', 1)");
    }

    private void seedEvent(long rawSn, String type) throws SQLException {
        String table = relExists("r", OLD_EVT) ? OLD_EVT : NEW_EVT;
        exec("INSERT INTO ls_data_raw (raw_sn) VALUES (" + rawSn + ") ON CONFLICT DO NOTHING");
        exec("INSERT INTO " + table
                + " (raw_data_id, evnt_type_cd, actor_user_no, rsn) VALUES ("
                + rawSn + ", " + literal(type) + ", " + SECRET_ACTOR + ", " + literal(SECRET_RSN) + ")");
    }

    /** {@code 순서:컬럼명:타입:널허용} 목록 — 컬럼이 개명·이동·재정의되면 값이 달라진다. */
    private List<String> columnSignature(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT ordinal_position, column_name, data_type, is_nullable"
                             + " FROM information_schema.columns"
                             + " WHERE table_schema = current_schema() AND table_name = "
                             + literal(table) + " ORDER BY ordinal_position")) {
            while (rs.next()) {
                out.add(rs.getInt(1) + ":" + rs.getString(2) + ":" + rs.getString(3) + ":" + rs.getString(4));
            }
        }
        assertThat(out).as("컬럼 시그니처를 읽지 못했다 — 테이블 %s 가 없다", table).isNotEmpty();
        return out;
    }

    /** {@code relkind} 는 r=테이블 · S=시퀀스 · i=인덱스. */
    private boolean relExists(String relkind, String name) throws SQLException {
        return scalarLong("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = current_schema() AND c.relkind = " + literal(relkind)
                + " AND c.relname = " + literal(name)) == 1L;
    }

    private boolean constraintExists(String table, String name) throws SQLException {
        return scalarLong("SELECT count(*) FROM pg_constraint"
                + " WHERE conrelid = to_regclass(" + literal(table) + ")"
                + " AND conname = " + literal(name)) == 1L;
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
