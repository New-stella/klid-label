package kr.co.cudo.authoring.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V111 (LS_PORTAL_TUS_ULD) 마이그레이션 파일 정합 검증 (SQL 텍스트 검사).
 *
 * <p>예약어 OFFSET 회피(ULD_OFFSET) + 낙관적 잠금(VER) + TTL(EXPRY_DT) + 동시 세션 상한/만료
 * 스캔 인덱스 등 시나리오 방어 스키마를 확인한다.
 */
class V111PortalTusUploadMigrationTest {

    private static final Path V111 = Paths.get(
            "src/test/resources/db-archive/migration/V111__create_ls_portal_tus_upload.sql");

    @Test
    @DisplayName("V111_마이그레이션_파일_존재")
    void fileExists() {
        assertThat(Files.exists(V111)).isTrue();
    }

    @Test
    @DisplayName("V111_LS_PORTAL_TUS_ULD_핵심컬럼_정의")
    void hasCoreColumns() throws IOException {
        String sql = Files.readString(V111).toUpperCase();
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS LS_PORTAL_TUS_ULD");
        assertThat(sql).contains("ULD_ID");
        assertThat(sql).contains("UUID");
        assertThat(sql).contains("PORTAL_USER_NO");
        assertThat(sql).contains("ULD_LEN");
        // 예약어 OFFSET 회피 — ULD_OFFSET 사용
        assertThat(sql).contains("ULD_OFFSET");
        assertThat(sql).contains("STTS_CD");
        assertThat(sql).contains("FILE_PATH_NM");
        assertThat(sql).contains("EXPRY_DT");
        assertThat(sql).contains("VER");
        assertThat(sql).contains("PRIMARY KEY (ULD_ID)");
    }

    @Test
    @DisplayName("V111_동시세션상한_및_만료스윕_인덱스_정의")
    void hasIndexes() throws IOException {
        String sql = Files.readString(V111).toUpperCase();
        assertThat(sql).contains("IDX_LPTU_USER_STTS");
        assertThat(sql).contains("IDX_LPTU_EXPRY");
    }

    @Test
    @DisplayName("V111_만료스윕_인덱스는_진행중_부분인덱스")
    void expiryIndexIsPartial() throws IOException {
        String sql = Files.readString(V111).toUpperCase();
        // database 🟡3: 진행 중 세션만 대상이므로 부분 인덱스로 좁힘(PostgreSQL partial index).
        assertThat(sql).contains("WHERE STTS_CD = 'IN_PROGRESS'");
    }

    @Test
    @DisplayName("V111_PostgreSQL표준_MariaDB고유문법_미사용")
    void noMariaDbSyntax() throws IOException {
        String sql = Files.readString(V111).toUpperCase();
        assertThat(sql).doesNotContain("AUTO_INCREMENT");
        assertThat(sql).doesNotContain("ENGINE=");
    }
}
