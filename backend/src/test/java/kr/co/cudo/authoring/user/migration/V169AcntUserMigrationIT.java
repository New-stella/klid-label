package kr.co.cudo.authoring.user.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V169 — 저작도구 소유 사용자 마스터({@code LS_ACNT_USER}) 신설 + 관제 {@code MNG_ACCT_USER} 이관·제거
 * 실동작 검증 (Testcontainers PostgreSQL, Flyway migrate 후 부팅).
 *
 * <h3>왜 정보 스키마를 직접 대조하나</h3>
 * <p>이 프로젝트의 {@code ddl-auto=validate} 는 실제로 동작하지 않는다({@code JpaBuilderConfig} 가
 * {@code spring.jpa.hibernate.*} 를 EMF 에 넘기지 않아 <b>없는 컬럼을 매핑해도 기동이 성공</b>한다).
 * 따라서 "기동 성공"을 엔티티↔DDL 정합의 증거로 삼을 수 없으므로 신규 컬럼의 물리명·타입·크기·
 * NULL 허용을 {@code information_schema} 로 1:1 대조한다.
 *
 * <h3>이관 검증 방식</h3>
 * <p>{@code V168EvntTypeMigrationIT} 와 같은 관례로 <b>배포되는 마이그레이션 파일 원본을 다시 실행</b>
 * 한다(테스트가 SQL 을 복제하면 파일이 바뀌어도 통과하는 흉내가 된다). 컨텍스트 기동 시 이미 V169 가
 * 적용돼 관제 마스터가 없으므로, 재실행 전에 <b>V1 원문 DDL 로 스크래치 마스터를 만들고</b> 종료 시
 * DROP 한다 — 실제 배포 순서(마스터 존재 → 이관 → DROP)를 재현하는 것이 목적이다.
 */
@SpringBootTest
@ActiveProfiles("local")
class V169AcntUserMigrationIT {

    private static final String MIGRATION =
            "db/migration/V169__create_ls_acnt_user_and_drop_mng_acct_user.sql";

    /** 이 테스트가 만드는 사용자 번호 구간 — 시드(1~3001)·다른 테스트와 겹치지 않게 한다. */
    private static final long BASE_USER_NO = 969_000L;
    private static final int MIGRATED_ROWS = 5;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
        createScratchMaster();
    }

    @AfterEach
    void tearDown() {
        dropScratchMaster();
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM ls_acnt_user WHERE user_no >= ?", BASE_USER_NO);
    }

    /** V1 원문 DDL — 이관 원본을 재현하기 위한 스크래치(테스트 종료 시 DROP). */
    private void createScratchMaster() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS MNG_ACCT_USER (
                    USER_NO     BIGINT          NOT NULL,
                    USER_ID     VARCHAR(64)     NOT NULL,
                    USER_NM     VARCHAR(128)    NOT NULL,
                    USER_EMAIL  VARCHAR(255),
                    USE_YN      VARCHAR(1)      NOT NULL,
                    REG_DT      TIMESTAMP       NOT NULL,
                    UPD_DT      TIMESTAMP,
                    PRIMARY KEY (USER_NO)
                )""");
    }

    private void dropScratchMaster() {
        jdbc.execute("DROP TABLE IF EXISTS MNG_ACCT_USER");
    }

    @Test
    @DisplayName("구_관제_사용자마스터는_더_이상_존재하지_않는다")
    void 구_관제_사용자마스터는_더_이상_존재하지_않는다() {
        // given/when: 스크래치를 걷어낸 상태의 실제 스키마
        dropScratchMaster();

        // then: V169 가 DROP 했으므로 배포 스키마에는 없어야 한다
        assertThat(tableExists("mng_acct_user"))
                .as("V169 가 MNG_ACCT_USER 를 DROP 했어야 한다")
                .isFalse();
        assertThat(tableExists("ls_acnt_user"))
                .as("저작도구 소유 LS_ACNT_USER 가 생성돼 있어야 한다")
                .isTrue();

        // 뒤 테스트를 위해 스크래치 복구(@BeforeEach 계약 유지)
        createScratchMaster();
    }

    @Test
    @DisplayName("신규_사용자마스터_컬럼이_표준도메인_타입과_크기를_따른다")
    void 신규_사용자마스터_컬럼이_표준도메인_타입과_크기를_따른다() {
        // given/when/then — 표준용어·표준도메인 근거는 V169 헤더 주석 참조.
        //   USER_NO 만 표준도메인(번호V10=문자)이 아니라 BIGINT 다: 기존 LS_USER_ROLE·
        //   LS_TASK_ASSIGNMENT 등 저작도구 소유 컬럼이 전부 BIGINT 라 조인 정합을 우선한다.
        assertThat(columnMeta("ls_acnt_user", "user_no").get("data_type")).isEqualTo("bigint");
        assertThat(columnMeta("ls_acnt_user", "user_no").get("is_nullable")).isEqualTo("NO");

        assertVarchar("user_id", 20, true);      // 사용자아이디 USER_ID / 명V20
        assertVarchar("user_nm", 100, false);    // 사용자명   USER_NM / 명V100
        assertVarchar("user_eml_addr", 320, true); // 사용자이메일주소 USER_EML_ADDR / 주소V320

        Map<String, Object> useYn = columnMeta("ls_acnt_user", "use_yn");
        assertThat(useYn.get("data_type")).as("사용여부 USE_YN / 여부C1").isEqualTo("character");
        assertThat(useYn.get("character_maximum_length")).isEqualTo(1);
        assertThat(useYn.get("is_nullable")).isEqualTo("NO");

        assertThat(columnMeta("ls_acnt_user", "reg_dt").get("data_type"))
                .as("등록일시 REG_DT / 연월일시분초D")
                .isEqualTo("timestamp without time zone");
        assertThat(columnMeta("ls_acnt_user", "mdfcn_dt").get("data_type"))
                .as("수정일시 MDFCN_DT / 연월일시분초D — 구 UPD_DT 는 비표준 약어라 승계하지 않는다")
                .isEqualTo("timestamp without time zone");

        // then: 구 비표준 컬럼명(USER_EMAIL — 이메일의 표준약어는 EML 이며 EMAIL 은 금칙어)이 없다
        assertThat(columnExists("ls_acnt_user", "user_email")).isFalse();
        assertThat(columnExists("ls_acnt_user", "upd_dt")).isFalse();
    }

    @Test
    @DisplayName("기존_사용자_행이_전부_이관된다")
    void 기존_사용자_행이_전부_이관된다() throws IOException {
        // given: 관제 마스터에 실제와 같은 5행(활성 4 + 비활성 1)
        for (int i = 0; i < MIGRATED_ROWS; i++) {
            seedMasterUser(BASE_USER_NO + i, "user" + i, "사용자" + i,
                    "user" + i + "@example.com", i == 4 ? "N" : "Y");
        }

        // when: 배포되는 마이그레이션 파일의 이관 구간을 그대로 재실행
        runMigrationUpToDrop();

        // then: 5행 전부 옮겨진다 — 값 손실·잘림 없이
        Long moved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ls_acnt_user WHERE user_no >= ?", Long.class, BASE_USER_NO);
        assertThat(moved).as("관제 마스터 %d행이 전부 이관돼야 한다", MIGRATED_ROWS)
                .isEqualTo(MIGRATED_ROWS);

        Map<String, Object> first = acntUserRow(BASE_USER_NO);
        assertThat(first.get("user_id")).isEqualTo("user0");
        assertThat(first.get("user_nm")).isEqualTo("사용자0");
        assertThat(first.get("user_eml_addr")).isEqualTo("user0@example.com");
        assertThat(((String) first.get("use_yn")).trim()).isEqualTo("Y");
        assertThat(first.get("reg_dt")).as("등록일시가 보존돼야 한다").isNotNull();

        // then: 비활성 사용자도 그대로 이관된다(작업자 목록 제외 필터의 근거값이므로 버리지 않는다)
        assertThat(((String) acntUserRow(BASE_USER_NO + 4).get("use_yn")).trim()).isEqualTo("N");
    }

    @Test
    @DisplayName("이관은_멱등이라_재실행해도_덮어쓰지_않는다")
    void 이관은_멱등이라_재실행해도_덮어쓰지_않는다() throws IOException {
        // given: 1차 이관 후 자동등록이 이름을 갱신했다(관제 마스터에는 옛 이름이 남아 있다)
        seedMasterUser(BASE_USER_NO, "user0", "옛이름", "user0@example.com", "Y");
        runMigrationUpToDrop();
        jdbc.update("UPDATE ls_acnt_user SET user_nm = ? WHERE user_no = ?", "새이름", BASE_USER_NO);

        // when: 부분 실패 후 재개 등으로 마이그레이션이 다시 돌아도
        runMigrationUpToDrop();

        // then: 기존 행을 덮어쓰지 않는다(ON CONFLICT DO NOTHING)
        assertThat(acntUserRow(BASE_USER_NO).get("user_nm")).isEqualTo("새이름");
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ls_acnt_user WHERE user_no = ?", Long.class, BASE_USER_NO);
        assertThat(rows).isEqualTo(1L);
    }

    // --- helpers ---

    /**
     * 마이그레이션 파일을 <b>DROP 직전까지</b> 실행한다. 파일 전체를 실행하면 스크래치 마스터를
     * DROP 해 버리므로 이관 구간만 재현한다. 그 앞 문장은 전부 멱등이다
     * ({@code IF NOT EXISTS} · {@code ON CONFLICT DO NOTHING}).
     */
    private void runMigrationUpToDrop() throws IOException {
        String sql = new String(new ClassPathResource(MIGRATION).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        String executable = sql.replaceAll("--[^\\n\\r]*", " ");
        int dropAt = executable.toUpperCase().indexOf("DROP TABLE IF EXISTS");
        assertThat(dropAt).as("V169 에 DROP 구문이 있어야 한다").isPositive();
        for (String statement : splitStatements(executable.substring(0, dropAt))) {
            jdbc.execute(statement);
        }
    }

    /**
     * {@code ;} 로 문장을 나눈다. {@code COMMENT ON} 설명문에는 {@code ;} 가 없으므로 단순 분할로
     * 충분하다(설명문에 세미콜론이 들어가면 이 헬퍼부터 고쳐야 한다).
     */
    private List<String> splitStatements(String sql) {
        return java.util.Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void seedMasterUser(long userNo, String userId, String userNm, String email, String useYn) {
        jdbc.update("INSERT INTO MNG_ACCT_USER (USER_NO, USER_ID, USER_NM, USER_EMAIL, USE_YN, REG_DT)"
                        + " VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                userNo, userId, userNm, email, useYn);
    }

    private Map<String, Object> acntUserRow(long userNo) {
        return jdbc.queryForMap("SELECT * FROM ls_acnt_user WHERE user_no = ?", userNo);
    }

    private void assertVarchar(String column, int length, boolean nullable) {
        Map<String, Object> meta = columnMeta("ls_acnt_user", column);
        assertThat(meta.get("data_type")).as("%s 타입", column).isEqualTo("character varying");
        assertThat(meta.get("character_maximum_length")).as("%s 크기", column).isEqualTo(length);
        assertThat(meta.get("is_nullable")).as("%s nullable", column)
                .isEqualTo(nullable ? "YES" : "NO");
    }

    private Map<String, Object> columnMeta(String table, String column) {
        return jdbc.queryForMap(
                "SELECT data_type, character_maximum_length, is_nullable, column_default"
                        + " FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                table, column);
    }

    private boolean columnExists(String table, String column) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE table_name = ? AND column_name = ?", Long.class, table, column);
        return count != null && count > 0;
    }

    private boolean tableExists(String table) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Long.class, table);
        return count != null && count > 0;
    }
}
