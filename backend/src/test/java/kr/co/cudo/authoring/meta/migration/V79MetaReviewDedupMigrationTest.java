package kr.co.cudo.authoring.meta.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V79 중복정리 마이그레이션 검증 — DEV_FIX 2차 #2.
 *
 * <p>(DATA_META_SN, META_TYPE_CD) UNIQUE 제약 추가 전 중복 정리 시, 검수 상태(RVW_STTS_CD)를
 * 무시하고 "최소 SN 보존" 하면 종결상태(APPROVED/REJECTED)를 삭제하고 PENDING 을 남겨
 * 데이터마트 V_COMPLETED_META 소스가 소실되는 검수 판정 회귀가 발생한다.
 * 본 테스트는 dedup 우선순위가 "가장 유의미한 최종 상태 보존" 으로 교체되었는지 SQL 로 검증한다.
 */
class V79MetaReviewDedupMigrationTest {

    private static final Path MIGRATION = Paths.get(
            "src/test/resources/db-archive/migration/V79__add_ls_data_meta_review_unique.sql");

    @Test
    @DisplayName("V79_마이그레이션_파일_존재")
    void migrationFileExists() {
        assertThat(Files.exists(MIGRATION))
                .as("V79 마이그레이션 파일이 존재해야 한다 (%s)", MIGRATION)
                .isTrue();
    }

    @Test
    @DisplayName("V79_dedup_은_검수상태_우선순위로_종결상태를_보존한다")
    void dedupPreservesTerminalReviewState() throws IOException {
        String sql = Files.readString(MIGRATION);

        // 상태 우선순위 보존: APPROVED > REJECTED > PENDING > AUTO_GENERATED
        assertThat(sql).contains("ROW_NUMBER() OVER");
        assertThat(sql).contains("PARTITION BY DATA_META_SN, META_TYPE_CD");
        assertThat(sql).contains("WHEN 'APPROVED' THEN 4");
        assertThat(sql).contains("WHEN 'REJECTED' THEN 3");
        assertThat(sql).contains("WHEN 'PENDING' THEN 2");
        assertThat(sql).contains("WHEN 'AUTO_GENERATED' THEN 1");
        // 최근 검수 우선 + 결정적 tiebreak
        assertThat(sql).contains("RVW_DT DESC NULLS LAST");
        assertThat(sql).contains("DATA_META_REVIEW_SN DESC");
        // 그룹당 1위만 남기고 나머지 삭제
        assertThat(sql).contains("WHERE ranked.rn > 1");

        // ❌ 회귀 가드: 상태 무시하고 최소 SN 만 보존하는 과거 자기조인이 남아있으면 안 됨.
        assertThat(sql)
                .as("검수 상태를 무시하는 'a.SN > b.SN' 최소 SN 보존 방식이 제거되어야 한다")
                .doesNotContain("a.DATA_META_REVIEW_SN > b.DATA_META_REVIEW_SN");
    }

    @Test
    @DisplayName("V79_UNIQUE_제약은_멱등하게_추가된다")
    void addsUniqueConstraintIdempotently() throws IOException {
        String sql = Files.readString(MIGRATION);
        assertThat(sql).contains("uq_ls_data_meta_review_meta_type");
        assertThat(sql).contains("UNIQUE (DATA_META_SN, META_TYPE_CD)");
        assertThat(sql).contains("IF NOT EXISTS");
        // MariaDB 전용 문법 미사용 (PostgreSQL 표준)
        assertThat(sql).doesNotContain("ENGINE=InnoDB");
        assertThat(sql).doesNotContain("utf8mb4");
    }
}
