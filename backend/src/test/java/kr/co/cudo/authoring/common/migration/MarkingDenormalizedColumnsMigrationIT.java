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
 * V27 — 마킹 원장에서 <b>무엇이 사라지고 무엇이 넓어졌는지</b>를 실 DB 로 검증
 * (Testcontainers PostgreSQL, Flyway migrate 후 부팅). [design: ERD-013]
 *
 * <h3>왜 이 시험이 따로 필요한가</h3>
 * <p>{@code FlywaySquashBaselineIT} 는 «V27 이 적용됐다»만 단언하고 «무엇을 바꿨는가»는 보지 않는다.
 * 그리고 이 프로젝트의 {@code ddl-auto=validate} 는 실제로 동작하지 않아 <b>기동 성공을 정합의 증거로
 * 삼을 수 없다</b>. 즉 이 시험이 없으면 컬럼이 그대로 남아 있거나 폭이 어긋나도 잡아 줄 층이 없다.
 *
 * <h3>특히 「남은 NOT NULL 집합」은 여기서만 볼 수 있다</h3>
 * <p>이 변경의 목적은 <b>포털 업로드 경로가 마킹을 저장할 수 있게</b> 하는 것이다. 그 판정은 결국
 * "비울 수 없는 칸이 무엇만 남았는가" 이며, 컬럼이 두 개 사라진 사실만으로는 확인되지 않는다.
 * 그래서 남은 NOT NULL 집합을 <b>정확히</b> 고정한다 — 여기에 새 NOT NULL 이 늘면 그 경로가 다시
 * 막히므로, 늘리려는 변경은 이 시험을 반드시 통과해야 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class MarkingDenormalizedColumnsMigrationIT {

    private static final String TABLE = "ls_marking";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    /** 카탈로그 조회는 반드시 {@code current_schema()} 로 스코프한다 — 'public' 리터럴은 klid_at 에서 항상 거짓이다. */
    private List<Map<String, Object>> columns() {
        return jdbc().queryForList("""
                SELECT column_name, data_type, character_maximum_length, is_nullable
                  FROM information_schema.columns
                 WHERE table_schema = current_schema()
                   AND table_name = ?
                 ORDER BY column_name
                """, TABLE);
    }

    @Test
    @DisplayName("★중복_두_칸이_사라졌다 — 영상_행에_있는_값을_베껴_두지_않는다")
    void 중복_두_칸이_사라졌다() {
        // 두 값은 LS_DATA_RAW.EVNT_TYPE_CD · RAW_FILE_PATH_NM 을 마킹 행에 복사해 두던 것이라
        // 영상 쪽이 바뀌면 어긋났다. 응답에는 영상 행에서 조달해 그대로 싣는다(계약 무변경).
        assertThat(columns().stream().map(c -> c.get("column_name")))
                .as("마킹 원장의 컬럼 목록")
                .doesNotContain("evnt_nm", "video_file_path_nm");
    }

    @Test
    @DisplayName("★등록사용자번호가_문자_100자로_넓어졌다 — 포털_토큰_주체를_담기_위해")
    void 등록사용자번호가_문자_100자다() {
        Map<String, Object> col = columns().stream()
                .filter(c -> "reg_user_no".equals(c.get("column_name")))
                .findFirst()
                .orElseThrow();

        // 숫자로 되돌리면 포털이 발급한 비숫자 주체가 파싱 실패로 조용히 null 이 되어
        // 소유자 없는 마킹이 저장된다(인가 판정의 키를 잃는다).
        assertThat(col.get("data_type")).isEqualTo("character varying");
        // 폭은 공통표준도메인 번호V100. 좁히면 서로 다른 사용자가 같은 값으로 잘려 인가가 어긋난다.
        assertThat(((Number) col.get("character_maximum_length")).intValue()).isEqualTo(100);
        // 내부 채널의 과거 행·토큰 주체 미상은 비어 있을 수 있다.
        assertThat(col.get("is_nullable")).isEqualTo("YES");
    }

    @Test
    @DisplayName("★비울_수_없는_칸은_포털_경로가_전부_채울_수_있는_것만_남았다")
    void 남은_NOT_NULL_집합이_고정된다() {
        List<String> notNull = columns().stream()
                .filter(c -> "NO".equals(c.get("is_nullable")))
                .map(c -> (String) c.get("column_name"))
                .toList();

        // 하나라도 늘면 포털 업로드 경로의 마킹 저장이 다시 막힌다 — 그 경로에는 관제 인입값도
        // 비식별 산출물도 없기 때문이다. 늘리려면 그 경로가 무엇으로 채울지를 먼저 정해야 한다.
        //   marking_sn   : 자동 증가
        //   raw_sn       : 대상 영상
        //   mark_mode_cd : AUTO/MANUAL — 포털도 두 모드를 쓴다
        //   mark_cn      : 마킹 지점 배열
        //   stts_cd      : 기본값 PENDING
        //   reg_dt·mdfcn_dt : 기본값 CURRENT_TIMESTAMP
        assertThat(notNull).containsExactlyInAnyOrder(
                "marking_sn", "raw_sn", "mark_mode_cd", "mark_cn", "stts_cd", "reg_dt", "mdfcn_dt");
    }
}
