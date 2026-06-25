package kr.co.cudo.authoring.batch.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V66 (LS_DATA_LBL.POINT_CN 평탄/객체배열 → nested 백필) 마이그레이션 실동작 검증.
 *
 * <p>Phase 1 에서 오토라벨 write 경로를 nested {@code [[x,y],...]} 로 통일했고, 본 마이그레이션은
 * 기존 DB 에 남은 평탄 {@code [x1,y1,x2,y2]} / 객체배열 {@code [{"x":..,"y":..}]} 포맷을 nested 로
 * 일괄 백필한다. 그러면 포맷 혼재가 사라지고 관제 노출 View(V_COMPLETED_LABEL)가 항상 일관된
 * nested 를 내보낸다.
 *
 * <p><b>검증 방식</b>: 실제 Flyway 마이그레이션 스키마(Testcontainers PostgreSQL) 위에서 V66 이전
 * 상태(평탄/객체배열 POINT_CN)를 raw INSERT 로 재현하고, V66 SQL 파일을 그대로 실행하여 변환·멱등·
 * 무변경·데이터파괴금지를 검증한다. 부팅 시 Flyway 가 V66 까지 자동 적용하지만, 신규 빈 DB 에는
 * 변환 대상 행이 없으므로 테스트가 직접 비-nested 행을 만들어 변환 동작을 확인한다.
 *
 * <p>각 테스트는 INSERT 한 라벨 행이 다른 통합테스트를 오염시키지 않도록 고유 SRC_SN 대역(966_00xx)
 * 을 쓰고 테스트 종료 시 정리(DELETE)한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class V66NormalizeLabelPointsMigrationTest {

    private static final Path V66 = Paths.get(
            "src/main/resources/db/migration/V66__normalize_label_points_to_nested.sql");

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    /** 본 테스트 전용 SRC_SN 대역 — 다른 통합테스트와 충돌 회피. */
    private static final AtomicLong SRC_SN_SEQ = new AtomicLong(966_0000L);

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    /** V66 마이그레이션 SQL 본문(주석 제외 실행부)을 그대로 실행한다. */
    private void applyV66() throws IOException {
        String sql = Files.readString(V66);
        jdbc().execute(sql);
    }

    /** 비-nested POINT_CN 라벨 1행을 INSERT 하고 LBL_SN 반환. */
    private long insertLabel(String pointCn) {
        long srcSn = SRC_SN_SEQ.incrementAndGet();
        JdbcTemplate jdbc = jdbc();
        jdbc.update(
                "INSERT INTO LS_DATA_LBL (SRC_SN, LBL_TYPE_CD, LBL_NM, POINT_CN) VALUES (?, ?, ?, ?)",
                srcSn, "BBOX", "test-label", pointCn);
        return jdbc.queryForObject(
                "SELECT LBL_SN FROM LS_DATA_LBL WHERE SRC_SN = ?", Long.class, srcSn);
    }

    private String pointOf(long lblSn) {
        return jdbc().queryForObject(
                "SELECT POINT_CN FROM LS_DATA_LBL WHERE LBL_SN = ?", String.class, lblSn);
    }

    private void cleanup(long lblSn) {
        jdbc().update("DELETE FROM LS_DATA_LBL WHERE LBL_SN = ?", lblSn);
    }

    // ============================================================
    // 변환 케이스
    // ============================================================

    @Test
    @DisplayName("V66_평탄_bbox를_nested로_변환")
    void flatBboxToNested() throws IOException {
        // given — 평탄 bbox [10,20,30,40]
        long lblSn = insertLabel("[10,20,30,40]");

        // when — V66 백필 실행
        applyV66();

        // then — nested [[10,20],[30,40]] (jsonb 동치 비교)
        String result = pointOf(lblSn);
        assertThat(jsonbEq(result, "[[10,20],[30,40]]")).isTrue();
        cleanup(lblSn);
    }

    @Test
    @DisplayName("V66_객체배열을_nested로_변환")
    void objectArrayToNested() throws IOException {
        // given — 객체배열 [{"x":1,"y":2},{"x":3,"y":4}]
        long lblSn = insertLabel("[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]");

        // when
        applyV66();

        // then — nested [[1,2],[3,4]]
        assertThat(jsonbEq(pointOf(lblSn), "[[1,2],[3,4]]")).isTrue();
        cleanup(lblSn);
    }

    @Test
    @DisplayName("V66_다점_폴리곤_평탄도_nested로_변환")
    void flatPolygonToNested() throws IOException {
        // given — 6점 평탄 폴리곤 [1,2,3,4,5,6]
        long lblSn = insertLabel("[1,2,3,4,5,6]");

        // when
        applyV66();

        // then — [[1,2],[3,4],[5,6]]
        assertThat(jsonbEq(pointOf(lblSn), "[[1,2],[3,4],[5,6]]")).isTrue();
        cleanup(lblSn);
    }

    // ============================================================
    // 무변경(멱등/보존) 케이스 — 바이트 동일
    // ============================================================

    @Test
    @DisplayName("V66_이미_nested인_행은_무변경")
    void alreadyNestedUnchanged() throws IOException {
        // given — 이미 nested [[1,2],[3,4]]
        long lblSn = insertLabel("[[1,2],[3,4]]");

        // when
        applyV66();

        // then — 바이트 동일 (변환 제외)
        assertThat(pointOf(lblSn)).isEqualTo("[[1,2],[3,4]]");
        cleanup(lblSn);
    }

    @Test
    @DisplayName("V66_null과_빈배열_무변경")
    void nullAndEmptyUnchanged() throws IOException {
        // given — NULL POINT_CN 과 빈배열 [] 각 1행
        long nullSn = insertLabel(null);
        long emptySn = insertLabel("[]");

        // when
        applyV66();

        // then — 둘 다 무변경
        assertThat(pointOf(nullSn)).isNull();
        assertThat(pointOf(emptySn)).isEqualTo("[]");
        cleanup(nullSn);
        cleanup(emptySn);
    }

    @Test
    @DisplayName("V66_재실행시_변경행_0_멱등")
    void idempotentRerunZeroRows() throws IOException {
        // given — 평탄 행 하나 + 1차 백필로 nested 전환
        long lblSn = insertLabel("[10,20,30,40]");
        applyV66();
        String afterFirst = pointOf(lblSn);

        // when — 동일 마이그레이션 재실행
        applyV66();

        // then — 2차 실행은 이미 nested 라 무변경 (바이트 동일)
        assertThat(pointOf(lblSn)).isEqualTo(afterFirst);
        assertThat(jsonbEq(afterFirst, "[[10,20],[30,40]]")).isTrue();
        cleanup(lblSn);
    }

    // ============================================================
    // 수용 기준 — 백필 후 비-nested 잔존 0
    // ============================================================

    @Test
    @DisplayName("V66_백필후_비_nested_잔존_0")
    void noNonNestedRemains() throws IOException {
        // given — 평탄·객체배열·다점평탄·이미nested·빈배열·null 혼재
        long a = insertLabel("[10,20,30,40]");
        long b = insertLabel("[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]");
        long c = insertLabel("[1,2,3,4,5,6]");
        long d = insertLabel("[[1,2],[3,4]]");
        long e = insertLabel("[]");
        long f = insertLabel(null);

        // when
        applyV66();

        // then — 수용 기준 쿼리: 비어있지 않은 배열 중 첫 원소가 array 아닌 행 = 0
        Integer nonNested = jdbc().queryForObject(
                "SELECT count(*) FROM ls_data_lbl "
                        + "WHERE point_cn IS NOT NULL AND point_cn <> '[]' "
                        + "AND jsonb_typeof((point_cn::jsonb)->0) <> 'array'",
                Integer.class);
        assertThat(nonNested).isZero();

        // 변환된 nested 각 원소가 길이2 좌표쌍인지 확인 (a/b/c)
        for (long sn : new long[]{a, b, c}) {
            String p = pointOf(sn);
            Integer badPairs = jdbc().queryForObject(
                    "SELECT count(*) FROM jsonb_array_elements(?::jsonb) e "
                            + "WHERE jsonb_array_length(e) <> 2",
                    Integer.class, p);
            assertThat(badPairs).as("LBL_SN=%s 좌표쌍 길이2", sn).isZero();
        }

        for (long sn : new long[]{a, b, c, d, e, f}) {
            cleanup(sn);
        }
    }

    @Test
    @DisplayName("V66_홀수_평탄은_변환제외_원본보존")
    void oddFlatPreserved() throws IOException {
        // given — 홀수(5점) 평탄 — malformed, 변환 대상 제외(파괴 금지)
        long lblSn = insertLabel("[1,2,3,4,5]");

        // when
        applyV66();

        // then — 원본 바이트 보존 (WHERE 가드로 스킵)
        assertThat(pointOf(lblSn)).isEqualTo("[1,2,3,4,5]");
        cleanup(lblSn);
    }

    // ============================================================
    // 데이터 파괴 금지 — 비-JSON/비숫자배열 보존 + 순서/숫자 정확성 (V66 DEV_FIX)
    // ============================================================

    @Test
    @DisplayName("V66_비JSON_빈문자열_행은_변환제외_원본보존_마이그레이션_성공")
    void nonJsonAndEmptyStringPreservedMigrationSucceeds() throws IOException {
        // given — 빈문자열·비-JSON('none')·정상 평탄 혼재.
        //         빈문자열/none 은 ::jsonb 캐스팅이 불가해 가드 없으면 전체 UPDATE 가 롤백된다.
        long emptyStr = insertLabel("");
        long none = insertLabel("none");
        long flat = insertLabel("[10,20,30,40]");
        try {
            // when — malformed 행이 있어도 마이그레이션이 예외 없이 성공해야 함
            applyV66();

            // then — 비-JSON 행은 원본 바이트 보존, 정상 행만 변환
            assertThat(pointOf(emptyStr)).isEqualTo("");
            assertThat(pointOf(none)).isEqualTo("none");
            assertThat(jsonbEq(pointOf(flat), "[[10,20],[30,40]]")).isTrue();
        } finally {
            cleanup(emptyStr);
            cleanup(none);
            cleanup(flat);
        }
    }

    @Test
    @DisplayName("V66_문자열배열_불리언배열은_NULL로_손실되지_않고_보존")
    void nonNumericArraysPreserved() throws IOException {
        // given — 문자열배열·불리언배열·null배열 — 어느 변환 분기에도 매칭 안 됨.
        //         CASE ELSE/WHERE 가드 없으면 point_cn=NULL 로 영구 손실된다.
        long strArr = insertLabel("[\"a\",\"b\"]");
        long boolArr = insertLabel("[true,false]");
        long nullArr = insertLabel("[null,null]");
        try {
            // when
            applyV66();

            // then — 전부 원본 보존(NULL 손실 없음, jsonb 동치)
            assertThat(pointOf(strArr)).isNotNull();
            assertThat(jsonbEq(pointOf(strArr), "[\"a\",\"b\"]")).isTrue();
            assertThat(pointOf(boolArr)).isNotNull();
            assertThat(jsonbEq(pointOf(boolArr), "[true,false]")).isTrue();
            assertThat(pointOf(nullArr)).isNotNull();
            assertThat(jsonbEq(pointOf(nullArr), "[null,null]")).isTrue();
        } finally {
            cleanup(strArr);
            cleanup(boolArr);
            cleanup(nullArr);
        }
    }

    @Test
    @DisplayName("V66_다점_객체배열_폴리곤_좌표순서_보존")
    void objectArrayPolygonOrderPreserved() throws IOException {
        // given — 3점 객체배열 폴리곤 (순서 검증용 — jsonb_agg 무순서면 뒤섞임)
        long lblSn = insertLabel("[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4},{\"x\":5,\"y\":6}]");
        try {
            // when
            applyV66();

            // then — 순서 정확히 [[1,2],[3,4],[5,6]] (배열 순서는 jsonb 동치로도 보장됨)
            assertThat(jsonbEq(pointOf(lblSn), "[[1,2],[3,4],[5,6]]")).isTrue();
        } finally {
            cleanup(lblSn);
        }
    }

    @Test
    @DisplayName("V66_음수_소수점_좌표_정확변환")
    void negativeAndDecimalCoordsConverted() throws IOException {
        // given — 음수·소수점 평탄 [-10,-20,1.5,2.7]
        long lblSn = insertLabel("[-10,-20,1.5,2.7]");
        try {
            // when
            applyV66();

            // then — [[-10,-20],[1.5,2.7]] (숫자값 보존)
            assertThat(jsonbEq(pointOf(lblSn), "[[-10,-20],[1.5,2.7]]")).isTrue();
        } finally {
            cleanup(lblSn);
        }
    }

    // ============================================================
    // SQL 파일 정합
    // ============================================================

    @Test
    @DisplayName("V66_마이그레이션_파일_존재_LS_DATA_LBL만_대상")
    void fileExistsTargetsOnlyLsDataLbl() throws IOException {
        assertThat(Files.exists(V66)).isTrue();
        String sql = Files.readString(V66).toLowerCase();
        // LS_DATA_LBL 만 UPDATE 대상 (다른 POINT_CN 컬럼 미손상)
        assertThat(sql).contains("update ls_data_lbl");
        assertThat(sql).doesNotContain("ls_portal_user_label");
    }

    /** PostgreSQL jsonb 동치 비교(공백/표현차 무시). */
    private boolean jsonbEq(String actual, String expected) {
        Boolean eq = jdbc().queryForObject(
                "SELECT (?::jsonb = ?::jsonb)", Boolean.class, actual, expected);
        return Boolean.TRUE.equals(eq);
    }
}
