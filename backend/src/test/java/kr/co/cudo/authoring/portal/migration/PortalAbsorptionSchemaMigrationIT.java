package kr.co.cudo.authoring.portal.migration;

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
 * V28 — 포털 전용 표를 공용 원장에 흡수하기 위한 스키마 정합을 <b>실 DB</b>로 검증한다
 * (Testcontainers PostgreSQL, Flyway migrate 후 부팅). [design: ADR-058] [design: ERD-028]
 *
 * <h3>왜 이 시험이 따로 필요한가</h3>
 * <p>{@code FlywaySquashBaselineIT} 는 «V28 이 적용됐다»만 단언하고 «무엇을 바꿨는가»는 보지 않는다.
 * 그리고 이 저장소의 {@code ddl-auto=validate} 는 <b>실제로 동작하지 않아</b> 기동 성공을 정합의
 * 증거로 삼을 수 없다. 즉 이 시험이 없으면 컬럼이 안 생겼거나 폭·자료형이 어긋나도 잡아 줄 층이 없다.
 *
 * <h3>특히 자료형은 「있다/없다」로는 부족하다</h3>
 * <p>세 변경은 전부 <b>포털이 발급한 토큰 주체가 숫자가 아니다</b>라는 하나의 사실에서 나온다.
 * 컬럼이 존재해도 자료형이 숫자로 남아 있으면 파싱 실패로 조용히 {@code null} 이 되어 <b>소유자 없는
 * 행</b>이 저장되고, 폭이 좁으면 서로 다른 사용자가 같은 값으로 잘려 인가가 어긋난다. 둘 다
 * 예외 없이 조용히 잘못되는 부류라 자료형과 폭을 <b>수치로</b> 고정한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class PortalAbsorptionSchemaMigrationIT {

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    /** 카탈로그 조회는 반드시 {@code current_schema()} 로 스코프한다 — 'public' 리터럴은 klid_at 에서 항상 거짓이다. */
    private Map<String, Object> column(String table, String column) {
        List<Map<String, Object>> rows = jdbc().queryForList("""
                SELECT column_name, data_type, character_maximum_length, is_nullable
                  FROM information_schema.columns
                 WHERE table_schema = current_schema()
                   AND table_name = ?
                   AND column_name = ?
                """, table, column);
        assertThat(rows).as("%s.%s 컬럼이 존재해야 한다", table, column).hasSize(1);
        return rows.get(0);
    }

    private static int width(Map<String, Object> col) {
        return ((Number) col.get("character_maximum_length")).intValue();
    }

    @Test
    @DisplayName("★영상_원장에_포털_소유자_컬럼이_생겼다 — 문자_100자이고_비어_있을_수_있다")
    void 영상_원장에_포털_소유자_컬럼이_생겼다() {
        Map<String, Object> col = column("ls_data_raw", "portal_user_no");

        assertThat(col.get("data_type")).isEqualTo("character varying");
        // 폭 100 = 공통표준도메인 번호V100. ls_marking.reg_user_no(V27)와 같다.
        assertThat(width(col)).isEqualTo(100);
        // 관제 인입 영상에는 소유자 개념이 없어 비어 있다 — NOT NULL 로 만들면 그 적재가 전건 실패한다.
        assertThat(col.get("is_nullable")).isEqualTo("YES");
    }

    @Test
    @DisplayName("★소유자_목록_조회를_받치는_부분_인덱스가_있다 — 관제_영상까지_색인하지_않는다")
    void 소유자_부분_인덱스가_있다() {
        List<Map<String, Object>> idx = jdbc().queryForList("""
                SELECT indexdef
                  FROM pg_indexes
                 WHERE schemaname = current_schema()
                   AND tablename = 'ls_data_raw'
                   AND indexname = 'idx_ldr_portal_user_reg'
                """);

        assertThat(idx).as("포털 소유자 목록 조회용 인덱스").hasSize(1);
        String def = String.valueOf(idx.get(0).get("indexdef"));
        // ★ 부분 인덱스여야 한다 — 전체 인덱스로 만들면 관제 영상(소유자 없음) 전량이 색인에 들어가
        //   원장 규모에 비례해 부푼다. 흡수 전 idx_ls_portal_uld_user_reg 는 전용 표라 그 부담이 없었다.
        assertThat(def).containsIgnoringCase("portal_user_no IS NOT NULL");
        assertThat(def).contains("portal_user_no", "reg_dt");
    }

    @Test
    @DisplayName("★라벨_등록자가_문자_100자로_넓어졌다 — 포털_토큰_주체를_담기_위해")
    void 라벨_등록자가_문자_100자다() {
        Map<String, Object> col = column("ls_data_lbl", "reg_user_no");

        // 숫자로 되돌리면 포털이 발급한 비숫자 주체가 파싱 실패로 조용히 null 이 되어
        // 소유자 없는 라벨이 저장된다(소유자 스코프 조회의 키를 잃는다).
        assertThat(col.get("data_type")).isEqualTo("character varying");
        assertThat(width(col)).isEqualTo(100);
        // 내부 채널의 과거 행·AI 가 만든 라벨은 등록자가 비어 있다.
        assertThat(col.get("is_nullable")).isEqualTo("YES");
    }

    @Test
    @DisplayName("★재개_업로드_세션의_사용자_식별자_폭이_100_이상이다 — 좁히면_인가가_조용히_어긋난다")
    void 재개_업로드_세션_사용자_식별자_폭() {
        Map<String, Object> col = column("ls_tus_upload", "user_no");

        assertThat(col.get("data_type")).isEqualTo("character varying");
        // 흡수 전 64 였다. 좁히면 서로 다른 포털 사용자가 같은 값으로 잘려 세션 소유권이 겹친다.
        assertThat(width(col)).isEqualTo(100);
        assertThat(col.get("is_nullable")).isEqualTo("NO");
    }

    @Test
    @DisplayName("★구_포털_전용_표는_아직_남아_있다 — 드롭은_되돌릴_수_없어_별도_단계다")
    void 구_포털_전용_표는_아직_남아_있다() {
        List<String> tables = jdbc().queryForList("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = current_schema()
                   AND table_name IN ('ls_portal_uld', 'ls_portal_uld_frme', 'ls_portal_uld_lbl',
                                      'ls_portal_tus_uld', 'ls_portal_user_label')
                 ORDER BY table_name
                """, String.class);

        // V28 은 순수 확장이다 — 구 표를 지우면 아직 그 표를 읽는 코드가 통째로 깨지고,
        // 드롭은 되돌릴 수 없어 데이터 이관 검증 전에 할 수 없다.
        assertThat(tables).containsExactly(
                "ls_portal_tus_uld", "ls_portal_uld", "ls_portal_uld_frme",
                "ls_portal_uld_lbl", "ls_portal_user_label");
    }

    @Test
    @DisplayName("★데이터마트_오버레이는_흡수_대상이_아니다 — 합치면_저장이_원본을_덮어쓴다")
    void 데이터마트_오버레이는_전용으로_남는다() {
        // ADR-058 이 「전용으로 남길지」를 비용이 아니라 방향으로 판정한 유일한 표다.
        // 라벨 원장에 합치면 포털 저장이 원본을 덮어쓰고, 소유자 구분으로 섞으면 그 구분을
        // 한 번만 잊는 순간 남의 오버레이가 정본 라벨로 읽히는 fail-open 이 된다.
        // ⇒ 이 표가 사라지거나 라벨 원장으로 합쳐지면 그 불변식이 조회 필터로 내려앉는다.
        Integer exists = jdbc().queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = current_schema() AND table_name = 'ls_portal_user_label'
                """, Integer.class);
        assertThat(exists).isEqualTo(1);
    }
}
