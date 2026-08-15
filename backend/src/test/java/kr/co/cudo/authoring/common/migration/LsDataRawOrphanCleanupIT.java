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
import java.util.List;
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
     * V146 원문이 열거하는 자식 중 <b>그 뒤에 제거된</b> 테이블. 재생 시 이 항목들만 덜어낸다.
     *
     * <p>V3(2026-08-13)가 {@code LS_RAW_DATA_ENROLLMENT} 를, V4 가 {@code LS_TASK_ASSIGN_HISTORY} ·
     * {@code LS_DATA_RAW_HSTRY} 를, V6 가 {@code LS_DATA_LBL_AI_INFO} 를 DROP 했다
     * (V6 는 그 테이블을 {@code LS_DATA_LBL} 로 흡수했다 — 옮긴 5컬럼에 {@code DATA_RAW_SN} 은 없다.
     * 라벨→프레임→영상으로 이미 도달 가능한 사본이라 이관 대상이 아니었고, 그래서 이 FK 도 함께 사라졌다).
     * V146 의 자식 목록은 하드코딩 배열이고 실재 여부를
     * 확인하지 않으므로, 원문 그대로 재생하면 고아 조사 첫 루프에서 {@code relation does not exist} 로
     * 죽는다 — 이 테스트가 검증하려는 <b>고아 선행 정리 로직</b>에 닿기도 전이다.
     *
     * <p><b>아카이브 원문을 고치지 않는 이유</b>: {@code db-archive/} 는 구 180개의 <i>원문 보존처</i>이고
     * {@code FlywaySquashBaselineIT} 가 개수·처음/끝을 고정하고 있다. 과거에 실제로 적용된 SQL 을
     * 나중에 고치면 그 시점의 기록이 아니게 된다. 그래서 파일은 그대로 두고 <b>재생용 사본에서만</b>
     * 덜어낸다. 실제 FK 목록의 정합은 {@code LsDataRawChildFkCascadeIT} 가 별도로 지킨다.
     *
     * <h3>★ 테이블을 DROP 하는 마이그레이션을 추가하면 <b>두 곳</b>을 함께 갱신해야 한다</h3>
     * 이 저장소에는 "제거되는 것"을 <b>명시 목록</b>으로 추적하는 가드가 둘 있고, 둘 다 느슨한 매칭을
     * 금지하고 있어 자동으로 따라오지 않는다:
     * <ul>
     *   <li><b>여기</b>({@code DROPPED_CHILD_SPEC_LINES}) — 그 테이블이 V146 자식 목록에 있었다면 한 줄 추가</li>
     *   <li>{@code FlywaySquashBaselineIT} — 신규 마이그레이션 버전·파일명을 각각 한 줄 추가</li>
     * </ul>
     * ⚠ 둘 다 <b>스코프 테스트로는 잡히지 않고 FULL 회귀에서만 드러난다</b>. 실제로 V6(라벨 AI 정보
     * 흡수) 라운드에서 94건 스코프 실행이 전부 통과한 뒤 FULL 에서 이 두 가드가 함께 실패했다.
     */
    private static final List<String> DROPPED_CHILD_SPEC_LINES = List.of(
            "        ['ls_raw_data_enrollment',     'raw_data_id', 'CASCADE'],\n",
            "        ['ls_task_assign_history',     'raw_data_id', 'CASCADE'],\n",
            "        ['ls_data_raw_hstry',          'raw_sn',      'CASCADE'],\n",
            "        ['ls_data_lbl_ai_info',        'data_raw_sn', 'CASCADE'],\n");

    /**
     * V146 원문이 열거하는 자식 중 <b>그 뒤에 개명된</b> 테이블. 재생 시 이 항목들을 현재 물리명으로
     * 바꿔치기한다 — {@code {원문 라인, 현재 라인}}.
     *
     * <p>V9(2026-08-15)가 {@code LS_TASK_ASSIGNMENT} → {@code LS_TASK_ALTMNT} ·
     * {@code LS_TASK_EVENT_LOG} → {@code LS_TASK_EVNT_LOG} 로 표준용어 개명했다. 위
     * {@link #DROPPED_CHILD_SPEC_LINES} 와 달리 <b>덜어내면 안 된다</b> — 테이블은 실재하고 FK 도
     * 필요하므로, 덜어내면 이 테스트가 그 자식의 고아 정리를 더 이상 검증하지 않는 <b>조용한 커버리지
     * 축소</b>가 된다. 이름만 오늘의 것으로 바꿔 재생한다.
     *
     * <p>재생 시 V146 이 만들려는 FK 이름도 함께 따라와({@code fk_ls_task_altmnt_raw}) 실제 스키마와
     * 일치하므로, V146 의 {@code pg_constraint} 존재 확인이 그대로 멱등으로 동작한다.
     *
     * <p>⚠ <b>테이블을 개명하는 마이그레이션을 추가할 때도 여기를 갱신해야 한다</b> — 위 클래스
     * Javadoc 의 "두 곳" 경고와 같은 성질이며, 마찬가지로 <b>스코프 테스트로는 잡히지 않는다</b>
     * (이 클래스는 개명 대상 테이블명을 자바 소스에 갖고 있지 않아 <b>grep 으로도 드러나지 않는다</b>).
     */
    private static final List<String[]> RENAMED_CHILD_SPEC_LINES = List.of(
            new String[]{
                    "        ['ls_task_assignment',         'raw_data_id', 'CASCADE'],\n",
                    "        ['ls_task_altmnt',             'raw_data_id', 'CASCADE'],\n"},
            new String[]{
                    "        ['ls_task_event_log',          'raw_data_id', 'CASCADE'],\n",
                    "        ['ls_task_evnt_log',           'raw_data_id', 'CASCADE'],\n"});

    @BeforeEach
    void setUp() throws IOException {
        jdbc = new JdbcTemplate(controlDataSource);
        String original = FileCopyUtils.copyToString(new InputStreamReader(
                new ClassPathResource("db-archive/migration/V146__add_ls_data_raw_child_fk.sql").getInputStream(),
                StandardCharsets.UTF_8));

        migrationSql = original;
        for (String line : DROPPED_CHILD_SPEC_LINES) {
            // 각 항목은 정확히 1건이어야 한다 — 0건이면 원문이 바뀐 것이고(아카이브 훼손),
            // 2건이면 가정이 깨진 것이다.
            assertThat(original.split(Pattern.quote(line), -1).length - 1)
                    .as("V146 원문에서 제거 대상 자식 스펙 라인(%s)은 정확히 1건이어야 한다", line.trim())
                    .isEqualTo(1);
            migrationSql = migrationSql.replace(line, "");
        }
        for (String[] rename : RENAMED_CHILD_SPEC_LINES) {
            // 덜어내기와 같은 이유로 정확히 1건이어야 한다 — 0건이면 원문이 바뀐 것이다(아카이브 훼손).
            assertThat(original.split(Pattern.quote(rename[0]), -1).length - 1)
                    .as("V146 원문에서 개명 대상 자식 스펙 라인(%s)은 정확히 1건이어야 한다", rename[0].trim())
                    .isEqualTo(1);
            migrationSql = migrationSql.replace(rename[0], rename[1]);
        }

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
