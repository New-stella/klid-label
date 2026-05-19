package kr.co.cudo.authoring.version.fallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 — Flyway V41 마이그레이션 파일 존재 + 핵심 DDL 항목 검증.
 *
 * <p>{@code @DataJpaTest} 기반 통합 테스트 인프라가 본 프로젝트에 없어 SQL 파일 텍스트
 * 검사로 마이그레이션 정합성을 확인한다. (운영은 Flyway 가 부팅 시 자동 적용)
 */
class V41GiteaFallbackQueueMigrationTest {

    private static final Path MIGRATION = Paths.get(
            "src/main/resources/db/migration/V41__create_ls_gitea_fallback_queue.sql");

    @Test
    @DisplayName("Phase3_V41_마이그레이션_파일_존재")
    void migrationFileExists() {
        assertThat(Files.exists(MIGRATION))
                .as("V41 마이그레이션 파일이 존재해야 한다 (%s)", MIGRATION)
                .isTrue();
    }

    @Test
    @DisplayName("Phase3_V41_LS_GITEA_FALLBACK_QUEUE_핵심_컬럼_정의됨")
    void migrationContainsCoreColumns() throws IOException {
        String sql = Files.readString(MIGRATION);
        assertThat(sql).contains("CREATE TABLE LS_GITEA_FALLBACK_QUEUE");
        assertThat(sql).contains("IDEMPOTENCY_KEY");
        assertThat(sql).contains("OPERATION");
        assertThat(sql).contains("STATUS");
        assertThat(sql).contains("RETRY_COUNT");
        assertThat(sql).contains("MAX_RETRY");
        assertThat(sql).contains("NEXT_RETRY_AT");
        assertThat(sql).contains("DEAD_LETTER_AT");
        assertThat(sql).contains("CONTENT_BASE64");
    }

    @Test
    @DisplayName("Phase3_V41_UNIQUE_idempotencyKey_제약_S_4_방어")
    void migrationDeclaresUniqueIdempotencyKey() throws IOException {
        String sql = Files.readString(MIGRATION);
        // UNIQUE KEY 또는 UNIQUE 제약이 IDEMPOTENCY_KEY 에 적용되어야 한다.
        assertThat(sql).containsAnyOf(
                "UNIQUE KEY uk_idempotency_key",
                "UNIQUE (IDEMPOTENCY_KEY)",
                "UNIQUE KEY (IDEMPOTENCY_KEY)");
    }

    @Test
    @DisplayName("Phase3_V41_STATUS_NEXT_RETRY_AT_복합_인덱스_polling_최적화")
    void migrationDeclaresStatusNextRetryIndex() throws IOException {
        String sql = Files.readString(MIGRATION);
        assertThat(sql).contains("idx_status_next_retry");
    }

    @Test
    @DisplayName("Phase3_V41_InnoDB_utf8mb4_엔진_charset_지정")
    void migrationDeclaresInnoDbUtf8mb4() throws IOException {
        String sql = Files.readString(MIGRATION);
        assertThat(sql).contains("ENGINE=InnoDB");
        assertThat(sql).contains("utf8mb4");
    }
}
