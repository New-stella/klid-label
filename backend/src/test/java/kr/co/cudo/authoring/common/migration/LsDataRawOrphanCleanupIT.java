package kr.co.cudo.authoring.common.migration;

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
import org.springframework.util.FileCopyUtils;

import javax.sql.DataSource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-ISSUE-01 — V146 의 <b>고아 선행 정리</b> 로직 실동작 검증 (Testcontainers PostgreSQL).
 *
 * <p>FK 는 고아가 하나라도 있으면 생성 자체가 실패하므로 마이그레이션에 선행 정리가 들어 있다.
 * 다만 "조용한 대량 삭제" 는 위험하므로 아래 규칙을 둔다 — 본 테스트가 그 규칙을 고정한다.
 * <ol>
 *   <li>일반 자식 테이블의 고아는 삭제한다(RAISE NOTICE 로 건수 기록).</li>
 *   <li>데이터마트 뷰(V_COMPLETED_*)에 공급되는 테이블에 고아가 있으면 <b>삭제하지 않고 중단</b>한다
 *       — 관제가 보던 행이 예고 없이 사라지는 것을 막는다("검수 완료·통지 건 관제 접근 보장" 구속 제약).</li>
 * </ol>
 *
 * <p>검증 방법: 대상 FK 를 일시 제거해 고아를 인위로 만든 뒤 <b>실제 V146 스크립트</b>를 재실행한다
 * (스크립트는 멱등 — FK 는 {@code pg_constraint} 존재 확인 후 생성).
 */
@SpringBootTest
@ActiveProfiles("local")
class LsDataRawOrphanCleanupIT {

    /** 실재하지 않는 부모 — 고아 생성용. */
    private static final long GHOST_RAW_SN = 9_999_999_998L;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;
    private String migrationSql;

    /**
     * V146 원문이 열거하는 자식 중 <b>그 뒤에 제거된</b> 테이블. 재생 시 이 항목만 덜어낸다.
     *
     * <p>V3(2026-08-13)가 사용처 0 테이블 {@code LS_RAW_DATA_ENROLLMENT} 를 DROP 했다. V146 의 자식
     * 목록은 하드코딩 배열이고 실재 여부를 확인하지 않으므로, 원문 그대로 재생하면 고아 조사 첫
     * 루프에서 {@code relation does not exist} 로 죽는다 — 이 테스트가 검증하려는 <b>고아 선행 정리
     * 로직</b>에 닿기도 전이다.
     *
     * <p><b>아카이브 원문을 고치지 않는 이유</b>: {@code db-archive/} 는 구 180개의 <i>원문 보존처</i>이고
     * {@code FlywaySquashBaselineIT} 가 개수·처음/끝을 고정하고 있다. 과거에 실제로 적용된 SQL 을
     * 나중에 고치면 그 시점의 기록이 아니게 된다. 그래서 파일은 그대로 두고 <b>재생용 사본에서만</b>
     * 덜어낸다. 실제 FK 목록의 정합은 {@code LsDataRawChildFkCascadeIT} 가 별도로 지킨다.
     */
    private static final String DROPPED_CHILD_SPEC_LINE =
            "        ['ls_raw_data_enrollment',     'raw_data_id', 'CASCADE'],\n";

    @BeforeEach
    void setUp() throws IOException {
        jdbc = new JdbcTemplate(controlDataSource);
        String original = FileCopyUtils.copyToString(new InputStreamReader(
                new ClassPathResource("db-archive/migration/V146__add_ls_data_raw_child_fk.sql").getInputStream(),
                StandardCharsets.UTF_8));

        // 정확히 1건이어야 한다 — 0건이면 원문이 바뀐 것이고(아카이브 훼손), 2건이면 가정이 깨진 것이다.
        assertThat(original.split(Pattern.quote(DROPPED_CHILD_SPEC_LINE), -1).length - 1)
                .as("V146 원문에서 제거 대상 자식 스펙 라인은 정확히 1건이어야 한다")
                .isEqualTo(1);
        migrationSql = original.replace(DROPPED_CHILD_SPEC_LINE, "");

        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
        // 중단 케이스는 DO 블록이 FK 재생성 전에 예외로 빠지므로, 스키마를 원상 복구해 둔다.
        runMigration();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM LS_MARKING WHERE RAW_SN = ?", GHOST_RAW_SN);
        jdbc.update("DELETE FROM LS_DATA_META WHERE RAW_SN = ?", GHOST_RAW_SN);
    }

    private void runMigration() {
        jdbc.execute(migrationSql);
    }

    private void dropFk(String table) {
        jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS fk_" + table.toLowerCase() + "_raw");
    }

    private boolean fkExists(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = ?",
                Long.class, "fk_" + table.toLowerCase() + "_raw");
        return n != null && n > 0;
    }

    private long countGhost(String table, String column) {
        Long n = jdbc.queryForObject(
                String.format("SELECT count(*) FROM %s WHERE %s = ?", table, column), Long.class, GHOST_RAW_SN);
        return n == null ? 0L : n;
    }

    @Test
    @DisplayName("일반_자식_테이블의_고아는_선행정리로_삭제되고_FK가_생성된다")
    void orphansInPlainChildTableAreCleanedAndFkCreated() {
        // given — FK 를 잠시 제거하고 고아 마킹 1행을 만든다(실측 결함 재현: rawSn 삭제 후 마킹 잔존)
        dropFk("ls_marking");
        jdbc.update("INSERT INTO LS_MARKING "
                        + "(RAW_SN, EVNT_NM, VIDEO_FILE_PATH_NM, MARK_MODE_CD, STTS_CD, MARK_CN, REG_DT) "
                        + "VALUES (?, 'ORPHAN', '/nas/o.mp4', 'AUTO', 'PENDING', '[]', ?)",
                GHOST_RAW_SN, LocalDateTime.now());
        assertThat(countGhost("LS_MARKING", "RAW_SN")).isEqualTo(1L);

        // when — 실제 V146 스크립트 재실행
        runMigration();

        // then — 고아는 정리되고 FK 가 복원된다
        assertThat(countGhost("LS_MARKING", "RAW_SN")).isZero();
        assertThat(fkExists("ls_marking")).isTrue();
    }

    @Test
    @DisplayName("데이터마트_뷰_공급_테이블에_고아가_있으면_자동삭제하지_않고_중단한다")
    void orphansInViewFeedingTableAbortMigration() {
        // given — V_COMPLETED_META 공급 테이블(LS_DATA_META)에 고아 1행
        dropFk("ls_data_meta");
        jdbc.update("INSERT INTO LS_DATA_META (RAW_SN, META_KEY, META_VL, REG_DT) VALUES (?, ?, ?, ?)",
                GHOST_RAW_SN, "orphan.key", "v", LocalDateTime.now());

        // when / then — 조용한 삭제 대신 예외로 중단(운영자 판단 요구)
        assertThatThrownBy(this::runMigration)
                .hasMessageContaining("V146 중단")
                .hasMessageContaining("ls_data_meta");

        // 중단이므로 고아 행은 그대로 남아 있어야 한다(삭제되지 않음)
        assertThat(countGhost("LS_DATA_META", "RAW_SN")).isEqualTo(1L);
    }
}
