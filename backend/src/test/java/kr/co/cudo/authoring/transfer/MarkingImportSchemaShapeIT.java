package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.transfer.entity.LsEblcUldJobArtcl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 일괄 적재 원장 두 표의 <b>실제 스키마 형상</b>을 설계와 대조한다(ERD-032).
 *
 * <h3>★ 왜 이 시험이 있어야 하는가 — 실제 결함이 어떤 시험에도 걸리지 않았다</h3>
 * <p>두 표를 만든 마이그레이션의 초안이 외래키의 <b>지움 규칙을 정반대로</b> 썼다
 * ({@code RAW_SN} 을 {@code SET NULL} 이 아니라 {@code CASCADE} 로). 그 결함은 시험이 아니라
 * <b>설계 문서 대조</b>로 잡혔다 — 그때는 두 표에 엔티티가 없어 매핑 검증이 닿지 않았고 형상 시험도
 * 없었기 때문이다.
 * <p>엔티티가 생긴 지금 매핑 검증이 <b>컬럼</b>은 덮는다. 그러나 <b>인덱스와 외래키의 지움 규칙</b>은
 * 덮지 않는다 — 그 둘은 엔티티에 적히지 않기 때문이다. 그래서 여기서 직접 카탈로그를 읽는다.
 *
 * <h3>지움 규칙이 왜 이 표에서 특별한가</h3>
 * <p>항목은 <b>작업의 부품</b>이라 작업이 사라지면 함께 사라져야 하지만, <b>영상보다는 오래 살아야</b>
 * 한다. 이 표는 영상에 종속된 데이터가 아니라 작업 이력이다. 영상과 함께 지우면
 * ①무엇이 왜 실패했는지가 감사 기록째 사라지고 ②부모의 성공·실패 건수는 남는데 항목 행만 없어져
 * <b>집계와 건별 목록이 서로 다른 말</b>을 한다.
 *
 * <h3>{@code @SpringBootTest} 설정은 다른 이관 통합 시험과 글자 그대로 같다</h3>
 * <p>다르게 쓰면 캐시되는 스프링 컨텍스트가 하나 더 늘어난다. 이 시험에는 웹 계층이 필요 없지만
 * 그 설정을 덜어 내는 것만으로도 <b>다른 컨텍스트</b>가 되므로 그대로 맞춘다.
 *
 * @design DOMAIN-017
 * @design ERD-032
 * @design AC-1033
 */
@SpringBootTest(properties = "authoring.storage.external-read-roots=../docs")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MarkingImportSchemaShapeIT {

    private static final String JOB = "ls_eblc_uld_job";
    private static final String ARTCL = "ls_eblc_uld_job_artcl";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
    }

    // ------------------------------------------------------------------ 컬럼

    @Test
    @DisplayName("작업_표의_컬럼_이름과_자료형과_폭이_설계와_일치한다")
    void 작업_표의_컬럼_이름과_자료형과_폭이_설계와_일치한다() {
        Map<String, String> columns = columnsOf(JOB);

        assertThat(columns).containsOnlyKeys(
                "eblc_uld_job_sn", "orgnl_fldr_path_nm", "job_stts_cd",
                "trgt_nocs", "scs_nocs", "fail_nocs", "dmnd_cn",
                "bgng_dt", "cmptn_dt", "reg_id", "reg_dt", "mdfcn_dt");

        assertThat(columns.get("eblc_uld_job_sn")).isEqualTo("bigint");
        // 폴더경로명 표준 도메인 — 폭을 좁히면 깊은 폴더의 위치를 담지 못한다.
        assertThat(columns.get("orgnl_fldr_path_nm")).isEqualTo("character varying(300)");
        // 코드값 표준 도메인.
        assertThat(columns.get("job_stts_cd")).isEqualTo("character varying(20)");
        assertThat(columns.get("trgt_nocs")).isEqualTo("integer");
        assertThat(columns.get("scs_nocs")).isEqualTo("integer");
        assertThat(columns.get("fail_nocs")).isEqualTo("integer");
        // 사람이 지정한 값을 담는 자리 — 항목이 늘 수 있어 폭을 두지 않는다.
        assertThat(columns.get("dmnd_cn")).isEqualTo("text");
        assertThat(columns.get("reg_id")).isEqualTo("character varying(30)");
        assertThat(columns.get("reg_dt")).isEqualTo("timestamp without time zone");
    }

    @Test
    @DisplayName("항목_표의_컬럼_이름과_자료형과_폭이_설계와_일치한다")
    void 항목_표의_컬럼_이름과_자료형과_폭이_설계와_일치한다() {
        Map<String, String> columns = columnsOf(ARTCL);

        assertThat(columns).containsOnlyKeys(
                "eblc_uld_job_artcl_sn", "eblc_uld_job_sn", "mark_file_path_nm", "vdo_file_path_nm",
                "artcl_stts_cd", "raw_sn", "fail_rsn", "rtry_nmtm",
                "bgng_dt", "cmptn_dt", "reg_dt", "mdfcn_dt");

        assertThat(columns.get("mark_file_path_nm")).isEqualTo("character varying(300)");
        assertThat(columns.get("vdo_file_path_nm")).isEqualTo("character varying(300)");
        assertThat(columns.get("artcl_stts_cd")).isEqualTo("character varying(20)");
        assertThat(columns.get("raw_sn")).isEqualTo("bigint");
        assertThat(columns.get("fail_rsn"))
                .isEqualTo("character varying(" + LsEblcUldJobArtcl.FAIL_RSN_MAX + ")");
        assertThat(columns.get("rtry_nmtm")).isEqualTo("integer");
    }

    @Test
    @DisplayName("짝을_찾지_못한_항목을_담을_수_있게_영상_경로와_영상_일련번호가_비어_있을_수_있다")
    void 짝을_찾지_못한_항목을_담을_수_있게_영상_경로와_영상_일련번호가_비어_있을_수_있다() {
        // 짝을 찾지 못한 항목도 <행으로 남아야> 사람이 무엇이 왜 빠졌는지 되짚을 수 있다.
        assertThat(isNullable(ARTCL, "vdo_file_path_nm")).isTrue();
        assertThat(isNullable(ARTCL, "raw_sn")).isTrue();
        assertThat(isNullable(ARTCL, "fail_rsn")).isTrue();
        // 반면 어느 작업의 어느 문서인지는 비울 수 없다 — 비면 그 행이 무엇인지 알 수 없다.
        assertThat(isNullable(ARTCL, "eblc_uld_job_sn")).isFalse();
        assertThat(isNullable(ARTCL, "mark_file_path_nm")).isFalse();
        assertThat(isNullable(ARTCL, "artcl_stts_cd")).isFalse();
        assertThat(isNullable(ARTCL, "rtry_nmtm")).isFalse();
    }

    @Test
    @DisplayName("집계_컬럼은_비지_않고_0_에서_시작한다")
    void 집계_컬럼은_비지_않고_0_에서_시작한다() {
        // 비어 있을 수 있으면 진행률 계산이 곳곳에서 <값 없음>을 따로 다뤄야 하고, 한 곳만 빠뜨리면
        // 사람이 보는 진행률이 비거나 예외가 된다.
        for (String column : List.of("trgt_nocs", "scs_nocs", "fail_nocs")) {
            assertThat(isNullable(JOB, column)).isFalse();
            assertThat(defaultOf(JOB, column)).isEqualTo("0");
        }
        assertThat(defaultOf(ARTCL, "rtry_nmtm")).isEqualTo("0");
    }

    @Test
    @DisplayName("★사유_폭은_컬럼과_매핑과_자르는_자리가_모두_같다")
    void 사유_폭은_컬럼과_매핑과_자르는_자리가_모두_같다() {
        // ★셋이 갈리면 기동 시 매핑 검증이 깨지거나, 잘리는 지점이 DB 와 달라 저장 시점에
        //  알 수 없는 오류가 난다. 폭을 숫자로 두 번 적는 대신 <같음>을 단언한다.
        assertThat(columnsOf(ARTCL).get("fail_rsn"))
                .isEqualTo("character varying(" + LsEblcUldJobArtcl.FAIL_RSN_MAX + ")");

        // 넘는 사유는 잘라 담는다 — 길이 초과로 기록 자체를 잃는 것이 사유가 잘리는 것보다 나쁘다.
        String over = "가".repeat(LsEblcUldJobArtcl.FAIL_RSN_MAX + 10);
        assertThat(LsEblcUldJobArtcl.truncateReason(over))
                .hasSize(LsEblcUldJobArtcl.FAIL_RSN_MAX);
        // 폭에 딱 맞는 사유는 손대지 않는다.
        String exact = "나".repeat(LsEblcUldJobArtcl.FAIL_RSN_MAX);
        assertThat(LsEblcUldJobArtcl.truncateReason(exact)).isEqualTo(exact);
    }

    // ------------------------------------------------------------------ 인덱스

    @Test
    @DisplayName("항목_표의_인덱스_3종이_설계대로_있다")
    void 항목_표의_인덱스_3종이_설계대로_있다() {
        Map<String, String> indexes = indexesOf(ARTCL);

        // 어느 작업의 항목인지로 찾는다 — 진행 조회가 매번 쓰는 축이다.
        assertThat(indexes).containsKey("idx_leuja_job");
        assertThat(indexes.get("idx_leuja_job")).contains("eblc_uld_job_sn");

        // ★두 컬럼이어야 한다 — 처리기가 <다음에 집을 항목>을 상태로 거르고 일련번호로 정렬한다.
        //  상태 하나만 걸면 정렬이 인덱스를 못 타고, 두 노드가 같은 후보를 서로 다른 순서로 집어
        //  경합이 늘어난다.
        assertThat(indexes).containsKey("idx_leuja_stts");
        assertThat(indexes.get("idx_leuja_stts"))
                .contains("artcl_stts_cd").contains("eblc_uld_job_artcl_sn");

        // 같은 작업 안에서 같은 마킹 문서를 두 번 담지 않는다 — 한 문서가 두 항목이 되면 같은 영상을
        // 두 번 적재하려 든다.
        assertThat(indexes).containsKey("uk_leuja_job_mark");
        assertThat(indexes.get("uk_leuja_job_mark")).contains("UNIQUE")
                .contains("eblc_uld_job_sn").contains("mark_file_path_nm");
    }

    @Test
    @DisplayName("작업_표는_상태로_찾을_수_있다")
    void 작업_표는_상태로_찾을_수_있다() {
        // 재기동 복구가 <진행중인 작업>을 찾아 일꾼을 다시 띄우는 축이다.
        assertThat(indexesOf(JOB)).containsKey("idx_leuj_stts");
    }

    // ------------------------------------------------------------------ 외래키 지움 규칙 (핵심)

    @Test
    @DisplayName("작업이_지워지면_그_항목도_함께_지워진다")
    void 작업이_지워지면_그_항목도_함께_지워진다() {
        // 항목은 작업의 부품이라 부모 없이 남을 자리가 없다.
        assertThat(onDeleteOf("fk_leuja_job")).isEqualTo("c");
    }

    @Test
    @DisplayName("★영상이_지워져도_항목은_남고_연결만_끊긴다")
    void 영상이_지워져도_항목은_남고_연결만_끊긴다() {
        // ★이 한 글자가 이번 CO 의 실제 결함이었다. 함께 지우면 ①실패 사유가 감사 기록째 사라지고
        //  ②부모의 성공·실패 건수만 남아 진행률의 분자와 분모가 어긋난다.
        //  ⚠ 「영상을 참조하는 표는 전부 함께 지운다」는 관찰은 사실이지만 근거가 아니다 —
        //    그 표들은 영상에 종속된 데이터이고 이 표는 작업 이력이다.
        assertThat(onDeleteOf("fk_leuja_raw")).isEqualTo("n");
    }

    @Test
    @DisplayName("외래키가_가리키는_표가_설계대로다")
    void 외래키가_가리키는_표가_설계대로다() {
        assertThat(referencedTableOf("fk_leuja_job")).isEqualTo(JOB);
        assertThat(referencedTableOf("fk_leuja_raw")).isEqualTo("ls_data_raw");
    }

    // ------------------------------------------------------------------ 실동작

    @Test
    @DisplayName("실제로_영상을_지워도_항목과_실패_사유가_남는다")
    void 실제로_영상을_지워도_항목과_실패_사유가_남는다() {
        // 카탈로그 값만 보면 「그렇게 선언돼 있다」까지다. 실제로 지워 보아야 그 선언이 동작으로
        // 이어지는지 알 수 있다.
        Long jobSn = jdbc.queryForObject(
                "INSERT INTO " + JOB + " (orgnl_fldr_path_nm, trgt_nocs) VALUES ('/tmp/shape-it', 1) "
                        + "RETURNING eblc_uld_job_sn", Long.class);
        Long rawSn = jdbc.queryForObject(
                "INSERT INTO ls_data_raw (vms_clip_id, prvc_type_cd, de_ident_yn, data_stts_cd, raw_file_path_nm) "
                        + "VALUES ('SHAPEIT-0001', 'PRVC', 'N', 'PENDING', '/tmp/shape-it/a.mp4') "
                        + "RETURNING raw_sn", Long.class);
        jdbc.update("INSERT INTO " + ARTCL
                        + " (eblc_uld_job_sn, mark_file_path_nm, artcl_stts_cd, raw_sn, fail_rsn) "
                        + "VALUES (?, '/tmp/shape-it/a.json', 'SUCCESS', ?, '짝을 찾지 못함')",
                jobSn, rawSn);

        try {
            jdbc.update("DELETE FROM ls_data_raw WHERE raw_sn = ?", rawSn);

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT raw_sn, fail_rsn FROM " + ARTCL + " WHERE eblc_uld_job_sn = ?", jobSn);
            assertThat(row.get("raw_sn")).isNull();
            assertThat(row.get("fail_rsn")).isEqualTo("짝을 찾지 못함");

            // 반면 작업을 지우면 항목도 함께 사라진다.
            jdbc.update("DELETE FROM " + JOB + " WHERE eblc_uld_job_sn = ?", jobSn);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + ARTCL + " WHERE eblc_uld_job_sn = ?", Integer.class, jobSn))
                    .isZero();
        } finally {
            jdbc.update("DELETE FROM " + ARTCL + " WHERE eblc_uld_job_sn = ?", jobSn);
            jdbc.update("DELETE FROM " + JOB + " WHERE eblc_uld_job_sn = ?", jobSn);
            jdbc.update("DELETE FROM ls_data_raw WHERE vms_clip_id = 'SHAPEIT-0001'");
        }
    }

    // ------------------------------------------------------------------ 카탈로그 조회

    /**
     * 컬럼 이름 → 자료형 표기.
     *
     * <p>스키마를 리터럴로 박지 않고 {@code current_schema()} 를 쓴다 — 배포 스키마 이름이 설정으로
     * 정해지므로, 박아 두면 그 설정을 바꾼 형상에서 이 시험이 <b>언제나 참</b>(대상 0건)이 된다.
     */
    private Map<String, String> columnsOf(String table) {
        return jdbc.query(
                "SELECT column_name, "
                        + "  CASE WHEN character_maximum_length IS NULL THEN data_type "
                        + "       ELSE data_type || '(' || character_maximum_length || ')' END AS type "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ?",
                rs -> {
                    Map<String, String> map = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        map.put(rs.getString("column_name"), rs.getString("type"));
                    }
                    return map;
                }, table);
    }

    private boolean isNullable(String table, String column) {
        return "YES".equals(jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                String.class, table, column));
    }

    private String defaultOf(String table, String column) {
        return jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    /** 인덱스 이름 → 정의문. 정의문에는 유일 여부와 컬럼 구성이 함께 들어 있다. */
    private Map<String, String> indexesOf(String table) {
        return jdbc.query(
                "SELECT indexname, indexdef FROM pg_indexes "
                        + "WHERE schemaname = current_schema() AND tablename = ?",
                rs -> {
                    Map<String, String> map = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        map.put(rs.getString("indexname"), rs.getString("indexdef"));
                    }
                    return map;
                }, table);
    }

    /**
     * 외래키의 지움 규칙 — {@code c}=함께 지움 · {@code n}=연결만 끊음 · {@code a}=아무것도 안 함.
     */
    private String onDeleteOf(String constraint) {
        return jdbc.queryForObject(
                "SELECT confdeltype FROM pg_constraint c "
                        + "JOIN pg_namespace n ON n.oid = c.connamespace "
                        + "WHERE n.nspname = current_schema() AND c.conname = ?",
                String.class, constraint);
    }

    private String referencedTableOf(String constraint) {
        return jdbc.queryForObject(
                "SELECT cl.relname FROM pg_constraint c "
                        + "JOIN pg_namespace n ON n.oid = c.connamespace "
                        + "JOIN pg_class cl ON cl.oid = c.confrelid "
                        + "WHERE n.nspname = current_schema() AND c.conname = ?",
                String.class, constraint);
    }
}
