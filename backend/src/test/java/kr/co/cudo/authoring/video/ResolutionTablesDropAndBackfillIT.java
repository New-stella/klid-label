package kr.co.cudo.authoring.video;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V126 — 구 해상도 전용 테이블(LS_RESOLUTION_EXPORT / LS_RESOLUTION_LBL_MAP) 백필 후 제거 검증
 * (Testcontainers PostgreSQL 실 DB).
 *
 * <p>Flyway 는 @SpringBootTest 컨텍스트 기동 시 V126 까지 전부 적용하므로, 백필 대상 데이터를
 * 마이그레이션 이전 상태에서 주입할 수 없다. 따라서 백필 정확성은 V126 파일의 실제 INSERT ... SELECT
 * 문(백필 로직)을 <b>파일에서 읽어 그대로 재현</b>하는 방식으로 검증한다(SQL 드리프트 없음):
 * 드롭된 소스 테이블을 임시 재생성 + 시드 → V126 의 INSERT 문 실행 → LS_DATA_AUG(RESL_*)/
 * LS_DATA_AUG_LBL_MAP 정합 확인 → 재생성 테이블·시드 정리.
 *
 * <p>공유 컨테이너 오염 방지 — 시드는 고유 RAW_SN(990101)/DATA_LBL_SN(990222) 로 좁히고
 * {@link #cleanup()} 에서 시드 행 삭제 + 임시 재생성 테이블 DROP 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class ResolutionTablesDropAndBackfillIT {

    private static final long SEED_RAW_SN = 990101L;
    private static final long SEED_ORGNL_LBL_SN = 990111L;
    private static final long SEED_DATA_LBL_SN = 990222L;
    private static final String SEED_REG_ID = "mig-user";

    private final JdbcTemplate jdbc;

    ResolutionTablesDropAndBackfillIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        // 백필/가드 테스트가 재생성/시드한 데이터·임시 테이블 제거 (다른 테스트·존재검증 테스트 격리).
        // 모든 시드는 REG_ID=SEED_REG_ID('mig-user') 마커 + RAW_SN 990000~990999 대역만 사용하므로
        // 마커 기준으로 일괄 정리한다(FK 순서: aug_lbl_map → aug → src).
        jdbc.execute("DROP TABLE IF EXISTS LS_RESOLUTION_LBL_MAP");
        jdbc.execute("DROP TABLE IF EXISTS LS_RESOLUTION_EXPORT");
        jdbc.update("DELETE FROM LS_DATA_AUG_LBL_MAP WHERE REG_ID = ?", SEED_REG_ID);
        jdbc.update("DELETE FROM LS_DATA_AUG WHERE REG_USER_NO = ?", SEED_REG_ID);
        jdbc.update("DELETE FROM LS_DATA_SRC WHERE RAW_SN BETWEEN 990000 AND 990999");
    }

    @Test
    @DisplayName("V126_적용후_LS_RESOLUTION_EXPORT와_LBL_MAP_테이블이_존재하지_않는다")
    void resolutionTablesDropped() {
        assertThat(tableExists("ls_resolution_export")).isFalse();
        assertThat(tableExists("ls_resolution_lbl_map")).isFalse();
    }

    @Test
    @DisplayName("앱이_ddl_auto_validate로_정상_기동한다_엔티티제거_반영")
    void contextBootsWithValidate() {
        // @SpringBootTest 컨텍스트 기동 자체가 ddl-auto=validate 통과를 의미(엔티티 2종 제거 반영).
        // 매핑이 유지되는 다른 증강 테이블은 여전히 존재해 validate 가 성공한다.
        Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
        assertThat(one).isEqualTo(1);
        assertThat(tableExists("ls_data_aug")).isTrue();
        assertThat(tableExists("ls_data_aug_lbl_map")).isTrue();
    }

    @Test
    @DisplayName("GOAL_RESL_CD_RES접두는_RESL로_변환되고_이미_RESL이면_불변이다")
    void resToReslConversion() {
        String converted = jdbc.queryForObject(
                "SELECT REPLACE('RES_1080P', 'RES_', 'RESL_')", String.class);
        assertThat(converted).isEqualTo("RESL_1080P");

        String unchanged = jdbc.queryForObject(
                "SELECT REPLACE('RESL_1080P', 'RES_', 'RESL_')", String.class);
        assertThat(unchanged).isEqualTo("RESL_1080P");
    }

    @Test
    @DisplayName("백필SQL이_export와_lblmap을_LS_DATA_AUG_RESL과_AUG_LBL_MAP으로_대표프레임기준_정확히_이관한다")
    void backfillMigratesExportAndLblMap() {
        // given — V126 가 이미 드롭한 소스 두 테이블을 백필에 필요한 컬럼만 최소 재생성 후 시드.
        recreateSourceTables();

        // 대표프레임 시드: RAW 990101 에 FRM_NO 5, 2 두 프레임. 대표 = FRM_NO 최소(2).
        jdbc.update("INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM) VALUES (?, ?, ?)",
                SEED_RAW_SN, 5L, "/x/f5.jpg");
        jdbc.update("INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM) VALUES (?, ?, ?)",
                SEED_RAW_SN, 2L, "/x/f2.jpg");
        Long repSrcSn = jdbc.queryForObject(
                "SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN = ? AND FRM_NO = 2", Long.class, SEED_RAW_SN);

        // export 1행(GOAL_RESL_CD='RES_720P' 구 접두) + lbl_map 1행.
        jdbc.update("INSERT INTO LS_RESOLUTION_EXPORT (DATA_RAW_SN, GOAL_RESL_CD, REG_ID, REG_DT) "
                        + "VALUES (?, 'RES_720P', ?, CURRENT_TIMESTAMP)",
                SEED_RAW_SN, SEED_REG_ID);
        Long exportSn = jdbc.queryForObject(
                "SELECT RESL_EXPORT_SN FROM LS_RESOLUTION_EXPORT WHERE DATA_RAW_SN = ?", Long.class, SEED_RAW_SN);
        jdbc.update("INSERT INTO LS_RESOLUTION_LBL_MAP "
                        + "(RESL_EXPORT_SN, ORGNL_DATA_LBL_SN, DATA_LBL_SN, COORD_RECALC_YN, SCALE_X, SCALE_Y, REG_ID, REG_DT) "
                        + "VALUES (?, ?, ?, 'Y', 0.5, 0.5, ?, CURRENT_TIMESTAMP)",
                exportSn, SEED_ORGNL_LBL_SN, SEED_DATA_LBL_SN, SEED_REG_ID);

        // when — V126 의 실제 INSERT ... SELECT 문(백필 로직)을 파일에서 읽어 그대로 실행.
        for (String insert : backfillInsertStatements()) {
            jdbc.execute(insert);
        }

        // then #1 — LS_DATA_AUG 에 대표프레임(FRM_NO 최소) SRC_SN + 변환 AUG_TYPE_CD='RESL_720P' ACCEPTED 1행.
        Map<String, Object> aug = jdbc.queryForMap(
                "SELECT DATA_AUG_SN, SRC_SN, AUG_TYPE_CD, AUG_PROC_STTS_CD, REG_USER_NO, RTRY_NMTM "
                        + "FROM LS_DATA_AUG WHERE SRC_SN = ?", repSrcSn);
        assertThat(((Number) aug.get("SRC_SN")).longValue()).isEqualTo(repSrcSn);
        assertThat(aug.get("AUG_TYPE_CD")).isEqualTo("RESL_720P");
        assertThat(aug.get("AUG_PROC_STTS_CD")).isEqualTo("ACCEPTED");
        assertThat(aug.get("REG_USER_NO")).isEqualTo(SEED_REG_ID);
        assertThat(((Number) aug.get("RTRY_NMTM")).intValue()).isZero();
        long dataAugSn = ((Number) aug.get("DATA_AUG_SN")).longValue();

        // then #2 — LS_DATA_AUG_LBL_MAP 에 위 aug 행을 DATA_AUG_SN 으로 연결한 1행(원본/파생 라벨·배율 복사).
        Map<String, Object> map = jdbc.queryForMap(
                "SELECT DATA_AUG_SN, ORGNL_DATA_LBL_SN, DATA_LBL_SN, COORD_RECALC_YN, SCALE_X, SCALE_Y, REG_ID "
                        + "FROM LS_DATA_AUG_LBL_MAP WHERE DATA_LBL_SN = ?", SEED_DATA_LBL_SN);
        assertThat(((Number) map.get("DATA_AUG_SN")).longValue()).isEqualTo(dataAugSn);
        assertThat(((Number) map.get("ORGNL_DATA_LBL_SN")).longValue()).isEqualTo(SEED_ORGNL_LBL_SN);
        assertThat(map.get("COORD_RECALC_YN")).isEqualTo("Y");
        assertThat(((Number) map.get("SCALE_X")).doubleValue()).isEqualTo(0.5d);
        assertThat(map.get("REG_ID")).isEqualTo(SEED_REG_ID);
    }

    @Test
    @DisplayName("대표프레임없는_export에_라벨매핑이_있으면_V126가_RAISE_EXCEPTION으로_중단되고_DROP되지_않는다")
    void failClosedWhenNoFrameExportHasLblMap() {
        // given — 소스 두 테이블 재생성.
        recreateSourceTables();

        // 정상 export(프레임 보유) 1건: 백필되면 aug 1행 생성 대상 — 예외 롤백 시 사라져야 함(롤백 증거).
        long okRaw = 990201L;
        jdbc.update("INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM) VALUES (?, 1, '/x/ok.jpg')", okRaw);
        jdbc.update("INSERT INTO LS_RESOLUTION_EXPORT (DATA_RAW_SN, GOAL_RESL_CD, REG_ID, REG_DT) "
                + "VALUES (?, 'RES_1080P', ?, CURRENT_TIMESTAMP)", okRaw, SEED_REG_ID);
        Long okExportSn = jdbc.queryForObject(
                "SELECT RESL_EXPORT_SN FROM LS_RESOLUTION_EXPORT WHERE DATA_RAW_SN = ?", Long.class, okRaw);
        jdbc.update("INSERT INTO LS_RESOLUTION_LBL_MAP "
                + "(RESL_EXPORT_SN, ORGNL_DATA_LBL_SN, DATA_LBL_SN, COORD_RECALC_YN, SCALE_X, SCALE_Y, REG_ID, REG_DT) "
                + "VALUES (?, 990211, 990221, 'Y', 0.5, 0.5, ?, CURRENT_TIMESTAMP)", okExportSn, SEED_REG_ID);

        // degenerate export(부모 RAW 990202 에 LS_DATA_SRC 프레임 0건) + 라벨매핑 2건 → 소실 위험.
        long noFrameRaw = 990202L;
        jdbc.update("INSERT INTO LS_RESOLUTION_EXPORT (DATA_RAW_SN, GOAL_RESL_CD, REG_ID, REG_DT) "
                + "VALUES (?, 'RES_720P', ?, CURRENT_TIMESTAMP)", noFrameRaw, SEED_REG_ID);
        Long noFrameExportSn = jdbc.queryForObject(
                "SELECT RESL_EXPORT_SN FROM LS_RESOLUTION_EXPORT WHERE DATA_RAW_SN = ?", Long.class, noFrameRaw);
        jdbc.update("INSERT INTO LS_RESOLUTION_LBL_MAP "
                + "(RESL_EXPORT_SN, DATA_LBL_SN, COORD_RECALC_YN, REG_ID, REG_DT) "
                + "VALUES (?, 990231, 'Y', ?, CURRENT_TIMESTAMP)", noFrameExportSn, SEED_REG_ID);
        jdbc.update("INSERT INTO LS_RESOLUTION_LBL_MAP "
                + "(RESL_EXPORT_SN, DATA_LBL_SN, COORD_RECALC_YN, REG_ID, REG_DT) "
                + "VALUES (?, 990232, 'Y', ?, CURRENT_TIMESTAMP)", noFrameExportSn, SEED_REG_ID);

        // when/then — 백필 ①② + 가드 + DROP 을 Flyway 처럼 단일 트랜잭션으로 실행하면 가드가 예외를 던지고
        //   전체(백필 + DROP)가 롤백된다.
        assertThatThrownBy(() -> runInSingleTransaction(allV126Statements()))
                .hasStackTraceContaining("이관 불가");

        // 두 소스 테이블이 DROP 되지 않고 그대로 남는다(fail-closed).
        assertThat(tableExists("ls_resolution_export")).isTrue();
        assertThat(tableExists("ls_resolution_lbl_map")).isTrue();

        // 백필 aug 행도 롤백돼 남지 않는다(정상 export 의 이관분까지 원자적으로 취소).
        Integer augCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_DATA_AUG WHERE REG_USER_NO = ?", Integer.class, SEED_REG_ID);
        assertThat(augCnt).isZero();
    }

    @Test
    @DisplayName("입력_라벨매핑_전건이_이관되면_손실0이고_정상통과한다")
    void allLblMapsMigratedNoLossPasses() {
        // given — 프레임 보유 export 2건 + 각 export 에 라벨매핑(총 K=3건) 시드.
        recreateSourceTables();

        long rawA = 990101L;
        long rawB = 990102L;
        jdbc.update("INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM) VALUES (?, 3, '/x/a.jpg')", rawA);
        jdbc.update("INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM) VALUES (?, 1, '/x/b.jpg')", rawB);

        long exportSnA = insertExport(rawA, "RES_1080P");
        long exportSnB = insertExport(rawB, "RES_480P");
        insertLblMap(exportSnA, 990241L);
        insertLblMap(exportSnA, 990242L);
        insertLblMap(exportSnB, 990243L);

        // when — 백필 ①② + 가드(예외 없이 통과) 실행.
        for (String stmt : backfillInsertStatements()) {
            jdbc.execute(stmt);
        }
        jdbc.execute(guardBlock());

        // then — 입력 라벨매핑 K=3 건이 손실 없이 전부 LS_DATA_AUG_LBL_MAP 으로 이관된다.
        Integer migrated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_DATA_AUG_LBL_MAP WHERE REG_ID = ?", Integer.class, SEED_REG_ID);
        assertThat(migrated).isEqualTo(3);
    }

    @Test
    @DisplayName("대표프레임없는_export는_라벨매핑이_없어도_export자체_소실이므로_fail_closed로_RAISE되어_중단된다")
    void noFrameExportEvenWithoutLblMapIsFailClosed() {
        // given — 부모 RAW 프레임 0건 export 1건, 라벨매핑은 0건.
        //   기존 lbl_map 손실 가드로는 소실 0(통과)이지만, export 행 자체가 DROP 으로 영구 소실되므로
        //   새 export 이관 가드가 이를 잡아 중단해야 한다(진짜 fail-closed).
        recreateSourceTables();
        insertExport(990301L, "RES_720P");

        // when — 백필 ①②(모두 0행 no-op) 실행 후 가드 실행.
        for (String stmt : backfillInsertStatements()) {
            jdbc.execute(stmt);
        }

        // then — 대응 aug RESL_ 행이 존재하지 않는 export 1건이 감지돼 export 가드가 RAISE 한다.
        String guard = guardBlock();
        assertThatThrownBy(() -> jdbc.execute(guard))
                .hasStackTraceContaining("이관되지 못한 해상도 export");

        // aug/lbl_map 은 이관된 것이 없다(백필 no-op).
        Integer migrated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_DATA_AUG_LBL_MAP WHERE REG_ID = ?", Integer.class, SEED_REG_ID);
        assertThat(migrated).isZero();
    }

    @Test
    @DisplayName("대표프레임있는_export는_라벨매핑유무와무관하게_새_export가드를_예외없이_통과한다")
    void frameBearingExportPassesExportGuard() {
        // given — 프레임 보유 export 2건. 하나는 라벨매핑 보유, 하나는 라벨매핑 없음(둘 다 export 자체는 이관됨).
        recreateSourceTables();

        long rawWithMap = 990401L;
        long rawNoMap = 990402L;
        jdbc.update("INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM) VALUES (?, 1, '/x/wm.jpg')", rawWithMap);
        jdbc.update("INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM) VALUES (?, 1, '/x/nm.jpg')", rawNoMap);

        long exportWithMap = insertExport(rawWithMap, "RES_1080P");
        insertExport(rawNoMap, "RES_480P");
        insertLblMap(exportWithMap, 990441L);

        // when — 백필 ①② + 가드(예외 없이 통과) 실행.
        for (String stmt : backfillInsertStatements()) {
            jdbc.execute(stmt);
        }
        jdbc.execute(guardBlock());

        // then — 두 export 모두 대응 aug RESL_ 행으로 이관돼 export 가드를 통과, lbl_map 은 1건 이관.
        Integer augCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_DATA_AUG WHERE REG_USER_NO = ?", Integer.class, SEED_REG_ID);
        assertThat(augCnt).isEqualTo(2);
        Integer migrated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_DATA_AUG_LBL_MAP WHERE REG_ID = ?", Integer.class, SEED_REG_ID);
        assertThat(migrated).isEqualTo(1);
    }

    // ---------------------------------------------------------------------

    private long insertExport(long rawSn, String goalReslCd) {
        jdbc.update("INSERT INTO LS_RESOLUTION_EXPORT (DATA_RAW_SN, GOAL_RESL_CD, REG_ID, REG_DT) "
                + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)", rawSn, goalReslCd, SEED_REG_ID);
        return jdbc.queryForObject(
                "SELECT RESL_EXPORT_SN FROM LS_RESOLUTION_EXPORT WHERE DATA_RAW_SN = ?", Long.class, rawSn);
    }

    private void insertLblMap(long exportSn, long dataLblSn) {
        jdbc.update("INSERT INTO LS_RESOLUTION_LBL_MAP "
                + "(RESL_EXPORT_SN, ORGNL_DATA_LBL_SN, DATA_LBL_SN, COORD_RECALC_YN, SCALE_X, SCALE_Y, REG_ID, REG_DT) "
                + "VALUES (?, ?, ?, 'Y', 0.5, 0.5, ?, CURRENT_TIMESTAMP)",
                exportSn, dataLblSn - 100, dataLblSn, SEED_REG_ID);
    }

    /** V126 의 모든 실행문(INSERT ①② + 가드 DO 블록 + DROP)을 파일 순서대로 반환(단일 트랜잭션 재현용). */
    private List<String> allV126Statements() {
        return parseStatements();
    }

    /** V126 의 fail-closed 가드 DO 블록(단일)을 추출한다. */
    private String guardBlock() {
        return parseStatements().stream()
                .filter(s -> s.toUpperCase().startsWith("DO"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("V126 에서 가드 DO 블록을 찾을 수 없습니다."));
    }

    /** 주어진 문들을 하나의 커넥션·트랜잭션에서 순차 실행한다(예외 시 롤백 후 전파 — Flyway 원자성 재현). */
    private void runInSingleTransaction(List<String> statements) {
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String s : statements) {
                    st.execute(s);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException(e.getMessage(), e);
            } finally {
                conn.setAutoCommit(prevAuto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("트랜잭션 실행 실패", e);
        }
    }

    private boolean tableExists(String lowerName) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE lower(table_name) = ?",
                Integer.class, lowerName);
        return cnt != null && cnt > 0;
    }

    /** 백필에 필요한 컬럼만 갖는 소스 두 테이블 최소 재생성(V126 가 드롭한 상태에서 테스트 전용). */
    private void recreateSourceTables() {
        jdbc.execute("CREATE TABLE LS_RESOLUTION_EXPORT ("
                + "RESL_EXPORT_SN BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                + "DATA_RAW_SN BIGINT NOT NULL, "
                + "GOAL_RESL_CD VARCHAR(16) NOT NULL, "
                + "REG_ID VARCHAR(30), "
                + "REG_DT TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE LS_RESOLUTION_LBL_MAP ("
                + "RESL_LBL_MAP_SN BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                + "RESL_EXPORT_SN BIGINT NOT NULL, "
                + "ORGNL_DATA_LBL_SN BIGINT, "
                + "DATA_LBL_SN BIGINT NOT NULL, "
                + "COORD_RECALC_YN VARCHAR(1) NOT NULL DEFAULT 'Y', "
                + "SCALE_X DECIMAL(10,6), "
                + "SCALE_Y DECIMAL(10,6), "
                + "REG_ID VARCHAR(30), "
                + "REG_DT TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    }

    /** V126 마이그레이션 파일에서 INSERT 문(백필 로직)만 추출한다(SQL 드리프트 방지). */
    private List<String> backfillInsertStatements() {
        return parseStatements().stream()
                .filter(s -> s.toUpperCase().startsWith("INSERT"))
                .toList();
    }

    /**
     * V126 파일을 라인 주석 제거 후 문 단위로 분할한다(SQL 드리프트 방지).
     * ';' 분할 시 {@code $$ ... $$} 달러 인용 블록(가드 DO 블록) 내부의 ';' 는 경계로 보지 않는다.
     */
    private List<String> parseStatements() {
        String sql;
        try {
            sql = StreamUtils.copyToString(
                    new ClassPathResource("db/migration/V126__backfill_and_drop_ls_resolution_tables.sql")
                            .getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("V126 마이그레이션 파일을 읽을 수 없습니다.", e);
        }
        // 라인 주석(-- ...) 제거(블록 내부 주석 포함).
        String noComments = Arrays.stream(sql.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));

        List<String> statements = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inDollar = false;
        int i = 0;
        while (i < noComments.length()) {
            if (noComments.startsWith("$$", i)) {
                inDollar = !inDollar;
                cur.append("$$");
                i += 2;
                continue;
            }
            char c = noComments.charAt(i);
            if (c == ';' && !inDollar) {
                String s = cur.toString().trim();
                if (!s.isEmpty()) {
                    statements.add(s);
                }
                cur.setLength(0);
            } else {
                cur.append(c);
            }
            i++;
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }
}
