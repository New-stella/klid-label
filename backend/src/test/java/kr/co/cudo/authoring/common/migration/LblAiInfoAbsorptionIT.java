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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V6 — {@code LS_DATA_LBL_AI_INFO} 흡수 마이그레이션 실동작 검증(Testcontainers PostgreSQL).
 *
 * <p>여기서 검증하는 것은 <b>마이그레이션 SQL 자체의 성질</b>이다(엔티티 매핑 정합은 컨텍스트 로드가
 * 이미 증명한다 — {@code ddl-auto=validate}).
 *
 * <ul>
 *   <li>분리 테이블이 사라지고 흡수 컬럼 5개가 라벨 테이블에 있다</li>
 *   <li>흡수 컬럼에 <b>DB DEFAULT 가 없다</b> — 걸면 "AI 가 만들지 않았다"와 "만들었는데 자동이 아니다"가
 *       구분 불가능해진다(V6 헤더)</li>
 *   <li>보간 출처 필터를 받칠 인덱스가 승계됐다</li>
 *   <li>데이터마트 뷰 4종 정의가 바뀌지 않았다(관제 계약면)</li>
 *   <li>고아 행은 중단 사유가 아니라 <b>제외 + 기록</b>이고, 정상 행 누락은 <b>fail-closed</b>다</li>
 *   <li>두 번 적용해도 안전하다</li>
 * </ul>
 *
 * <p><b>왜 실 스키마가 아니라 임시 스키마에서 마이그레이션을 재현하는가</b>: 이 컨테이너의
 * {@code klid_at} 은 Flyway 가 이미 V6 까지 적용한 <b>결과 상태</b>라 "이관 도중"을 관측할 수 없다.
 * 그래서 고아·중복·fail-closed 케이스는 같은 컨테이너의 <b>별도 스키마</b>에 흡수 <b>이전</b> 형상을
 * 만들고 실제 마이그레이션 파일을 그대로 실행해 관측한다(SQL 사본을 테스트에 옮겨 적으면 그 사본이
 * 두 번째 진실원이 되어 본 파일과 조용히 어긋난다).
 *
 * @req R4
 */
@SpringBootTest
@ActiveProfiles("local")
class LblAiInfoAbsorptionIT {

    /** 임시 재현 스키마 — 실 스키마({@code klid_at})를 건드리지 않는다. */
    private static final String SANDBOX = "v6_absorb_sandbox";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    // ---------- 적용 결과 (실 스키마) ----------

    @Test
    @DisplayName("분리_테이블은_사라지고_흡수_컬럼_5개가_라벨_테이블에_있다")
    void absorbedColumnsExistAndTableGone() {
        assertThat(jdbc().queryForObject(
                "SELECT to_regclass(current_schema() || '.ls_data_lbl_ai_info')", String.class))
                .as("분리 테이블은 DROP 됐어야 한다")
                .isNull();

        List<String> cols = jdbc().queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'ls_data_lbl' "
                        + "  AND column_name IN ('lbl_src_cd','mdl_nm','mdl_ver','conf_score','auto_lbl_yn')",
                String.class);
        assertThat(cols).containsExactlyInAnyOrder(
                "lbl_src_cd", "mdl_nm", "mdl_ver", "conf_score", "auto_lbl_yn");
    }

    @Test
    @DisplayName("흡수_컬럼에는_DB_DEFAULT_가_없고_NULL_을_허용한다")
    void absorbedColumnsHaveNoDefaultAndAreNullable() {
        List<Map<String, Object>> rows = jdbc().queryForList(
                "SELECT column_name, column_default, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'ls_data_lbl' "
                        + "  AND column_name IN ('lbl_src_cd','mdl_nm','mdl_ver','conf_score','auto_lbl_yn')");
        assertThat(rows).hasSize(5);
        for (Map<String, Object> r : rows) {
            assertThat(r.get("column_default"))
                    .as("%s 에 DEFAULT 를 걸면 '사람이 그린 라벨'과 'AI 가 만든 비자동 라벨'이 "
                            + "영구히 구분되지 않는다", r.get("column_name"))
                    .isNull();
            assertThat(r.get("is_nullable")).isEqualTo("YES");
        }
    }

    @Test
    @DisplayName("흡수_컬럼_타입과_폭이_표준도메인_그대로_옮겨졌다")
    void absorbedColumnTypesMatchStandardDomain() {
        Map<String, String> actual = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : jdbc().queryForList(
                "SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'ls_data_lbl' "
                        + "  AND column_name IN ('lbl_src_cd','mdl_nm','mdl_ver','conf_score','auto_lbl_yn')")) {
            String type = String.valueOf(r.get("data_type"));
            Object len = r.get("character_maximum_length");
            if (len != null) {
                type = type + "(" + len + ")";
            } else if (r.get("numeric_precision") != null) {
                type = type + "(" + r.get("numeric_precision") + "," + r.get("numeric_scale") + ")";
            }
            actual.put(String.valueOf(r.get("column_name")), type);
        }
        // 사업표준용어 도메인: 라벨출처코드 V20 · 모델명 V100 · 모델버전 V50 · 신뢰도점수 N(6,5) · 자동라벨여부 C1
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(Map.of(
                "lbl_src_cd", "character varying(20)",
                "mdl_nm", "character varying(100)",
                "mdl_ver", "character varying(50)",
                "conf_score", "numeric(6,5)",
                "auto_lbl_yn", "character(1)"));
    }

    @Test
    @DisplayName("보간_출처_필터가_인덱스를_탄다")
    void interpolateSourceFilterUsesIndex() {
        assertThat(jdbc().queryForList(
                "SELECT indexname FROM pg_indexes "
                        + "WHERE schemaname = current_schema() AND tablename = 'ls_data_lbl' "
                        + "  AND indexdef ILIKE '%lbl_src_cd%'", String.class))
                .as("구 IDX_LS_DATA_LBL_AI_INFO_SRC_CD 를 승계하지 않으면 보간 산출물 조회가 풀스캔이 된다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("데이터마트_뷰_4종은_흡수_대상을_참조하지_않고_그대로_있다")
    void completedViewsUnchanged() {
        List<String> views = jdbc().queryForList(
                "SELECT viewname FROM pg_views WHERE schemaname = current_schema() "
                        + "  AND viewname LIKE 'v_completed%' ORDER BY viewname", String.class);
        assertThat(views).containsExactly(
                "v_completed_frame", "v_completed_label_change", "v_completed_meta", "v_completed_video");

        // 관제가 SELECT 하는 계약면이라, 흡수가 뷰 본문을 건드리지 않았음을 명시적으로 고정한다.
        assertThat(jdbc().queryForList(
                "SELECT viewname FROM pg_views WHERE schemaname = current_schema() "
                        + "  AND definition ILIKE '%ai_info%'", String.class))
                .isEmpty();
    }

    // ---------- 마이그레이션 자체의 성질 (임시 스키마 재현) ----------

    @Test
    @DisplayName("대응_라벨이_없는_AI정보는_이관에서_제외되고_기록만_남는다")
    void orphanRowsAreExcludedNotFatal() {
        withSandbox(jdbc -> {
            seedPreAbsorptionShape(jdbc);
            // 정상 2건 + 고아 2건(라벨 900/901 부재).
            jdbc.update("INSERT INTO ls_data_lbl (lbl_sn, src_sn, lbl_type_cd, lbl_nm) VALUES "
                    + "(1, 10, 'BBOX', 'yolo'), (2, 10, 'BBOX', 'manual')");
            insertAiInfo(jdbc, 1, "YOLO", "0.90000", "Y");
            insertAiInfo(jdbc, 900, "YOLO", "0.10000", "Y");
            insertAiInfo(jdbc, 901, "SAM2", "0.20000", "Y");

            applyV6(jdbc);

            // 고아는 중단 사유가 아니다 — 정상 행은 그대로 이관된다.
            assertThat(jdbc.queryForObject(
                    "SELECT lbl_src_cd FROM ls_data_lbl WHERE lbl_sn = 1", String.class))
                    .isEqualTo("YOLO");
            // AI 정보가 없던 라벨은 흡수 컬럼이 NULL 이다('N' 이 아니다).
            assertThat(jdbc.queryForMap("SELECT auto_lbl_yn, lbl_src_cd, conf_score "
                    + "FROM ls_data_lbl WHERE lbl_sn = 2"))
                    .containsEntry("auto_lbl_yn", null)
                    .containsEntry("lbl_src_cd", null)
                    .containsEntry("conf_score", null);
            assertThat(jdbc.queryForObject(
                    "SELECT to_regclass('" + SANDBOX + ".ls_data_lbl_ai_info')", String.class)).isNull();
        });
    }

    @Test
    @DisplayName("한_라벨에_AI정보가_여러_행이면_자동라벨_행을_우선해_1건만_채택한다")
    void duplicateRowsResolvedDeterministically() {
        withSandbox(jdbc -> {
            seedPreAbsorptionShape(jdbc);
            jdbc.update("INSERT INTO ls_data_lbl (lbl_sn, src_sn, lbl_type_cd, lbl_nm) "
                    + "VALUES (5, 10, 'BBOX', 'multi')");
            // 더 <최신>인 'N' 행과 더 <오래된> 'Y' 행. 단순 최신 규칙이면 'N' 이 뽑혀 흡수 전 화면 값이 뒤집힌다.
            jdbc.update("INSERT INTO ls_data_lbl_ai_info "
                    + "(data_lbl_sn, data_raw_sn, data_src_sn, lbl_src_cd, conf_score, auto_lbl_yn, reg_dt, mdfcn_dt) "
                    + "VALUES (5, 1, 10, 'YOLO', 0.70000, 'Y', '2026-01-01', '2026-01-01'), "
                    + "       (5, 1, 10, 'VLM',  0.30000, 'N', '2026-02-02', '2026-02-02')");

            applyV6(jdbc);

            assertThat(jdbc.queryForMap("SELECT auto_lbl_yn, lbl_src_cd FROM ls_data_lbl WHERE lbl_sn = 5"))
                    .containsEntry("auto_lbl_yn", "Y")
                    .containsEntry("lbl_src_cd", "YOLO");
        });
    }

    @Test
    @DisplayName("정상_행_이관에_실패하면_마이그레이션이_중단되고_분리_테이블이_남는다")
    void failClosedWhenAnyNonOrphanRowIsNotMigrated() {
        withSandbox(jdbc -> {
            seedPreAbsorptionShape(jdbc);
            jdbc.update("INSERT INTO ls_data_lbl (lbl_sn, src_sn, lbl_type_cd, lbl_nm) VALUES "
                    + "(1, 10, 'BBOX', 'a'), (2, 10, 'BBOX', 'b')");
            insertAiInfo(jdbc, 1, "YOLO", "0.90000", "Y");
            insertAiInfo(jdbc, 2, "SAM2", "0.80000", "Y");
            // 라벨 2 의 UPDATE 만 삼키는 트리거 — "값이 조용히 누락된 상태" 를 인위적으로 만든다.
            jdbc.execute("CREATE FUNCTION " + SANDBOX + ".swallow() RETURNS trigger AS $f$ "
                    + "BEGIN IF NEW.lbl_sn = 2 THEN RETURN NULL; END IF; RETURN NEW; END $f$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER t_swallow BEFORE UPDATE ON ls_data_lbl "
                    + "FOR EACH ROW EXECUTE FUNCTION " + SANDBOX + ".swallow()");

            // ⚠ Spring 래퍼 메시지에는 <b>실행한 SQL 전문</b>(= 마이그레이션 파일 주석 포함)이 실려 있어
            //   거기서 값 유출을 판정하면 오탐이 난다. 가드가 만든 문구만 보려면 root cause 를 봐야 한다.
            Throwable root = org.assertj.core.api.Assertions.catchThrowable(() -> applyV6(jdbc));
            assertThat(root).isNotNull();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root.getMessage())
                    .contains("AI 정보 이관 건수가 맞지 않는다")
                    .contains("기대 2건")
                    .contains("실제 1건")
                    // 값·식별자를 실으면 로그로 새어 나간다(CWE-209/117) — 건수만 싣는다.
                    .doesNotContain("YOLO")
                    .doesNotContain("SAM2")
                    .doesNotContain("0.90000");

            // 중단됐으므로 분리 테이블이 남아 있어야 한다(부분 적용 금지).
            assertThat(jdbc.queryForObject(
                    "SELECT to_regclass('" + SANDBOX + ".ls_data_lbl_ai_info')", String.class))
                    .isEqualTo("ls_data_lbl_ai_info");
        });
    }

    @Test
    @DisplayName("V6를_두_번_적용해도_안전하다")
    void applyingTwiceIsSafe() {
        withSandbox(jdbc -> {
            seedPreAbsorptionShape(jdbc);
            jdbc.update("INSERT INTO ls_data_lbl (lbl_sn, src_sn, lbl_type_cd, lbl_nm) "
                    + "VALUES (1, 10, 'BBOX', 'yolo')");
            insertAiInfo(jdbc, 1, "YOLO", "0.90000", "Y");

            applyV6(jdbc);
            Map<String, Object> first = jdbc.queryForMap(
                    "SELECT lbl_src_cd, auto_lbl_yn, conf_score FROM ls_data_lbl WHERE lbl_sn = 1");

            applyV6(jdbc);   // 두 번째 적용 — 예외 없이 no-op 이어야 한다.

            assertThat(jdbc.queryForMap(
                    "SELECT lbl_src_cd, auto_lbl_yn, conf_score FROM ls_data_lbl WHERE lbl_sn = 1"))
                    .isEqualTo(first);
        });
    }

    // ---------- 재현 유틸 ----------

    /**
     * 임시 스키마에서 콜백을 실행하고 <b>반드시</b> 정리한다.
     * {@code search_path} 를 그 스키마로 고정해 마이그레이션의 {@code current_schema()} 스코프가
     * 실 스키마를 건드리지 않게 한다.
     */
    private void withSandbox(java.util.function.Consumer<JdbcTemplate> body) {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("DROP SCHEMA IF EXISTS " + SANDBOX + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SANDBOX);
        try {
            jdbc.execute("SET search_path TO " + SANDBOX);
            body.accept(jdbc);
        } finally {
            jdbc.execute("RESET search_path");
            jdbc.execute("DROP SCHEMA IF EXISTS " + SANDBOX + " CASCADE");
        }
    }

    /** 흡수 <b>이전</b> 형상 — V1 베이스라인의 두 테이블 정의 그대로(이관에 쓰이는 컬럼만). */
    private void seedPreAbsorptionShape(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE ls_data_lbl ("
                + "lbl_sn bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                + "src_sn bigint NOT NULL,"
                + "lbl_type_cd character varying(16) NOT NULL,"
                + "lbl_nm character varying(80) NOT NULL,"
                + "point_cn text, trck_id character varying(30), reg_user_no bigint,"
                + "reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,"
                + "mdfcn_dt timestamp without time zone, lbl_id bigint)");
        jdbc.execute("CREATE TABLE ls_data_lbl_ai_info ("
                + "data_lbl_ai_info_sn bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                + "data_lbl_sn bigint NOT NULL, data_raw_sn bigint NOT NULL, data_src_sn bigint NOT NULL,"
                + "lbl_src_cd character varying(20) NOT NULL,"
                + "mdl_nm character varying(100), mdl_ver character varying(50),"
                + "conf_score numeric(6,5),"
                + "auto_lbl_yn character(1) DEFAULT 'Y'::character varying NOT NULL,"
                + "reg_id character varying(30),"
                + "reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,"
                + "mdfcn_id character varying(30), mdfcn_dt timestamp without time zone)");
    }

    private void insertAiInfo(JdbcTemplate jdbc, long lblSn, String src, String conf, String autoYn) {
        jdbc.update("INSERT INTO ls_data_lbl_ai_info "
                        + "(data_lbl_sn, data_raw_sn, data_src_sn, lbl_src_cd, conf_score, auto_lbl_yn) "
                        + "VALUES (?, 1, 10, ?, ?::numeric, ?)",
                lblSn, src, conf, autoYn);
    }

    /**
     * 마이그레이션 <b>파일 원본</b>을 그대로 실행한다 — SQL 을 테스트에 옮겨 적지 않는다.
     * 사본을 두면 그 사본이 두 번째 진실원이 되어 본 파일과 조용히 어긋난다.
     */
    private void applyV6(JdbcTemplate jdbc) {
        String sql;
        try (var in = getClass().getResourceAsStream(
                "/db/migration/V6__absorb_lbl_ai_info_into_ls_data_lbl.sql")) {
            sql = new String(java.util.Objects.requireNonNull(in).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("V6 마이그레이션 파일을 읽지 못했다", e);
        }
        jdbc.execute(sql);
    }
}
