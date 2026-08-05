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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V172 — 인입 테이블({@code LS_DATA_INGEST}) 표준용어·표준도메인 정합 검증 (@req R1 · R2).
 *
 * <h3>무엇을 고정하는가</h3>
 * <ul>
 *   <li><b>물리명 4건</b>(@req R1) — {@code PROC_STTS_CD→PRCS_STTS_CD} ·
 *       {@code NEXT_RTRY_DT→NXTM_RTRY_DT} · {@code RGN_NM→LCLGV_NM} · {@code FRM_CNT→FRME_CNT}.
 *       <b>구 이름이 사라졌다</b>는 단언을 함께 둔다 — 되돌림(rename 취소)이 조용히 통과하지 않게.
 *       <p>⚠ 재시도 컬럼의 <b>최종 물리명은 {@code NXTM_RTRY_DT}(V175)</b> 다. V172 는 표준 우선순위를
 *       거꾸로(사업→행안부) 적용해 {@code NXTM_RTY_DT}(사업 전용 약어 RTY)로 바꿨고, V175 가
 *       행안부 공통표준 {@code RTRY} 로 재정정했다. 이 테스트는 <b>지금 스키마</b>를 고정하므로
 *       중간 이름({@code nxtm_rty_dt})이 남아 있으면 실패한다.</li>
 *   <li><b>표준도메인 2건</b>(@req R2) — {@code EVNT_CLSF_CD} 코드C2({@code CHAR(2)}) ·
 *       {@code EVNT_CTGRY_CD} 코드C4({@code CHAR(4)}). {@code LCLGV_NM} 은 명V100.</li>
 *   <li><b>부분 인덱스</b> — 술어와 {@code INCLUDE} 에 컬럼명이 박혀 있어 rename 만으로는 갱신되지
 *       않는다. 새 이름으로 재생성됐는지 {@code pg_indexes.indexdef} 로 확인한다.</li>
 *   <li><b>동명이표 오염 가드</b> — {@code PROC_STTS_CD} 는 {@code LS_DEIDENT_PROC_LOG} 에도,
 *       {@code NEXT_RTRY_DT} 는 {@code LS_CONTROL_NOTIFY_FALLBACK} 에도 있다. 전역 치환으로
 *       무관한 테이블이 함께 바뀌면 여기서 죽는다(계획 위험#1 — HIGH).</li>
 * </ul>
 *
 * <p>스키마만 보므로 JDBC 로 {@code information_schema} 를 직접 읽는다
 * ({@code V147DataIngestSchemaMigrationIT} 관례).
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class V172IngestStandardTermMigrationIT {

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    @Test
    @DisplayName("인입_컬럼이_표준물리명으로_변경됨")
    void 인입_컬럼이_표준물리명으로_변경됨() {
        // given / when — 인입 테이블의 실제 컬럼 목록(information_schema 는 소문자로 돌려준다)
        List<String> columns = ingestColumns();

        // then — 표준 물리명 4종이 존재한다
        assertThat(columns)
                .as("@req R1 — 표준용어 조합(처리=PRCS · 차기=NXTM/재시도=RTRY · 지방자치단체=LCLGV · 프레임=FRME)")
                .contains("prcs_stts_cd", "nxtm_rtry_dt", "lclgv_nm", "frme_cnt");

        // then — 구 비표준 물리명은 사라졌다(되돌림 방지 — 이 단언이 없으면 컬럼 추가만으로도 통과한다)
        //   nxtm_rty_dt 는 V172 의 <중간 이름>이다. 표준 우선순위(①행안부→②사업) 재판정으로 V175 가
        //   행안부 공통표준 RTRY 로 바꿨으므로 이 이름도 남아 있으면 안 된다.
        assertThat(columns)
                .as("구 비표준 물리명 잔존 — rename 이 아니라 컬럼 추가로 처리하면 두 축이 생긴다")
                .doesNotContain("proc_stts_cd", "next_rtry_dt", "rgn_nm", "frm_cnt", "nxtm_rty_dt");
    }

    @Test
    @DisplayName("EVNT_CLSF_CD_는_CHAR2_EVNT_CTGRY_CD_는_CHAR4")
    void EVNT_CLSF_CD_는_CHAR2_EVNT_CTGRY_CD_는_CHAR4() {
        // given / when / then — 표준도메인 코드C2 / 코드C4 (@req R2).
        //   information_schema 는 CHAR(n) 을 'character' 로 돌려준다(VARCHAR 는 'character varying').
        assertColumnType("evnt_clsf_cd", "character", 2);
        assertColumnType("evnt_ctgry_cd", "character", 4);
    }

    @Test
    @DisplayName("LCLGV_NM_은_VARCHAR100")
    void LCLGV_NM_은_VARCHAR100() {
        // given / when / then — 표준도메인 명V100. 관제 datasets.lclgv_nm 과 물리명·길이까지 일치한다.
        assertColumnType("lclgv_nm", "character varying", 100);
    }

    @Test
    @DisplayName("PENDING_폴링_부분인덱스가_새_컬럼명으로_재생성됨")
    void PENDING_폴링_부분인덱스가_새_컬럼명으로_재생성됨() {
        // given / when — 폴링 전용 부분 인덱스 정의
        List<String> defs = jdbc().queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'ls_data_ingest' "
                        + "AND indexname = 'ix_ls_data_ingest_poll'",
                String.class);

        // then — 실재하고, 술어·INCLUDE 가 <새 컬럼명>이다.
        //   RENAME COLUMN 만으로도 PostgreSQL 이 인덱스 정의를 추종하지만, DROP+CREATE 를 명시해
        //   V147 정의와 어긋나지 않게 고정한다(계획 위험#4).
        assertThat(defs).as("IX_LS_DATA_INGEST_POLL").hasSize(1);
        assertThat(defs.get(0))
                .contains("rcptn_dt")
                .contains("rcptn_sn")
                .contains("nxtm_rtry_dt")
                .contains("prcs_stts_cd")
                .contains("'PENDING'")
                .doesNotContain("proc_stts_cd")
                .doesNotContain("next_rtry_dt");
    }

    @Test
    @DisplayName("LS_DEIDENT_PROC_LOG_의_PROC_STTS_CD_는_변경되지_않음")
    void LS_DEIDENT_PROC_LOG_의_PROC_STTS_CD_는_변경되지_않음() {
        // 동명이표 오염 가드(계획 위험#1 — HIGH). 이 컬럼은 V_COMPLETED_VIDEO 뷰 본문에
        // pl.PROC_STTS_CD='SUCCEEDED' 로 등장하므로, 함께 바뀌면 뷰가 조용히 깨진다.
        List<String> columns = columnsOf("ls_deident_proc_log");
        assertThat(columns).contains("proc_stts_cd");
        assertThat(columns)
                .as("전역 치환으로 무관 테이블이 함께 바뀌었다")
                .doesNotContain("prcs_stts_cd");
    }

    @Test
    @DisplayName("LS_CONTROL_NOTIFY_FALLBACK_의_NEXT_RTRY_DT_는_변경되지_않음")
    void LS_CONTROL_NOTIFY_FALLBACK_의_NEXT_RTRY_DT_는_변경되지_않음() {
        // 동명이표 오염 가드(V44 — IDX_LCNF_STATUS 인덱스가 이 컬럼을 키로 쓴다).
        List<String> columns = columnsOf("ls_control_notify_fallback");
        assertThat(columns).contains("next_rtry_dt");
        assertThat(columns)
                .as("전역 치환으로 무관 테이블이 함께 바뀌었다")
                .doesNotContain("nxtm_rty_dt", "nxtm_rtry_dt");
    }

    @Test
    @DisplayName("표준물리명_컬럼으로_인입행을_읽고_쓸_수_있다")
    void 표준물리명_컬럼으로_인입행을_읽고_쓸_수_있다() {
        // given — 관제가 넣는 최소 형상 + 이번에 이름·타입이 바뀐 4컬럼
        jdbc().update("""
                INSERT INTO ls_data_ingest
                    (vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm, src_type,
                     lclgv_nm, frme_cnt, evnt_clsf_cd, evnt_ctgry_cd)
                VALUES (?, 'CCTV-001', 'clip.mp4', '/nas-storage/raw/clip.mp4', 'RELAY',
                     '서울특별시 동대문구', 900, '02', '0002')
                """, "V172-CLIP-001");

        // when
        Map<String, Object> row = jdbc().queryForMap(
                "SELECT prcs_stts_cd, nxtm_rtry_dt, lclgv_nm, frme_cnt, evnt_clsf_cd, evnt_ctgry_cd "
                        + "FROM ls_data_ingest WHERE vms_clip_id = 'V172-CLIP-001'");

        // then — DEFAULT·값 왕복이 새 물리명으로 그대로 성립한다
        assertThat(row.get("prcs_stts_cd")).as("DEFAULT 'PENDING' 이 rename 후에도 유지된다").isEqualTo("PENDING");
        assertThat(row.get("nxtm_rtry_dt")).isNull();
        assertThat(row.get("lclgv_nm")).isEqualTo("서울특별시 동대문구");
        assertThat(((Number) row.get("frme_cnt")).intValue()).isEqualTo(900);
        // CHAR(n) 은 고정 길이라 값이 정확히 n 자여야 패딩이 생기지 않는다(실제 값은 2자/4자).
        assertThat(row.get("evnt_clsf_cd")).isEqualTo("02");
        assertThat(row.get("evnt_ctgry_cd")).isEqualTo("0002");
    }

    // --- helpers ---

    private List<String> ingestColumns() {
        return columnsOf("ls_data_ingest");
    }

    private List<String> columnsOf(String tableName) {
        return jdbc().queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, tableName);
    }

    private void assertColumnType(String column, String dataType, int length) {
        Map<String, Object> meta = jdbc().queryForMap(
                "SELECT data_type, character_maximum_length FROM information_schema.columns "
                        + "WHERE table_name = 'ls_data_ingest' AND column_name = ?", column);
        assertThat(meta.get("data_type")).as("%s 타입", column).isEqualTo(dataType);
        assertThat(((Number) meta.get("character_maximum_length")).intValue())
                .as("%s 길이", column).isEqualTo(length);
    }
}
