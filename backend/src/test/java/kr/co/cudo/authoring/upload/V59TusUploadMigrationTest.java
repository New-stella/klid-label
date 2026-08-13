package kr.co.cudo.authoring.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V59 (LS_TUS_UPLOAD) 마이그레이션 파일 정합 검증 (SQL 텍스트 검사).
 *
 * <p>예약어 OFFSET 회피(UPLOAD_OFFSET) + 낙관적 잠금(VERSION) + TTL(EXPIRES_AT) +
 * 동시 세션 상한 인덱스(USER_NO, STATUS) 등 시나리오 방어 스키마를 확인한다.
 */
class V59TusUploadMigrationTest {

    private static final Path V59 = Paths.get(
            "src/test/resources/db-archive/migration/V59__create_ls_tus_upload.sql");

    @Test
    @DisplayName("V59_마이그레이션_파일_존재")
    void fileExists() {
        assertThat(Files.exists(V59)).isTrue();
    }

    @Test
    @DisplayName("V59_LS_TUS_UPLOAD_핵심컬럼_정의")
    void hasCoreColumns() throws IOException {
        String sql = Files.readString(V59).toUpperCase();
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS LS_TUS_UPLOAD");
        assertThat(sql).contains("UPLOAD_ID");
        assertThat(sql).contains("UUID");
        assertThat(sql).contains("USER_NO");
        assertThat(sql).contains("UPLOAD_LENGTH");
        // 예약어 OFFSET 회피 — UPLOAD_OFFSET 사용
        assertThat(sql).contains("UPLOAD_OFFSET");
        assertThat(sql).contains("STATUS");
        assertThat(sql).contains("FILE_PATH");
        assertThat(sql).contains("EXPIRES_AT");
        assertThat(sql).contains("VERSION");
        assertThat(sql).contains("PRIMARY KEY (UPLOAD_ID)");
    }

    @Test
    @DisplayName("V59_동시세션상한_및_만료정리_인덱스_정의")
    void hasIndexes() throws IOException {
        String sql = Files.readString(V59).toUpperCase();
        assertThat(sql).contains("IDX_LTU_USER_STATUS");
        assertThat(sql).contains("IDX_LTU_EXPIRES");
    }

    @Test
    @DisplayName("V59_PostgreSQL표준_MariaDB고유문법_미사용")
    void noMariaDbSyntax() throws IOException {
        String sql = Files.readString(V59).toUpperCase();
        assertThat(sql).doesNotContain("AUTO_INCREMENT");
        assertThat(sql).doesNotContain("ENGINE=");
    }
}
