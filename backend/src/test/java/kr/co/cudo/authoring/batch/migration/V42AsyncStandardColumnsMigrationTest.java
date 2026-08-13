package kr.co.cudo.authoring.batch.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 — Flyway V42 (비동기 표준 컬럼 4종 추가) + V43 (backfill) 마이그레이션 파일 검증.
 *
 * <p>{@code @DataJpaTest} 인프라가 없어 SQL 텍스트 검사로 정합성을 확인한다.
 * (운영은 Flyway 가 부팅 시 자동 적용)
 *
 * <p>S-1/S-2 방어 — UNIQUE 제약 + backfill 결정적 키로 NULL 충돌 0 / 자연키 충돌 0 보장.
 */
class V42AsyncStandardColumnsMigrationTest {

    private static final Path V42 = Paths.get(
            "src/test/resources/db-archive/migration/V42__add_async_standard_columns.sql");
    private static final Path V43 = Paths.get(
            "src/test/resources/db-archive/migration/V43__backfill_idempotency_keys.sql");

    // ============================================================
    // V42 — 표준 컬럼 추가 + UNIQUE 제약
    // ============================================================

    @Test
    @DisplayName("Phase4_V42_마이그레이션_파일_존재")
    void v42FileExists() {
        assertThat(Files.exists(V42))
                .as("V42 마이그레이션 파일이 존재해야 한다 (%s)", V42)
                .isTrue();
    }

    @Test
    @DisplayName("Phase4_V42_LS_DATA_META_표준_컬럼_4종_추가")
    void v42AddsAsyncColumnsToLsDataMeta() throws IOException {
        String sql = Files.readString(V42);
        assertThat(sql).contains("ALTER TABLE LS_DATA_META");
        assertThat(sql).contains("IDEMPOTENCY_KEY");
        assertThat(sql).contains("EXTERNAL_JOB_ID");
        assertThat(sql).contains("RETRY_COUNT");
        assertThat(sql).contains("DEAD_LETTER_AT");
    }

    @Test
    @DisplayName("Phase4_V42_LS_DATA_AUG_표준_컬럼_4종_추가")
    void v42AddsAsyncColumnsToLsDataAug() throws IOException {
        String sql = Files.readString(V42);
        assertThat(sql).contains("ALTER TABLE LS_DATA_AUG");
        // 동일 4종이 LS_DATA_AUG 에도 추가
        assertThat(sql).contains("IDEMPOTENCY_KEY");
        assertThat(sql).contains("EXTERNAL_JOB_ID");
        assertThat(sql).contains("RETRY_COUNT");
        assertThat(sql).contains("DEAD_LETTER_AT");
    }

    @Test
    @DisplayName("Phase4_V42_LS_DATA_META_UNIQUE_idempotencyKey_externalJobId_S_1_방어")
    void v42DeclaresUniqueOnMeta() throws IOException {
        String sql = Files.readString(V42);
        assertThat(sql).containsAnyOf("uk_meta_idempotency_key", "UK_LS_DATA_META_IDEMPOTENCY_KEY");
        assertThat(sql).containsAnyOf("uk_meta_external_job_id", "UK_LS_DATA_META_EXTERNAL_JOB_ID");
    }

    @Test
    @DisplayName("Phase4_V42_LS_DATA_AUG_UNIQUE_idempotencyKey_externalJobId_S_1_방어")
    void v42DeclaresUniqueOnAug() throws IOException {
        String sql = Files.readString(V42);
        assertThat(sql).containsAnyOf("uk_aug_idempotency_key", "UK_LS_DATA_AUG_IDEMPOTENCY_KEY");
        assertThat(sql).containsAnyOf("uk_aug_external_job_id", "UK_LS_DATA_AUG_EXTERNAL_JOB_ID");
    }

    @Test
    @DisplayName("Phase4_V42_표준_컬럼_NULL_허용_기존_행_보호")
    void v42AllowsNullForBackwardCompat() throws IOException {
        String sql = Files.readString(V42);
        // 기존 행 보호 — IDEMPOTENCY_KEY / EXTERNAL_JOB_ID 는 NULL 허용 (NOT NULL 금지)
        // RETRY_COUNT 만 NOT NULL DEFAULT 0
        assertThat(sql).contains("RETRY_COUNT").contains("DEFAULT 0");
        // 명시적으로 NOT NULL 이 IDEMPOTENCY_KEY 컬럼에 붙어있지 않은지 검사
        assertThat(sql).doesNotContain("IDEMPOTENCY_KEY VARCHAR(64) NOT NULL");
        assertThat(sql).doesNotContain("EXTERNAL_JOB_ID VARCHAR(128) NOT NULL");
    }

    // ============================================================
    // V43 — backfill
    // ============================================================

    @Test
    @DisplayName("Phase4_V43_마이그레이션_파일_존재")
    void v43FileExists() {
        assertThat(Files.exists(V43))
                .as("V43 backfill 파일이 존재해야 한다 (%s)", V43)
                .isTrue();
    }

    @Test
    @DisplayName("Phase4_V43_LS_DATA_META_backfill_idempotencyKey_결정적_키_S_2_방어")
    void v43BackfillsMetaIdempotencyKey() throws IOException {
        String sql = Files.readString(V43);
        assertThat(sql).contains("UPDATE LS_DATA_META");
        assertThat(sql).contains("IDEMPOTENCY_KEY");
        assertThat(sql).contains("legacy-meta-");
        // 결정적 키 — 자연키 조합 기반 (RAW_SN + META_KEY)
        assertThat(sql).contains("RAW_SN");
        assertThat(sql).contains("META_KEY");
        // NULL 행만 백필 (idempotent 재실행 안전)
        assertThat(sql).contains("IDEMPOTENCY_KEY IS NULL");
    }

    @Test
    @DisplayName("Phase4_V43_LS_DATA_AUG_backfill_idempotencyKey_결정적_키_S_2_방어")
    void v43BackfillsAugIdempotencyKey() throws IOException {
        String sql = Files.readString(V43);
        assertThat(sql).contains("UPDATE LS_DATA_AUG");
        assertThat(sql).contains("legacy-aug-");
        // 결정적 키 — 자연키 조합 (SRC_SN + AUG_TYPE_CD + DATA_AUG_SN)
        assertThat(sql).contains("SRC_SN");
        assertThat(sql).contains("AUG_TYPE_CD");
        assertThat(sql).contains("IDEMPOTENCY_KEY IS NULL");
    }
}
