package kr.co.cudo.authoring.label;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V120/V121 — 라벨 마스터 이름 유일성 정책 DB 레벨 실증 (Testcontainers PostgreSQL).
 *
 * <p>확정 설계(Q1): 유일 범위 = 활성(USE_YN='Y')만, soft-delete 된 이름은 재사용 허용.
 * V120 이 활성 한정 함수형 부분 유니크 인덱스(UK_LS_LABEL_NM_CI)로 활성 유일성을 강제하고,
 * V121 이 all-rows exact 제약(UK_LS_LABEL_NAME)을 제거해 soft-delete 이름 재사용을 실현한다.
 *
 * <p>검증 축:
 * <ul>
 *   <li>활성 라벨 대상 함수형(LOWER(TRIM)) 부분 유니크 인덱스 존재.</li>
 *   <li>같은 정규화 이름의 활성 라벨 중복 INSERT 가 DB 에서 원자적으로 거부됨(대소문자+공백 무시).</li>
 *   <li>soft delete(USE_YN='N') 행은 인덱스 대상에서 제외 → 동일 이름 재사용 허용.</li>
 *   <li><b>V121: all-rows exact 제약(UK_LS_LABEL_NAME) 제거</b> — soft-delete 후 동일 exact
 *       이름 재사용 성공(핵심 회귀 방지), 동일 exact 활성 중복은 여전히 CI 인덱스가 거부(409 경로).</li>
 *   <li>V120 precheck 의 근사중복 감지 SELECT(GROUP BY LOWER(TRIM) HAVING COUNT>1) 로직 검증.</li>
 * </ul>
 *
 * <p>비트랜잭션 + 수동 cleanup 패턴(LsLabelPresetCodeLabelIdUniqueIT 와 동일) — 유니크 위반이
 * 트랜잭션을 오염시키므로 롤백 대신 명시 DELETE 로 시드 행을 정리한다. 고유 접미사로 격리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class LabelNameCiUniqueMigrationIT {

    private static final String INDEX_NAME = "uk_ls_label_nm_ci";
    private static final String EXACT_CONSTRAINT_NAME = "uk_ls_label_name";

    private final JdbcTemplate jdbc;
    private final List<Long> seededLabelIds = new ArrayList<>();

    LabelNameCiUniqueMigrationIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        for (Long id : seededLabelIds) {
            jdbc.update("DELETE FROM LS_LABEL WHERE LBL_ID = ?", id);
        }
        seededLabelIds.clear();
    }

    private long insertLabel(String name, String useYn) {
        Long id = jdbc.queryForObject(
                "INSERT INTO LS_LABEL (LBL_NM, COLR_VL, LBL_TYPE_CD, SORT_SEQ, USE_YN, REG_DT) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING LBL_ID",
                Long.class, name, "#FF0000", "BBOX", 0, useYn, Timestamp.valueOf(LocalDateTime.now()));
        seededLabelIds.add(id);
        return id;
    }

    @Test
    @DisplayName("V120_적용후_활성_대소문자공백무시_유니크인덱스_존재")
    void 인덱스_존재() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'ls_label' AND indexname = ?",
                Integer.class, INDEX_NAME);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("동일_LOWER_TRIM_활성_중복INSERT_거부")
    void 활성_근사중복_INSERT_거부() {
        // given — 활성 라벨 하나 (고유 접미사)
        String base = "CIDUP_" + System.nanoTime();
        insertLabel(base, "Y");

        // when / then — 대소문자+공백만 다른 활성 라벨 INSERT 는 부분 유니크 인덱스가 거부
        String variant = "  " + base.toLowerCase() + "  ";
        assertThatThrownBy(() -> insertLabel(variant, "Y"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("soft_delete행은_CI유니크_대상에서_제외")
    void soft_delete_행은_인덱스_제외() {
        // given — 활성 라벨 하나
        String base = "CISOFT_" + System.nanoTime();
        insertLabel(base, "Y");

        // when / then — 동일 정규화 이름이라도 USE_YN='N' 은 인덱스 대상 제외 → 삽입 성공
        long softId = insertLabel(base.toLowerCase(), "N");
        assertThat(softId).isPositive();
    }

    // ─── V121 — all-rows exact 제약(UK_LS_LABEL_NAME) 제거 실증 ───

    @Test
    @DisplayName("V121_적용후_all_rows_exact_제약_UK_LS_LABEL_NAME_제거됨")
    void exact_제약_제거됨() {
        // exact all-rows UNIQUE 제약/백킹 인덱스가 더 이상 존재하지 않아야 한다(활성 CI 인덱스 단독 강제).
        Integer constraintCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = ?",
                Integer.class, EXACT_CONSTRAINT_NAME);
        assertThat(constraintCnt).isZero();
        Integer indexCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'ls_label' AND indexname = ?",
                Integer.class, EXACT_CONSTRAINT_NAME);
        assertThat(indexCnt).isZero();
    }

    @Test
    @DisplayName("soft_delete후_동일_exact_이름_재사용_허용")
    void soft_delete후_동일_exact_이름_재사용_허용() {
        // given — 활성 라벨 'person...' 생성 후 soft-delete
        String name = "EXACTREUSE_" + System.nanoTime();
        long firstId = insertLabel(name, "Y");
        jdbc.update("UPDATE LS_LABEL SET USE_YN = 'N' WHERE LBL_ID = ?", firstId);

        // when — 동일 exact 이름을 활성으로 재생성 (V121 로 all-rows exact 제약 제거됨)
        long reusedId = insertLabel(name, "Y");

        // then — 재사용 성공 (핵심 회귀 방지: V121 이전엔 exact 제약이 500 을 유발했다)
        assertThat(reusedId).isPositive();
        assertThat(reusedId).isNotEqualTo(firstId);
    }

    @Test
    @DisplayName("동일_exact_활성_중복INSERT는_CI인덱스가_거부_409경로")
    void 동일_exact_활성_중복_거부() {
        // given — 활성 라벨 하나
        String name = "EXACTDUP_" + System.nanoTime();
        insertLabel(name, "Y");

        // when / then — 완전히 동일한 exact 이름의 활성 INSERT 도 CI 부분 유니크 인덱스가 거부한다.
        //   (exact 제약 제거 후에도 활성 유일성은 유지 — GlobalExceptionHandler 가 409 로 정규화)
        assertThatThrownBy(() -> insertLabel(name, "Y"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("V120_precheck_근사중복_감지_SELECT는_동일정규화_이름을_감지한다")
    void precheck_근사중복_감지_로직() {
        // V120 안전중단 precheck 의 감지 SELECT(GROUP BY LOWER(TRIM) HAVING COUNT>1)를
        // seeded 중복 데이터(inline VALUES)에 대해 직접 실행해 감지됨을 확인한다.
        // (활성 근사중복은 CI 인덱스가 막아 실제 테이블에 시드 불가하므로 VALUES 로 재현)
        String dupNames = jdbc.queryForObject(
                "SELECT string_agg(g.norm_name, ', ') FROM ("
                        + "  SELECT LOWER(TRIM(v.lbl_nm)) AS norm_name"
                        + "    FROM (VALUES ('Person'), ('person '), ('car')) AS v(lbl_nm)"
                        + "   GROUP BY LOWER(TRIM(v.lbl_nm))"
                        + "  HAVING COUNT(*) > 1"
                        + ") g",
                String.class);
        assertThat(dupNames).isEqualTo("person");
    }
}
