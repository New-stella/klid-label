package kr.co.cudo.authoring.preset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V72 (프리셋 EVT_* → 관제 categoryKey 전환) 마이그레이션 파일 정합 검증 (SQL 텍스트 검사).
 *
 * <p>6종 EVT_* → categoryKey 매핑 + 멱등(WHERE 한정) + PostgreSQL 표준 + 비파괴(값 UPDATE 만)를 확인한다.
 * 실 DB 적용 검증은 별도 Flyway IT 가 담당하고, 본 테스트는 매핑 누락·오타·MariaDB 문법 혼입을 차단한다.
 */
class V72MigratePresetEvntTypeMigrationTest {

    private static final Path V72 = Paths.get(
            "src/main/resources/db/migration/V72__migrate_preset_evnt_type_to_category.sql");

    private String sql() throws IOException {
        return Files.readString(V72).toUpperCase();
    }

    @Test
    @DisplayName("V72_마이그레이션_파일_존재")
    void fileExists() {
        assertThat(Files.exists(V72)).isTrue();
    }

    @Test
    @DisplayName("프리셋_EVT_ABNORMAL이_V72로_categoryKey_050007로_전환된다")
    void preset_abnormal_to_050007() throws IOException {
        String sql = sql();
        // EVT_ABNORMAL → categoryKey 050007, LS_LABEL_PRESET 대상, WHERE 로 멱등 한정.
        assertThat(sql).contains(
                "UPDATE LS_LABEL_PRESET SET EVNT_TYPE_CD = '050007' WHERE EVNT_TYPE_CD = 'EVT_ABNORMAL'");
    }

    @Test
    @DisplayName("프리셋_EVT_6종_categoryKey_매핑_전부_포함")
    void preset_all_six_mappings() throws IOException {
        String sql = sql();
        assertThat(sql).contains("UPDATE LS_LABEL_PRESET SET EVNT_TYPE_CD = '020002' WHERE EVNT_TYPE_CD = 'EVT_FALL'");
        assertThat(sql).contains("UPDATE LS_LABEL_PRESET SET EVNT_TYPE_CD = '050001' WHERE EVNT_TYPE_CD = 'EVT_VIOLENCE'");
        assertThat(sql).contains("UPDATE LS_LABEL_PRESET SET EVNT_TYPE_CD = '030001' WHERE EVNT_TYPE_CD = 'EVT_ACCIDENT'");
        assertThat(sql).contains("UPDATE LS_LABEL_PRESET SET EVNT_TYPE_CD = '050007' WHERE EVNT_TYPE_CD = 'EVT_ABNORMAL'");
        assertThat(sql).contains("UPDATE LS_LABEL_PRESET SET EVNT_TYPE_CD = '010001' WHERE EVNT_TYPE_CD = 'EVT_FLOOD'");
        assertThat(sql).contains("UPDATE LS_LABEL_PRESET SET EVNT_TYPE_CD = '020001' WHERE EVNT_TYPE_CD = 'EVT_FIRE'");
    }

    @Test
    @DisplayName("LS_DATA_RAW_EVT_6종_대표_EV코드_매핑_포함")
    void rawData_all_six_mappings() throws IOException {
        String sql = sql();
        assertThat(sql).contains("UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = 'EV02000201' WHERE EVNT_TYPE_CD = 'EVT_FALL'");
        assertThat(sql).contains("UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = 'EV05000101' WHERE EVNT_TYPE_CD = 'EVT_VIOLENCE'");
        assertThat(sql).contains("UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = 'EV03000101' WHERE EVNT_TYPE_CD = 'EVT_ACCIDENT'");
        assertThat(sql).contains("UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = 'EV05000701' WHERE EVNT_TYPE_CD = 'EVT_ABNORMAL'");
        assertThat(sql).contains("UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = 'EV01000101' WHERE EVNT_TYPE_CD = 'EVT_FLOOD'");
        assertThat(sql).contains("UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = 'EV02000101' WHERE EVNT_TYPE_CD = 'EVT_FIRE'");
    }

    @Test
    @DisplayName("V72_멱등성_모든_UPDATE는_WHERE_EVT조건_한정")
    void idempotentWhereGuarded() throws IOException {
        String sql = sql();
        // 무조건 UPDATE(WHERE 없는 전체 갱신) 금지 — 모든 UPDATE 가 WHERE EVNT_TYPE_CD = 'EVT_...' 한정.
        long updateCount = sql.lines().filter(l -> l.trim().startsWith("UPDATE ")).count();
        long whereEvtCount = sql.lines()
                .filter(l -> l.trim().startsWith("UPDATE "))
                .filter(l -> l.contains("WHERE EVNT_TYPE_CD = 'EVT_"))
                .count();
        assertThat(updateCount).isEqualTo(12);
        assertThat(whereEvtCount).isEqualTo(updateCount);
    }

    @Test
    @DisplayName("V72_PostgreSQL표준_MariaDB고유문법_미사용")
    void noMariaDbSyntax() throws IOException {
        String sql = sql();
        assertThat(sql).doesNotContain("AUTO_INCREMENT");
        assertThat(sql).doesNotContain("ENGINE=");
        // 비파괴 — 테이블/컬럼 삭제·변경 없이 값만 정정.
        assertThat(sql).doesNotContain("DROP TABLE");
        assertThat(sql).doesNotContain("ALTER TABLE");
    }
}
