package kr.co.cudo.authoring.common.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V14 — 외부 산출물 이관 저장소가 <b>무엇을 만들었는지</b> 실 DB 로 검증
 * (Testcontainers PostgreSQL, Flyway migrate 후 부팅).
 *
 * <h3>왜 이 시험이 따로 필요한가</h3>
 * {@code FlywaySquashBaselineIT} 는 «적용된 버전·파일 목록»만 단언하므로 V14 가 <b>돌았다</b>는 것은
 * 증명해도 «무엇을 만들었는가»는 보지 않는다. 그리고 이 프로젝트의 {@code ddl-auto=validate} 는
 * 실제로 동작하지 않아(EMF 에 설정이 전달되지 않는다) <b>기동 성공을 엔티티↔DDL 정합의 증거로 삼을 수 없다.</b>
 * 즉 이 시험이 없으면 컬럼 폭·제약·기본값이 어긋나도 그것을 잡아 줄 층이 하나도 없다.
 *
 * <h3>특히 DB 기본값을 여기서만 볼 수 있다</h3>
 * {@code DE_IDNTF_CMPTN_YN} 의 애플리케이션 기본값은 엔티티 필드 초기화가 담당하고 통합시험이 그 값을
 * 단언한다. 그런데 그 경로는 <b>JPA 가 명시로 써 넣은 값</b>을 다시 읽는 것이라, INSERT 가 이 컬럼을
 * 생략하는 경로(관제 직접 INSERT·수기 SQL·백필)에서 무엇이 들어가는지는 증명하지 못한다.
 * 그 축을 보는 것은 {@code column_default} 뿐이다.
 */
@SpringBootTest
@ActiveProfiles("local")
class ExternalImportTablesMigrationIT {

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    private Map<String, Object> column(String table, String column) {
        List<Map<String, Object>> rows = jdbc().queryForList(
                "SELECT data_type, character_maximum_length, numeric_precision, is_nullable, column_default "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                table, column);
        assertThat(rows).as("%s.%s 컬럼이 존재해야 한다", table, column).hasSize(1);
        return rows.get(0);
    }

    /** 제약 정의를 원문 그대로 받는다 — FK 의 삭제 규칙(RESTRICT/SET NULL)까지 한 문자열로 확인하기 위해. */
    private String constraintDef(String name) {
        List<String> defs = jdbc().queryForList(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = ? AND connamespace = current_schema()::regnamespace",
                String.class, name);
        assertThat(defs).as("제약 %s 가 존재해야 한다", name).hasSize(1);
        return defs.get(0);
    }

    private String indexDef(String name) {
        List<String> defs = jdbc().queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
                String.class, name);
        assertThat(defs).as("인덱스 %s 가 존재해야 한다", name).hasSize(1);
        return defs.get(0);
    }

    private String comment(String table, String column) {
        return jdbc().queryForObject(
                "SELECT col_description(to_regclass(?), "
                        + "  (SELECT attnum FROM pg_attribute WHERE attrelid = to_regclass(?) AND attname = ?))",
                String.class, table, table, column);
    }

    // ------------------------------------------------------------------ 승인 보류 플래그

    @Test
    @DisplayName("비식별화완료여부는_한자리_문자_NOT_NULL_이고_DB기본값이_Y_라_생략_INSERT_도_승인을_막지_않는다")
    void deidentCompletionFlagHasSafeDefault() {
        Map<String, Object> c = column("ls_raw_data_status", "de_idntf_cmptn_yn");

        // 표준도메인 여부C1 — 폭이 넓어지면 'Y'/'N' 밖의 값이 조용히 들어온다.
        assertThat(c.get("data_type")).isEqualTo("character");
        assertThat(((Number) c.get("character_maximum_length")).intValue()).isEqualTo(1);
        assertThat(c.get("is_nullable")).isEqualTo("NO");

        // ★ 기본값이 'Y' 라는 것이 곧 «이 경로와 무관한 영상은 영향을 받지 않는다»의 DB 쪽 보장이다.
        //   'N' 으로 바뀌면 이 컬럼을 모르는 모든 INSERT 경로의 영상이 검수 승인에서 막힌다.
        assertThat(String.valueOf(c.get("column_default"))).contains("'Y'");

        assertThat(comment("ls_raw_data_status", "de_idntf_cmptn_yn"))
                .as("컬럼 주석 — 왜 기본값이 Y 인지가 DB 에도 남아 있어야 한다")
                .isNotBlank();
    }

    // ------------------------------------------------------------------ 외부 분류 대응

    @Test
    @DisplayName("외부_분류_대응_테이블의_컬럼_폭이_표준도메인과_일치한다")
    void categoryMappingColumnsMatchStandardDomains() {
        String t = "ls_otsd_ctgry_mpng";

        assertThat(((Number) column(t, "mpng_knd_cd").get("character_maximum_length")).intValue()).isEqualTo(20);
        assertThat(((Number) column(t, "otsd_ctgry_cd").get("character_maximum_length")).intValue()).isEqualTo(20);
        assertThat(((Number) column(t, "otsd_ctgry_nm").get("character_maximum_length")).intValue()).isEqualTo(200);
        assertThat(((Number) column(t, "reg_id").get("character_maximum_length")).intValue()).isEqualTo(30);
        assertThat(((Number) column(t, "mdfr_id").get("character_maximum_length")).intValue()).isEqualTo(30);

        // 대응이 아직 확정되지 않은 외부 분류를 그대로 담을 수 있어야 한다(미연결 허용).
        assertThat(column(t, "lbl_id").get("is_nullable")).isEqualTo("YES");
        assertThat(column(t, "evnt_type_cd").get("is_nullable")).isEqualTo("YES");
        // 키 축은 비울 수 없다 — 비면 UK 가 중복을 못 막는다.
        assertThat(column(t, "mpng_knd_cd").get("is_nullable")).isEqualTo("NO");
        assertThat(column(t, "otsd_ctgry_cd").get("is_nullable")).isEqualTo("NO");
    }

    @Test
    @DisplayName("분류_대응은_종류_외부코드_쌍으로_유일하고_참조하는_마스터가_지워지지_않는다")
    void categoryMappingConstraints() {
        assertThat(constraintDef("ls_otsd_ctgry_mpng_pkey")).isEqualTo("PRIMARY KEY (mpng_sn)");

        // ★ 같은 외부 코드가 두 번 대응되면 어느 분류로 적재될지가 실행마다 달라진다.
        assertThat(indexDef("uk_ls_otsd_ctgry_mpng"))
                .contains("UNIQUE").contains("mpng_knd_cd").contains("otsd_ctgry_cd");

        // ★ RESTRICT 여야 한다 — CASCADE 면 라벨 마스터를 지울 때 대응이 조용히 사라져
        //   그 뒤의 이관이 «대응 없음» 으로 흘러가고, SET NULL 이면 이미 대응된 이력이 끊긴다.
        assertThat(constraintDef("fk_ls_otsd_ctgry_mpng_lbl"))
                .contains("REFERENCES ls_label(lbl_id)").contains("ON DELETE RESTRICT");
        assertThat(constraintDef("fk_ls_otsd_ctgry_mpng_evnt_type"))
                .contains("REFERENCES ls_evnt_type(evnt_type_cd)").contains("ON DELETE RESTRICT");
    }

    // ------------------------------------------------------------------ 이관 이력

    @Test
    @DisplayName("이관_이력_테이블의_컬럼_폭이_표준도메인과_일치하고_실패사유를_담을_수_있다")
    void transferHistoryColumns() {
        String t = "ls_otsd_datst_trnsf_hstry";

        assertThat(((Number) column(t, "orgnl_fldr_path_nm").get("character_maximum_length")).intValue()).isEqualTo(300);
        assertThat(((Number) column(t, "orgnl_fldr_nm").get("character_maximum_length")).intValue()).isEqualTo(300);
        assertThat(((Number) column(t, "otsd_datst_id").get("character_maximum_length")).intValue()).isEqualTo(50);
        assertThat(((Number) column(t, "trnsf_stts_cd").get("character_maximum_length")).intValue()).isEqualTo(20);
        assertThat(((Number) column(t, "fail_rsn").get("character_maximum_length")).intValue()).isEqualTo(1000);

        // 어디서 가져왔는지와 결과가 무엇인지는 비울 수 없다 — 비면 이력으로서 쓸모가 없다.
        assertThat(column(t, "orgnl_fldr_path_nm").get("is_nullable")).isEqualTo("NO");
        assertThat(column(t, "trnsf_stts_cd").get("is_nullable")).isEqualTo("NO");
        // 실패한 이관은 만들어진 영상이 없다.
        assertThat(column(t, "raw_sn").get("is_nullable")).isEqualTo("YES");
    }

    @Test
    @DisplayName("이관_이력은_영상이_지워져도_남는다")
    void transferHistorySurvivesVideoDeletion() {
        // ★ SET NULL 이어야 한다 — CASCADE 면 «무엇을 가져왔다가 어떻게 됐는지» 의 감사 기록이
        //   영상 삭제와 함께 사라진다. 이력의 존재 이유가 바로 그 추적이다.
        assertThat(constraintDef("fk_ls_otsd_datst_trnsf_hstry_raw"))
                .contains("REFERENCES ls_data_raw(raw_sn)").contains("ON DELETE SET NULL");

        assertThat(indexDef("ix_ls_otsd_datst_trnsf_hstry_raw")).contains("raw_sn");
        assertThat(indexDef("ix_ls_otsd_datst_trnsf_hstry_fldr"))
                .contains("orgnl_fldr_nm").contains("otsd_datst_id");
    }

    // ------------------------------------------------------------------ 식별자 채번

    @Test
    @DisplayName("두_테이블의_식별자는_DB가_채번한다")
    void primaryKeysAreGenerated() {
        // 애플리케이션이 PK 를 만들면 2노드 동시 이관에서 충돌한다.
        for (String[] tc : new String[][]{
                {"ls_otsd_ctgry_mpng", "mpng_sn"},
                {"ls_otsd_datst_trnsf_hstry", "trnsf_sn"}}) {
            String identity = jdbc().queryForObject(
                    "SELECT attidentity FROM pg_attribute "
                            + "WHERE attrelid = to_regclass(?) AND attname = ?",
                    String.class, tc[0], tc[1]);
            assertThat(identity).as("%s.%s 는 IDENTITY 여야 한다", tc[0], tc[1]).isNotBlank();
        }
    }
}
