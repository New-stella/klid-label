package kr.co.cudo.authoring.user.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V75 (LS_USER_ROLE 신설 + 기존 저작도구 역할 이관) 마이그레이션 실동작 검증.
 *
 * <p>V75 는 구 구조에서 관제 소유 권한 매핑 테이블({@code MNG_ACCT_USER_AUTHRT})에 있던 저작도구
 * 역할을 저작도구 자체 {@code LS_USER_ROLE} 로 이관한다. <b>저작도구 역할 행만</b> 옮기고 관제 고유
 * 역할({@code LEARN_MANAGER} 등)은 화이트리스트 IN 절로 제외한다.
 *
 * <p><b>★ 픽스처 자체 생성 이유 (V165 이후)</b>: V165 가 구 권한 테이블 2종을 DROP 했으므로 이 테스트가
 * 예전처럼 "마이그레이션이 적용된 스키마에 남아 있는 실제 테이블"에 픽스처를 INSERT 할 수 없다.
 * 그렇다고 테스트를 삭제하면 <b>V75 의 이관 로직 검증이 통째로 사라진다</b> — 그런데 V75 는 아직
 * 죽은 코드가 아니다. <b>신규 설치에서는 Flyway 가 V1(CREATE) → V75(SELECT) → V165(DROP) 순서로
 * 여전히 이 SQL 을 실행</b>하기 때문이다. 그래서 테스트를 없애는 대신, 각 테스트가 V1 원문과 동일한
 * DDL 로 <b>구 테이블을 임시 생성</b>했다가 종료 시 DROP 하는 방식으로 전환했다. 검증 의도는 100%
 * 보존되며(이관 대상 한정·우선순위·멱등성·MNG 변경 없음), 프로덕션 스키마에는 이 테이블이 남지 않는다.
 *
 * <p>임시 테이블은 {@code @BeforeEach} 에서 만들고 {@code @AfterEach} 에서 지운다(테스트 메서드
 * 구간에만 존재). 이 모듈은 테스트 병렬 실행을 켜지 않아(단일 fork·순차) 공유 컨테이너의 다른
 * 테스트와 간섭하지 않는다. 매핑하는 엔티티가 없으므로 {@code ddl-auto=validate} 에도 영향이 없다.
 *
 * <p>각 테스트는 다른 통합테스트를 오염시키지 않도록 고유 USER_NO 대역(975_2xx_xxx)을 쓰고
 * 종료 시 {@code LS_USER_ROLE}(실제 테이블) 행을 정리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class V75MigrateLsUserRoleMigrationTest {

    private static final Path MIGRATION_DIR = Paths.get("src/main/resources/db/migration");
    private static final Path V75 = MIGRATION_DIR.resolve("V75__create_ls_user_role.sql");
    private static final Path V1 = MIGRATION_DIR.resolve("V1__phase2_base_schema.sql");
    private static final Path V165 = MIGRATION_DIR.resolve("V165__drop_dead_acct_authrt_tables.sql");

    /**
     * 구 권한 매핑 테이블 DDL — {@code V1__phase2_base_schema.sql} 원문과 동일하다.
     * V75 가 읽던 당시의 스키마를 그대로 재현해야 이관 검증이 유효하다.
     */
    private static final String LEGACY_AUTHRT_DDL = """
            CREATE TABLE IF NOT EXISTS MNG_ACCT_USER_AUTHRT (
                USER_NO     BIGINT          NOT NULL,
                AUTHRT_CD   VARCHAR(32)     NOT NULL,
                REG_DT      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (USER_NO, AUTHRT_CD)
            )""";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    /** 본 테스트 전용 USER_NO 대역 — 다른 통합테스트/시드와 충돌 회피. */
    private static final AtomicLong USER_NO_SEQ = new AtomicLong(975_200_000L);

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    @BeforeEach
    void createLegacyFixtureTable() {
        // V165 가 지운 구 테이블을 <이 테스트 구간에만> 재현한다(위 클래스 Javadoc 참조).
        jdbc().execute(LEGACY_AUTHRT_DDL);
    }

    @AfterEach
    void dropLegacyFixtureTable() {
        // 프로덕션 스키마에 구 테이블이 되살아난 채로 남지 않도록 반드시 제거한다.
        jdbc().execute("DROP TABLE IF EXISTS MNG_ACCT_USER_AUTHRT");
    }

    /** V75 마이그레이션 SQL 본문을 그대로 실행한다(멱등 — 재실행 안전). */
    private void applyV75() throws IOException {
        jdbc().execute(Files.readString(V75));
    }

    private long insertAuthrt(String authrtCd) {
        long userNo = USER_NO_SEQ.incrementAndGet();
        jdbc().update(
                "INSERT INTO MNG_ACCT_USER_AUTHRT (USER_NO, AUTHRT_CD) VALUES (?, ?)",
                userNo, authrtCd);
        return userNo;
    }

    private String roleOf(long userNo) {
        return jdbc().query(
                "SELECT ROLE_CD FROM LS_USER_ROLE WHERE USER_NO = ?",
                rs -> rs.next() ? rs.getString(1) : null, userNo);
    }

    /** 실제 테이블(LS_USER_ROLE)만 정리한다 — 픽스처 테이블은 {@code @AfterEach} 가 통째로 DROP 한다. */
    private void cleanup(long userNo) {
        jdbc().update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
    }

    @Test
    @DisplayName("마이그레이션이_REVIEWER_WORKER_PORTAL_USER_행만_이관한다")
    void 마이그레이션이_REVIEWER_WORKER_PORTAL_USER_행만_이관한다() throws IOException {
        // given — 저작도구 역할 3종 + 관제 역할 1종
        long reviewer = insertAuthrt("REVIEWER");
        long worker = insertAuthrt("WORKER");
        long portal = insertAuthrt("PORTAL_USER");
        long learnMgr = insertAuthrt("LEARN_MANAGER");
        try {
            // when — V75 이관 실행
            applyV75();

            // then — 저작도구 역할만 ROLE_CD 보존하며 이관
            assertThat(roleOf(reviewer)).isEqualTo("REVIEWER");
            assertThat(roleOf(worker)).isEqualTo("WORKER");
            assertThat(roleOf(portal)).isEqualTo("PORTAL_USER");
            // 관제 고유 역할은 이관 제외
            assertThat(roleOf(learnMgr)).isNull();
        } finally {
            cleanup(reviewer);
            cleanup(worker);
            cleanup(portal);
            cleanup(learnMgr);
        }
    }

    @Test
    @DisplayName("관제역할_LEARN_MANAGER_행은_LS_USER_ROLE로_이관되지_않는다")
    void 관제역할_LEARN_MANAGER_행은_이관되지_않는다() throws IOException {
        // given — 관제 고유 역할만 존재
        long learnMgr = insertAuthrt("LEARN_MANAGER");
        try {
            // when
            applyV75();

            // then — LS_USER_ROLE 에 행 없음
            assertThat(roleOf(learnMgr)).isNull();
        } finally {
            cleanup(learnMgr);
        }
    }

    @Test
    @DisplayName("복수역할_사용자는_REVIEWER_우선순위로_결정적_이관된다")
    void 복수역할_사용자는_REVIEWER_우선순위로_결정적_이관된다() throws IOException {
        // given — 한 USER_NO 에 저작도구 역할 2개(WORKER, REVIEWER)가 동시에 존재
        long userNo = USER_NO_SEQ.incrementAndGet();
        jdbc().update(
                "INSERT INTO MNG_ACCT_USER_AUTHRT (USER_NO, AUTHRT_CD) VALUES (?, ?)",
                userNo, "WORKER");
        jdbc().update(
                "INSERT INTO MNG_ACCT_USER_AUTHRT (USER_NO, AUTHRT_CD) VALUES (?, ?)",
                userNo, "REVIEWER");
        try {
            // when — V75 이관 실행
            applyV75();

            // then — 권한이 가장 강한 REVIEWER 만 결정적으로 보존(물리 저장 순서 무관), 정확히 1행
            Integer count = jdbc().queryForObject(
                    "SELECT count(*) FROM LS_USER_ROLE WHERE USER_NO = ?",
                    Integer.class, userNo);
            assertThat(count).isEqualTo(1);
            assertThat(roleOf(userNo)).isEqualTo("REVIEWER");
        } finally {
            cleanup(userNo);
        }
    }

    @Test
    @DisplayName("V75_재실행시_중복없이_멱등하게_이관된다")
    void V75_재실행시_중복없이_멱등하게_이관된다() throws IOException {
        // given — REVIEWER 1행, 1차 이관
        long reviewer = insertAuthrt("REVIEWER");
        try {
            applyV75();

            // when — 동일 마이그레이션 재실행(ON CONFLICT DO NOTHING)
            applyV75();

            // then — 정확히 1행만 존재(중복 INSERT 없음)
            Integer count = jdbc().queryForObject(
                    "SELECT count(*) FROM LS_USER_ROLE WHERE USER_NO = ?",
                    Integer.class, reviewer);
            assertThat(count).isEqualTo(1);
            assertThat(roleOf(reviewer)).isEqualTo("REVIEWER");
        } finally {
            cleanup(reviewer);
        }
    }

    @Test
    @DisplayName("V75_마이그레이션_파일_존재_저작도구_역할만_IN절로_한정")
    void V75_마이그레이션_파일_존재_저작도구_역할만_IN절로_한정() throws IOException {
        // given/when — V75 SQL 본문
        assertThat(Files.exists(V75)).isTrue();
        String sql = Files.readString(V75).toUpperCase();

        // then — 화이트리스트 IN 절로 저작도구 역할만 한정
        assertThat(sql).contains("LS_USER_ROLE");
        assertThat(sql).contains("REVIEWER");
        assertThat(sql).contains("WORKER");
        assertThat(sql).contains("PORTAL_USER");

        // then — V75 자신은 구 권한 테이블을 <읽기만> 한다. 스키마 변경(ALTER/DROP)의 주체는
        //   V165 이며, V75 는 이미 적용된 이력이라 내용을 바꿀 수 없다(체크섬 → 2노드 기동 실패).
        assertThat(sql).doesNotContain("ALTER TABLE MNG_ACCT_USER_AUTHRT");
        assertThat(sql).doesNotContain("DROP TABLE MNG_ACCT_USER_AUTHRT");
    }

    @Test
    @DisplayName("Flyway_순서가_CREATE_이관_DROP_이라_신규설치에서도_V75가_깨지지_않는다")
    void Flyway_순서가_CREATE_이관_DROP_이라_신규설치에서도_V75가_깨지지_않는다() throws IOException {
        // given — V75 는 구 권한 테이블을 SELECT 하므로, 실행 시점에 그 테이블이 존재해야 한다.
        //   빈 DB 신규 설치는 V1 → V75 → V165 를 차례로 적용하므로 이 전제가 성립한다.
        assertThat(Files.readString(V1)).contains("CREATE TABLE IF NOT EXISTS MNG_ACCT_USER_AUTHRT");
        assertThat(Files.readString(V75)).contains("FROM MNG_ACCT_USER_AUTHRT");
        assertThat(Files.readString(V165)).contains("DROP TABLE IF EXISTS MNG_ACCT_USER_AUTHRT");

        // then — 버전 번호 순서가 CREATE(1) < 이관(75) < DROP(165) 이어야 한다.
        //   이 순서가 깨지면 신규 설치에서 V75 가 "relation does not exist" 로 실패한다.
        assertThat(versionOf(V1)).isLessThan(versionOf(V75));
        assertThat(versionOf(V75)).isLessThan(versionOf(V165));
    }

    /** {@code V{n}__...sql} 파일명에서 Flyway 버전 번호를 추출한다. */
    private int versionOf(Path migration) {
        String name = migration.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
