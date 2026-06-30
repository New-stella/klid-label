package kr.co.cudo.authoring.user.migration;

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
 * <p>저작도구 고유 역할(REVIEWER/WORKER/PORTAL_USER)은 현재 관제 소유
 * {@code MNG_ACCT_USER_AUTHRT} 에 저장돼 있다. V75 는 저작도구 자체 {@code LS_USER_ROLE} 을 만들고
 * <b>저작도구 역할 행만</b> 이관한다(관제 고유 역할 {@code LEARN_MANAGER} 등은 제외).
 *
 * <p><b>검증 방식</b>: 실제 Flyway 마이그레이션 스키마(Testcontainers PostgreSQL) 위에서 관제
 * 권한 매핑({@code MNG_ACCT_USER_AUTHRT})에 REVIEWER/WORKER/PORTAL_USER/LEARN_MANAGER 행을
 * 고유 USER_NO 대역으로 INSERT 하고, V75 SQL 파일을 그대로 실행해 저작도구 역할만 이관되는지·
 * 관제 역할은 누락되는지·재실행 멱등성을 검증한다.
 *
 * <p>각 테스트는 다른 통합테스트를 오염시키지 않도록 고유 USER_NO 대역(975_2xx_xxx)을 쓰고
 * 종료 시 정리(DELETE)한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class V75MigrateLsUserRoleMigrationTest {

    private static final Path V75 = Paths.get(
            "src/main/resources/db/migration/V75__create_ls_user_role.sql");

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    /** 본 테스트 전용 USER_NO 대역 — 다른 통합테스트/시드와 충돌 회피. */
    private static final AtomicLong USER_NO_SEQ = new AtomicLong(975_200_000L);

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
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

    private void cleanup(long userNo) {
        jdbc().update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
        jdbc().update("DELETE FROM MNG_ACCT_USER_AUTHRT WHERE USER_NO = ?", userNo);
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

        // then — 화이트리스트 IN 절로 저작도구 역할만 한정, MNG_* 변경(ALTER/DROP) 없음
        assertThat(sql).contains("LS_USER_ROLE");
        assertThat(sql).contains("REVIEWER");
        assertThat(sql).contains("WORKER");
        assertThat(sql).contains("PORTAL_USER");
        assertThat(sql).doesNotContain("ALTER TABLE MNG_ACCT_USER_AUTHRT");
        assertThat(sql).doesNotContain("DROP TABLE MNG_ACCT_USER_AUTHRT");
    }
}
