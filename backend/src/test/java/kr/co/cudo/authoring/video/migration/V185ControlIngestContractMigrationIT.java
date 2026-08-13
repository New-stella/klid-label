package kr.co.cudo.authoring.video.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V185 — 관제 인입 규격 변경 2건 + 산출 프레임수 COMMENT 재지정 검증.
 *
 * <h3>무엇을 고정하는가</h3>
 * <ul>
 *   <li><b>{@code OG_CD} 제거</b> — 관제 회신(2026-08-12) "현행 미사용 값, 공급 불가". 컬럼이 <b>사라졌다</b>는
 *       단언이라 되살림(재추가)이 조용히 통과하지 않는다.</li>
 *   <li><b>{@code VMS_CCTV_ID} NULL 허용</b> — 관제 회신 "CCTV 식별자가 없는 영상이 존재".
 *       <b>{@code LS_DATA_INGEST}·{@code LS_DATA_RAW} 두 테이블 모두</b>를 본다. 한쪽만 풀면
 *       인입 행은 받되 원시영상 적재에서 NOT NULL 위반으로 터져 관제 요구가 실질 미충족이다.</li>
 *   <li><b>{@code LS_DATASET_EXPORT.FRME_CNT} COMMENT</b> — 산정 방식이 "원본벌+비식별벌 합계"에서
 *       "실제 프레임 수"로 바뀌었는데 V173 이 남긴 서술이 그대로였다. DB 를 직접 보는 사람에게
 *       틀린 사실을 알리는 상태를 고정으로 막는다.</li>
 * </ul>
 *
 * <p>스키마·주석만 보므로 JDBC 로 {@code information_schema}/{@code col_description} 을 직접 읽는다
 * ({@code V147DataIngestSchemaMigrationIT} · {@code V172IngestStandardTermMigrationIT} 관례).
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class V185ControlIngestContractMigrationIT {

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    @Test
    @DisplayName("인입테이블에서_기관코드_컬럼이_제거됐다")
    void 인입테이블에서_기관코드_컬럼이_제거됐다() {
        // given / when — 인입 테이블 실제 컬럼(information_schema 는 소문자로 돌려준다)
        List<String> columns = columnsOf("ls_data_ingest");

        // then — 관제가 공급하지 않기로 확정한 값이라 수신 통로 자체를 없앤다.
        //   "미송신"과 "값 없음"이 구분되지 않는 컬럼을 남겨두면 화면·업로드 폼이 그 자리를 계속 그린다.
        assertThat(columns)
                .as("OG_CD — 관제 현행 미사용·공급 불가 확정(2026-08-12)")
                .doesNotContain("og_cd");
    }

    @Test
    @DisplayName("인입과_원시영상_양쪽에서_VMS_CCTV_ID가_NULL을_허용한다")
    void 인입과_원시영상_양쪽에서_VMS_CCTV_ID가_NULL을_허용한다() {
        // given / when / then — 관제에 CCTV 식별자가 없는 영상(수동 업로드 등)이 존재한다.
        //   ★ 두 테이블을 함께 본다 — 인입만 풀면 적재에서 NOT NULL 위반으로 터진다.
        assertThat(isNullable("ls_data_ingest", "vms_cctv_id"))
                .as("LS_DATA_INGEST.VMS_CCTV_ID")
                .isTrue();
        assertThat(isNullable("ls_data_raw", "vms_cctv_id"))
                .as("LS_DATA_RAW.VMS_CCTV_ID — 여기가 막혀 있으면 인입 행은 받되 적재에서 걸러진다")
                .isTrue();
    }

    @Test
    @DisplayName("CCTV식별자_없는_인입행을_INSERT할_수_있다")
    void CCTV식별자_없는_인입행을_INSERT할_수_있다() {
        // given / when — 관제가 실제로 넣는 최소 형상에서 VMS_CCTV_ID 만 비운 행
        jdbc().update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type, cctv_nm)
                VALUES (?, NULL, 'clip.mp4', '/nas-storage/raw/clip.mp4', 'RELAY', ?)
                """, "V185-CLIP-NO-CCTV", "수동업로드-20260812.mp4");

        // then — 제약 위반 없이 적재되고, 관제가 대체 표기를 채워 보내는 CCTV_NM 이 함께 남는다.
        var row = jdbc().queryForMap(
                "SELECT vms_cctv_id, cctv_nm FROM ls_data_ingest WHERE vms_clip_id = 'V185-CLIP-NO-CCTV'");
        assertThat(row.get("vms_cctv_id")).isNull();
        assertThat(row.get("cctv_nm")).isEqualTo("수동업로드-20260812.mp4");
    }

    @Test
    @DisplayName("산출_프레임수_주석이_합계가_아니라_실제_프레임수를_말한다")
    void 산출_프레임수_주석이_합계가_아니라_실제_프레임수를_말한다() {
        // given / when — 컬럼 COMMENT 원문
        String comment = columnComment("ls_dataset_export", "frme_cnt");

        // then — V173 이 남긴 "원본벌+비식별벌 합계" 서술은 산정 방식 변경으로 사실이 아니다.
        //   이 값은 관제 datasets.img_nocs 로 그대로 나가므로 틀린 서술은 관제 해석까지 오염시킨다.
        assertThat(comment).isNotNull();
        assertThat(comment)
                .as("구 서술(합계)이 단독으로 남아 있으면 안 된다")
                .contains("합계가 아니다")
                .contains("실제 프레임 수");
    }

    // --- helpers ---

    private List<String> columnsOf(String tableName) {
        return jdbc().queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, tableName);
    }

    private boolean isNullable(String tableName, String columnName) {
        String isNullable = jdbc().queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?",
                String.class, tableName, columnName);
        return "YES".equals(isNullable);
    }

    /** 컬럼 COMMENT — {@code col_description} 은 (테이블 OID, 컬럼 순번)으로 읽는다. */
    private String columnComment(String tableName, String columnName) {
        return jdbc().queryForObject(
                "SELECT col_description(c.oid, a.attnum) FROM pg_class c "
                        + "JOIN pg_attribute a ON a.attrelid = c.oid "
                        + "WHERE c.relname = ? AND a.attname = ?",
                String.class, tableName, columnName);
    }
}
