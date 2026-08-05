package kr.co.cudo.authoring.dataset.export.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V173 — 산출 원장({@code LS_DATASET_EXPORT}) 표준용어 정합(@req R3) + 데이터구축용량 신설(@req R4) 검증.
 *
 * <h3>무엇을 고정하는가</h3>
 * <ul>
 *   <li><b>물리명 5건</b>(@req R3) — {@code EXPORT_SN→OUTPUT_SN} · {@code EXPORT_PATH_NM→OUTPUT_PATH_NM}
 *       · {@code EXPORT_STTS_CD→OUTPUT_STTS_CD} · {@code EXPORT_VER_NO→OUTPUT_VER_NO} ·
 *       {@code FRAME_CNT→FRME_CNT}. <b>구 이름이 사라졌다</b>는 단언을 함께 둔다 — rename 이 아니라
 *       컬럼 추가로 처리하면 두 축이 생기고, 되돌림이 조용히 통과한다.</li>
 *   <li><b>신설 컬럼</b>(@req R4) — {@code DATA_ETBL_CPCT BIGINT}(표준도메인 수B20), nullable.</li>
 *   <li><b>테이블명 불변</b> — {@code LS_DATASET_EXPORT} 는 그대로다(컬럼만 정정).</li>
 *   <li><b>동명이표 오염 가드</b> — 전역 치환이 무관한 테이블/과거 마이그레이션까지 바꾸지 않았는지
 *       확인한다(계획 위험#1 — HIGH).</li>
 * </ul>
 *
 * <p>스키마만 보므로 JDBC 로 {@code information_schema} 를 직접 읽는다
 * ({@code V172IngestStandardTermMigrationIT} 관례).
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class V173DatasetExportStandardTermMigrationIT {

    private static final String TABLE = "ls_dataset_export";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    @Test
    @DisplayName("export_원장_컬럼이_표준물리명으로_변경됨")
    void export_원장_컬럼이_표준물리명으로_변경됨() {
        // given / when — 산출 원장의 실제 컬럼 목록(information_schema 는 소문자로 돌려준다)
        List<String> columns = columnsOf(TABLE);

        // then — 표준 물리명 5종이 존재한다(@req R3 — 산출물=OUTPUT · 프레임=FRME)
        assertThat(columns)
                .as("@req R3 — EXPORT 는 표준 미등록(수출 EXP 의 영문일 뿐)이라 산출물=OUTPUT 으로 정정")
                .contains("output_sn", "output_path_nm", "output_stts_cd", "output_ver_no", "frme_cnt");

        // then — 구 비표준 물리명은 사라졌다(되돌림 방지)
        assertThat(columns)
                .as("구 비표준 물리명 잔존 — rename 이 아니라 컬럼 추가로 처리하면 두 축이 생긴다")
                .doesNotContain("export_sn", "export_path_nm", "export_stts_cd", "export_ver_no", "frame_cnt");

        // then — 테이블명은 바꾸지 않았다(뷰·FK·인덱스 연쇄를 만들지 않기 위한 확정 방침)
        assertThat(columns).as("LS_DATASET_EXPORT 테이블 자체가 사라졌다").isNotEmpty();
    }

    @Test
    @DisplayName("DATA_ETBL_CPCT_컬럼이_BIGINT_로_추가됨")
    void DATA_ETBL_CPCT_컬럼이_BIGINT_로_추가됨() {
        // given / when — 데이터구축용량(@req R4). 사업표준용어 "데이터구축용량"=DATA_ETBL_CPCT,
        //   표준도메인 수B20(BIGINT). 관제 dataset_versions.data_etbl_cpct 와 물리명이 일치한다.
        Map<String, Object> meta = jdbc().queryForMap(
                "SELECT data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = 'data_etbl_cpct'", TABLE);

        // then — 타입은 표준도메인대로 BIGINT 이고, 미산출을 표현할 수 있도록 nullable 이다.
        assertThat(meta.get("data_type")).as("표준도메인 수B20").isEqualTo("bigint");
        assertThat(meta.get("is_nullable"))
                .as("용량 산출이 실패해도 export 는 성공으로 종결한다 — NULL 이 '미산출'이다")
                .isEqualTo("YES");
    }

    @Test
    @DisplayName("PK와_UK가_새_컬럼명을_참조하도록_자동_갱신됨")
    void PK와_UK가_새_컬럼명을_참조하도록_자동_갱신됨() {
        // given / when — 제약명에는 컬럼명이 없으므로 재생성하지 않았다. 참조 컬럼만 PostgreSQL 이 추종한다.
        List<String> pkColumns = constraintColumns("PRIMARY KEY");
        List<String> ukColumns = constraintColumns("UNIQUE");

        // then — PK = OUTPUT_SN, UK = (DATA_RAW_SN, OUTPUT_VER_NO)
        assertThat(pkColumns).containsExactly("output_sn");
        assertThat(ukColumns).containsExactlyInAnyOrder("data_raw_sn", "output_ver_no");
    }

    @Test
    @DisplayName("표준물리명_컬럼으로_산출행을_읽고_쓸_수_있다")
    void 표준물리명_컬럼으로_산출행을_읽고_쓸_수_있다() {
        // given — FK(V146: LS_DATASET_EXPORT → LS_DATA_RAW) 충족용 부모 영상 1건
        Long rawSn = jdbc().queryForObject("""
                INSERT INTO ls_data_raw (vms_clip_id, vms_cctv_id, evnt_type_cd, lclgv_cd,
                                         prvc_type_cd, prvc_yn, de_ident_yn, raw_file_path_nm,
                                         sht_dt, vdo_len_sec, data_stts_cd, reg_dt)
                VALUES (?, 'CCTV-V173', 'EVT01', '1111000000', 'PRVC', 'Y', 'Y',
                        '/nas-storage/raw/v173.mp4', now(), 30, 'COMPLETED', now())
                RETURNING raw_sn
                """, Long.class, "V173-CLIP-" + System.nanoTime());

        // when — IDENTITY(구 시퀀스 rename 대상)로 PK 가 채번되고 새 물리명으로 값이 왕복한다
        jdbc().update("""
                INSERT INTO ls_dataset_export
                    (data_raw_sn, output_ver_no, output_path_nm, output_stts_cd, frme_cnt,
                     data_etbl_cpct, reg_dt)
                VALUES (?, 1, '/nas-storage/raw/v173', 'SUCCEEDED', 4, 20480, now())
                """, rawSn);
        Map<String, Object> row = jdbc().queryForMap(
                "SELECT output_sn, output_ver_no, output_path_nm, output_stts_cd, frme_cnt, data_etbl_cpct "
                        + "FROM ls_dataset_export WHERE data_raw_sn = ?", rawSn);

        // then — IDENTITY 채번 유지 + 값 왕복(rename 은 무손실이며 시퀀스 rename 이 채번을 깨지 않는다)
        assertThat(((Number) row.get("output_sn")).longValue()).isPositive();
        assertThat(((Number) row.get("output_ver_no")).intValue()).isEqualTo(1);
        assertThat(row.get("output_path_nm")).isEqualTo("/nas-storage/raw/v173");
        assertThat(row.get("output_stts_cd")).isEqualTo("SUCCEEDED");
        assertThat(((Number) row.get("frme_cnt")).intValue()).isEqualTo(4);
        assertThat(((Number) row.get("data_etbl_cpct")).longValue()).isEqualTo(20480L);
    }

    @Test
    @DisplayName("뷰_출력컬럼명은_rename_을_따라가지_않는다 — 관제 연동 계약면 무변경")
    void 뷰_출력컬럼명은_rename_을_따라가지_않는다() {
        // given / when — PostgreSQL 은 RENAME COLUMN 시 뷰 <본문>만 새 컬럼으로 추종하고,
        //   <출력 컬럼명>은 자동 별칭으로 보존한다. 즉 V173 은 관제가 SELECT 하는 이름을 바꾸지 않았다.
        List<String> viewColumns = columnsOf("v_completed_video");
        String viewDef = jdbc().queryForObject(
                "SELECT pg_get_viewdef('v_completed_video'::regclass, true)", String.class);

        // then — 출력명은 <구 이름 그대로>다(뷰 출력명 정합은 후속 라운드의 명시 재작성이 담당).
        assertThat(viewColumns)
                .as("뷰 출력명이 바뀌면 관제 쿼리가 예고 없이 깨진다 — 이 Phase 의 범위가 아니다")
                .contains("export_path_nm", "frame_cnt", "export_stts_cd");
        assertThat(viewColumns).doesNotContain("output_path_nm", "frme_cnt", "output_stts_cd");

        // and — 본문은 새 물리명을 참조한다(rename 이 뷰를 깨지 않았다는 증거).
        assertThat(viewDef).contains("output_path_nm").contains("frme_cnt").contains("output_stts_cd");
    }

    // ── 동명이표 오염 가드 (계획 위험#1 — HIGH). 전역 sed 금지의 회귀 방어. ─────────────────

    @Test
    @DisplayName("LS_MON_NOTI_ACML_의_EXPORT_RPRCS_YN_는_변경되지_않음")
    void LS_MON_NOTI_ACML_의_EXPORT_RPRCS_YN_는_변경되지_않음() {
        // 현재 DB 에 실재하는 유일한 동명이표(V144). 전역 치환으로 함께 바뀌면 여기서 죽는다.
        List<String> columns = columnsOf("ls_mon_noti_acml");
        assertThat(columns).contains("export_rprcs_yn");
        assertThat(columns)
                .as("전역 치환으로 무관 테이블이 함께 바뀌었다")
                .doesNotContain("output_rprcs_yn");
    }

    @Test
    @DisplayName("LS_DATA_SET_의_EXPORT_SN_과_EXPORT_STTS_CD_는_변경되지_않음")
    void LS_DATA_SET_의_EXPORT_SN_과_EXPORT_STTS_CD_는_변경되지_않음() {
        // 이 테이블은 V86 에서 DROP 됐지만 <과거 마이그레이션 SQL 원문>은 남아 있고, 신규 설치는 그
        // 원문을 그대로 재생한다. 전역 sed 가 V8 까지 바꾸면 재생 결과가 어긋나므로 원문을 고정한다.
        String v8 = migrationSql("V8__add_ls_data_set.sql");
        assertThat(v8).contains("EXPORT_SN").contains("EXPORT_STTS_CD");
        assertThat(v8)
                .as("과거 마이그레이션이 전역 치환으로 오염됐다 — 신규 설치 재생 결과가 달라진다")
                .doesNotContain("OUTPUT_SN").doesNotContain("OUTPUT_STTS_CD");

        // 런타임에도 이 테이블은 부재해야 한다(V86 DROP 유지 — 되살아나면 동명이표가 실재하게 된다).
        assertThat(columnsOf("ls_data_set")).isEmpty();
    }

    @Test
    @DisplayName("LS_RESOLUTION_EXPORT_의_FRAME_CNT_는_변경되지_않음")
    void LS_RESOLUTION_EXPORT_의_FRAME_CNT_는_변경되지_않음() {
        // V126 에서 DROP 된 테이블. 위와 같은 이유로 과거 마이그레이션 원문을 고정한다.
        // (PK 는 V55 시점에 RES_EXPORT_SN 이었고 V123 에서 RESL_EXPORT_SN 으로 개명됐다.)
        String v55 = migrationSql("V55__create_ls_resolution_export.sql");
        assertThat(v55).contains("FRAME_CNT").contains("RES_EXPORT_SN");
        assertThat(v55)
                .as("과거 마이그레이션이 전역 치환으로 오염됐다")
                .doesNotContain("FRME_CNT");
        assertThat(migrationSql("V123__rename_res_to_standard_resl_resp.sql"))
                .as("V123 의 해상도 산출 PK 개명 원문")
                .contains("RESL_EXPORT_SN");

        assertThat(columnsOf("ls_resolution_export")).isEmpty();
    }

    @Test
    @DisplayName("LS_DATA_INGEST_의_FRME_CNT_는_이번_변경과_무관하게_유지됨")
    void LS_DATA_INGEST_의_FRME_CNT_는_이번_변경과_무관하게_유지됨() {
        // 같은 표준약어(FRME)를 쓰는 <다른> 테이블(V172). 이번 rename 이 이쪽을 건드리지 않았는지 확인.
        assertThat(columnsOf("ls_data_ingest")).contains("frme_cnt");
    }

    // --- helpers ---

    private List<String> columnsOf(String tableName) {
        return jdbc().queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, tableName);
    }

    /** 이 테이블의 지정 제약 유형이 참조하는 컬럼 목록. */
    private List<String> constraintColumns(String constraintType) {
        return jdbc().queryForList("""
                SELECT kcu.column_name
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = tc.constraint_name
                   AND kcu.table_name = tc.table_name
                 WHERE tc.table_name = ? AND tc.constraint_type = ?
                """, String.class, TABLE, constraintType);
    }

    /** 마이그레이션 SQL 원문(클래스패스). */
    private static String migrationSql(String fileName) {
        try {
            return new String(new ClassPathResource("db/migration/" + fileName)
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("마이그레이션 원문 읽기 실패: " + fileName, e);
        }
    }
}
