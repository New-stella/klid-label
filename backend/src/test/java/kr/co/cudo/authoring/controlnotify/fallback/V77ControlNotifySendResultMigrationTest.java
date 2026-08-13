package kr.co.cudo.authoring.controlnotify.fallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관제 통지 발송 결과 관찰 — Flyway V77 마이그레이션 파일 존재 + SEND_RSLT_CD 컬럼 추가 DDL 검증.
 */
class V77ControlNotifySendResultMigrationTest {

    private static final Path MIGRATION = Paths.get(
            "src/test/resources/db-archive/migration/V77__add_control_notify_send_result.sql");

    @Test
    @DisplayName("V77_마이그레이션_파일_존재")
    void migrationFileExists() {
        // given / when / then
        assertThat(Files.exists(MIGRATION))
                .as("V77 마이그레이션 파일이 존재해야 한다 (%s)", MIGRATION)
                .isTrue();
    }

    @Test
    @DisplayName("V77_SEND_RSLT_CD_컬럼_추가_DDL")
    void migrationAddsSendResultColumn() throws IOException {
        // given
        String sql = Files.readString(MIGRATION);

        // when / then — PostgreSQL 표준 ADD COLUMN (nullable, length 명시).
        assertThat(sql).contains("ALTER TABLE LS_CONTROL_NOTIFY_FALLBACK");
        assertThat(sql).contains("ADD COLUMN");
        assertThat(sql).contains("SEND_RSLT_CD");
        assertThat(sql).contains("VARCHAR(16)");
        // MariaDB 전용 문법 미사용
        assertThat(sql).doesNotContain("ENGINE=InnoDB");
        assertThat(sql).doesNotContain("utf8mb4");
    }
}
